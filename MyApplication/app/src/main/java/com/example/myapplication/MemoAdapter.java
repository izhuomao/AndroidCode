package com.example.myapplication;

import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * 备忘录列表适配器
 */
public class MemoAdapter extends RecyclerView.Adapter<MemoAdapter.MemoViewHolder> {

    public interface OnMemoActionListener {
        void onToggleDone(int position, boolean isDone);
        void onDelete(int position);
    }

    private List<MemoItem> memoList;
    private OnMemoActionListener listener;

    public MemoAdapter(List<MemoItem> memoList, OnMemoActionListener listener) {
        this.memoList = memoList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public MemoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_memo, parent, false);
        return new MemoViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MemoViewHolder holder, int position) {
        MemoItem item = memoList.get(position);

        holder.tvTitle.setText(item.getTitle());
        holder.tvContent.setText(item.getContent());
        holder.cbDone.setChecked(item.isDone());

        // 提醒时间
        if (item.hasRemindTime()) {
            holder.tvTime.setText("⏰ " + item.getTime());
            holder.tvTime.setVisibility(View.VISIBLE);
        } else {
            holder.tvTime.setVisibility(View.GONE);
        }

        // 来源标记
        if ("voice".equals(item.getSource())) {
            holder.tvSource.setText("🎤 语音录入");
        } else {
            holder.tvSource.setText("✍️ 手动录入");
        }

        // 完成状态 - 划线效果
        if (item.isDone()) {
            holder.tvTitle.setPaintFlags(holder.tvTitle.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            holder.tvContent.setPaintFlags(holder.tvContent.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            holder.tvTitle.setAlpha(0.5f);
            holder.tvContent.setAlpha(0.5f);
        } else {
            holder.tvTitle.setPaintFlags(holder.tvTitle.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
            holder.tvContent.setPaintFlags(holder.tvContent.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
            holder.tvTitle.setAlpha(1.0f);
            holder.tvContent.setAlpha(1.0f);
        }

        // 勾选完成
        holder.cbDone.setOnCheckedChangeListener((buttonView, isChecked) -> {
            int pos = holder.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && listener != null) {
                listener.onToggleDone(pos, isChecked);
            }
        });

        // 删除
        holder.btnDelete.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && listener != null) {
                listener.onDelete(pos);
            }
        });
    }

    @Override
    public int getItemCount() {
        return memoList.size();
    }

    static class MemoViewHolder extends RecyclerView.ViewHolder {
        CheckBox cbDone;
        TextView tvTitle, tvContent, tvTime, tvSource;
        ImageButton btnDelete;

        public MemoViewHolder(@NonNull View itemView) {
            super(itemView);
            cbDone = itemView.findViewById(R.id.cb_done);
            tvTitle = itemView.findViewById(R.id.tv_memo_title);
            tvContent = itemView.findViewById(R.id.tv_memo_content);
            tvTime = itemView.findViewById(R.id.tv_memo_time);
            tvSource = itemView.findViewById(R.id.tv_memo_source);
            btnDelete = itemView.findViewById(R.id.btn_memo_delete);
        }
    }
}
