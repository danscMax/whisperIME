package com.whispertflite.models;

import android.content.SharedPreferences;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.whispertflite.R;
import com.whispertflite.models.ModelInfo.Engine;
import com.whispertflite.utils.ThemeUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Catalog of ASR models with per-model download / select / delete actions. */
public class ModelCatalogActivity extends AppCompatActivity implements ModelDownloadManager.Listener {

    private ModelDownloadManager manager;
    private SharedPreferences prefs;
    private Adapter adapter;
    private TextView storageUsed;
    /** Extra flag: render the iOS liquid-glass variant of this screen (style comparison). */
    static final String EXTRA_GLASS = "glass";
    private boolean glass;
    private Engine filter; // null = all

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        glass = getIntent().getBooleanExtra(EXTRA_GLASS, false);
        if (glass) getDelegate().setLocalNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
        else ThemeUtils.applyNightMode(this);
        super.onCreate(savedInstanceState);
        ThemeUtils.applyPalette(this);
        setContentView(glass ? R.layout.activity_model_catalog_glass : R.layout.activity_model_catalog);
        ThemeUtils.setStatusBarAppearance(this);

        manager = ModelDownloadManager.get(this);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        if (!glass) {
            toolbar.inflateMenu(R.menu.menu_catalog);
            toolbar.setOnMenuItemClickListener(item -> {
                startActivity(new Intent(this, ModelCatalogActivity.class).putExtra(EXTRA_GLASS, true));
                return true;
            });
        }

        RecyclerView recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        adapter.setHasStableIds(true);
        recycler.setAdapter(adapter);

        // Frosted top bar + footer (default style only): cards scroll under them, blurred (API 31+).
        if (!glass) {
            com.whispertflite.ui.FrostedBlurView blurBar = findViewById(R.id.blurBar);
            com.whispertflite.ui.FrostedBlurView footerBar = findViewById(R.id.footerBar);
            int glassTint = androidx.core.graphics.ColorUtils.setAlphaComponent(
                    androidx.core.content.ContextCompat.getColor(this, R.color.aurora_bg), 0xEE);
            int line = androidx.core.content.ContextCompat.getColor(this, R.color.aurora_panel_brd);
            blurBar.attach(recycler);
            blurBar.setGlass(glassTint, line);
            footerBar.attach(recycler);
            footerBar.setGlass(glassTint, line);
            recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                    blurBar.markDirty();
                    footerBar.markDirty();
                }
            });
            blurBar.post(() -> recycler.setPadding(recycler.getPaddingLeft(), blurBar.getHeight(),
                    recycler.getPaddingRight(), footerBar.getHeight()));
        }

        ChipGroup filterGroup = findViewById(R.id.filterGroup);
        filterGroup.setOnCheckedStateChangeListener((group, ids) -> {
            int id = ids.isEmpty() ? R.id.filterAll : ids.get(0);
            if (id == R.id.filterTflite) filter = Engine.TFLITE;
            else if (id == R.id.filterWhisperCpp) filter = Engine.WHISPER_CPP;
            else filter = null;
            refresh();
        });

        storageUsed = findViewById(R.id.storageUsed);

        MaterialSwitch wifiOnly = findViewById(R.id.wifiOnlySwitch);
        wifiOnly.setChecked(prefs.getBoolean(ModelDownloadManager.PREF_WIFI_ONLY, true));
        wifiOnly.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean(ModelDownloadManager.PREF_WIFI_ONLY, checked).apply());

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        manager.addListener(this);
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        manager.removeListener(this);
    }

    /** Rebuild the filtered list and the storage footer. */
    private void refresh() {
        List<ModelInfo> visible = new ArrayList<>();
        for (ModelInfo m : ModelRegistry.all()) {
            if (filter == null || m.engine == filter) visible.add(m);
        }
        adapter.setItems(visible);
        updateStorage();
    }

    private void updateStorage() {
        long total = 0;
        File dir = getExternalFilesDir(null);
        for (ModelInfo m : ModelRegistry.all()) {
            File f = new File(dir, m.filename);
            if (f.exists()) total += f.length();
        }
        storageUsed.setText(getString(R.string.catalog_storage_used,
                Formatter.formatShortFileSize(this, total)));
    }

    /** Refresh a single row without disrupting the rest of the list. */
    private void refreshRow(String modelId) {
        int pos = adapter.indexOf(modelId);
        if (pos >= 0) adapter.notifyItemChanged(pos);
    }

    // --- ModelDownloadManager.Listener (main thread) ---

    @Override
    public void onProgress(String modelId, long bytes, long total, long bytesPerSec) {
        adapter.progress.put(modelId, new long[]{bytes, total, bytesPerSec});
        refreshRow(modelId);
    }

    @Override
    public void onDone(String modelId) {
        adapter.progress.remove(modelId);
        refreshRow(modelId);
        updateStorage();
    }

    @Override
    public void onError(String modelId, String message) {
        adapter.progress.remove(modelId);
        refreshRow(modelId);
        int msg = ModelDownloadManager.ERR_WIFI.equals(message)
                ? R.string.catalog_err_wifi : R.string.catalog_err_download;
        Snackbar.make(storageUsed, msg, Snackbar.LENGTH_LONG).show();
    }

    // --- actions ---

    private void use(ModelInfo model) {
        if (model.engine == Engine.WHISPER_CPP && !ModelRegistry.WHISPER_CPP_READY) {
            Snackbar.make(storageUsed, R.string.catalog_engine_soon_hint, Snackbar.LENGTH_LONG).show();
            return;
        }
        prefs.edit().putString(ModelDownloadManager.PREF_SELECTED_MODEL, model.id).apply();
        refresh();
    }

    private void confirmDelete(ModelInfo model) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.catalog_delete)
                .setMessage(getString(R.string.catalog_delete_confirm, model.displayName))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.catalog_delete, (d, w) -> {
                    File f = new File(getExternalFilesDir(null), model.filename);
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    if (model.id.equals(prefs.getString(ModelDownloadManager.PREF_SELECTED_MODEL, null))) {
                        prefs.edit().remove(ModelDownloadManager.PREF_SELECTED_MODEL).apply();
                    }
                    refresh();
                })
                .show();
    }

    /** Number of models currently on disk (to hide delete on the last one). */
    private int downloadedCount() {
        int n = 0;
        File dir = getExternalFilesDir(null);
        for (ModelInfo m : ModelRegistry.all()) {
            if (new File(dir, m.filename).exists()) n++;
        }
        return n;
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        private final List<ModelInfo> items = new ArrayList<>();
        // modelId -> {bytes, total, bytesPerSec}
        final java.util.HashMap<String, long[]> progress = new java.util.HashMap<>();

        void setItems(List<ModelInfo> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        int indexOf(String modelId) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).id.equals(modelId)) return i;
            }
            return -1;
        }

        @Override
        public long getItemId(int position) {
            return items.get(position).id.hashCode();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(glass ? R.layout.item_model_glass : R.layout.item_model, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            ModelInfo m = items.get(position);
            ModelState state = manager.stateOf(m);

            // Leading engine icon tile: gradient blue (TFLite) / violet (whisper.cpp).
            View icon = h.itemView.findViewById(R.id.icon);
            if (icon != null) {
                icon.setBackgroundResource(m.engine == Engine.WHISPER_CPP
                        ? R.drawable.icon_tile_whispercpp : R.drawable.icon_tile_tflite);
            }

            h.name.setText(m.displayName);
            h.engineChip.setText(m.engine == Engine.TFLITE
                    ? R.string.catalog_engine_tflite : R.string.catalog_engine_whispercpp);
            h.meta.setText(meta(m));

            // Aurora: cards stay on the translucent panel; the active one is marked with a palette
            // accent hairline instead of a light fill (which would break the dark surface).
            boolean active = state == ModelState.ACTIVE;
            float density = getResources().getDisplayMetrics().density;
            h.card.setStrokeWidth((int) ((active ? 1.5f : 1f) * density));
            h.card.setStrokeColor(active
                    ? getColorAttr(androidx.appcompat.R.attr.colorPrimary)
                    : androidx.core.content.ContextCompat.getColor(
                            ModelCatalogActivity.this, R.color.aurora_panel_brd));

            // status chip: Active, or "engine soon" for not-yet-wired gguf
            boolean soon = m.engine == Engine.WHISPER_CPP && !ModelRegistry.WHISPER_CPP_READY;
            if (active) {
                h.statusChip.setVisibility(View.VISIBLE);
                h.statusChip.setText(R.string.catalog_active);
            } else if (soon) {
                h.statusChip.setVisibility(View.VISIBLE);
                h.statusChip.setText(R.string.catalog_engine_soon);
            } else {
                h.statusChip.setVisibility(View.GONE);
            }

            boolean downloading = state == ModelState.DOWNLOADING;
            h.progressGroup.setVisibility(downloading ? View.VISIBLE : View.GONE);
            h.buttonRow.setVisibility(downloading ? View.GONE : View.VISIBLE);

            if (downloading) {
                long[] p = progress.get(m.id);
                if (p != null && p[1] > 0) {
                    h.progress.setIndeterminate(false);
                    h.progress.setProgress((int) (p[0] * 100 / p[1]));
                    h.progressText.setText(getString(R.string.catalog_progress,
                            p[0] / 1048576f, p[1] / 1048576f, p[2] / 1048576f));
                } else {
                    h.progress.setIndeterminate(true);
                    h.progressText.setText(R.string.catalog_starting);
                }
                h.cancelButton.setOnClickListener(v -> {
                    manager.cancel(m.id);
                    progress.remove(m.id);
                    refreshRow(m.id);
                });
                return;
            }

            h.downloadButton.setVisibility(state == ModelState.AVAILABLE ? View.VISIBLE : View.GONE);
            h.useButton.setVisibility(state == ModelState.DOWNLOADED ? View.VISIBLE : View.GONE);
            // Hide delete for the active-only case and when it is the sole downloaded model.
            boolean canDelete = (state == ModelState.DOWNLOADED)
                    || (active && downloadedCount() > 1);
            h.deleteButton.setVisibility(canDelete ? View.VISIBLE : View.GONE);

            h.downloadButton.setOnClickListener(v -> {
                manager.download(m);
                refreshRow(m.id);
            });
            h.useButton.setOnClickListener(v -> use(m));
            h.deleteButton.setOnClickListener(v -> confirmDelete(m));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final com.google.android.material.card.MaterialCardView card;
            final TextView name, meta, progressText;
            final Chip engineChip, statusChip;
            final View progressGroup, buttonRow;
            final LinearProgressIndicator progress;
            final ImageButton cancelButton;
            final MaterialButton downloadButton, useButton, deleteButton;

            VH(@NonNull View v) {
                super(v);
                card = (com.google.android.material.card.MaterialCardView) v;
                name = v.findViewById(R.id.name);
                meta = v.findViewById(R.id.meta);
                engineChip = v.findViewById(R.id.engineChip);
                statusChip = v.findViewById(R.id.statusChip);
                progressGroup = v.findViewById(R.id.progressGroup);
                buttonRow = v.findViewById(R.id.buttonRow);
                progress = v.findViewById(R.id.progress);
                progressText = v.findViewById(R.id.progressText);
                cancelButton = v.findViewById(R.id.cancelButton);
                downloadButton = v.findViewById(R.id.downloadButton);
                useButton = v.findViewById(R.id.useButton);
                deleteButton = v.findViewById(R.id.deleteButton);
            }
        }
    }

    private String meta(ModelInfo m) {
        String langs = m.englishOnly
                ? getString(R.string.catalog_english_only)
                : getString(R.string.catalog_languages, m.languages);
        String size = Formatter.formatShortFileSize(this, m.sizeBytes);
        int hint = m.qualityClass >= 3 ? R.string.catalog_hint_best
                : (m.qualityClass == 2 ? R.string.catalog_hint_balanced : R.string.catalog_hint_fast);
        return langs + " · " + size + " · " + getString(hint);
    }

    private int getColorAttr(int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }
}
