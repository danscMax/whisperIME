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

import com.konovalov.vad.webrtc.Vad;
import com.konovalov.vad.webrtc.VadWebRTC;
import com.konovalov.vad.webrtc.config.FrameSize;
import com.konovalov.vad.webrtc.config.Mode;
import com.konovalov.vad.webrtc.config.SampleRate;
import com.whispertflite.R;
import com.whispertflite.util.AudioMath;

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

    private volatile boolean shouldStartRecording = false;
    private boolean useVAD = false;
    private VadWebRTC vad = null;
    private static final int VAD_FRAME_SIZE = 480;                 // 30 ms @ 16 kHz
    private static final int CHUNK_SILENCE_FRAMES = 700 / 30;      // ~700 ms pause splits a chunk
    private static final int CHUNK_HARD_CAP_BYTES = 16000 * 2 * 28; // 28 s force-split mid-speech

    private final Thread workerThread;

    public Recorder(Context context) {
        this.mContext = context;

        // Initialize and start the worker thread
        workerThread = new Thread(this::recordLoop);
        workerThread.start();
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
        lock.lock();
        try {
            Log.d(TAG, "Recording starts now");
            shouldStartRecording = true;
            hasTask.signal();
        } finally {
            lock.unlock();
        }
    }

    public void initVad(){
        vad = Vad.builder()
                .setSampleRate(SampleRate.SAMPLE_RATE_16K)
                .setFrameSize(FrameSize.FRAME_SIZE_480)
                .setMode(Mode.VERY_AGGRESSIVE)
                .setSilenceDurationMs(800)
                .setSpeechDurationMs(200)
                .build();
        useVAD = true;
        Log.d(TAG, "VAD initialized");
    }


    public void stop() {
        Log.d(TAG, "Recording stopped");
        mInProgress.set(false);

        // Wait for the recording thread to finish
        synchronized (fileSavedLock) {
            try {
                fileSavedLock.wait(); // Wait until notified by the recording thread
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore interrupted status
            }
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
     * Peak-normalize 16-bit little-endian PCM in place. Phone mics on VOICE_RECOGNITION deliver a
     * raw, often very quiet signal (no AGC); Whisper and the VAD both degrade on it. Gain is capped
     * so noise-only audio is not blown up.
     */
    private static void normalizePcm(byte[] pcm) {
        AudioMath.normalizeInPlace(pcm);
    }

    /** Start Bluetooth SCO only when a Bluetooth input device is actually connected. */
    private boolean maybeStartSco(AudioManager audioManager) {
        try {
            for (android.media.AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (d.getType() == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    audioManager.startBluetoothSco();
                    audioManager.setBluetoothScoOn(true);
                    return true;
                }
            }
        } catch (Exception ignored) { }
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
        } catch (Exception ignored) { }
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
        while (true) {
            lock.lock();
            try {
                while (!shouldStartRecording) {
                    hasTask.await();
                }
                shouldStartRecording = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                lock.unlock();
            }

            // Start recording process
            try {
                if (mChunkListener != null) recordAudioChunked();
                else recordAudio();
            } catch (Exception e) {
                Log.e(TAG, "Recording error...", e);
                sendUpdate(e.getMessage());
            } finally {
                mInProgress.set(false);
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
        int audioSource = MediaRecorder.AudioSource.MIC;

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

        AudioRecord audioRecord = builder.build();
        attachEffects(audioRecord);
        audioRecord.startRecording();

        // Calculate maximum byte counts for 30 seconds (for saving)
        int bytesForThirtySeconds = sampleRateInHz * bytesPerSample * channels * 30;

        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream(); // Buffer for saving data RecordBuffer

        byte[] audioData = new byte[bufferSize];
        int totalBytesRead = 0;

        boolean isSpeech;
        boolean isRecording = false;
        byte[] vadAudioBuffer = new byte[VAD_FRAME_SIZE * 2];  //VAD needs 16 bit

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
                byte[] outputBufferByteArray = outputBuffer.toByteArray();
                if (outputBufferByteArray.length >= VAD_FRAME_SIZE * 2) {
                    // Always use the last VAD_FRAME_SIZE * 2 bytes (16 bit) from outputBuffer for VAD
                    System.arraycopy(outputBufferByteArray, outputBufferByteArray.length - VAD_FRAME_SIZE * 2, vadAudioBuffer, 0, VAD_FRAME_SIZE * 2);

                    isSpeech = vad.isSpeech(vadAudioBuffer);
                    if (isSpeech) {
                        if (!isRecording) {
                            Log.d(TAG, "VAD Speech detected: recording starts");
                            sendUpdate(MSG_RECORDING);
                        }
                        isRecording = true;
                    } else {
                        if (isRecording) {
                            isRecording = false;
                            mInProgress.set(false);
                        }
                    }
                }
            } else {
                if (!isRecording) sendUpdate(MSG_RECORDING);
                isRecording = true;
            }
        }
        Log.d(TAG, "Total bytes recorded: " + totalBytesRead);

        if (useVAD){
            useVAD = false;
            vad.close();
            vad = null;
            Log.d(TAG, "Closing VAD");
        }
        audioRecord.stop();
        audioRecord.release();
        releaseEffects();
        if (scoStarted) {
            audioManager.stopBluetoothSco();
            audioManager.setBluetoothScoOn(false);
        }

        // Save recorded audio data to BufferStore (up to 30 seconds)
        byte[] recorded = outputBuffer.toByteArray();
        normalizePcm(recorded);
        RecordBuffer.setOutputBuffer(recorded);
        if (totalBytesRead > 6400){  //min 0.2s
            sendUpdate(MSG_RECORDING_DONE);
        } else {
            sendUpdate(MSG_RECORDING_ERROR);
        }

        // Notify the waiting thread that recording is complete
        synchronized (fileSavedLock) {
            fileSavedLock.notify(); // Notify that recording is finished
        }

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
        int audioSource = MediaRecorder.AudioSource.MIC;

        int bufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
        if (bufferSize < VAD_FRAME_SIZE * 2) bufferSize = VAD_FRAME_SIZE * 2;

        AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        boolean scoStarted = maybeStartSco(audioManager);

        AudioRecord audioRecord = new AudioRecord.Builder()
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

        VadWebRTC chunkVad = Vad.builder()
                .setSampleRate(SampleRate.SAMPLE_RATE_16K)
                .setFrameSize(FrameSize.FRAME_SIZE_480)
                .setMode(Mode.VERY_AGGRESSIVE)
                .setSilenceDurationMs(300)
                .setSpeechDurationMs(100)
                .build();

        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        byte[] frame = new byte[VAD_FRAME_SIZE * 2];
        int silenceFrames = 0;
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

            // VAD sees a normalized copy: quiet mics (raw VOICE_RECOGNITION path) otherwise never
            // cross the speech threshold. webrtc VAD is spectral, so amplified noise stays rejected.
            boolean isSpeech = false;
            if (bytesRead == VAD_FRAME_SIZE * 2) {
                byte[] vadFrame = frame.clone();
                normalizePcm(vadFrame);
                isSpeech = chunkVad.isSpeech(vadFrame);
            }
            if (isSpeech) {
                if (!announced) { announced = true; sendUpdate(MSG_RECORDING); }
                chunkHasSpeech = true;
                silenceFrames = 0;
            } else if (chunkHasSpeech) {
                silenceFrames++;
            }

            boolean pauseSplit = chunkHasSpeech && silenceFrames >= CHUNK_SILENCE_FRAMES;
            boolean capSplit = chunk.size() >= CHUNK_HARD_CAP_BYTES;
            if (pauseSplit || capSplit) {
                byte[] out = chunk.toByteArray();
                normalizePcm(out);
                mChunkListener.onChunk(out);
                chunksEmitted++;
                chunk.reset();
                chunkHasSpeech = false;
                silenceFrames = 0;
            }
        }

        // Flush the trailing chunk (whatever was captured since the last split). Also, if the VAD
        // never fired for the whole session (soft/quiet speech that stayed under the aggressive
        // threshold), fall back to transcribing the captured audio anyway once it's ~0.5 s+, so quiet
        // speech is not silently dropped — the legacy single-buffer path always transcribed.
        boolean trailing = chunkHasSpeech && chunk.size() > 6400;   // 0.2 s of detected speech
        boolean softFallback = chunksEmitted == 0 && chunk.size() > 16000; // ~0.5 s, VAD never fired
        if (trailing || softFallback) {
            byte[] out = chunk.toByteArray();
            normalizePcm(out);
            mChunkListener.onChunk(out);
            chunksEmitted++;
        }

        chunkVad.close();
        audioRecord.stop();
        audioRecord.release();
        releaseEffects();
        if (scoStarted) {
            audioManager.stopBluetoothSco();
            audioManager.setBluetoothScoOn(false);
        }

        Log.d(TAG, "Chunked recording done, chunks emitted: " + chunksEmitted);
        sendUpdate(chunksEmitted > 0 ? MSG_RECORDING_DONE : MSG_RECORDING_ERROR);

        synchronized (fileSavedLock) {
            fileSavedLock.notify();
        }
    }

}
