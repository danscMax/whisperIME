package com.whispertflite;

import static android.speech.SpeechRecognizer.ERROR_CLIENT;
import static android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS;
import static android.speech.SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE;
import static android.speech.SpeechRecognizer.ERROR_NO_MATCH;
import static com.whispertflite.MainActivity.ENGLISH_ONLY_MODEL_EXTENSION;
import static com.whispertflite.MainActivity.ENGLISH_ONLY_VOCAB_FILE;
import static com.whispertflite.MainActivity.MULTILINGUAL_VOCAB_FILE;
import static com.whispertflite.MainActivity.MULTI_LINGUAL_TOP_WORLD_SLOW;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.whispertflite.asr.Recorder;
import com.whispertflite.asr.Whisper;
import com.whispertflite.asr.WhisperResult;
import com.whispertflite.utils.HapticFeedback;
import com.whispertflite.utils.InputLang;

import java.io.File;
import java.util.ArrayList;

public class WhisperRecognitionService extends RecognitionService {
    private static final String TAG = "WhisperRecognitionService";
    private Recorder mRecorder = null;
    private Whisper mWhisper = null;
    private String loadedModelPath = null;   // keep the model warm across utterances (avoid reloading)
    private File sdcardDataFolder = null;
    private File selectedTfliteFile = null;
    private boolean recognitionCancelled = false;
    // Guards the SpeechRecognizer contract: exactly one terminal callback (results OR error) per
    // utterance. Without it an empty/failed run never calls back and the client hangs forever (C1).
    private boolean resultDelivered = false;
    private SharedPreferences sp = null;

    @Override
    protected void onStartListening(Intent recognizerIntent, Callback callback) {
        String targetLang = recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE);
        sp = PreferenceManager.getDefaultSharedPreferences(this);
        String langCode = sp.getString("recognitionServiceLanguage", "auto");
        int langToken = InputLang.getIdForLanguage(InputLang.getLangList(),langCode);
        Log.d(TAG,"default langToken " + langToken);

        if (targetLang != null) {
            Log.d(TAG,"StartListening in " + targetLang);
            langCode = targetLang.split("[-_]")[0].toLowerCase();   //support both de_DE and de-DE
            langToken = InputLang.getIdForLanguage(InputLang.getLangList(),langCode);
        } else {
            Log.d(TAG,"StartListening, no language specified");
        }

        checkRecordPermission(callback);

        sdcardDataFolder = this.getExternalFilesDir(null);
        selectedTfliteFile = new File(sdcardDataFolder, sp.getString("recognitionServiceModelName", MULTI_LINGUAL_TOP_WORLD_SLOW));

        if (!selectedTfliteFile.exists()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    callback.error(ERROR_LANGUAGE_UNAVAILABLE);
                } else {
                    callback.error(ERROR_CLIENT);
                }
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        } else if (ensureModel(selectedTfliteFile, callback, langToken)) {
            // Reuse one Recorder across requests (its worker thread is expensive and was leaked when
            // a fresh one was created per request); rebind the listener to this request's callback.
            if (mRecorder == null) mRecorder = new Recorder(this);
            mRecorder.setListener(message -> {
                if (message.equals(Recorder.MSG_RECORDING)){
                    try {
                        callback.rmsChanged(10);
                    } catch (RemoteException e) {
                        throw new RuntimeException(e);
                    }
                } else if (message.equals(Recorder.MSG_RECORDING_DONE)) {
                    HapticFeedback.vibrate(this);
                    try {
                        callback.rmsChanged(-20.0f);
                    } catch (RemoteException e) {
                        throw new RuntimeException(e);
                    }
                    startTranscription();
                } else if (message.equals(Recorder.MSG_RECORDING_ERROR)) {
                    deliverError(callback, ERROR_CLIENT);
                }
            });

            if (!mWhisper.isInProgress()) {
                HapticFeedback.vibrate(this);
                startRecording();
                try {
                    callback.beginningOfSpeech();
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }
        }

    }

    private void stopRecording() {
        if (mRecorder != null && mRecorder.isInProgress()) {
            mRecorder.stop();
        }
    }

    @Override
    protected void onCancel(Callback callback) {
        Log.d(TAG,"cancel");
        stopRecording();
        if (mWhisper != null) mWhisper.stop();   // abort an in-flight run but keep the model warm
        recognitionCancelled = true;
    }

    @Override
    protected void onStopListening(Callback callback) {
        Log.d(TAG,"StopListening");
        stopRecording();
    }

    // Load the model only when it's not already loaded (or the selection changed), then (re)bind the
    // current request's callback. Keeping the native context warm across utterances avoids reloading
    // hundreds of MB per request; the listener is rebound each time so results reach the live callback.
    private boolean ensureModel(File modelFile, Callback callback, int langToken) {
        if (mWhisper == null || !modelFile.getAbsolutePath().equals(loadedModelPath)) {
            deinitModel();
            boolean isMultilingualModel = !(modelFile.getName().endsWith(ENGLISH_ONLY_MODEL_EXTENSION));
            String vocabFileName = isMultilingualModel ? MULTILINGUAL_VOCAB_FILE : ENGLISH_ONLY_VOCAB_FILE;
            File vocabFile = new File(sdcardDataFolder, vocabFileName);
            mWhisper = new Whisper(this);
            if (!mWhisper.loadModel(modelFile, vocabFile, isMultilingualModel)) {
                // Load failed — don't mark the path loaded (C2); tell the client instead of recording
                // into a dead engine and hanging (C4/C13).
                deinitModel();
                deliverError(callback, ERROR_CLIENT);
                return false;
            }
            loadedModelPath = modelFile.getAbsolutePath();
            Log.d(TAG, "Loaded: " + modelFile.getName());
        }
        mWhisper.setLanguage(langToken);
        Log.d(TAG, "Language token " + langToken);
        mWhisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) {
                // Route a failure update to a terminal error so the client never waits forever (C1/C4).
                if (message.startsWith(Whisper.MSG_TRANSCRIBE_FAILED)
                        || message.startsWith(Whisper.MSG_LOAD_FAILED)
                        || message.equals(Whisper.MSG_ENGINE_NOT_INIT)) {
                    deliverError(callback, ERROR_CLIENT);
                }
            }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                String trimmed = whisperResult.getResult().trim();
                if (trimmed.isEmpty()) {
                    // Empty (silence) or failed run — fire the REQUIRED terminal callback the old code
                    // omitted, which left the client's SpeechRecognizer waiting forever (C1/C5).
                    deliverError(callback, whisperResult.isError() ? ERROR_CLIENT : ERROR_NO_MATCH);
                    return;
                }
                if (resultDelivered) return;
                resultDelivered = true;
                Log.d(TAG, trimmed);
                try {
                    callback.endOfSpeech();
                    Bundle results = new Bundle();
                    ArrayList<String> resultList = new ArrayList<>();

                    String result = whisperResult.getResult();
                    if (whisperResult.getLanguage().equals("zh")){
                        boolean simpleChinese = sp.getBoolean("RecognitionServiceSimpleChinese",false);
                        result = simpleChinese ? ZhConverterUtil.toSimple(result) : ZhConverterUtil.toTraditional(result);
                    }

                    resultList.add(result.trim());
                    results.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, resultList);
                    callback.results(results);
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        return true;
    }

    /** Fire a single terminal error callback, honouring the one-terminal-callback contract (C1). */
    private void deliverError(Callback callback, int errorCode) {
        if (resultDelivered) return;
        resultDelivered = true;
        try {
            callback.error(errorCode);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    private void startRecording() {
        resultDelivered = false;   // new utterance — re-arm the one-terminal-callback guard (C1)
        mRecorder.initVad();
        mRecorder.start();
        recognitionCancelled = false;
    }

    private void startTranscription() {
        if (!recognitionCancelled){
            Handler handler = new Handler(Looper.getMainLooper());
            handler.post(()-> {
                Toast toast = new Toast(this);
                toast.setDuration(Toast.LENGTH_SHORT);
                toast.setText(R.string.processing);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    toast.addCallback(new Toast.Callback() {
                        @Override
                        public void onToastHidden() {
                            super.onToastHidden();
                            // keep the "processing" toast up only while a run is actually in flight;
                            // the model now stays loaded, so gating on mWhisper!=null would loop forever
                            if (mWhisper != null && mWhisper.isInProgress()) toast.show();
                        }
                    });
                }
                toast.show();
            });
            mWhisper.setAction(Whisper.ACTION_TRANSCRIBE);
            mWhisper.start();
            Log.d(TAG,"Start Transcription");
        }
    }

    @Override
    public void onDestroy (){
        deinitModel();
        if (mRecorder != null) {
            mRecorder.shutdown();   // ends the worker thread (was leaked: one per recognition request)
            mRecorder = null;
        }
    }
    private void deinitModel() {
        if (mWhisper != null) {
            mWhisper.shutdown();   // free the engine AND stop the worker thread (unloadModel leaks it)
            mWhisper = null;
        }
        loadedModelPath = null;
    }

    private void checkRecordPermission(Callback callback) {
        int permission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO);
        if (permission != PackageManager.PERMISSION_GRANTED){
            Log.d(TAG,getString(R.string.need_record_audio_permission));
            try {
                callback.error(ERROR_INSUFFICIENT_PERMISSIONS);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
    }

}
