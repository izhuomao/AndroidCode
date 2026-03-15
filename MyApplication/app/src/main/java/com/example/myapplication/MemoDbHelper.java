package com.example.myapplication;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 备忘录数据库管理类
 * 使用 SQLite 持久化存储备忘录数据
 */
public class MemoDbHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "zhuomao_memo.db";
    private static final int DB_VERSION = 1;

    // 表名与字段
    private static final String TABLE_MEMO = "memos";
    private static final String COL_ID = "id";              // 主键（时间戳字符串）
    private static final String COL_TITLE = "title";
    private static final String COL_TIME = "remind_time";   // 提醒时间 yyyy-MM-dd HH:mm 或 "无"
    private static final String COL_CONTENT = "content";
    private static final String COL_SOURCE = "source";      // "voice" 或 "manual"
    private static final String COL_CREATED = "created_at";
    private static final String COL_DONE = "done";          // 0=未完成, 1=已完成

    public MemoDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String sql = "CREATE TABLE " + TABLE_MEMO + " ("
                + COL_ID + " TEXT PRIMARY KEY, "
                + COL_TITLE + " TEXT NOT NULL, "
                + COL_TIME + " TEXT DEFAULT '无', "
                + COL_CONTENT + " TEXT, "
                + COL_SOURCE + " TEXT DEFAULT 'manual', "
                + COL_CREATED + " TEXT, "
                + COL_DONE + " INTEGER DEFAULT 0"
                + ")";
        db.execSQL(sql);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 后续版本升级时在此处做迁移
        // 目前是 v1，直接重建
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_MEMO);
        onCreate(db);
    }

    // ==========================================
    //              增
    // ==========================================

    /**
     * 插入一条备忘录
     * @return 插入成功返回 true
     */
    public boolean insertMemo(MemoItem memo) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_ID, memo.getId());
        values.put(COL_TITLE, memo.getTitle());
        values.put(COL_TIME, memo.getTime());
        values.put(COL_CONTENT, memo.getContent());
        values.put(COL_SOURCE, memo.getSource());
        values.put(COL_CREATED, memo.getCreated());
        values.put(COL_DONE, memo.isDone() ? 1 : 0);

        long result = db.insertWithOnConflict(TABLE_MEMO, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        return result != -1;
    }

    // ==========================================
    //              删
    // ==========================================

    /**
     * 根据 ID 删除单条备忘
     */
    public void deleteMemo(String id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_MEMO, COL_ID + " = ?", new String[]{id});
    }

    /**
     * 删除所有已完成的备忘
     * @return 被删除的条数
     */
    public int deleteAllDone() {
        SQLiteDatabase db = getWritableDatabase();
        return db.delete(TABLE_MEMO, COL_DONE + " = 1", null);
    }

    /**
     * 清空所有备忘（慎用）
     */
    public void deleteAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_MEMO, null, null);
    }

    // ==========================================
    //              改
    // ==========================================

    /**
     * 更新完成状态
     */
    public void updateDoneStatus(String id, boolean done) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_DONE, done ? 1 : 0);
        db.update(TABLE_MEMO, values, COL_ID + " = ?", new String[]{id});
    }

    /**
     * 更新备忘内容（标题、内容、时间）
     */
    public void updateMemo(MemoItem memo) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_TITLE, memo.getTitle());
        values.put(COL_TIME, memo.getTime());
        values.put(COL_CONTENT, memo.getContent());
        values.put(COL_DONE, memo.isDone() ? 1 : 0);
        db.update(TABLE_MEMO, values, COL_ID + " = ?", new String[]{memo.getId()});
    }

    // ==========================================
    //              查
    // ==========================================

    /**
     * 查询所有备忘录（按创建时间倒序：最新的在前）
     */
    public List<MemoItem> getAllMemos() {
        List<MemoItem> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();

        Cursor cursor = db.query(TABLE_MEMO, null, null, null,
                null, null, COL_CREATED + " DESC");

        if (cursor != null) {
            while (cursor.moveToNext()) {
                list.add(cursorToMemo(cursor));
            }
            cursor.close();
        }
        return list;
    }

    /**
     * 只查询未完成的备忘（按提醒时间升序，无提醒时间的排最后）
     */
    public List<MemoItem> getUndoneMemos() {
        List<MemoItem> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();

        // 排序逻辑：有提醒时间的按时间升序排在前面，无提醒时间的排后面
        String orderBy = "CASE WHEN " + COL_TIME + " = '无' THEN 1 ELSE 0 END, "
                + COL_TIME + " ASC";

        Cursor cursor = db.query(TABLE_MEMO, null,
                COL_DONE + " = 0", null,
                null, null, orderBy);

        if (cursor != null) {
            while (cursor.moveToNext()) {
                list.add(cursorToMemo(cursor));
            }
            cursor.close();
        }
        return list;
    }

    /**
     * 根据 ID 查询单条
     */
    public MemoItem getMemoById(String id) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_MEMO, null,
                COL_ID + " = ?", new String[]{id},
                null, null, null);

        MemoItem memo = null;
        if (cursor != null && cursor.moveToFirst()) {
            memo = cursorToMemo(cursor);
            cursor.close();
        }
        return memo;
    }

    /**
     * 统计信息
     */
    public int getTotalCount() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_MEMO, null);
        int count = 0;
        if (cursor != null && cursor.moveToFirst()) {
            count = cursor.getInt(0);
            cursor.close();
        }
        return count;
    }

    public int getUndoneCount() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_MEMO + " WHERE " + COL_DONE + " = 0", null);
        int count = 0;
        if (cursor != null && cursor.moveToFirst()) {
            count = cursor.getInt(0);
            cursor.close();
        }
        return count;
    }

    // ==========================================
    //              工具方法
    // ==========================================

    /**
     * 将 Cursor 行转为 MemoItem 对象
     */
    private MemoItem cursorToMemo(Cursor cursor) {
        MemoItem memo = new MemoItem();
        memo.setId(cursor.getString(cursor.getColumnIndexOrThrow(COL_ID)));
        memo.setTitle(cursor.getString(cursor.getColumnIndexOrThrow(COL_TITLE)));
        memo.setTime(cursor.getString(cursor.getColumnIndexOrThrow(COL_TIME)));
        memo.setContent(cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTENT)));
        memo.setSource(cursor.getString(cursor.getColumnIndexOrThrow(COL_SOURCE)));
        memo.setCreated(cursor.getString(cursor.getColumnIndexOrThrow(COL_CREATED)));
        memo.setDone(cursor.getInt(cursor.getColumnIndexOrThrow(COL_DONE)) == 1);
        return memo;
    }
}
