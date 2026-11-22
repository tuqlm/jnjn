package com.retroforge.gba.screen;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.retroforge.gba.R;
import com.retroforge.gba.engine.LineEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LineListAdapter extends RecyclerView.Adapter<LineListAdapter.VH> {

    public interface OnLineClick {
        void onClick(LineEntry entry);
    }

    private final List<LineEntry> items = new ArrayList<>();
    private final OnLineClick callback;

    public LineListAdapter(OnLineClick callback) {
        this.callback = callback;
    }

    public void setItems(List<LineEntry> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.row_line_entry, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        LineEntry e = items.get(pos);
        h.tvHeader.setText(String.format(Locale.US, "#%d | 0x%X | %dB", e.id, e.offset, e.byteLen));
        h.tvSrc.setText(e.original);
        if (e.hasTranslation()) {
            h.tvVi.setText(e.vietnamese);
            h.tvVi.setTextColor(ContextCompat.getColor(h.itemView.getContext(), R.color.rf_emerald));
        } else {
            h.tvVi.setText("(Chua dich - cham de sua)");
            h.tvVi.setTextColor(ContextCompat.getColor(h.itemView.getContext(), R.color.rf_muted));
        }
        h.itemView.setOnClickListener(v -> callback.onClick(e));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tvHeader, tvSrc, tvVi;

        VH(@NonNull View v) {
            super(v);
            tvHeader = v.findViewById(R.id.tvRowHeader);
            tvSrc = v.findViewById(R.id.tvRowSrc);
            tvVi = v.findViewById(R.id.tvRowVi);
        }
    }
}
