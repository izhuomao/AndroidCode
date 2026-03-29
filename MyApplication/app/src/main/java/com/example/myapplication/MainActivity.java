package com.example.myapplication;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 主页面 —— 不再直接管理 MQTT 连接
 * 
 * 所有 MQTT 操作委托给 MqttService（前台服务），
 * Activity 只负责 UI 展示和发送控制指令。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private MqttService mqttService;
    private boolean serviceBound = false;
    private MemoDbHelper dbHelper;

    // ================= UI 控件 =================
    private TextView tvStatus, tvMood, tvArrow, tvMemoBadge;
    private GridLayout gridActionsContent;
    private LinearLayout layoutActionHeader;

    // ================= Service 连接回调 =================
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            MqttService.LocalBinder localBinder = (MqttService.LocalBinder) binder;
            mqttService = localBinder.getService();
            serviceBound = true;

            // 注册 UI 回调，让 Service 能通知我们更新界面
            mqttService.setUiCallback(new MqttService.MqttUiCallback() {
                @Override
                public void onConnectionStatusChanged(String status) {
                    runOnUiThread(() -> tvStatus.setText(status));
                }

                @Override
                public void onPetStatusReceived(String mood, int battery) {
                    runOnUiThread(() -> tvMood.setText("心情: " + mood + " | 电量: " + battery + "%"));
                }

                @Override
                public void onMemoSaved(String title) {
                    runOnUiThread(() -> {
                        refreshMemoBadge();
                        Toast.makeText(MainActivity.this, "🎤 语音备忘已记录: " + title, Toast.LENGTH_SHORT).show();
                    });
                }
            });

            // 更新连接状态显示
            if (mqttService.isConnected()) {
                tvStatus.setText("连接状态：在线 (阿里云)");
            } else {
                tvStatus.setText("连接状态：等待连接...");
            }

            Log.d(TAG, "已绑定 MqttService");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mqttService = null;
            serviceBound = false;
            Log.d(TAG, "MqttService 断开");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        setContentView(R.layout.activity_main);

        dbHelper = new MemoDbHelper(this);

        initViews();
        initExpandableLogic();
        initListeners();

        // 启动前台服务（如果还没启动的话）
        startMqttService();

        // 绑定服务
        bindMqttService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshMemoBadge();

        // 重新绑定（可能从其他页面回来）
        if (!serviceBound) {
            bindMqttService();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 解绑服务（但不停止服务，服务继续在后台运行）
        if (serviceBound) {
            if (mqttService != null) {
                mqttService.setUiCallback(null);  // 避免回调到已销毁的 Activity
            }
            unbindService(serviceConnection);
            serviceBound = false;
        }
        if (dbHelper != null) {
            dbHelper.close();
        }
    }

    // ================= 启动 / 绑定 Service =================

    private void startMqttService() {
        Intent serviceIntent = new Intent(this, MqttService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void bindMqttService() {
        Intent serviceIntent = new Intent(this, MqttService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    // ================= 发送指令（委托给 Service） =================

    private void sendCommand(String commandJson) {
        if (serviceBound && mqttService != null) {
            if (mqttService.isConnected()) {
                mqttService.publishCommand(commandJson);
            } else {
                Toast.makeText(this, "未连接服务器", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "服务未就绪", Toast.LENGTH_SHORT).show();
        }
    }

    // ================= UI 初始化 =================

    private void initViews() {
        tvStatus = findViewById(R.id.tv_title);
        tvMood   = findViewById(R.id.tv_mood_info);
        tvArrow  = findViewById(R.id.tv_arrow);
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
        // 方向控制
        findViewById(R.id.btn_up).setOnClickListener(v -> sendCommand("{\"move\":\"forward\"}"));
        findViewById(R.id.btn_down).setOnClickListener(v -> sendCommand("{\"move\":\"backward\"}"));
        findViewById(R.id.btn_left).setOnClickListener(v -> sendCommand("{\"move\":\"left\"}"));
        findViewById(R.id.btn_right).setOnClickListener(v -> sendCommand("{\"move\":\"right\"}"));

        // 模式
        findViewById(R.id.btn_stop).setOnClickListener(v -> sendCommand("{\"mode\":\"stop\"}"));
        findViewById(R.id.btn_free_mode).setOnClickListener(v -> sendCommand("{\"mode\":\"auto_patrol\"}"));

        // 重连按钮 → 让 Service 重连
        findViewById(R.id.btn_connect).setOnClickListener(v -> {
            v.setEnabled(false);
            tvStatus.setText("连接状态：正在重连...");
            if (serviceBound && mqttService != null) {
                mqttService.reconnect();
            } else {
                startMqttService();
                bindMqttService();
            }
            v.postDelayed(() -> v.setEnabled(true), 3000);
        });

        // 动作
        findViewById(R.id.btn_act_stand).setOnClickListener(v -> sendCommand("{\"action\":\"stand\"}"));
        findViewById(R.id.btn_act_sit).setOnClickListener(v -> sendCommand("{\"action\":\"sit\"}"));
        findViewById(R.id.btn_act_lie).setOnClickListener(v -> sendCommand("{\"action\":\"lie\"}"));
        findViewById(R.id.btn_act_sleep).setOnClickListener(v -> sendCommand("{\"action\":\"sleep\"}"));
        findViewById(R.id.btn_act_greet).setOnClickListener(v -> sendCommand("{\"action\":\"greet\"}"));
        findViewById(R.id.btn_act_wag).setOnClickListener(v -> sendCommand("{\"action\":\"wag\"}"));
        findViewById(R.id.btn_act_sway).setOnClickListener(v -> sendCommand("{\"action\":\"sway\"}"));
        findViewById(R.id.btn_act_custom).setOnClickListener(v -> sendCommand("{\"action\":\"cute\"}"));

        // 打开备忘录页面
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
}
