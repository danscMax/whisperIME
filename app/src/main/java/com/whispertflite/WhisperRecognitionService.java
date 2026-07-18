package com.whispertflite;

import static android.speech.SpeechRecognizer.ERROR_CLIENT;
import static android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS;
import static android.speech.SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE;
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
        } else {
            ensureModel(selectedTfliteFile, callback, langToken);

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
                    try {
                        callback.error(ERROR_CLIENT);
                    } catch (RemoteException e) {
                        throw new RuntimeException(e);
                    }
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
    private void ensureModel(File modelFile, Callback callback, int langToken) {
        if (mWhisper == null || !modelFile.getAbsolutePath().equals(loadedModelPath)) {
            deinitModel();
            boolean isMultilingualModel = !(modelFile.getName().endsWith(ENGLISH_ONLY_MODEL_EXTENSION));
            String vocabFileName = isMultilingualModel ? MULTILINGUAL_VOCAB_FILE : ENGLISH_ONLY_VOCAB_FILE;
            File vocabFile = new File(sdcardDataFolder, vocabFileName);
            mWhisper = new Whisper(this);
            mWhisper.loadModel(modelFile, vocabFile, isMultilingualModel);
            loadedModelPath = modelFile.getAbsolutePath();
            Log.d(TAG, "Loaded: " + modelFile.getName());
        }
        mWhisper.setLanguage(langToken);
        Log.d(TAG, "Language token " + langToken);
        mWhisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) { }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                if (whisperResult.getResult().trim().length() > 0){
                    Log.d(TAG, whisperResult.getResult().trim());
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
            }
        });
    }

    private void startRecording() {
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
