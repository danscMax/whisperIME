package com.whispertflite;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.TileService;

/**
 * Quick Settings tile that launches the floating recognition dialog.
 */
public class WhisperTileService extends TileService {

    @Override
    public void onClick() {
        super.onClick();
        Intent intent = new Intent(this, WhisperRecognizeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Unlock the device first so the dialog is visible right away.
        unlockAndRun(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Intent overload is deprecated on API 34+; must pass a PendingIntent.
                PendingIntent pi = PendingIntent.getActivity(
                        this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
                startActivityAndCollapse(pi);
            } else {
                startActivityAndCollapse(intent);
            }
        });
    }
}
