package com.whispertflite;

import android.app.Application;

import com.whispertflite.utils.ThemeUtils;

/**
 * Applies the saved night mode once, before any activity is attached.
 *
 * <p>AppCompat needs the default night mode set this early: an activity that sets it from its own
 * onCreate repaints itself, but popup windows (exposed dropdowns, menus) still resolve -night
 * resources against the system configuration, which inverts their text whenever the user's choice
 * differs from the system theme.
 */
public class WhisperApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        ThemeUtils.applyNightMode(this);
    }
}
