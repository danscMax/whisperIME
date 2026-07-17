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
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(activity);
        String palette = sp.getString("palette", "teal");
        if ("dynamic".equals(palette)) {
            DynamicColors.applyToActivityIfAvailable(activity);
            return;
        }
        int overlay;
        switch (palette) {
            case "terracotta": overlay = R.style.ThemeOverlay_Whisper_Terracotta; break;
            case "indigo":     overlay = R.style.ThemeOverlay_Whisper_Indigo;     break;
            case "forest":     overlay = R.style.ThemeOverlay_Whisper_Forest;     break;
            case "teal":
            default:           overlay = R.style.ThemeOverlay_Whisper_Teal;       break;
        }
        activity.getTheme().applyStyle(overlay, true);
    }

    /**
     * Re-point the Material colour roles at the warm liquid-glass tokens. Call after
     * {@link #applyPalette} and before setContentView on every glass (light frosted) screen, so
     * stock widgets inherit the card look instead of the tonal palette.
     */
    public static void applyGlass(Activity activity) {
        activity.getTheme().applyStyle(R.style.ThemeOverlay_Whisper_Glass, true);
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
