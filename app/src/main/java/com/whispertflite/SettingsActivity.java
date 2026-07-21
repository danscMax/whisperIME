package com.whispertflite;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.whispertflite.history.HistoryActivity;
import com.whispertflite.models.ModelDownloadManager;
import com.whispertflite.models.ModelInfo;
import com.whispertflite.models.ModelRegistry;
import com.whispertflite.utils.ThemeUtils;

public class SettingsActivity extends AppCompatActivity {

    // Palette keys map to ThemeUtils overlays; dynamic (last) uses a gradient swatch.
    private static final String[] PALETTE_KEYS = {"teal", "terracotta", "indigo", "forest", "dynamic"};
    private static final int[] PALETTE_COLORS = {0xFF006A60, 0xFF9C4234, 0xFF3F51E0, 0xFF3E6837, 0};

    private SharedPreferences sp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtils.applyPalette(this);
        ThemeUtils.applyGlass(this);
        setContentView(R.layout.activity_settings);
        ThemeUtils.setStatusBarAppearance(this);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        sp = PreferenceManager.getDefaultSharedPreferences(this);

        buildPaletteRow();
        buildThemeToggle();
        buildOrbToggle();
        buildModeToggle();

        // simpleChinese is an existing upstream key; hapticFeedback/speakResult are new
        // settings keys consumed when MainActivity is rewired in Task 1.3.
        bindSwitch(R.id.switch_haptic, "hapticFeedback", true);
        bindSwitch(R.id.switch_tts, "speakResult", false);
        bindSwitch(R.id.switch_simple_chinese, "simpleChinese", false);
        bindVocabulary();   // A3: custom vocabulary prompt
        bindSwitch(R.id.switch_history, "historyEnabled", true);
        bindSwitch(R.id.switch_history_ime, "historyFromIme", true);

        findViewById(R.id.row_history_open).setOnClickListener(
                v -> startActivity(new Intent(this, HistoryActivity.class)));
        findViewById(R.id.row_models).setOnClickListener(
                v -> startActivity(new Intent(this, com.whispertflite.models.ModelCatalogActivity.class)));
        findViewById(R.id.row_enable_keyboard).setOnClickListener(
                v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        findViewById(R.id.row_github).setOnClickListener(
                v -> startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/danscMax/whisperIME"))));

        bindAdvancedToggle();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Rebuild on every resume so a model downloaded/selected in the catalog shows up on return.
        buildModelList();
    }

    /** Inline picker of the models already present on device: tap a row to switch the active model
     *  without leaving Settings. The "Models &amp; engines" row below stays the place to download new
     *  ones. Mirrors the dynamic-view pattern of {@link #buildPaletteRow()}. */
    private void buildModelList() {
        LinearLayout list = findViewById(R.id.model_list);
        list.removeAllViews();
        ModelDownloadManager mgr = ModelDownloadManager.get(this);
        String selected = sp.getString(ModelDownloadManager.PREF_SELECTED_MODEL, null);
        boolean any = false;
        for (ModelInfo m : ModelRegistry.all()) {
            if (!mgr.isPresent(m)) continue; // only downloaded models are selectable here
            any = true;
            list.addView(modelRow(m, m.id.equals(selected), mgr));
        }
        if (!any) {
            TextView empty = new TextView(this);
            empty.setText(R.string.settings_model_none);
            empty.setTextColor(ContextCompat.getColor(this, R.color.glass_ink_dim));
            empty.setTextSize(13f);
            list.addView(empty);
        }
    }

    /** One selectable glass row; a leading check (tinted colorPrimary) marks the active model. */
    private View modelRow(ModelInfo model, boolean active, ModelDownloadManager mgr) {
        TextView row = new TextView(this);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.setMinHeight(dp(48));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTextAppearance(resolveAttr(com.google.android.material.R.attr.textAppearanceBodyLarge));
        row.setTextColor(ContextCompat.getColor(this, R.color.glass_ink)); // after appearance: keep our ink
        row.setText(model.label(this));

        // Always attach the check drawable so labels align; tint transparent when not active.
        android.graphics.drawable.Drawable check = ContextCompat.getDrawable(this, R.drawable.ic_check_20dp);
        if (check != null) {
            check = check.mutate();
            check.setTint(active
                    ? MaterialColors.getColor(row, androidx.appcompat.R.attr.colorPrimary)
                    : android.graphics.Color.TRANSPARENT);
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(check, null, null, null);
            row.setCompoundDrawablePadding(dp(8));
        }

        row.setBackgroundResource(resolveAttr(android.R.attr.selectableItemBackground));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> {
            mgr.setSelected(model.id); // IME/recognizer re-read selectedModelId on next use
            buildModelList();          // refresh the check markers
        });
        return row;
    }

    /** Resolve a theme attribute to its referenced resource id (e.g. a style or drawable). */
    private int resolveAttr(int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return tv.resourceId;
    }

    /** Collapsible "Advanced" section: the header row shows/hides the power-settings block. */
    private void bindAdvancedToggle() {
        View header = findViewById(R.id.advanced_header);
        View content = findViewById(R.id.advanced_content);
        android.widget.ImageView chevron = findViewById(R.id.advanced_chevron);
        android.view.ViewGroup root = findViewById(R.id.settings_content);
        header.setOnClickListener(v -> {
            boolean expand = content.getVisibility() != View.VISIBLE;
            android.transition.TransitionManager.beginDelayedTransition(root);
            content.setVisibility(expand ? View.VISIBLE : View.GONE);
            chevron.animate().rotation(expand ? 180f : 0f).setDuration(180).start();
        });
    }

    private void buildPaletteRow() {
        LinearLayout row = findViewById(R.id.palette_row);
        String current = sp.getString("palette", "teal");
        int size = dp(44);
        int margin = dp(8);
        for (int i = 0; i < PALETTE_KEYS.length; i++) {
            final String key = PALETTE_KEYS[i];
            View swatch = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(margin);
            swatch.setLayoutParams(lp);
            swatch.setBackground(swatchDrawable(i, key.equals(current)));
            swatch.setContentDescription(getString(R.string.settings_palette) + " " + key);
            swatch.setOnClickListener(v -> {
                sp.edit().putString("palette", key).apply();
                recreate();
            });
            row.addView(swatch);
        }
    }

    private GradientDrawable swatchDrawable(int index, boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (PALETTE_COLORS[index] == 0) {
            // dynamic: multi-color gradient to hint "adaptive"
            g.setColors(new int[]{0xFF006A60, 0xFF3F51E0, 0xFF9C4234});
            g.setOrientation(GradientDrawable.Orientation.TL_BR);
        } else {
            g.setColor(PALETTE_COLORS[index]);
        }
        if (selected) {
            // Aurora dark panel: light ink ring reads clearly against the swatch.
            g.setStroke(dp(3), ContextCompat.getColor(this, R.color.glass_ink));
        }
        return g;
    }

    /** Recording mode: one 3-way choice — hold / tap / auto-start ({@code recordMode}). Shared with the
     *  IME + recognizer, which derive {@code modeAuto} from it. */
    private void buildModeToggle() {
        MaterialButtonToggleGroup group = findViewById(R.id.record_mode_group);
        String mode = sp.getString("recordMode", sp.getBoolean("imeModeAuto", false) ? "auto" : "hold");
        group.check("auto".equals(mode) ? R.id.mode_auto : "tap".equals(mode) ? R.id.mode_tap : R.id.mode_hold);
        group.addOnButtonCheckedListener((g, id, isChecked) -> {
            if (!isChecked) return;
            String m = id == R.id.mode_auto ? "auto" : id == R.id.mode_tap ? "tap" : "hold";
            if (m.equals(sp.getString("recordMode", "hold"))) return;
            sp.edit().putString("recordMode", m).apply();
        });
    }

    private void buildOrbToggle() {
        MaterialButtonToggleGroup group = findViewById(R.id.orb_group);
        group.check(sp.getInt("orbStyle", 0) == 1 ? R.id.orb_plasma : R.id.orb_cloud);
        group.addOnButtonCheckedListener((g, id, isChecked) -> {
            if (!isChecked) return;
            int s = id == R.id.orb_plasma ? 1 : 0;
            if (s == sp.getInt("orbStyle", 0)) return;
            sp.edit().putInt("orbStyle", s).apply();   // orbs re-read this on resume (LivingSignalView.refreshStyle)
        });
    }

    private void buildThemeToggle() {
        MaterialButtonToggleGroup group = findViewById(R.id.theme_group);
        String mode = sp.getString("nightMode", "system");
        int checkedId = mode.equals("light") ? R.id.theme_light
                : mode.equals("dark") ? R.id.theme_dark : R.id.theme_system;
        group.check(checkedId);
        group.addOnButtonCheckedListener((g, id, isChecked) -> {
            if (!isChecked) return;
            String m = id == R.id.theme_light ? "light"
                    : id == R.id.theme_dark ? "dark" : "system";
            if (m.equals(sp.getString("nightMode", "system"))) return;
            sp.edit().putString("nightMode", m).apply();
            ThemeUtils.applyNightMode(this);
            recreate();
        });
    }

    private void bindSwitch(int id, String key, boolean def) {
        MaterialSwitch s = findViewById(id);
        s.setChecked(sp.getBoolean(key, def));
        s.setOnCheckedChangeListener((b, checked) -> sp.edit().putBoolean(key, checked).apply());
    }

    /** Bind the free-text custom-vocabulary field to the {@code customVocabulary} pref (A3). */
    private void bindVocabulary() {
        android.widget.EditText field = findViewById(R.id.edit_vocabulary);
        field.setText(sp.getString("customVocabulary", ""));
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                sp.edit().putString("customVocabulary", e.toString()).apply();
            }
        });
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
