package com.whispertflite.utils;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.preference.PreferenceManager;

import com.google.android.material.color.DynamicColors;
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
     * owns colorPrimary on every screen, so the orb cannot read its hue off the activity's theme.
     * Resolved against a throwaway context wearing the palette alone, which keeps this independent
     * of whether the caller has applied the glass overlay yet.
     */
    public static int[] orbColors(Activity activity) {
        Context c = paletteContext(activity);
        return new int[]{
                resolveColor(c, androidx.appcompat.R.attr.colorPrimary),
                resolveColor(c, com.google.android.material.R.attr.colorPrimaryContainer)};
    }

    private static Context paletteContext(Activity activity) {
        String palette = paletteKey(activity);
        if ("dynamic".equals(palette)) {
            return DynamicColors.wrapContextIfAvailable(activity);
        }
        return new androidx.appcompat.view.ContextThemeWrapper(activity, paletteOverlay(palette));
    }

    private static int resolveColor(Context c, int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        c.getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    /**
     * Apply the night mode chosen in preferences ("nightMode": system|light|dark).
     *
     * <p>Call this from {@link android.app.Application#onCreate()}. Calling it from an activity's
     * onCreate is too late for that activity's base context: the activity itself repaints, but
     * windows inflated outside it (exposed-dropdown popups, menus) keep resolving -night resources
     * against the *system* configuration, so their text comes out inverted and unreadable.
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
