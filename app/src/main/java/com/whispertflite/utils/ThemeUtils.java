package com.whispertflite.utils;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.preference.PreferenceManager;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.whispertflite.R;

public class ThemeUtils {

    /**
     * Apply the palette chosen in preferences ("palette": teal|terracotta|indigo|forest|dynamic).
     * Must be called BEFORE setContentView so views inflate with the overlaid colors.
     */
    public static void applyPalette(Activity activity) {
        String palette = paletteKey(activity);
        if ("dynamic".equals(palette)) {
            DynamicColors.applyToActivityIfAvailable(activity);
            return;
        }
        activity.getTheme().applyStyle(paletteOverlay(palette), true);
    }

    private static String paletteKey(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getString("palette", "teal");
    }

    private static int paletteOverlay(String palette) {
        switch (palette) {
            case "terracotta": return R.style.ThemeOverlay_Whisper_Terracotta;
            case "indigo":     return R.style.ThemeOverlay_Whisper_Indigo;
            case "forest":     return R.style.ThemeOverlay_Whisper_Forest;
            case "amber":      return R.style.ThemeOverlay_Whisper_Amber;
            case "mint":       return R.style.ThemeOverlay_Whisper_Mint;
            case "aqua":       return R.style.ThemeOverlay_Whisper_Aqua;
            case "sky":        return R.style.ThemeOverlay_Whisper_Sky;
            case "violet":     return R.style.ThemeOverlay_Whisper_Violet;
            case "plum":       return R.style.ThemeOverlay_Whisper_Plum;
            case "rose":       return R.style.ThemeOverlay_Whisper_Rose;
            case "teal":
            default:           return R.style.ThemeOverlay_Whisper_Teal;
        }
    }

    /**
     * Re-point the Material colour roles at the warm liquid-glass tokens. Call after
     * {@link #applyPalette} and before setContentView on every glass screen, so stock widgets
     * inherit the card look instead of the tonal palette.
     */
    public static void applyGlass(Activity activity) {
        activity.getTheme().applyStyle(R.style.ThemeOverlay_Whisper_Glass, true);
    }

    /**
     * The orb's {bright, soft} tint. The chosen palette survives here and only here: the glass
     * owns colorPrimary on every screen, so the orb cannot read its hue off the caller's theme.
     * Resolved against a throwaway context wearing the palette alone, which keeps this independent
     * of whether the caller has applied the glass overlay yet.
     */
    public static int[] orbColors(Context context) {
        // Night-correct first: the palette colours are -night qualified, and a service's resources
        // follow the system, not the app's preference.
        Context c = paletteContext(nightContext(context));
        return new int[]{
                MaterialColors.getColor(c, androidx.appcompat.R.attr.colorPrimary, Color.GRAY),
                MaterialColors.getColor(c, com.google.android.material.R.attr.colorPrimaryContainer,
                        Color.GRAY)};
    }

    /**
     * A context wearing the app theme, the chosen palette, the chosen night mode and the glass.
     * The IME is a service: it has no activity theme, and AppCompat's night mode never reaches it,
     * so the preference has to be turned into a real configuration by hand.
     */
    public static Context serviceContext(Context service) {
        Context themed = paletteContext(nightContext(service));
        themed.getTheme().applyStyle(R.style.ThemeOverlay_Whisper_Glass, true);
        return themed;
    }

    /**
     * The night mode preference as a real configuration. A no-op for activities, whose resources
     * AppCompat has already overridden; the work is for contexts it never touches, like the IME.
     */
    private static Context nightContext(Context context) {
        String nightMode = PreferenceManager.getDefaultSharedPreferences(context)
                .getString("nightMode", "system");
        if ("system".equals(nightMode)) return context;
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | ("dark".equals(nightMode)
                ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return context.createConfigurationContext(config);
    }

    /**
     * What the UI is built from, for spotting that the user changed it. Only the app's own
     * preferences: a change in the *system* night mode arrives as a configuration change, which
     * rebuilds views on its own.
     */
    public static String appearanceSignature(Context context) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        return sp.getString("nightMode", "system") + "/" + sp.getString("palette", "teal");
    }

    /**
     * A context wearing the palette and nothing else of the caller's. It must never hand back the
     * caller's own theme: wrapContextIfAvailable is a no-op below API 31, and the callers have
     * already stamped the glass over colorPrimary — the orb would come back ink.
     */
    private static Context paletteContext(Context context) {
        Context base = new androidx.appcompat.view.ContextThemeWrapper(
                context, R.style.Theme_Whisper_NoActionBar);
        String palette = paletteKey(context);
        if ("dynamic".equals(palette)) {
            return DynamicColors.wrapContextIfAvailable(base);
        }
        base.getTheme().applyStyle(paletteOverlay(palette), true);
        return base;
    }

    /**
     * Apply the night mode chosen in preferences ("nightMode": system|light|dark).
     *
     * <p>Call this from {@link android.app.Application#onCreate()} at startup, and again from the
     * settings screen when the user picks a new mode. Not from an activity's own onCreate: that is
     * too late for its base context, and windows inflated outside it (exposed-dropdown popups,
     * menus) then resolve -night resources against the system configuration instead.
     */
    public static void applyNightMode(Context context) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        String mode = sp.getString("nightMode", "system");
        int nightMode;
        switch (mode) {
            case "light": nightMode = AppCompatDelegate.MODE_NIGHT_NO;  break;
            case "dark":  nightMode = AppCompatDelegate.MODE_NIGHT_YES; break;
            case "system":
            default:      nightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM; break;
        }
        AppCompatDelegate.setDefaultNightMode(nightMode);
    }

    /**
     * Match the system-bar icons to the surface under them: dark icons on the light glass, light
     * icons after dark. The androidx controller covers every API this app runs on; the platform
     * one only exists from 30 and was previously gated to 35+, leaving the bars unset below that.
     */
    public static void setStatusBarAppearance(Activity activity) {
        boolean night = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(
                activity.getWindow(), activity.getWindow().getDecorView());
        c.setAppearanceLightStatusBars(!night);
        c.setAppearanceLightNavigationBars(!night);
    }
}
