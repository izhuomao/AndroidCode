package com.example.myapplication;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/**
 * 备忘录提醒广播接收器
 * 闹钟触发时：
 * 1. 发送系统通知
 * 2. 启动 MainActivity 并携带提醒数据，让它通过 MQTT 通知 ESP32 桌宠
 */
public class MemoReminderReceiver extends BroadcastReceiver {

    public static final String CHANNEL_ID = "memo_reminder_channel";
    public static final String EXTRA_MEMO_TITLE = "memo_title";
    public static final String EXTRA_MEMO_CONTENT = "memo_content";
    public static final String EXTRA_MEMO_ID = "memo_id";

    // ▼▼▼ 新增：用于触发桌宠提醒的 Action ▼▼▼
    public static final String ACTION_TRIGGER_PET_REMIND = "com.example.myapplication.TRIGGER_PET_REMIND";

    @Override
    public void onReceive(Context context, Intent intent) {
        String title = intent.getStringExtra(EXTRA_MEMO_TITLE);
        String content = intent.getStringExtra(EXTRA_MEMO_CONTENT);
        int memoId = intent.getIntExtra(EXTRA_MEMO_ID, 0);

        if (title == null) title = "茁猫提醒";
        if (content == null) content = "你有一条待办事项";

        // 1. 发送系统通知
        createNotificationChannel(context);
        showNotification(context, title, content, memoId);

        // 2. ▼▼▼ 新增：启动 MainActivity，让它通过 MQTT 通知桌宠 ▼▼▼
        Intent mainIntent = new Intent(context, MainActivity.class);
        mainIntent.setAction(ACTION_TRIGGER_PET_REMIND);
        mainIntent.putExtra(EXTRA_MEMO_TITLE, title);
        mainIntent.putExtra(EXTRA_MEMO_CONTENT, content);
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(mainIntent);
    }

    private void showNotification(Context context, String title, String content, int memoId) {
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, memoId, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("🐱 茁猫提醒: " + title)
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setDefaults(NotificationCompat.DEFAULT_ALL);

        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(memoId, builder.build());
        }
    }

    private void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "茁猫备忘录提醒",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("桌宠备忘录到期提醒");
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
