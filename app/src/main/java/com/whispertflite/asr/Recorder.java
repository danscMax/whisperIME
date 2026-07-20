package com.whispertflite.asr;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.whispertflite.R;
import com.whispertflite.engine.SileroVad;

import java.io.ByteArrayOutputStream;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Recorder {

    public interface RecorderListener {
        void onUpdateReceived(String message);
    }

    /** Lightweight live-audio level callback for the waveform (~30/sec, on the worker thread). */
    public interface RmsListener {
        void onRms(float rms);
    }

    /**
     * Emits a completed audio chunk (16-bit little-endian PCM @16k) at a VAD speech pause (or hard
     * cap) while recording keeps going. When a ChunkListener is set the recorder switches to
     * chunked/unlimited mode; otherwise it keeps the legacy single-buffer 30 s behavior.
     */
    public interface ChunkListener {
        void onChunk(byte[] pcm16k);
    }

    private static final String TAG = "Recorder";
    public static final String ACTION_STOP = "Stop";
    public static final String ACTION_RECORD = "Record";
    public static final String MSG_RECORDING = "Recording...";
    public static final String MSG_RECORDING_DONE = "Recording done...!";
    public static final String MSG_RECORDING_ERROR = "Recording error...";

    private final Context mContext;
    private final AtomicBoolean mInProgress = new AtomicBoolean(false);

    private RecorderListener mListener;
    private RmsListener mRmsListener;
    private ChunkListener mChunkListener;
    private final Lock lock = new ReentrantLock();
    private final Condition hasTask = lock.newCondition();
    private final Object fileSavedLock = new Object(); // Lock object for wait/notify
    private boolean recordingFinished = true;           // guarded by fileSavedLock
    private volatile boolean released = false;          // set by shutdown() to end the worker

    private volatile boolean shouldStartRecording = false;
    private boolean useVAD = false;
    private SileroVad vad = null;
    private static final int VAD_FRAME_SIZE = 480;                 // 30 ms @ 16 kHz read granularity
    private static final int CHUNK_HARD_CAP_BYTES = 16000 * 2 * 28; // 28 s force-split mid-speech

    private final Thread workerThread;

    public Recorder(Context context) {
        this.mContext = context;

        // Initialize and start the worker thread
        workerThread = new Thread(this::recordLoop);
        workerThread.start();

        primeMic();   // vivo cold-start: warm the audio route so the FIRST real capture isn't silent
    }

    private static volatile boolean micPrimed = false;

    /**
     * One-time microphone warm-up. On this hardware (vivo) the FIRST {@link AudioRecord} session after
     * the process starts comes back as pure silence for its ENTIRE lifetime — the VivoAudioPolicyProxy is
     * still binding when it opens — while the very next session captures normally. Symptom: the first
     * dictation right after a (re)install / engine switch "listens but hears nothing", the second works.
     *
     * <p>So burn a short throwaway capture here, once per process and off any recording path, so the cold
     * session is this primer and the user's first real dictation is the warm second one. It runs at Recorder
     * construction (IME view creation), well before auto-mode fires, so there is no overlap with a real
     * capture. Failures (no permission yet, route busy) are swallowed and leave it un-primed to retry later.
     */
    private void primeMic() {
        if (micPrimed) return;
        micPrimed = true;   // claim up-front so only one primer ever runs, even across concurrent Recorders
        if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            micPrimed = false;   // permission not granted yet — allow a later Recorder to prime
            return;
        }
        new Thread(() -> {
            AudioRecord warm = null;
            try {
                int channelConfig = AudioFormat.CHANNEL_IN_MONO;
                int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
                int sampleRate = 16000;
                int bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
                if (bufferSize <= 0) { micPrimed = false; return; }
                warm = new AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)   // same source as the real capture
                        .setAudioFormat(new AudioFormat.Builder()
                                .setChannelMask(channelConfig)
                                .setEncoding(audioFormat)
                                .setSampleRate(sampleRate)
                                .build())
                        .setBufferSizeInBytes(bufferSize)
                        .build();
                if (warm.getState() != AudioRecord.STATE_INITIALIZED) { micPrimed = false; return; }
                warm.startRecording();
                byte[] buf = new byte[bufferSize];
                long end = System.currentTimeMillis() + 500;   // read + discard ~0.5 s so the route actually engages
                while (System.currentTimeMillis() < end) {
                    if (warm.read(buf, 0, buf.length) <= 0) break;
                }
                warm.stop();
                Log.d(TAG, "Mic primed (cold-start warm-up)");
            } catch (Exception e) {
                micPrimed = false;
                Log.d(TAG, "Mic prime failed: " + e.getMessage());
            } finally {
                if (warm != null) { try { warm.release(); } catch (Exception ignored) {} }
            }
        }, "mic-prime").start();
    }

    public void setListener(RecorderListener listener) {
        this.mListener = listener;
    }

    public void setRmsListener(RmsListener listener) {
        this.mRmsListener = listener;
    }

    /** Setting a ChunkListener enables chunked/unlimited recording (used by MainActivity). */
    public void setChunkListener(ChunkListener listener) {
        this.mChunkListener = listener;
    }


    public void start() {
        if (!mInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Recording is already in progress...");
            return;
        }
        synchronized (fileSavedLock) { recordingFinished = false; }
        lock.lock();
        try {
            Log.d(TAG, "Recording starts now");
            shouldStartRecording = true;
            hasTask.signal();
        } finally {
            lock.unlock();
        }
    }

    // Arm auto-stop. The Silero VAD itself is built at capture start on the worker thread (recordAudio),
    // NOT here — this runs on the caller's UI thread and constructing the VAD loads an ONNX model.
    public void initVad(){
        useVAD = true;
        Log.d(TAG, "Auto-stop VAD armed");
    }


    public void stop() {
        Log.d(TAG, "Recording stopped");
        mInProgress.set(false);

        // Wait (bounded) for the worker to drain and signal completion. The guard flag defeats the
        // lost-wakeup race (the worker can finish between our set(false) and wait()), and the 3 s cap
        // means a capture that never started (e.g. permission denied) can't hang the caller — stop()
        // runs on the UI thread, so an unbounded wait() was an ANR waiting to happen.
        synchronized (fileSavedLock) {
            long end = System.currentTimeMillis() + 3000;
            while (!recordingFinished) {
                long remaining = end - System.currentTimeMillis();
                if (remaining <= 0) break;
                try {
                    fileSavedLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Permanently stop this recorder: abort any capture, end the worker thread and wake anyone
     * blocked in {@link #stop()}. Unusable afterwards. Every owner MUST call this in teardown — the
     * worker is a while-loop that otherwise parks forever (one leaked thread per abandoned Recorder).
     */
    public void shutdown() {
        released = true;
        mInProgress.set(false);
        lock.lock();
        try {
            hasTask.signalAll();
        } finally {
            lock.unlock();
        }
        workerThread.interrupt();
        synchronized (fileSavedLock) {
            recordingFinished = true;
            fileSavedLock.notifyAll();
        }
    }

    public boolean isInProgress() {
        return mInProgress.get();
    }

    private void sendUpdate(String message) {
        if (mListener != null)
            mListener.onUpdateReceived(message);
    }

    /**
     * Deliberately does NOT force Bluetooth SCO (A10). SCO is a narrowband (8/16 kHz telephone-band)
     * link that noticeably degrades dictation, and forcing it also overrode the VOICE_RECOGNITION
     * source's own capture-device routing. We let the platform choose the input device instead. Kept as
     * a hook returning false so the SCO-teardown call sites remain harmless no-ops.
     */
    private boolean maybeStartSco(AudioManager audioManager) {
        return false;
    }

    // Platform effects attached to the current record session; held so they can be released with it.
    private final java.util.List<android.media.audiofx.AudioEffect> mEffects = new java.util.ArrayList<>();

    /** Attach platform AGC/noise-suppression to the record session where the device offers them. */
    private void attachEffects(AudioRecord audioRecord) {
        try {
            if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
                android.media.audiofx.AutomaticGainControl agc =
                        android.media.audiofx.AutomaticGainControl.create(audioRecord.getAudioSessionId());
                if (agc != null) { agc.setEnabled(true); mEffects.add(agc); }
            }
            if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                android.media.audiofx.NoiseSuppressor ns =
                        android.media.audiofx.NoiseSuppressor.create(audioRecord.getAudioSessionId());
                if (ns != null) { ns.setEnabled(true); mEffects.add(ns); }
            }
        } catch (Exception e) {
            Log.w(TAG, "Attaching AGC/NoiseSuppressor failed", e);   // diagnosable, not swallowed (C14)
        }
    }

    /** Release the AGC/noise-suppression effects created for the just-finished record session. */
    private void releaseEffects() {
        for (android.media.audiofx.AudioEffect e : mEffects) {
            try { e.release(); } catch (Exception ignored) { }
        }
        mEffects.clear();
    }

    /** Compute normalized RMS of a 16-bit little-endian PCM frame and notify the listener. */
    private void emitRms(byte[] data, int bytes) {
        if (mRmsListener == null || bytes < 2) return;
        long sumSq = 0;
        int n = bytes / 2;
        for (int i = 0; i + 1 < bytes; i += 2) {
            int sample = (data[i] & 0xff) | (data[i + 1] << 8);
            sumSq += (long) sample * sample;
        }
        double rms = Math.sqrt((double) sumSq / n) / 32768.0;
        mRmsListener.onRms((float) Math.min(1.0, rms * 4.0)); // scale up so quiet speech is visible
    }


    private void recordLoop() {
        while (!released) {
            lock.lock();
            try {
                while (!shouldStartRecording && !released) {
                    hasTask.await();
                }
                shouldStartRecording = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                lock.unlock();
            }
            if (released) return;

            // Start recording process
            try {
                if (mChunkListener != null) recordAudioChunked();
                else recordAudio();
            } catch (Exception e) {
                Log.e(TAG, "Recording error...", e);
                sendUpdate(e.getMessage());
            } finally {
                mInProgress.set(false);
                // Signal completion on EVERY path — normal, early-return (permission denied) and
                // exception — so a caller waiting in stop() is never stranded. (Previously the notify
                // lived only at the tail of the record methods, which the early returns skipped.)
                synchronized (fileSavedLock) {
                    recordingFinished = true;
                    fileSavedLock.notifyAll();
                }
            }
        }
    }

    private void recordAudio() {
        if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "AudioRecord permission is not granted");
            sendUpdate(mContext.getString(R.string.need_record_audio_permission));
            return;
        }

        int channels = 1;
        int bytesPerSample = 2;
        int sampleRateInHz = 16000;
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        // VOICE_RECOGNITION applies the platform's ASR-tuned capture path (speech AGC, echo/music
        // suppression) and yields a less-raw signal than MIC — best practice for dictation (A1).
        int audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION;

        int bufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
        if (bufferSize < VAD_FRAME_SIZE * 2) bufferSize = VAD_FRAME_SIZE * 2;

        AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        boolean scoStarted = maybeStartSco(audioManager);

        AudioRecord.Builder builder = new AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(new AudioFormat.Builder()
                        .setChannelMask(channelConfig)
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRateInHz)
                        .build())
                .setBufferSizeInBytes(bufferSize);

        AudioRecord audioRecord = null;
        try {
            audioRecord = builder.build();
            attachEffects(audioRecord);
            audioRecord.startRecording();

            // Single-buffer capture cap. Auto (VAD) keeps a 30 s safety net for a stuck-open VAD; push-to-talk
            // (no VAD — the user holds the key) gets 120 s so a long hold is not silently truncated, while the
            // in-RAM buffer stays bounded (~3.8 MB). The chunked/unlimited path (MainActivity) is separate.
            int maxSeconds = useVAD ? 30 : 120;
            int bytesForThirtySeconds = sampleRateInHz * bytesPerSample * channels * maxSeconds;

            ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream(); // Buffer for saving data RecordBuffer

            byte[] audioData = new byte[bufferSize];
            int totalBytesRead = 0;

            boolean isRecording = false;
            boolean speechStarted = false;

            // Built here (worker thread) so the ONNX model load stays off the caller's UI thread. 0.8 s of
            // trailing silence ends the utterance — tolerant of breath/thinking pauses. ponytail: pause knob,
            // lower = snappier stop, higher = safer against early cutoff (device-tune).
            if (useVAD && vad == null) vad = new SileroVad(mContext, 0.8f);

            while (mInProgress.get() && totalBytesRead < bytesForThirtySeconds) {
                int bytesRead = audioRecord.read(audioData, 0, VAD_FRAME_SIZE * 2);
                if (bytesRead > 0) {
                    outputBuffer.write(audioData, 0, bytesRead);  // Save all bytes read up to 30 seconds
                    totalBytesRead += bytesRead;
                    emitRms(audioData, bytesRead);
                } else {
                    Log.d(TAG, "AudioRecord error, bytes read: " + bytesRead);
                    break;
                }

                if (useVAD){
                    if (bytesRead == VAD_FRAME_SIZE * 2) {
                        // Feed Silero the RAW frame — it is level-robust, no normalization. isSpeech() stays true
                        // through short pauses and flips false only after ~0.8 s of trailing silence, so the
                        // false-after-speech edge IS the end of the utterance: stop there. No RMS floor needed.
                        vad.accept(audioData, bytesRead);
                        boolean isSpeech = vad.isSpeech();
                        if (isSpeech) {
                            if (!isRecording) {
                                Log.d(TAG, "VAD speech detected: recording starts");
                                sendUpdate(MSG_RECORDING);
                            }
                            isRecording = true;
                            speechStarted = true;
                        } else if (speechStarted) {
                            Log.d(TAG, "Silero auto-stop: end of speech");
                            isRecording = false;
                            mInProgress.set(false);
                        }
                    }
                } else {
                    if (!isRecording) sendUpdate(MSG_RECORDING);
                    isRecording = true;
                }
            }
            Log.d(TAG, "Total bytes recorded: " + totalBytesRead);

            // Save recorded audio data to BufferStore (up to 30 seconds). No manual normalization: the
            // VOICE_RECOGNITION source + platform AGC already level the signal; a second manual gain stage
            // fought the AGC and rescaled each buffer differently (A9).
            byte[] recorded = outputBuffer.toByteArray();
            RecordBuffer.setOutputBuffer(recorded);
            if (totalBytesRead > 6400){  //min 0.2s
                sendUpdate(MSG_RECORDING_DONE);
            } else {
                sendUpdate(MSG_RECORDING_ERROR);
            }
        } finally {
            // Free every native resource on ALL exit paths — normal, early break, or an exception from
            // build()/startRecording()/VAD — so a failure can't leave the mic locked for the next session
            // or a dirty useVAD/vad silence-gating the next push-to-talk (F02).
            if (useVAD) {
                useVAD = false;
                if (vad != null) { vad.release(); vad = null; }
                Log.d(TAG, "Closing VAD");
            }
            if (audioRecord != null) {
                try { audioRecord.stop(); } catch (Exception ignored) { }
                audioRecord.release();
            }
            releaseEffects();
            if (scoStarted) {
                audioManager.stopBluetoothSco();
                audioManager.setBluetoothScoOn(false);
            }
        }
        // Completion is signalled centrally in recordLoop's finally (covers every exit path).
    }

    /**
     * Chunked/unlimited recording: no 30 s cap. A local webrtc VAD splits the stream into chunks at
     * ~700 ms speech pauses (or a 28 s hard cap mid-speech); each completed chunk is handed to the
     * ChunkListener while recording continues. Stops only on stop()/release. Legacy modeAuto
     * auto-stop-on-silence lives in recordAudio() and is untouched.
     */
    private void recordAudioChunked() {
        if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "AudioRecord permission is not granted");
            sendUpdate(mContext.getString(R.string.need_record_audio_permission));
            return;
        }

        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int sampleRateInHz = 16000;
        // VOICE_RECOGNITION applies the platform's ASR-tuned capture path (speech AGC, echo/music
        // suppression) and yields a less-raw signal than MIC — best practice for dictation (A1).
        int audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION;

        int bufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
        if (bufferSize < VAD_FRAME_SIZE * 2) bufferSize = VAD_FRAME_SIZE * 2;

        AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        boolean scoStarted = maybeStartSco(audioManager);

        AudioRecord audioRecord = null;
        SileroVad chunkVad = null;
        try {
            audioRecord = new AudioRecord.Builder()
                    .setAudioSource(audioSource)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setChannelMask(channelConfig)
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRateInHz)
                            .build())
                    .setBufferSizeInBytes(bufferSize)
                    .build();
            attachEffects(audioRecord);
            audioRecord.startRecording();

            // 0.4 s of silence splits a phrase here (snappier than the auto path's 0.8 s) so partial results
            // surface fast; 0.10 s min-speech rejects clicks. ponytail: device-tune both knobs.
            chunkVad = new SileroVad(mContext, 0.4f, 0.10f);

            ByteArrayOutputStream chunk = new ByteArrayOutputStream();
            byte[] frame = new byte[VAD_FRAME_SIZE * 2];
            boolean chunkHasSpeech = false;
            boolean announced = false;
            int chunksEmitted = 0;

            while (mInProgress.get()) {
                int bytesRead = audioRecord.read(frame, 0, VAD_FRAME_SIZE * 2);
                if (bytesRead <= 0) {
                    Log.d(TAG, "AudioRecord error, bytes read: " + bytesRead);
                    break;
                }
                chunk.write(frame, 0, bytesRead);
                emitRms(frame, bytesRead);

                // Silero on the RAW frame (level-robust, no normalization). It holds isSpeech() true through
                // short pauses and flips false only after ~0.4 s of silence — that edge marks a phrase boundary.
                boolean isSpeech = false;
                if (bytesRead == VAD_FRAME_SIZE * 2) {
                    chunkVad.accept(frame, bytesRead);
                    isSpeech = chunkVad.isSpeech();
                }
                if (isSpeech) {
                    if (!announced) { announced = true; sendUpdate(MSG_RECORDING); }
                    chunkHasSpeech = true;
                }

                // Split at a confirmed pause (speech then Silero-confirmed silence) or the mid-speech hard cap.
                boolean pauseSplit = chunkHasSpeech && !isSpeech;
                boolean capSplit = chunk.size() >= CHUNK_HARD_CAP_BYTES;
                if (pauseSplit || capSplit) {
                    // Gate the emit on `announced` (VAD fired at least once this session) — the SAME
                    // session-wide gate the trailing/residual flush uses (F03). A hard-cap split before any
                    // speech is 28 s of pure silence/noise the transducer would hallucinate a sentence from,
                    // so drop it; but once the user HAS spoken, a quiet interior window is still their speech
                    // (below Silero's threshold) and must not be dropped. Always reset to keep the buffer
                    // bounded. No manual normalization — trust the VOICE_RECOGNITION source + AGC (A6/A9).
                    if (announced) {
                        mChunkListener.onChunk(chunk.toByteArray());
                        chunksEmitted++;
                    }
                    chunk.reset();
                    chunkHasSpeech = false;
                }
            }

            // Flush the trailing chunk (whatever was captured since the last split). Also, if the VAD
            // never fired for the whole session (soft/quiet speech that stayed under the aggressive
            // threshold), fall back to transcribing the captured audio anyway once it's ~0.5 s+, so quiet
            // speech is not silently dropped — the legacy single-buffer path always transcribed.
            boolean trailing = chunkHasSpeech && chunk.size() > 6400;   // 0.2 s of detected speech
            // Any substantial residual (~0.5 s+) left after the last split, even with chunkHasSpeech==false:
            // a soft trailing fragment after an earlier chunk (VAD stopped firing on quiet tail speech) used
            // to match neither branch and was silently dropped. Subsumes the old VAD-never-fired fallback (A5).
            boolean residual = chunk.size() > 16000;
            // Gate on `announced` — the VAD must have fired at least once this session. If it never did, the
            // whole recording is silence/noise (the spectral VAD is reliable even when the platform AGC has
            // boosted room noise to speech level, which defeats any energy test): don't flush it, so neither
            // engine transcribes silence into a hallucinated sentence ("The train is leaving the station").
            if (announced && (trailing || residual)) {
                byte[] out = chunk.toByteArray();
                mChunkListener.onChunk(out);
                chunksEmitted++;
            }

            Log.d(TAG, "Chunked recording done, chunks emitted: " + chunksEmitted);
            sendUpdate(chunksEmitted > 0 ? MSG_RECORDING_DONE : MSG_RECORDING_ERROR);
        } finally {
            // Free every native resource on ALL exit paths so a throw can't leak the mic/VAD (F02).
            if (chunkVad != null) chunkVad.release();
            if (audioRecord != null) {
                try { audioRecord.stop(); } catch (Exception ignored) { }
                audioRecord.release();
            }
            releaseEffects();
            if (scoStarted) {
                audioManager.stopBluetoothSco();
                audioManager.setBluetoothScoOn(false);
            }
        }
        // Completion is signalled centrally in recordLoop's finally (covers every exit path).
    }

}
