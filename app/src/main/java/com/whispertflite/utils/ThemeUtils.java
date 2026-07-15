package com.whispertflite.utils;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.view.WindowInsetsController;

import androidx.appcompat.app.AppCompatDelegate;
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
     * Apply the night mode chosen in preferences ("nightMode": system|light|dark).
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

    public static void setStatusBarAppearance(Activity activity){
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            int nightModeFlags = activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            boolean isDarkMode = (nightModeFlags == Configuration.UI_MODE_NIGHT_YES);
            WindowInsetsController insetsController = activity.getWindow().getInsetsController();
            if (insetsController != null) {
                if (isDarkMode) {
                    // Dark mode: remove light status bar appearance (use light icons)
                    insetsController.setSystemBarsAppearance(
                            0,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    );
                } else {
                    // Light mode: enable light status bar appearance (dark icons)
                    insetsController.setSystemBarsAppearance(
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    );
                }
            }
        }
    }
}
