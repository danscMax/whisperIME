package com.whispertflite.models;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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
    private Engine filter; // null = all

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtils.applyPalette(this);
        // The warm glass is a light surface in both themes. It gets there through its own tokens,
        // never through a local night mode: forcing MODE_NIGHT_NO here leaked the light config into
        // the shared resources, so every popup inflated afterwards (even back on the main screen)
        // came out light-on-dark.
        ThemeUtils.applyGlass(this);
        setContentView(R.layout.activity_model_catalog);
        ThemeUtils.setStatusBarAppearance(this);

        manager = ModelDownloadManager.get(this);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        RecyclerView recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        adapter.setHasStableIds(true);
        recycler.setAdapter(adapter);

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
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
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
        // The confirm is destructive; the shared dialog theme keeps both actions neutral ink.
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setTextColor(color(R.color.glass_danger));
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
                    .inflate(R.layout.item_model, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            ModelInfo m = items.get(position);
            ModelState state = manager.stateOf(m);

            h.name.setText(m.displayName);
            h.meta.setText(meta(m));

            // Engine badge is tinted by engine, not by the app palette: the reader is telling two
            // runtimes apart, and a palette accent here just fights the card.
            boolean tflite = m.engine == Engine.TFLITE;
            h.engineChip.setText(tflite ? R.string.catalog_engine_tflite : R.string.catalog_engine_whispercpp);
            tint(h.engineChip,
                    tflite ? R.color.glass_engine_tflite_bg : R.color.glass_engine_cpp_bg,
                    tflite ? R.color.glass_engine_tflite_fg : R.color.glass_engine_cpp_fg);

            // Warm liquid glass: the active model glows warm (dark icon tile + warm sheen + warm shadow),
            // the rest are light frosted cards with a dark-on-light icon.
            boolean active = state == ModelState.ACTIVE;
            h.icon.setBackgroundResource(active ? R.drawable.icon_tile_active : R.drawable.icon_tile);
            h.icon.setImageTintList(ColorStateList.valueOf(
                    color(active ? R.color.glass_on_solid : R.color.glass_tile_ink)));
            h.cardSheen.setBackgroundResource(active ? R.drawable.card_sheen_warm : R.drawable.card_sheen_glass);
            h.card.setCardBackgroundColor(color(active ? R.color.glass_card_active : R.color.glass_card));
            h.card.setStrokeWidth(Math.round(getResources().getDisplayMetrics().density));
            h.card.setStrokeColor(color(active ? R.color.glass_card_active_brd : R.color.glass_card_brd));
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                h.card.setOutlineSpotShadowColor(
                        color(active ? R.color.glass_shadow_active : R.color.glass_shadow));
            }

            // status chip: Active (warm, echoing the card), or "engine soon" for not-yet-wired gguf
            boolean soon = m.engine == Engine.WHISPER_CPP && !ModelRegistry.WHISPER_CPP_READY;
            h.statusChip.setVisibility(active || soon ? View.VISIBLE : View.GONE);
            if (active) {
                h.statusChip.setText(R.string.catalog_active);
                tint(h.statusChip, R.color.glass_warm_bg, R.color.glass_warm_ink);
            } else if (soon) {
                h.statusChip.setText(R.string.catalog_engine_soon);
                tint(h.statusChip, R.color.glass_pill, R.color.glass_ink_dim);
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
            final View progressGroup, buttonRow, cardSheen;
            final ImageView icon;
            final LinearProgressIndicator progress;
            final ImageButton cancelButton;
            final MaterialButton downloadButton, useButton, deleteButton;

            VH(@NonNull View v) {
                super(v);
                card = (com.google.android.material.card.MaterialCardView) v;
                icon = v.findViewById(R.id.icon);
                cardSheen = v.findViewById(R.id.cardSheen);
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

    private int color(@ColorRes int res) {
        return ContextCompat.getColor(this, res);
    }

    /** Paint a chip as a soft tinted pill. */
    private void tint(Chip chip, @ColorRes int bg, @ColorRes int fg) {
        chip.setChipBackgroundColor(ColorStateList.valueOf(color(bg)));
        chip.setTextColor(color(fg));
    }
}
