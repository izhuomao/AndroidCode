package com.example.myapplication;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import info.mqtt.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * MQTT 前台服务
 * 
 * 职责：
 * 1. 维持 MQTT 长连接（app 退出后也能存活）
 * 2. 接收语音备忘 → 写入本地数据库 + 设置闹钟
 * 3. 接收桌宠状态上报 → 通知 UI 更新
 * 4. 发送控制指令 / 提醒指令给 ESP32
 */
public class MqttService extends Service {

    private static final String TAG = "MqttService";

    // ================= MQTT 配置 =================
    private static final String BROKER_URL = "tcp://a1k6uay65rc.iot-as-mqtt.cn-shanghai.aliyuncs.com:1883";
    private static final String TOPIC_PUB  = "/a1k6uay65rc/Phone01/user/control";
    private static final String TOPIC_SUB  = "/a1k6uay65rc/Phone01/user/status";
    private static final String TOPIC_MEMO_SUB = "/a1k6uay65rc/Phone01/user/memo";

    private static final String CHANNEL_ID_SERVICE = "mqtt_service_channel";
    private static final int NOTIFICATION_ID = 9999;

    // Action：由 MemoReminderReceiver 发来的提醒请求
    public static final String ACTION_SEND_REMINDER = "com.example.myapplication.SEND_REMINDER";
    public static final String EXTRA_REMINDER_CONTENT = "reminder_content";

    private MqttAndroidClient client;
    private MemoDbHelper dbHelper;

    // 防止重复连接
    private boolean isConnecting = false;

    // 备忘录去重（需要同步，因为可能多线程回调）
    private String lastMemoFingerprint = "";
    private long lastMemoTime = 0;
    private final Object memoLock = new Object();

    // 暂存的提醒（MQTT 未连接时）
    private String pendingReminderPayload = null;

    // ================= UI 回调接口 =================
    public interface MqttUiCallback {
        void onConnectionStatusChanged(String status);
        void onPetStatusReceived(String mood, int battery);
        void onMemoSaved(String title);
    }

    private MqttUiCallback uiCallback;

    public void setUiCallback(MqttUiCallback callback) {
        this.uiCallback = callback;
    }

    // ================= Binder =================
    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public MqttService getService() {
            return MqttService.this;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // ================= 生命周期 =================

    @Override
    public void onCreate() {
        super.onCreate();
        dbHelper = new MemoDbHelper(this);
        createServiceNotificationChannel();
        startForeground(NOTIFICATION_ID, buildForegroundNotification("正在连接..."));
        connectMQTT();
        Log.d(TAG, "🚀 MqttService 已创建");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_SEND_REMINDER.equals(intent.getAction())) {
            String content = intent.getStringExtra(EXTRA_REMINDER_CONTENT);
            if (content != null && !content.isEmpty()) {
                sendReminderToESP32(content);
            }
        }
        // START_STICKY：系统杀掉后会自动重启
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        disconnectAndCleanup();
        if (dbHelper != null) {
            dbHelper.close();
        }
        Log.d(TAG, "🛑 MqttService 已销毁");
    }

    /**
     * 安全地断开并清理旧的 MQTT client
     */
    private void disconnectAndCleanup() {
        if (client != null) {
            try {
                // MqttAndroidClient.setCallback 不允许传 null
                // 用一个空实现替代
                client.setCallback(new MqttCallback() {
                    @Override public void connectionLost(Throwable cause) {}
                    @Override public void messageArrived(String topic, MqttMessage message) {}
                    @Override public void deliveryComplete(IMqttDeliveryToken token) {}
                });
            } catch (Exception e) {
                Log.w(TAG, "清理回调: " + e.getMessage());
            }
            try {
                if (client.isConnected()) {
                    client.disconnect();
                }
            } catch (Exception e) {
                Log.w(TAG, "断开连接: " + e.getMessage());
            }
            try {
                client.close();
            } catch (Exception e) {
                Log.w(TAG, "关闭 client: " + e.getMessage());
            }
            client = null;
        }
        isConnecting = false;
    }

    // ================= 对外方法（Activity 调用） =================

    /**
     * 发送控制指令（移动、动作等）
     */
    public void publishCommand(String commandJson) {
        if (client != null && client.isConnected()) {
            try {
                MqttMessage message = new MqttMessage();
                message.setPayload(commandJson.getBytes());
                message.setQos(0);
                client.publish(TOPIC_PUB, message);
                Log.d(TAG, "📤 发送: " + commandJson);
            } catch (Exception e) {
                Log.e(TAG, "发送失败: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "⚠️ MQTT 未连接，指令未发送: " + commandJson);
        }
    }

    /**
     * 判断是否已连接
     */
    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    /**
     * 手动重连
     */
    public void reconnect() {
        disconnectAndCleanup();
        connectMQTT();
    }

    // ================= MQTT 连接 =================

    private void connectMQTT() {
        // 防止重复连接
        if (isConnecting) {
            Log.w(TAG, "⚠️ 正在连接中，忽略重复请求");
            return;
        }
        if (client != null && client.isConnected()) {
            Log.w(TAG, "⚠️ 已经连接，忽略重复请求");
            return;
        }

        isConnecting = true;

        String validClientId = "a1k6uay65rc.Phone01|securemode=2,signmethod=hmacsha256,timestamp=1769934859030|";
        String validUserName = "Phone01&a1k6uay65rc";
        String validPassword = "dcc9bb62b0fcac4451cbedbd1e60927ed162de37f9f66cb6d6dae526107a267e";

        client = new MqttAndroidClient(getApplicationContext(), BROKER_URL, validClientId);

        // 先设置回调再连接，确保不会漏消息
        client.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                Log.w(TAG, "⚠️ MQTT 连接断开");
                isConnecting = false;
                notifyStatus("连接状态：掉线");
                updateForegroundNotification("已断开，等待重连...");
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                String payload = new String(message.getPayload(), "UTF-8");
                Log.d(TAG, "📩 MQTT 收到 [" + topic + "]: " + payload);

                if (topic.contains("memo")) {
                    handleMemoMessage(payload);
                } else {
                    handleStatusMessage(payload);
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) { }
        });

        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(80);
        options.setAutomaticReconnect(true);
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        options.setUserName(validUserName);
        options.setPassword(validPassword.toCharArray());

        try {
            client.connect(options, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    isConnecting = false;
                    Log.d(TAG, "✅ MQTT 连接成功");
                    notifyStatus("连接状态：在线 (阿里云)");
                    updateForegroundNotification("已连接");
                    subscribeToTopics();

                    // 发送暂存的提醒
                    if (pendingReminderPayload != null) {
                        Log.d(TAG, "🔔 发送暂存的桌宠提醒");
                        publishCommand(pendingReminderPayload);
                        pendingReminderPayload = null;
                    }
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    isConnecting = false;
                    Log.e(TAG, "❌ MQTT 连接失败: " + exception.toString());
                    notifyStatus("连接失败 (检查网络)");
                    updateForegroundNotification("连接失败");
                }
            });

        } catch (Exception e) {
            isConnecting = false;
            Log.e(TAG, "MQTT 连接异常: " + e.getMessage());
        }
    }

    private void subscribeToTopics() {
        try {
            if (client != null && client.isConnected()) {
                client.subscribe(TOPIC_SUB, 0);
                client.subscribe(TOPIC_MEMO_SUB, 0);
                Log.d(TAG, "已订阅: " + TOPIC_SUB + " & " + TOPIC_MEMO_SUB);
            }
        } catch (Exception e) {
            Log.e(TAG, "订阅失败: " + e.getMessage());
        }
    }

    // ================= 发送提醒给 ESP32 =================

    private void sendReminderToESP32(String content) {
        try {
            JSONObject json = new JSONObject();
            json.put("reminder", content);
            String payload = json.toString();

            Log.d(TAG, "🔔 准备发送桌宠提醒: " + payload);

            if (client != null && client.isConnected()) {
                publishCommand(payload);
                Log.d(TAG, "🔔 桌宠提醒已发送");
            } else {
                pendingReminderPayload = payload;
                Log.d(TAG, "🔔 MQTT 未连接，提醒已暂存");
            }
        } catch (JSONException e) {
            Log.e(TAG, "构造提醒 JSON 失败: " + e.getMessage());
        }
    }

    // ================= 处理收到的备忘录 =================

    private void handleMemoMessage(String jsonPayload) {
        try {
            JSONObject json = new JSONObject(jsonPayload);
            String type = json.optString("type", "");

            if ("memo".equals(type)) {
                String title   = json.optString("title", "未命名");
                String time    = json.optString("time", "无");
                String content = json.optString("content", "");
                String source  = json.optString("source", "voice");
                String created = json.optString("created",
                        new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date()));

                // 去重（加锁，防止多线程同时写入）
                String fingerprint = title + "|" + time + "|" + content;
                synchronized (memoLock) {
                    long now = System.currentTimeMillis();
                    if (fingerprint.equals(lastMemoFingerprint) && (now - lastMemoTime) < 5000) {
                        Log.w(TAG, "⚠️ 重复备忘录已过滤: " + title);
                        return;
                    }
                    lastMemoFingerprint = fingerprint;
                    lastMemoTime = now;

                    String id = String.valueOf(now);
                    MemoItem memo = new MemoItem(id, title, time, content, source, created);

                    boolean ok = dbHelper.insertMemo(memo);
                    if (ok) {
                        Log.d(TAG, "🎤 语音备忘已入库: " + title);

                        if (memo.hasRemindTime()) {
                            scheduleReminder(memo);
                        }

                        // 通知 UI（如果 Activity 在前台）
                        if (uiCallback != null) {
                            uiCallback.onMemoSaved(title);
                        }
                    }
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "解析备忘录 JSON 失败: " + e.getMessage());
        }
    }

    // ================= 处理桌宠状态上报 =================

    private void handleStatusMessage(String jsonPayload) {
        try {
            JSONObject json = new JSONObject(jsonPayload);
            String mood = json.optString("mood", "正常");
            int battery = json.optInt("battery", 0);

            if (uiCallback != null) {
                uiCallback.onPetStatusReceived(mood, battery);
            }
        } catch (JSONException e) {
            Log.w(TAG, "状态消息解析: " + e.getMessage());
        }
    }

    // ================= 设置闹钟提醒 =================

    private void scheduleReminder(MemoItem memo) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            Date remindDate = sdf.parse(memo.getTime());
            if (remindDate == null || remindDate.getTime() <= System.currentTimeMillis()) {
                return;
            }

            AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(this, MemoReminderReceiver.class);
            intent.putExtra(MemoReminderReceiver.EXTRA_MEMO_TITLE, memo.getTitle());
            intent.putExtra(MemoReminderReceiver.EXTRA_MEMO_CONTENT, memo.getContent());
            intent.putExtra(MemoReminderReceiver.EXTRA_MEMO_ID, memo.getId().hashCode());

            int requestCode = memo.getId().hashCode();
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    this, requestCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            if (alarmManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(
                                AlarmManager.RTC_WAKEUP, remindDate.getTime(), pendingIntent);
                    } else {
                        alarmManager.set(AlarmManager.RTC_WAKEUP, remindDate.getTime(), pendingIntent);
                    }
                } else {
                    alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP, remindDate.getTime(), pendingIntent);
                }
                Log.d(TAG, "⏰ 已设置提醒: " + memo.getTitle() + " @ " + memo.getTime());
            }
        } catch (ParseException e) {
            Log.e(TAG, "解析提醒时间失败: " + e.getMessage());
        }
    }

    // ================= 通知相关 =================

    private void notifyStatus(String status) {
        if (uiCallback != null) {
            uiCallback.onConnectionStatusChanged(status);
        }
    }

    private void createServiceNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID_SERVICE,
                    "茁猫桌宠连接服务",
                    NotificationManager.IMPORTANCE_LOW  // 低优先级，不打扰用户
            );
            channel.setDescription("保持与桌宠的连接");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildForegroundNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("🐱 茁猫桌宠")
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateForegroundNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildForegroundNotification(text));
        }
    }
}
