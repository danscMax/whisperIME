package com.whispertflite.models;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import java.util.function.Consumer;

/**
 * Live thermal-throttling signal for a recording session. {@link DeviceProfile} is only a one-shot
 * onboarding snapshot; this reacts to real throttling as it happens. Wraps
 * {@link PowerManager#addThermalStatusListener} / {@link PowerManager#getCurrentThermalStatus()}
 * (API 29+) and degrades to a clean no-op on API 28 — mirroring the {@code Build.SOC_MODEL} API-31
 * gate style in {@link DeviceProfile}.
 *
 * <p>Minimal by design: it exposes {@link #isThrottling()} and logs on severe throttling; a caller
 * that wants to actively react (e.g. downgrade the decode) can pass a {@link Consumer} for the raw
 * status. It never touches the decode thread itself.
 */
public final class ThermalMonitor {
    private static final String TAG = "ThermalMonitor";

    private final PowerManager pm;                 // may be null; the thermal API is only touched when SDK >= 29
    private final Consumer<Integer> onStatus;      // optional caller hook, fired on the binder thread
    private volatile int status;                   // last seen status; 0 == THERMAL_STATUS_NONE
    // PowerManager.OnThermalStatusChangedListener (API 29+); Object so the pre-29 no-op path never
    // references the newer type. volatile: start() runs on the main thread, stop() can fire from the
    // recorder worker thread (record DONE/ERROR), so the reference must publish across threads.
    private volatile Object thermalListener;

    public ThermalMonitor(Context ctx, Consumer<Integer> onStatus) {
        this.onStatus = onStatus;
        this.pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
    }

    /**
     * Pure status-to-decision map (unit-tested): true once the SoC is throttled hard enough to slow
     * on-device ASR. THERMAL_STATUS_SEVERE (3) and above.
     */
    static boolean isThrottling(int thermalStatus) {
        return thermalStatus >= 3;   // PowerManager.THERMAL_STATUS_SEVERE — literal keeps the helper pure
    }

    /** Whether the last reported thermal status counts as throttling. Cheap to poll. */
    public boolean isThrottling() {
        return isThrottling(status);
    }

    /** Begin listening. Idempotent. No-op on API < 29 (or if there is no PowerManager). */
    public void start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || pm == null) return;
        stop();   // drop any prior registration so a re-start never double-registers
        status = pm.getCurrentThermalStatus();
        PowerManager.OnThermalStatusChangedListener l = s -> {
            status = s;
            if (isThrottling(s)) Log.w(TAG, "thermal throttling during recording: status=" + s);
            if (onStatus != null) onStatus.accept(s);
        };
        thermalListener = l;
        pm.addThermalStatusListener(l);
    }

    /** Stop listening. Idempotent. No-op on API < 29 or when nothing is registered. */
    public void stop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || pm == null || thermalListener == null) return;
        pm.removeThermalStatusListener((PowerManager.OnThermalStatusChangedListener) thermalListener);
        thermalListener = null;
    }
}
