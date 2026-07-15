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

    /** True if at least one registry model file is present on disk (usable by an engine). */
    public boolean hasAnyModel() {
        for (ModelInfo m : ModelRegistry.all()) {
            if (targetFile(m).exists()) return true;
        }
        return false;
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
        if (targetFile(model).exists()) {
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
                runDownload(model);
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
        if (active.containsKey(modelId)) active.put(modelId, Boolean.TRUE);
    }

    // --- internals ---

    private void runDownload(ModelInfo model) throws IOException {
        File target = targetFile(model);
        if (target.exists()) { emitDone(model.id); return; }
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        File part = new File(target.getPath() + ".part");

        long existing = part.exists() ? part.length() : 0L;
        HttpURLConnection conn = (HttpURLConnection) new URL(model.url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        if (existing > 0) conn.setRequestProperty("Range", "bytes=" + existing + "-");

        InputStream in = null;
        OutputStream out = null;
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
            long total = model.sizeBytes;
            if (remaining > 0) total = existing + remaining;

            in = conn.getInputStream();
            out = new FileOutputStream(part, existing > 0); // append when resuming

            byte[] buf = new byte[BUFFER];
            long done = existing;
            long lastEmit = 0;
            long windowStart = System.currentTimeMillis();
            long windowBytes = 0;
            int len;
            while ((len = in.read(buf)) != -1) {
                if (Boolean.TRUE.equals(active.get(model.id))) { // cancelled: keep .part
                    return;
                }
                out.write(buf, 0, len);
                done += len;
                windowBytes += len;
                long now = System.currentTimeMillis();
                if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                    long elapsed = Math.max(1, now - windowStart);
                    long bps = windowBytes * 1000 / elapsed;
                    emitProgress(model.id, done, total, bps);
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

        // completed without cancel: promote .part -> final name
        if (!part.renameTo(target)) {
            throw new IOException("rename_failed");
        }
        emitDone(model.id);
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

    private File targetFile(ModelInfo model) {
        return new File(filesDir, model.filename);
    }

    private SharedPreferences prefs() {
        return PreferenceManager.getDefaultSharedPreferences(appContext);
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
