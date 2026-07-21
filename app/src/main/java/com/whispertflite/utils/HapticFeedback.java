package com.whispertflite.utils;

import static android.content.Context.VIBRATOR_SERVICE;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.View;

import androidx.preference.PreferenceManager;

public class HapticFeedback {

    /**
     * Preferred tap haptic: standard, permission-free, and it auto-honours the system (and
     * per-keyboard) haptic-intensity setting because the platform routes KEYBOARD_TAP through it.
     * Gated by the same in-app "Haptic feedback" pref (default on) as {@link #vibrate(Context)}.
     * No-op when the view is null. Use this on any surface that has a View (the IME dock / the
     * recognizer orb); {@link #vibrate(Context)} remains only for contexts without a View (a Service).
     */
    public static void perform(View view) {
        if (view == null) return;
        if (!PreferenceManager.getDefaultSharedPreferences(view.getContext())
                .getBoolean("hapticFeedback", true)) return;
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    public static void vibrate(Context context){
        if (!hapticEnabled(context)) return;
        // Obtain the vibrator the modern way on API 33+ (VibratorManager), matching hapticEnabled(); the
        // deprecated VIBRATOR_SERVICE path stays only for API < 33 where VibratorManager doesn't exist.
        Vibrator vibrator;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = vm != null ? vm.getDefaultVibrator() : null;
        } else {
            vibrator = (Vibrator) context.getSystemService(VIBRATOR_SERVICE);
        }
        if (vibrator == null) return;   // no vibrator hardware / service unavailable — F38
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK));
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(10, 255));
        }
    }

    private static boolean hapticEnabled(Context context){
        // Honour the in-app "Haptic feedback" setting (default on) on every surface, not just the
        // main screen — every caller routes through here, so this is the single gate.
        if (!PreferenceManager.getDefaultSharedPreferences(context).getBoolean("hapticFeedback", true)) {
            return false;
        }
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            VibratorManager vibratorManager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return vibratorManager != null && vibratorManager.getDefaultVibrator().hasVibrator();   // guard here too (F38)
        } else {
            return Settings.System.getInt(context.getContentResolver(), Settings.System.HAPTIC_FEEDBACK_ENABLED, 0) == 1;
        }
    }
}
