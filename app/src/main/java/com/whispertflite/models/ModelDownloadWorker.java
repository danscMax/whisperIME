package com.whispertflite.models;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * Runs one model download inside WorkManager so it survives process death (aggressive OEMs kill a
 * backgrounded app mid-download) and shows a foreground progress notification. The actual transfer +
 * HF/VPS fallback + resume/verify logic lives in {@link ModelDownloadManager}; this worker is just the
 * durable execution context.
 */
public class ModelDownloadWorker extends Worker {

    static final String KEY_MODEL_ID = "modelId";

    public ModelDownloadWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String id = getInputData().getString(KEY_MODEL_ID);
        if (id == null) return Result.failure();
        ModelInfo model = ModelRegistry.byId(id);
        if (model == null) return Result.failure();
        ModelDownloadManager mgr = ModelDownloadManager.get(getApplicationContext());
        try {
            setForegroundAsync(mgr.buildForegroundInfo(model, 0, model.sizeBytes)).get();
        } catch (Exception ignored) {
            // Couldn't go foreground (FGS start restricted): run in the background anyway.
        }
        return mgr.runJob(model) ? Result.success() : Result.failure();
    }

    @Override
    public void onStopped() {
        // WorkManager cancelled us (user cancel, constraints, system): stop the read loop, keep the .part.
        String id = getInputData().getString(KEY_MODEL_ID);
        if (id != null) ModelDownloadManager.get(getApplicationContext()).markCancelled(id);
    }
}
