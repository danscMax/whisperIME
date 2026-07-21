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
import java.security.MessageDigest;
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
    public static final String ERR_CORRUPT = "corrupt_download";

    private static final String HF_BASE = "https://huggingface.co/";
    /** VPS mirror of the HuggingFace model tree (same {@code {org}/{repo}/resolve/main/{path}} tail), for
     *  regions where huggingface.co is blocked. Static file server, no auth; shared with the desktop app. */
    static final String MIRROR_BASE = "https://sweetwhisper.app/models";
    /** Download source: "auto" (HF then mirror — default), "mirror" (mirror then HF), "hf" (HF only). */
    public static final String PREF_DOWNLOAD_SOURCE = "modelDownloadSource";

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
     * Download one asset, trying each candidate host (HuggingFace / VPS mirror) in the configured order.
     * On a transport failure the partial {@code .part} is deleted before moving to the next host, so a 206
     * resume never splices bytes from two different hosts. Returns true when promoted, false when stopped
     * without a retry (cancel / Wi-Fi lost / incomplete). Throws when EVERY host failed — runWithRetry then
     * re-runs the whole download.
     */
    private boolean downloadAsset(String modelId, ModelInfo.Asset asset, long doneBase, long total)
            throws IOException {
        java.util.List<String> hosts = downloadCandidates(asset.url);
        IOException last = null;
        for (int i = 0; i < hosts.size(); i++) {
            try {
                return downloadAssetFrom(hosts.get(i), modelId, asset, doneBase, total);
            } catch (IOException e) {
                last = e;
                boolean more = i + 1 < hosts.size();
                Log.w(TAG, "download host failed (" + hosts.get(i) + "): " + e.getMessage()
                        + (more ? " — trying next host" : " — no more hosts"));
                if (more) deletePart(asset); // start the next host clean: never resume across hosts
            }
        }
        if (last != null) throw last;
        return false;
    }

    /** The VPS-mirror URL for a HuggingFace resolve URL (pure host+prefix swap, path tail preserved), or
     *  null if the URL is not a mirrorable HF file URL (e.g. an API/search URL). */
    static String mirrorUrl(String hfUrl) {
        if (hfUrl == null || !hfUrl.startsWith(HF_BASE)) return null;
        String tail = hfUrl.substring(HF_BASE.length());
        if (!tail.contains("/resolve/")) return null; // only {org}/{repo}/resolve/... file URLs are mirrored
        return MIRROR_BASE + "/" + tail;
    }

    /** Ordered download hosts for an asset, per the {@link #PREF_DOWNLOAD_SOURCE} preference. A non-mirrorable
     *  URL or "hf" yields a single candidate. */
    private java.util.List<String> downloadCandidates(String hfUrl) {
        java.util.List<String> out = new java.util.ArrayList<>(2);
        String source = prefs().getString(PREF_DOWNLOAD_SOURCE, "auto");
        String mirror = mirrorUrl(hfUrl);
        if (mirror == null || "hf".equals(source)) { out.add(hfUrl); return out; }
        if ("mirror".equals(source)) { out.add(mirror); out.add(hfUrl); }  // RU / HF-blocked: mirror first
        else { out.add(hfUrl); out.add(mirror); }                          // auto (default): HF, mirror fallback
        return out;
    }

    /** Delete an asset's leftover {@code .part} (used between hosts so a resume never mixes hosts). */
    private void deletePart(ModelInfo.Asset asset) {
        File part = new File(new File(filesDir, asset.relPath).getPath() + ".part");
        //noinspection ResultOfMethodCallIgnored
        if (part.exists()) part.delete();
    }

    /**
     * Download ONE file from ONE host to its own {@code .part}, verify, and promote it. Progress is reported
     * against the whole model ({@code doneBase} = bytes from files already complete, {@code total} = model
     * total). Returns true on success; false when stopped without a retry (user cancel / Wi-Fi lost /
     * truncated) after emitting the reason. Throws IOException on transport errors (the host-fallback + the
     * runWithRetry loops handle those).
     */
    private boolean downloadAssetFrom(String url, String modelId, ModelInfo.Asset asset, long doneBase, long total)
            throws IOException {
        File target = new File(filesDir, asset.relPath);
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        File part = new File(target.getPath() + ".part");

        // ponytail: hashed assets don't resume — a pre-existing .part can't feed the running SHA-256
        // (no way to re-seed the digest with already-written bytes), so start clean and hash the whole
        // file in one pass. Upgrade path: keep Range-resume and re-hash the finished .part if the extra
        // download cost ever matters. Un-hashed assets keep the existing resume behaviour unchanged.
        final MessageDigest digest = asset.sha256 != null ? newSha256() : null;
        long existing = (digest == null && part.exists()) ? part.length() : 0L;
        Log.d(TAG, "fetch " + asset.relPath + " from " + url);   // records which host (HF vs VPS mirror) served it
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
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
                if (digest != null) digest.update(buf, 0, len);
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

        // Integrity gate: the byte-count floor above only catches truncation — a corrupt-but-plausibly
        // sized file would still be promoted and load as garbage. When the asset ships a SHA-256, verify
        // the running digest (covers the whole file since hashed assets never resume). On mismatch, drop
        // the .part and throw so runWithRetry re-downloads from scratch; after retries are exhausted the
        // download() catch surfaces ERR_CORRUPT. No hash => no check (behaves exactly as before).
        if (digest != null && !hashMatches(asset.sha256, toHex(digest.digest()))) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException(ERR_CORRUPT);
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

    /** SHA-256 digest; unchecked because the algorithm is guaranteed present on every Android device. */
    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Lowercase hex encoding of a digest's bytes. */
    static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** True when {@code actualHex} matches the expected hash. Null expected => no hash => skip (match). */
    static boolean hashMatches(String expectedSha256, String actualHex) {
        return expectedSha256 == null || expectedSha256.equalsIgnoreCase(actualHex);
    }
}
