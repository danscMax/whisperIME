package com.whispertflite.models;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Background download manager for {@link ModelInfo}. Process-scoped singleton:
 * downloads run on a small thread pool and continue while the process lives;
 * activities attach/detach as {@link Listener}s. Uses only HttpURLConnection.
 */
public class ModelDownloadManager {

    private static final String TAG = "ModelDownloadManager";
    private static final int BUFFER = 8 * 1024;
    private static final long PROGRESS_INTERVAL_MS = 250; // <=4 callbacks/sec
    private static final int MAX_PARALLEL = 2;
    public static final String PREF_SELECTED_MODEL = "selectedModelId";
    public static final String PREF_WIFI_ONLY = "wifiOnlyDownloads";
    public static final String ERR_WIFI = "wifi_required";

    public interface Listener {
        void onProgress(String modelId, long bytes, long total, long bytesPerSec);
        void onDone(String modelId);
        void onError(String modelId, String message);
    }

    private static volatile ModelDownloadManager INSTANCE;

    private final Context appContext;
    private final File filesDir;
    private final ExecutorService pool = Executors.newFixedThreadPool(MAX_PARALLEL);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    // modelId -> cancel flag for the in-flight download
    private final ConcurrentHashMap<String, Boolean> active = new ConcurrentHashMap<>();

    private ModelDownloadManager(Context ctx) {
        this.appContext = ctx.getApplicationContext();
        this.filesDir = appContext.getExternalFilesDir(null);
    }

    public static ModelDownloadManager get(Context ctx) {
        if (INSTANCE == null) {
            synchronized (ModelDownloadManager.class) {
                if (INSTANCE == null) INSTANCE = new ModelDownloadManager(ctx);
            }
        }
        return INSTANCE;
    }

    public void addListener(Listener l) { if (l != null && !listeners.contains(l)) listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    public boolean isDownloading(String modelId) {
        return active.containsKey(modelId);
    }

    /** True if at least one registry model is present on disk (usable by an engine). */
    public boolean hasAnyModel() {
        for (ModelInfo m : ModelRegistry.all()) {
            if (isPresent(m)) return true;
        }
        return false;
    }

    /** True once EVERY file of the model is present on disk. Single-file models have one file; sherpa
     *  models have several (encoder/decoder/joiner/tokens) — all must be present to be usable. */
    public boolean isPresent(ModelInfo model) {
        for (ModelInfo.Asset a : model.files) {
            if (!new File(filesDir, a.relPath).exists()) return false;
        }
        // A TFLite model also needs its bundled vocab file on disk to be usable; other call sites check
        // it separately, so fold it in here for one consistent presence definition (F33).
        if (model.engine == ModelInfo.Engine.TFLITE
                && !new File(filesDir, ModelRegistry.vocabFor(model)).exists()) return false;
        return true;
    }

    /** Remove every file of a model (plus any leftover .part siblings) and prune its now-empty dir. */
    public void delete(ModelInfo model) {
        for (ModelInfo.Asset a : model.files) {
            File f = new File(filesDir, a.relPath);
            //noinspection ResultOfMethodCallIgnored
            if (f.exists()) f.delete();
            File part = new File(f.getPath() + ".part");
            //noinspection ResultOfMethodCallIgnored
            if (part.exists()) part.delete();
        }
        // sherpa models live in their own subdirectory (filename = the dir) — prune it if now empty.
        File dir = new File(filesDir, model.filename);
        if (dir.isDirectory()) {
            String[] kids = dir.list();
            //noinspection ResultOfMethodCallIgnored
            if (kids == null || kids.length == 0) dir.delete();
        }
    }

    // Obsolete model files shipped by older app versions; deleted once on app start.
    private static final String[] OBSOLETE_FILES = {
            "whisper-base.tflite", "whisper-base.EUROPEAN_UNION.tflite", "whisper-small.tflite"
    };

    /** Copy bundled TFLite vocab files from assets if missing (needed by pre-seeded models). */
    public void ensureVocabAssets() {
        try {
            ensureVocab(ModelRegistry.ENGLISH_VOCAB);
            ensureVocab(ModelRegistry.MULTILINGUAL_VOCAB);
        } catch (IOException e) {
            Log.w(TAG, "vocab asset copy failed", e);
        }
    }

    /** One-time cleanup of pre-redesign model files. Idempotent: safe to call every start. */
    public void cleanupObsoleteModels() {
        for (String name : OBSOLETE_FILES) {
            File f = new File(filesDir, name);
            //noinspection ResultOfMethodCallIgnored
            if (f.exists()) f.delete();
        }
    }

    /** ACTIVE if selected and present; DOWNLOADED if present; DOWNLOADING if in flight; else AVAILABLE. */
    public ModelState stateOf(ModelInfo model) {
        if (isPresent(model)) {
            String selected = prefs().getString(PREF_SELECTED_MODEL, null);
            return model.id.equals(selected) ? ModelState.ACTIVE : ModelState.DOWNLOADED;
        }
        if (isDownloading(model.id)) return ModelState.DOWNLOADING;
        return ModelState.AVAILABLE;
    }

    /** Start (or resume) a download. No-op if already downloading. */
    public void download(final ModelInfo model) {
        if (active.putIfAbsent(model.id, Boolean.FALSE) != null) return; // already running

        if (isWifiOnly() && !isOnWifi()) {
            active.remove(model.id);
            emitError(model.id, ERR_WIFI);
            return;
        }
        pool.execute(() -> {
            try {
                // TFLite models need their vocab file present; copy from assets if missing.
                if (model.engine == ModelInfo.Engine.TFLITE) {
                    ensureVocab(ModelRegistry.vocabFor(model));
                }
                runWithRetry(model);
            } catch (IOException e) {
                Log.w(TAG, "download failed: " + model.id, e);
                emitError(model.id, e.getMessage() == null ? "io_error" : e.getMessage());
            } finally {
                active.remove(model.id);
            }
        });
    }

    /** Cancel an in-flight download; the .part file is kept for resume. */
    public void cancel(String modelId) {
        // Atomic: only flip the flag if the entry is still present. containsKey-then-put could re-insert
        // a phantom active entry after the worker's finally removed it, wedging future downloads.
        active.replace(modelId, Boolean.TRUE);
    }

    // --- internals ---

    /**
     * Retry a download that dies with a transient network error (dropped connection, read timeout).
     * Resume is already supported (HTTP Range + .part), so each retry continues where the last stopped
     * instead of re-downloading. Backs off exponentially; respects cancellation between attempts (C9).
     * Non-throwing outcomes (user cancel, Wi-Fi-lost, "incomplete") return from runDownload and are NOT
     * retried here — only thrown IOExceptions are.
     */
    private void runWithRetry(ModelInfo model) throws IOException {
        final int maxAttempts = 3;
        long backoffMs = 2000;
        IOException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (Boolean.TRUE.equals(active.get(model.id))) return; // cancelled between attempts
            try {
                runDownload(model);
                return;
            } catch (IOException e) {
                last = e;
                Log.w(TAG, "download attempt " + attempt + "/" + maxAttempts + " failed: " + model.id, e);
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    backoffMs *= 2;
                }
            }
        }
        if (last != null) throw last; // exhausted retries: surface to the caller's error path
    }

    private void runDownload(ModelInfo model) throws IOException {
        if (isPresent(model)) { emitDone(model.id); return; }
        final long total = model.sizeBytes;        // sum across all files, for aggregate progress
        long doneBase = 0;                          // bytes contributed by already-complete files
        for (ModelInfo.Asset a : model.files) {
            File target = new File(filesDir, a.relPath);
            if (target.exists()) { doneBase += a.size; continue; } // already downloaded (resume across files)
            if (!downloadAsset(model.id, a, doneBase, total)) return; // stopped (cancel/wifi/incomplete), reason emitted
            doneBase += a.size;
        }
        emitDone(model.id);
    }

    /**
     * Download ONE file to its own {@code .part}, verify, and promote it. Progress is reported against
     * the whole model ({@code doneBase} = bytes from files already complete, {@code total} = model total).
     * Returns true on success; false when stopped without a retry (user cancel / Wi-Fi lost / truncated)
     * after emitting the reason. Throws IOException on retryable transport errors — runWithRetry re-runs
     * runDownload, which skips files already promoted (per-file resume via {@code target.exists()}).
     */
    private boolean downloadAsset(String modelId, ModelInfo.Asset asset, long doneBase, long total)
            throws IOException {
        File target = new File(filesDir, asset.relPath);
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        File part = new File(target.getPath() + ".part");

        long existing = part.exists() ? part.length() : 0L;
        HttpURLConnection conn = (HttpURLConnection) new URL(asset.url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        if (existing > 0) conn.setRequestProperty("Range", "bytes=" + existing + "-");

        InputStream in = null;
        OutputStream out = null;
        long assetTotal = asset.size; // approximate until the server reports an exact size
        boolean exactTotal = false;   // true once assetTotal is the server's content length
        try {
            conn.connect();
            int code = conn.getResponseCode();
            boolean resumed = code == HttpURLConnection.HTTP_PARTIAL; // 206
            if (existing > 0 && !resumed) {
                existing = 0; // server ignored Range: restart from scratch
            }
            if (code != HttpURLConnection.HTTP_OK && !resumed) {
                throw new IOException("http_" + code);
            }

            long remaining = conn.getContentLengthLong(); // may be -1
            if (remaining > 0) { assetTotal = existing + remaining; exactTotal = true; }

            in = conn.getInputStream();
            out = new FileOutputStream(part, existing > 0); // append when resuming

            byte[] buf = new byte[BUFFER];
            long done = existing;
            long lastEmit = 0;
            long windowStart = System.currentTimeMillis();
            long windowBytes = 0;
            int len;
            while ((len = in.read(buf)) != -1) {
                if (Boolean.TRUE.equals(active.get(modelId))) { // cancelled: keep .part
                    return false;
                }
                out.write(buf, 0, len);
                done += len;
                windowBytes += len;
                long now = System.currentTimeMillis();
                if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                    // Wi-Fi-only can flip to metered mid-transfer (Wi-Fi→cellular handover); stop before
                    // burning the user's mobile data on a 500 MB model. .part is kept for later resume (C8).
                    if (isWifiOnly() && !isOnWifi()) {
                        emitError(modelId, ERR_WIFI);
                        return false;
                    }
                    long elapsed = Math.max(1, now - windowStart);
                    long bps = windowBytes * 1000 / elapsed;
                    emitProgress(modelId, doneBase + done, total, bps);
                    lastEmit = now;
                    windowStart = now;
                    windowBytes = 0;
                }
            }
            out.flush();
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            conn.disconnect();
        }

        // Guard against a truncated download (clean early EOF, e.g. dropped connection / half-closed
        // proxy socket): the stream ending is not proof of completeness. When the server gave an exact
        // Content-Length, require the full byte count; otherwise fall back to a floor of the registry's
        // approximate size (gross truncation only) so we never promote a short file. Keep .part so a
        // retry resumes via HTTP Range instead of silently promoting a corrupt file.
        long floor = exactTotal ? assetTotal : (long) (asset.size * 0.85);
        if (part.length() < floor) {
            emitError(modelId, "incomplete");
            return false;
        }

        // completed without cancel: promote .part -> final name
        if (!part.renameTo(target)) {
            throw new IOException("rename_failed");
        }
        return true;
    }

    /** Copy a bundled vocab file from assets into the external dir if not already present. */
    private void ensureVocab(String name) throws IOException {
        File dest = new File(filesDir, name);
        if (dest.exists()) return;
        InputStream in = null;
        OutputStream out = null;
        try {
            in = appContext.getAssets().open(name);
            out = new FileOutputStream(dest);
            byte[] buf = new byte[BUFFER];
            int len;
            while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
            out.flush();
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private SharedPreferences prefs() {
        return PreferenceManager.getDefaultSharedPreferences(appContext);
    }

    /** Mark a model as the active selection (what every entry point loads). Used by the wizard so a
     *  just-downloaded model is live immediately, without the user re-selecting it in the catalog. */
    public void setSelected(String modelId) {
        prefs().edit().putString(PREF_SELECTED_MODEL, modelId).apply();
    }

    private boolean isWifiOnly() {
        return prefs().getBoolean(PREF_WIFI_ONLY, true);
    }

    private boolean isOnWifi() {
        ConnectivityManager cm =
                (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true; // can't tell: don't block
        try {
            Network net = cm.getActiveNetwork();
            if (net == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } catch (SecurityException e) {
            return true; // can't tell: don't block
        }
    }

    private void emitProgress(String id, long bytes, long total, long bps) {
        main.post(() -> { for (Listener l : listeners) l.onProgress(id, bytes, total, bps); });
    }

    private void emitDone(String id) {
        main.post(() -> { for (Listener l : listeners) l.onDone(id); });
    }

    private void emitError(String id, String msg) {
        main.post(() -> { for (Listener l : listeners) l.onError(id, msg); });
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (IOException ignored) {}
    }
}
