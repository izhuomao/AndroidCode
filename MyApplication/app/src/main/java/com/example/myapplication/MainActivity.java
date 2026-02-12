package com.example.myapplication;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
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

import java.io.UnsupportedEncodingException;

public class MainActivity extends AppCompatActivity {

    // ================= 配置区域 (保持你的阿里云参数不变) =================
    private static final String BROKER_URL = "tcp://a1k6uay65rc.iot-as-mqtt.cn-shanghai.aliyuncs.com:1883";
    private static final String TOPIC_PUB = "/a1k6uay65rc/Phone01/user/control";
    private static final String TOPIC_SUB = "/a1k6uay65rc/Phone01/user/status";

    private MqttAndroidClient client;
    private TextView tvStatus, tvMood, tvArrow;
    private GridLayout gridActionsContent;
    private LinearLayout layoutActionHeader;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. 初始化界面控件
        initViews();

        // 2. 初始化收起/展开逻辑
        initExpandableLogic();

        // 3. 初始化并连接 MQTT
        connectMQTT();

        // 4. 绑定所有按钮点击事件
        initListeners();
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tv_title);
        tvMood = findViewById(R.id.tv_mood_info);
        tvArrow = findViewById(R.id.tv_arrow);
        gridActionsContent = findViewById(R.id.grid_actions_content);
        layoutActionHeader = findViewById(R.id.layout_action_header);
    }

    /**
     * 处理交互动作栏的折叠与展开
     */
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

    /**
     * 绑定所有按钮的点击事件
     */
    private void initListeners() {
        // --- 方向控制 ---
        findViewById(R.id.btn_up).setOnClickListener(v -> publishCommand("{\"move\":\"forward\"}"));
        findViewById(R.id.btn_down).setOnClickListener(v -> publishCommand("{\"move\":\"backward\"}"));
        findViewById(R.id.btn_left).setOnClickListener(v -> publishCommand("{\"move\":\"left\"}"));
        findViewById(R.id.btn_right).setOnClickListener(v -> publishCommand("{\"move\":\"right\"}"));

        // --- 系统控制 ---
        findViewById(R.id.btn_stop).setOnClickListener(v -> publishCommand("{\"mode\":\"stop\"}"));
        findViewById(R.id.btn_free_mode).setOnClickListener(v -> publishCommand("{\"mode\":\"auto_patrol\"}"));
        findViewById(R.id.btn_connect).setOnClickListener(v -> {
            updateStatus("连接状态：正在重连...");
            connectMQTT();
        });

        // --- 趣味交互动作 (根据新布局添加) ---
        // 这里的指令字符串可以根据你单片机定义的逻辑修改，比如 "{\"action\":\"sleep\"}" 或者直接发送数字 "0"
        findViewById(R.id.btn_act_stand).setOnClickListener(v -> publishCommand("{\"action\":\"stand\"}"));
        findViewById(R.id.btn_act_squat).setOnClickListener(v -> publishCommand("{\"action\":\"squat\"}"));
        findViewById(R.id.btn_act_lie).setOnClickListener(v -> publishCommand("{\"action\":\"lie\"}"));
        findViewById(R.id.btn_act_sleep).setOnClickListener(v -> publishCommand("{\"action\":\"sleep\"}"));
        findViewById(R.id.btn_act_greet).setOnClickListener(v -> publishCommand("{\"action\":\"greet\"}"));
        findViewById(R.id.btn_act_wag).setOnClickListener(v -> publishCommand("{\"action\":\"wag\"}"));
        findViewById(R.id.btn_act_sway).setOnClickListener(v -> publishCommand("{\"action\":\"sway\"}"));
        findViewById(R.id.btn_act_custom).setOnClickListener(v -> publishCommand("{\"action\":\"cute\"}"));
    }

    /**
     * 连接 MQTT 服务器
     */
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
                    subscribeToTopic();
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
                    handleIncomingMessage(payload);
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) { }
            });

        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

    private void subscribeToTopic() {
        try {
            if (client != null && client.isConnected()) {
                client.subscribe(TOPIC_SUB, 0);
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
