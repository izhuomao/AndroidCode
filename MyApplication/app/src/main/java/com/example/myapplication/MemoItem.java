package com.example.myapplication;

/**
 * 备忘录数据模型
 */
public class MemoItem {
    private String id;          // 唯一ID (时间戳)
    private String title;       // 标题
    private String time;        // 提醒时间 (yyyy-MM-dd HH:mm 或 "无")
    private String content;     // 详细内容
    private String source;      // 来源: "voice" 或 "manual"
    private String created;     // 创建时间
    private boolean done;       // 是否完成

    public MemoItem() {}

    public MemoItem(String id, String title, String time, String content, String source, String created) {
        this.id = id;
        this.title = title;
        this.time = time;
        this.content = content;
        this.source = source;
        this.created = created;
        this.done = false;
    }

    // Getters & Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getCreated() { return created; }
    public void setCreated(String created) { this.created = created; }

    public boolean isDone() { return done; }
    public void setDone(boolean done) { this.done = done; }

    /**
     * 是否有有效的提醒时间
     */
    public boolean hasRemindTime() {
        return time != null && !time.equals("无") && !time.isEmpty();
    }
}
