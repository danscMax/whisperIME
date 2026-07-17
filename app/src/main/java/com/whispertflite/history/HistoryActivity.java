package com.whispertflite.history;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.whispertflite.R;
import com.whispertflite.models.ModelInfo;
import com.whispertflite.models.ModelRegistry;
import com.whispertflite.utils.ThemeUtils;

import java.util.ArrayList;
import java.util.List;

public class HistoryActivity extends AppCompatActivity {

    private HistoryDb db;
    private HistoryAdapter adapter;
    private View emptyState;
    private String query = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtils.applyPalette(this);
        ThemeUtils.applyGlass(this);
        setContentView(R.layout.activity_history);
        ThemeUtils.setStatusBarAppearance(this);
        db = HistoryDb.get(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_history);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_clear_all) {
                confirmClearAll();
                return true;
            }
            return false;
        });

        emptyState = findViewById(R.id.emptyState);
        RecyclerView recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new HistoryAdapter();
        recycler.setAdapter(adapter);

        // Frosted top bar: the list scrolls under it, blurred (API 31+) / translucent glass otherwise.
        com.whispertflite.ui.FrostedBlurView blurBar = findViewById(R.id.blurBar);
        blurBar.attach(recycler);
        int bg = androidx.core.content.ContextCompat.getColor(this, R.color.glass_screen);
        blurBar.setGlass(
                androidx.core.graphics.ColorUtils.setAlphaComponent(bg, 0xEE),   // frosted veil over the blur
                androidx.core.content.ContextCompat.getColor(this, R.color.glass_card_brd));
        blurBar.post(() -> recycler.setPadding(recycler.getPaddingLeft(), blurBar.getHeight(),
                recycler.getPaddingRight(), recycler.getPaddingBottom()));
        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) { blurBar.markDirty(); }
        });

        EditText search = findViewById(R.id.searchField);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString();
                reload();
            }
        });

        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        List<HistoryDb.Entry> entries = db.list(query, 500);
        adapter.setItems(entries);
        emptyState.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void confirmClearAll() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.history_clear_all)
                .setMessage(R.string.history_clear_all_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.history_clear_all, (d, w) -> {
                    db.clearAll();
                    reload();
                })
                .show();
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("transcription", text));
        Toast.makeText(this, R.string.history_copied, Toast.LENGTH_SHORT).show();
    }

    private void share(String text) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(intent, getString(R.string.history_share)));
    }

    private String meta(HistoryDb.Entry e) {
        List<String> parts = new ArrayList<>();
        if (!TextUtils.isEmpty(e.modelId)) parts.add(modelLabel(e.modelId));
        if (!TextUtils.isEmpty(e.lang)) parts.add(e.lang);
        parts.add(DateUtils.getRelativeTimeSpanString(
                e.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString());
        return TextUtils.join(" · ", parts);
    }

    /** Map a stored model id to "displayName · engine"; fall back to the raw string (legacy rows). */
    private String modelLabel(String stored) {
        ModelInfo m = ModelRegistry.byId(stored);
        if (m == null) return stored;
        String engine = getString(m.engine == ModelInfo.Engine.WHISPER_CPP
                ? R.string.catalog_engine_whispercpp : R.string.main_badge_tflite);
        return m.displayName + " · " + engine;
    }

    private class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {
        private final List<HistoryDb.Entry> items = new ArrayList<>();

        void setItems(List<HistoryDb.Entry> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_history, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            HistoryDb.Entry e = items.get(position);
            h.text.setText(e.text);
            h.meta.setText(meta(e));
            h.itemView.setOnClickListener(v -> copy(e.text));
            h.itemView.setOnLongClickListener(v -> {
                PopupMenu menu = new PopupMenu(HistoryActivity.this, v);
                menu.getMenu().add(0, 1, 0, R.string.history_share);
                menu.getMenu().add(0, 2, 1, R.string.history_delete);
                menu.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 1) {
                        share(e.text);
                    } else {
                        db.delete(e.id);
                        reload();
                    }
                    return true;
                });
                menu.show();
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView text;
            final TextView meta;
            VH(@NonNull View v) {
                super(v);
                text = v.findViewById(R.id.text);
                meta = v.findViewById(R.id.meta);
            }
        }
    }
}
