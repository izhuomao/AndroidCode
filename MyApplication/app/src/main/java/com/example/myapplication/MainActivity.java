package com.example.myapplication;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // ================= MQTT 配置 =================
    private static final String BROKER_URL = "tcp://a1k6uay65rc.iot-as-mqtt.cn-shanghai.aliyuncs.com:1883";
    private static final String TOPIC_PUB = "/a1k6uay65rc/Phone01/user/control";
    private static final String TOPIC_SUB = "/a1k6uay65rc/Phone01/user/status";
    private static final String TOPIC_MEMO_SUB = "/a1k6uay65rc/Phone01/user/memo";

    private MqttAndroidClient client;
    private MemoDbHelper dbHelper;

    // ================= UI 控件 =================
    private TextView tvStatus, tvMood, tvArrow, tvMemoBadge;
    private GridLayout gridActionsContent;
    private LinearLayout layoutActionHeader;

    // ▼▼▼ 新增：待发送的提醒指令（MQTT 未连接时暂存） ▼▼▼
    private String pendingReminderPayload = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dbHelper = new MemoDbHelper(this);

        initViews();
        initExpandableLogic();
        connectMQTT();
        initListeners();

        // ▼▼▼ 检查是否由提醒广播启动 ▼▼▼
        handleReminderIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // ▼▼▼ Activity 已存在时由提醒广播唤醒 ▼▼▼
        handleReminderIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshMemoBadge();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (dbHelper != null) {
            dbHelper.close();
        }
    }

    /**
     * ▼▼▼ 新增：处理来自 MemoReminderReceiver 的提醒 Intent ▼▼▼
     * 提取标题和内容，通过 MQTT 发送给 ESP32，让桌宠做出提醒动作+播放语音
     */
    private void handleReminderIntent(Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        if (MemoReminderReceiver.ACTION_TRIGGER_PET_REMIND.equals(action)) {
            String title = intent.getStringExtra(MemoReminderReceiver.EXTRA_MEMO_TITLE);
            String content = intent.getStringExtra(MemoReminderReceiver.EXTRA_MEMO_CONTENT);

            if (content == null || content.isEmpty()) content = title;
            if (title == null || title.isEmpty()) return;

            // 构造 MQTT 指令: {"reminder":"提醒内容"}
            // ESP32 收到后会发给 Python 合成 TTS 语音并播放
            try {
                JSONObject json = new JSONObject();
                json.put("reminder", content);
                String payload = json.toString();

                Log.d(TAG, "🔔 准备发送桌宠提醒: " + payload);

                if (client != null && client.isConnected()) {
                    publishCommand(payload);
                    Log.d(TAG, "🔔 桌宠提醒已发送");
                } else {
                    // MQTT 还没连上，暂存，等连上后发
                    pendingReminderPayload = payload;
                    Log.d(TAG, "🔔 MQTT 未连接，提醒已暂存，等连接后发送");
                }
            } catch (JSONException e) {
                Log.e(TAG, "构造提醒 JSON 失败: " + e.getMessage());
            }

            // 清除 Intent action，防止重复触发
            intent.setAction(null);
        }
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tv_title);
        tvMood = findViewById(R.id.tv_mood_info);
        tvArrow = findViewById(R.id.tv_arrow);
        gridActionsContent = findViewById(R.id.grid_actions_content);
        layoutActionHeader = findViewById(R.id.layout_action_header);
        tvMemoBadge = findViewById(R.id.tv_memo_badge);
    }

    private void initExpandableLogic() {
        layoutActionHeader.setOnClickListener(v -> {
            if (gridActionsContent.getVisibility() == View.VISIBLE) {
                gridActionsContent.setVisibility(View.GONE);
                tvArrow.setText("▼");
            } else {
                gridActionsContent.setVisibility(View.VISIBLE);
                tvArrow.setText("▲");
            }
        });
    }

    private void initListeners() {
        findViewById(R.id.btn_up).setOnClickListener(v -> publishCommand("{\"move\":\"forward\"}"));
        findViewById(R.id.btn_down).setOnClickListener(v -> publishCommand("{\"move\":\"backward\"}"));
        findViewById(R.id.btn_left).setOnClickListener(v -> publishCommand("{\"move\":\"left\"}"));
        findViewById(R.id.btn_right).setOnClickListener(v -> publishCommand("{\"move\":\"right\"}"));

        findViewById(R.id.btn_stop).setOnClickListener(v -> publishCommand("{\"mode\":\"stop\"}"));
        findViewById(R.id.btn_free_mode).setOnClickListener(v -> publishCommand("{\"mode\":\"auto_patrol\"}"));
        findViewById(R.id.btn_connect).setOnClickListener(v -> {
            updateStatus("连接状态：正在重连...");
            connectMQTT();
        });

        findViewById(R.id.btn_act_stand).setOnClickListener(v -> publishCommand("{\"action\":\"stand\"}"));
        findViewById(R.id.btn_act_sit).setOnClickListener(v -> publishCommand("{\"action\":\"sit\"}"));
        findViewById(R.id.btn_act_lie).setOnClickListener(v -> publishCommand("{\"action\":\"lie\"}"));
        findViewById(R.id.btn_act_sleep).setOnClickListener(v -> publishCommand("{\"action\":\"sleep\"}"));
        findViewById(R.id.btn_act_greet).setOnClickListener(v -> publishCommand("{\"action\":\"greet\"}"));
        findViewById(R.id.btn_act_wag).setOnClickListener(v -> publishCommand("{\"action\":\"wag\"}"));
        findViewById(R.id.btn_act_sway).setOnClickListener(v -> publishCommand("{\"action\":\"sway\"}"));
        findViewById(R.id.btn_act_custom).setOnClickListener(v -> publishCommand("{\"action\":\"cute\"}"));

        findViewById(R.id.btn_open_memo).setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, MemoActivity.class);
            startActivity(intent);
        });
    }

    private void refreshMemoBadge() {
        int undone = dbHelper.getUndoneCount();
        if (undone > 0) {
            tvMemoBadge.setText(undone + " 条待办");
        } else {
            tvMemoBadge.setText("暂无待办");
        }
        tvMemoBadge.setVisibility(View.VISIBLE);
    }

    // ==========================================
    //     MQTT 收到语音备忘 → 写入数据库 + 设闹钟
    // ==========================================

    private void handleMemoMessage(String jsonPayload) {
        runOnUiThread(() -> {
            try {
                JSONObject json = new JSONObject(jsonPayload);
                String type = json.optString("type", "");

                if ("memo".equals(type)) {
                    String id = String.valueOf(System.currentTimeMillis());
                    String title = json.optString("title", "未命名");
                    String time = json.optString("time", "无");
                    String content = json.optString("content", "");
                    String source = json.optString("source", "voice");
                    String created = json.optString("created",
                            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date()));

                    MemoItem memo = new MemoItem(id, title, time, content, source, created);

                    boolean ok = dbHelper.insertMemo(memo);
                    if (ok) {
                        if (memo.hasRemindTime()) {
                            scheduleReminder(memo);
                        }
                        refreshMemoBadge();
                        Toast.makeText(this, "🎤 语音备忘已记录: " + title, Toast.LENGTH_SHORT).show();
                        Log.d(TAG, "🎤 语音备忘已入库: " + title);
                    }
                }
            } catch (JSONException e) {
                Log.e(TAG, "解析备忘录 JSON 失败: " + e.getMessage());
            }
        });
    }

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
            }
        } catch (ParseException e) {
            Log.e(TAG, "解析提醒时间失败: " + e.getMessage());
        }
    }

    // ==========================================
    //          MQTT 连接
    // ==========================================

    private void connectMQTT() {
        String validClientId = "a1k6uay65rc.Phone01|securemode=2,signmethod=hmacsha256,timestamp=1769934859030|";
        String validUserName = "Phone01&a1k6uay65rc";
        String validPassword = "dcc9bb62b0fcac4451cbedbd1e60927ed162de37f9f66cb6d6dae526107a267e";

        client = new MqttAndroidClient(this.getApplicationContext(), BROKER_URL, validClientId);
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
                    updateStatus("连接状态：在线 (阿里云)");
                    subscribeToTopics();

                    // ▼▼▼ 新增：连接成功后检查是否有待发送的提醒 ▼▼▼
                    if (pendingReminderPayload != null) {
                        Log.d(TAG, "🔔 MQTT 已连接，发送暂存的桌宠提醒");
                        publishCommand(pendingReminderPayload);
                        pendingReminderPayload = null;
                    }
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.e("MQTT", "连接失败: " + exception.toString());
                    updateStatus("连接失败 (检查网络)");
                }
            });

            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    updateStatus("连接状态：掉线");
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) throws Exception {
                    String payload = new String(message.getPayload(), "UTF-8");
                    Log.d(TAG, "MQTT 收到 [" + topic + "]: " + payload);

                    if (topic.contains("memo")) {
                        handleMemoMessage(payload);
                    } else {
                        handleIncomingMessage(payload);
                    }
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) { }
            });

        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

    private void subscribeToTopics() {
        try {
            if (client != null && client.isConnected()) {
                client.subscribe(TOPIC_SUB, 0);
                client.subscribe(TOPIC_MEMO_SUB, 0);
                Log.d(TAG, "已订阅: " + TOPIC_SUB + " & " + TOPIC_MEMO_SUB);
            }
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

    private void publishCommand(String commandJson) {
        if (client != null && client.isConnected()) {
            try {
                MqttMessage message = new MqttMessage();
                message.setPayload(commandJson.getBytes());
                message.setQos(0);
                client.publish(TOPIC_PUB, message);
                Log.d("MQTT", "发送: " + commandJson);
            } catch (MqttException e) {
                e.printStackTrace();
            }
        } else {
            Toast.makeText(this, "未连接服务器", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleIncomingMessage(String jsonPayload) {
        runOnUiThread(() -> {
            try {
                JSONObject json = new JSONObject(jsonPayload);
                String mood = json.optString("mood", "正常");
                int battery = json.optInt("battery", 0);
                tvMood.setText("心情: " + mood + " | 电量: " + battery + "%");
            } catch (JSONException e) {
                tvMood.setText("状态: " + jsonPayload);
            }
        });
    }

    private void updateStatus(String text) {
        runOnUiThread(() -> tvStatus.setText(text));
    }
}
