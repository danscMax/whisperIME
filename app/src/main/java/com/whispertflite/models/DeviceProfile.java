package com.whispertflite.models;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

/**
 * A snapshot of the device's on-device-ASR capability, used by {@link ModelRecommender} to pick a
 * model the device can actually run smoothly. On-device the binding constraint is RAM + CPU (and
 * whether the fast ONNX/sherpa path even has a 64-bit lane) — there is no discrete GPU/VRAM budget
 * like on desktop, so that is what we measure.
 */
public final class DeviceProfile {

    public final int cores;        // logical CPU cores
    public final long ramMb;       // total RAM in MB (0 = unknown)
    public final boolean arm64;    // arm64-v8a present -> the fast sherpa/ONNX lane; else armv7/x86 is slow
    public final boolean lowRam;   // ActivityManager.isLowRamDevice() (Go / very constrained devices)
    public final String socModel;  // Build.SOC_MODEL on API 31+, else "" — diagnostics only, not a decision input

    DeviceProfile(int cores, long ramMb, boolean arm64, boolean lowRam, String socModel) {
        this.cores = cores;
        this.ramMb = ramMb;
        this.arm64 = arm64;
        this.lowRam = lowRam;
        this.socModel = socModel;
    }

    public static DeviceProfile detect(Context ctx) {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        long ramMb = 0;
        boolean lowRam = false;
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            ramMb = mi.totalMem / (1024L * 1024L);
            lowRam = am.isLowRamDevice();
        }
        boolean arm64 = false;
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) { arm64 = true; break; }
        }
        String soc = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? Build.SOC_MODEL : "";
        return new DeviceProfile(cores, ramMb, arm64, lowRam, soc);
    }

    /** Build a profile with explicit values (unit tests; never touches Android APIs). */
    static DeviceProfile forTest(int cores, long ramMb, boolean arm64, boolean lowRam) {
        return new DeviceProfile(cores, ramMb, arm64, lowRam, "");
    }

    /**
     * Coarse capability tier: 0 = weak (32-bit / low-RAM / few cores → stay on the light whisper.cpp
     * baseline), 1 = mid, 2 = strong (can run the flagship multilingual model). Reported RAM is a bit
     * below the marketed figure (kernel reserve), so a "4 GB" phone reads ~3700 MB — thresholds allow for that.
     */
    public int tier() {
        if (lowRam || !arm64 || (ramMb != 0 && ramMb < 2800) || cores <= 4) return 0;
        if (ramMb >= 5500 && cores >= 8) return 2;
        return 1;
    }
}
