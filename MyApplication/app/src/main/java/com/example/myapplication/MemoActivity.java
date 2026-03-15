package com.example.myapplication;

import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 备忘录独立页面
 * 负责：展示列表、手动添加、勾选完成、删除、设置提醒闹钟
 * 语音录入的备忘由 MainActivity 的 MQTT 回调直接写入数据库，
 * 本页面 onResume 时会刷新列表。
 */
public class MemoActivity extends AppCompatActivity implements MemoAdapter.OnMemoActionListener {

    private static final String TAG = "MemoActivity";

    private RecyclerView rvMemos;
    private MemoAdapter memoAdapter;
    private List<MemoItem> memoList = new ArrayList<>();
    private TextView tvMemoCount;
    private LinearLayout layoutEmpty;

    private MemoDbHelper dbHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_memo);

        dbHelper = new MemoDbHelper(this);

        initViews();
        initListeners();
        loadAndDisplay();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 每次回到此页面都刷新（语音录入的新备忘会在后台写入数据库）
        loadAndDisplay();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (dbHelper != null) {
            dbHelper.close();
        }
    }

    private void initViews() {
        rvMemos = findViewById(R.id.rv_memos);
        tvMemoCount = findViewById(R.id.tv_memo_count);
        layoutEmpty = findViewById(R.id.layout_empty);

        memoAdapter = new MemoAdapter(memoList, this);
        rvMemos.setLayoutManager(new LinearLayoutManager(this));
        rvMemos.setAdapter(memoAdapter);
    }

    private void initListeners() {
        // 返回按钮
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        // 添加备忘
        findViewById(R.id.btn_add_memo).setOnClickListener(v -> showAddMemoDialog());

        // 清除已完成
        findViewById(R.id.btn_clear_done).setOnClickListener(v -> clearDoneMemos());
    }

    /**
     * 从数据库加载并刷新整个列表
     */
    private void loadAndDisplay() {
        memoList.clear();
        memoList.addAll(dbHelper.getAllMemos());
        memoAdapter.notifyDataSetChanged();
        updateMemoCount();
        updateEmptyState();
    }

    private void updateEmptyState() {
        if (memoList.isEmpty()) {
            rvMemos.setVisibility(View.GONE);
            layoutEmpty.setVisibility(View.VISIBLE);
        } else {
            rvMemos.setVisibility(View.VISIBLE);
            layoutEmpty.setVisibility(View.GONE);
        }
    }

    private void updateMemoCount() {
        int total = dbHelper.getTotalCount();
        int undone = dbHelper.getUndoneCount();
        tvMemoCount.setText("共 " + total + " 条，待办 " + undone + " 条");
    }

    // ==========================================
    //          添加备忘录
    // ==========================================

    private void showAddMemoDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_memo, null);
        EditText etTitle = dialogView.findViewById(R.id.et_memo_title);
        EditText etContent = dialogView.findViewById(R.id.et_memo_content);
        TextView tvSelectedTime = dialogView.findViewById(R.id.tv_selected_time);
        Button btnPickTime = dialogView.findViewById(R.id.btn_pick_time);

        final String[] selectedTime = {"无"};

        btnPickTime.setOnClickListener(v -> {
            Calendar cal = Calendar.getInstance();
            new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
                new TimePickerDialog(this, (view2, hourOfDay, minute) -> {
                    selectedTime[0] = String.format(Locale.getDefault(),
                            "%04d-%02d-%02d %02d:%02d", year, month + 1, dayOfMonth, hourOfDay, minute);
                    tvSelectedTime.setText("提醒时间：" + selectedTime[0]);
                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show();
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
        });

        new AlertDialog.Builder(this)
                .setTitle("📝 添加备忘录")
                .setView(dialogView)
                .setPositiveButton("确定", (dialog, which) -> {
                    String title = etTitle.getText().toString().trim();
                    String content = etContent.getText().toString().trim();
                    if (title.isEmpty()) {
                        Toast.makeText(this, "标题不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (content.isEmpty()) content = title;

                    String id = String.valueOf(System.currentTimeMillis());
                    String created = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date());

                    MemoItem memo = new MemoItem(id, title, selectedTime[0], content, "manual", created);
                    addMemo(memo);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void addMemo(MemoItem memo) {
        boolean ok = dbHelper.insertMemo(memo);
        if (!ok) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
            return;
        }

        memoList.add(0, memo);
        memoAdapter.notifyItemInserted(0);
        rvMemos.scrollToPosition(0);
        updateMemoCount();
        updateEmptyState();

        if (memo.hasRemindTime()) {
            scheduleReminder(memo);
        }

        Toast.makeText(this, "✅ 备忘已添加: " + memo.getTitle(), Toast.LENGTH_SHORT).show();
    }

    // ==========================================
    //          清除已完成
    // ==========================================

    private void clearDoneMemos() {
        int deleted = dbHelper.deleteAllDone();
        if (deleted == 0) {
            Toast.makeText(this, "没有已完成的备忘", Toast.LENGTH_SHORT).show();
            return;
        }

        List<MemoItem> toRemove = new ArrayList<>();
        for (MemoItem item : memoList) {
            if (item.isDone()) toRemove.add(item);
        }
        memoList.removeAll(toRemove);
        memoAdapter.notifyDataSetChanged();
        updateMemoCount();
        updateEmptyState();
        Toast.makeText(this, "已清除 " + deleted + " 条", Toast.LENGTH_SHORT).show();
    }

    // ==========================================
    //          Adapter 回调
    // ==========================================

    @Override
    public void onToggleDone(int position, boolean isDone) {
        if (position >= 0 && position < memoList.size()) {
            MemoItem memo = memoList.get(position);
            memo.setDone(isDone);
            dbHelper.updateDoneStatus(memo.getId(), isDone);
            memoAdapter.notifyItemChanged(position);
            updateMemoCount();
        }
    }

    @Override
    public void onDelete(int position) {
        if (position >= 0 && position < memoList.size()) {
            MemoItem removed = memoList.remove(position);
            dbHelper.deleteMemo(removed.getId());
            memoAdapter.notifyItemRemoved(position);
            updateMemoCount();
            updateEmptyState();
            cancelReminder(removed);
            Toast.makeText(this, "已删除: " + removed.getTitle(), Toast.LENGTH_SHORT).show();
        }
    }

    // ==========================================
    //          闹钟提醒
    // ==========================================

    private void scheduleReminder(MemoItem memo) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            Date remindDate = sdf.parse(memo.getTime());
            if (remindDate == null || remindDate.getTime() <= System.currentTimeMillis()) {
                Log.w(TAG, "提醒时间已过，不设置闹钟");
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

    private void cancelReminder(MemoItem memo) {
        try {
            AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(this, MemoReminderReceiver.class);
            int requestCode = memo.getId().hashCode();
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    this, requestCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            if (alarmManager != null) {
                alarmManager.cancel(pendingIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "取消提醒失败: " + e.getMessage());
        }
    }
}
