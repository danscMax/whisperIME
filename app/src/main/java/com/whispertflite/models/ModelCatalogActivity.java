package com.whispertflite.models;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.format.Formatter;
import android.text.style.ForegroundColorSpan;
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
import androidx.recyclerview.widget.SimpleItemAnimator;

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
    private int filterId = R.id.filterAll; // which filter chip is checked
    // The chosen palette accent (bypasses the glass overlay), so the "active" highlight matches the
    // toggles and orb instead of the old fixed amber. [0] = accent, [1] = its soft container tint.
    private int paletteAccent;
    private int paletteAccentSoft;

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

        int[] pal = ThemeUtils.orbColors(this);   // palette accent + its soft container, bypassing glass
        paletteAccent = pal[0];
        paletteAccentSoft = pal[1];

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        RecyclerView recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        adapter.setHasStableIds(true);
        recycler.setAdapter(adapter);
        // A running download calls notifyItemChanged on its row many times a second; the default
        // change animation crossfades the whole card each time, which reads as a flicker. Turn it
        // off so the progress row updates in place.
        if (recycler.getItemAnimator() instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) recycler.getItemAnimator()).setSupportsChangeAnimations(false);
        }

        ChipGroup filterGroup = findViewById(R.id.filterGroup);
        filterGroup.setOnCheckedStateChangeListener((group, ids) -> {
            filterId = ids.isEmpty() ? R.id.filterAll : ids.get(0);
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
            if (matches(m, filterId)) visible.add(m);
        }
        adapter.setItems(visible);
        updateStorage();
    }

    /** Which filter chip shows which models. Parakeet & GigaAM are both SHERPA — split by model id. */
    private boolean matches(ModelInfo m, int id) {
        if (id == R.id.filterParakeet) return m.engine == Engine.SHERPA && m.id.contains("parakeet");
        if (id == R.id.filterGigaam)   return m.engine == Engine.SHERPA && m.id.contains("gigaam");
        if (id == R.id.filterTflite)   return m.engine == Engine.TFLITE;
        if (id == R.id.filterWhisperCpp) return m.engine == Engine.WHISPER_CPP;
        return true; // filterAll
    }

    private void updateStorage() {
        long total = 0;
        File dir = getExternalFilesDir(null);
        for (ModelInfo m : ModelRegistry.all()) {
            for (ModelInfo.Asset a : m.files) { // sum every file (sherpa models have several)
                File f = new File(dir, a.relPath);
                if (f.exists()) total += f.length();
            }
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
        prefs.edit().putString(ModelDownloadManager.PREF_SELECTED_MODEL, model.id).apply();
        refresh();
    }

    private void confirmDelete(ModelInfo model) {
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.catalog_delete)
                .setMessage(getString(R.string.catalog_delete_confirm, model.displayName))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.catalog_delete, (d, w) -> {
                    manager.delete(model); // removes all files + prunes the sherpa dir
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
        for (ModelInfo m : ModelRegistry.all()) {
            if (manager.isPresent(m)) n++;
        }
        return n;
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_MODEL = 1;

    /** A section header row (one per engine group). */
    private static final class Header {
        final String title, sub;
        Header(String title, String sub) { this.title = title; this.sub = sub; }
    }

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final List<Object> rows = new ArrayList<>();   // Header | ModelInfo
        // modelId -> {bytes, total, bytesPerSec}
        final java.util.HashMap<String, long[]> progress = new java.util.HashMap<>();

        /**
         * Group the visible models into headed sections. Parakeet (multilingual lead) first, then GigaAM
         * (Russian specialist), then TFLite, and whisper.cpp last — kept as the slower fallback.
         */
        void setItems(List<ModelInfo> newItems) {
            rows.clear();
            addSherpaSection(newItems, "parakeet",
                    getString(R.string.catalog_group_parakeet), getString(R.string.catalog_group_parakeet_sub));
            addSherpaSection(newItems, "gigaam",
                    getString(R.string.catalog_group_gigaam), getString(R.string.catalog_group_gigaam_sub));
            addSection(newItems, Engine.TFLITE,
                    getString(R.string.catalog_engine_tflite), getString(R.string.catalog_section_tflite_sub));
            addSection(newItems, Engine.WHISPER_CPP,
                    getString(R.string.catalog_engine_whispercpp), getString(R.string.catalog_section_cpp_sub));
            notifyDataSetChanged();
        }

        private void addSection(List<ModelInfo> all, Engine engine, String title, String sub) {
            List<ModelInfo> group = new ArrayList<>();
            for (ModelInfo m : all) if (m.engine == engine) group.add(m);
            if (group.isEmpty()) return;
            rows.add(new Header(title, sub));
            rows.addAll(group);
        }

        /** A sherpa sub-group (Parakeet vs GigaAM) — both are Engine.SHERPA, split by model id. */
        private void addSherpaSection(List<ModelInfo> all, String idContains, String title, String sub) {
            List<ModelInfo> group = new ArrayList<>();
            for (ModelInfo m : all) if (m.engine == Engine.SHERPA && m.id.contains(idContains)) group.add(m);
            if (group.isEmpty()) return;
            rows.add(new Header(title, sub));
            rows.addAll(group);
        }

        int indexOf(String modelId) {
            for (int i = 0; i < rows.size(); i++) {
                Object r = rows.get(i);
                if (r instanceof ModelInfo && ((ModelInfo) r).id.equals(modelId)) return i;
            }
            return -1;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof Header ? TYPE_HEADER : TYPE_MODEL;
        }

        @Override
        public long getItemId(int position) {
            Object r = rows.get(position);
            return r instanceof Header ? ("h:" + ((Header) r).title).hashCode()
                    : ((ModelInfo) r).id.hashCode();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_HEADER) {
                return new HeaderVH(inf.inflate(R.layout.item_model_header, parent, false));
            }
            return new VH(inf.inflate(R.layout.item_model, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object r = rows.get(position);
            if (holder instanceof HeaderVH) {
                Header hd = (Header) r;
                ((HeaderVH) holder).title.setText(hd.title);
                ((HeaderVH) holder).sub.setText(hd.sub);
                return;
            }
            bindModel((VH) holder, (ModelInfo) r);
        }

        private void bindModel(@NonNull VH h, ModelInfo m) {
            ModelState state = manager.stateOf(m);

            h.name.setText(m.displayName);
            h.meta.setText(meta(m));

            // The active model glows in the CHOSEN PALETTE accent (matching the toggles and orb); the
            // rest are light frosted cards. The engine is shown by the section header, not a per-card badge.
            boolean active = state == ModelState.ACTIVE;
            h.cardSheen.setBackgroundResource(R.drawable.card_sheen_glass);
            h.card.setCardBackgroundColor(active ? withAlpha(paletteAccentSoft, 0xD8) : color(R.color.glass_card));
            h.card.setStrokeWidth(Math.round(getResources().getDisplayMetrics().density));
            h.card.setStrokeColor(active ? withAlpha(paletteAccent, 0x8A) : color(R.color.glass_card_brd));
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                h.card.setOutlineSpotShadowColor(active ? paletteAccent : color(R.color.glass_shadow));
            }

            // status chip: shown only for the active model, echoing the card's palette accent
            h.statusChip.setVisibility(active ? View.VISIBLE : View.GONE);
            if (active) {
                h.statusChip.setText(R.string.catalog_active);
                h.statusChip.setChipBackgroundColor(ColorStateList.valueOf(withAlpha(paletteAccentSoft, 0xFF)));
                h.statusChip.setTextColor(paletteAccent);
            }

            boolean downloading = state == ModelState.DOWNLOADING;
            h.progressGroup.setVisibility(downloading ? View.VISIBLE : View.GONE);

            if (downloading) {
                h.useButton.setVisibility(View.GONE);
                h.downloadButton.setVisibility(View.GONE);
                h.deleteButton.setVisibility(View.GONE);
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
            return rows.size();
        }

        class HeaderVH extends RecyclerView.ViewHolder {
            final TextView title, sub;
            HeaderVH(@NonNull View v) {
                super(v);
                title = v.findViewById(R.id.headerTitle);
                sub = v.findViewById(R.id.headerSub);
            }
        }

        class VH extends RecyclerView.ViewHolder {
            final com.google.android.material.card.MaterialCardView card;
            final TextView name, meta, progressText;
            final Chip statusChip;
            final View progressGroup, cardSheen;
            final LinearProgressIndicator progress;
            final ImageButton cancelButton, downloadButton, deleteButton;
            final MaterialButton useButton;

            VH(@NonNull View v) {
                super(v);
                card = (com.google.android.material.card.MaterialCardView) v;
                cardSheen = v.findViewById(R.id.cardSheen);
                name = v.findViewById(R.id.name);
                meta = v.findViewById(R.id.meta);
                statusChip = v.findViewById(R.id.statusChip);
                progressGroup = v.findViewById(R.id.progressGroup);
                progress = v.findViewById(R.id.progress);
                progressText = v.findViewById(R.id.progressText);
                cancelButton = v.findViewById(R.id.cancelButton);
                downloadButton = v.findViewById(R.id.downloadButton);
                useButton = v.findViewById(R.id.useButton);
                deleteButton = v.findViewById(R.id.deleteButton);
            }
        }
    }

    private CharSequence meta(ModelInfo m) {
        String langs = m.englishOnly
                ? getString(R.string.catalog_english_only)
                : getString(R.string.catalog_languages, m.languages);
        String size = Formatter.formatShortFileSize(this, m.sizeBytes);
        int speed = m.speedClass <= 1 ? R.string.catalog_speed_fast
                : (m.speedClass == 2 ? R.string.catalog_speed_mid : R.string.catalog_speed_slow);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(langs).append(" · ").append(size).append(" · ").append(getString(speed)).append(" · ");
        appendQualityDots(sb, m.qualityClass);
        if (m.isHeavy()) sb.append(" · ").append(getString(R.string.catalog_heavy));
        return sb;
    }

    /** Three dots: filled (warm accent) up to qualityClass, the rest hollow (faint) — a quality scale. */
    private void appendQualityDots(SpannableStringBuilder sb, int qualityClass) {
        int q = Math.max(1, Math.min(3, qualityClass));
        int filled = paletteAccent;   // the chosen palette accent, matching the rest of the UI
        int hollow = color(R.color.glass_ink_faint);
        for (int i = 1; i <= 3; i++) {
            int start = sb.length();
            sb.append((char) (i <= q ? 0x25CF : 0x25CB));
            sb.setSpan(new ForegroundColorSpan(i <= q ? filled : hollow),
                    start, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private int color(@ColorRes int res) {
        return ContextCompat.getColor(this, res);
    }

    /** The colour with a fixed alpha (0..255), keeping its RGB. */
    private static int withAlpha(int argb, int alpha) {
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    /** Paint a chip as a soft tinted pill. */
    private void tint(Chip chip, @ColorRes int bg, @ColorRes int fg) {
        chip.setChipBackgroundColor(ColorStateList.valueOf(color(bg)));
        chip.setTextColor(color(fg));
    }
}
