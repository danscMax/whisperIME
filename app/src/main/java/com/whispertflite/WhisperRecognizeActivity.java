package com.whispertflite;

import static com.whispertflite.MainActivity.ENGLISH_ONLY_MODEL_EXTENSION;
import static com.whispertflite.MainActivity.ENGLISH_ONLY_VOCAB_FILE;
import static com.whispertflite.MainActivity.MULTILINGUAL_VOCAB_FILE;
import static com.whispertflite.MainActivity.MULTI_LINGUAL_TOP_WORLD_SLOW;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.google.android.material.color.MaterialColors;
import com.whispertflite.asr.Recorder;
import com.whispertflite.asr.Whisper;
import com.whispertflite.asr.WhisperResult;
import com.whispertflite.history.HistoryDb;
import com.whispertflite.models.ModelInfo;
import com.whispertflite.models.ModelRegistry;
import com.whispertflite.utils.HapticFeedback;
import com.whispertflite.utils.InputLang;
import com.whispertflite.utils.ThemeUtils;

import java.io.File;
import java.util.ArrayList;

public class WhisperRecognizeActivity extends AppCompatActivity {
    private static final String TAG = "WhisperRecognizeActivity";
    private ImageButton btnRecord;
    private ImageButton btnCancel;
    private com.whispertflite.ui.AuroraOrbView orb;
    private ProgressBar processingBar = null;
    private TextView statusText;
    private TextView partialText;
    private Recorder mRecorder = null;
    private Whisper mWhisper = null;
    private File sdcardDataFolder = null;
    private File selectedTfliteFile = null;
    private SharedPreferences sp = null;
    private Context mContext;
    private CountDownTimer countDownTimer;
    private String langCode = "auto";

    // ACTION_PROCESS_TEXT support: dictation replaces the selected text.
    private boolean processTextMode = false;
    private boolean processTextReadonly = false;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = this;
        ThemeUtils.applyNightMode(this);
        ThemeUtils.applyPalette(this);
        sp = PreferenceManager.getDefaultSharedPreferences(this);
        sdcardDataFolder = this.getExternalFilesDir(null);
        // Use the SAME model the rest of the app selected (selectedModelId), not the legacy
        // "modelName" pref — otherwise the dialog reports "no model" whenever that stale default
        // (small) isn't the downloaded one. Works for TFLite and whisper.cpp (loadModel routes by file).
        ModelInfo sel = null;
        String selId = sp.getString("selectedModelId", null);
        if (selId != null) sel = ModelRegistry.byId(selId);
        // Fall back to any actually-downloaded model when none is selected OR the selected model's
        // file isn't on disk (stale pref) — otherwise the dialog would wrongly report "no model".
        if (sel == null || !new File(sdcardDataFolder, sel.filename).exists()) {
            sel = null;
            for (ModelInfo m : ModelRegistry.all()) {
                if (new File(sdcardDataFolder, m.filename).exists()) { sel = m; break; }
            }
        }
        selectedTfliteFile = new File(sdcardDataFolder, sel != null ? sel.filename : "none");

        Intent intent = getIntent();
        processTextMode = Intent.ACTION_PROCESS_TEXT.equals(intent.getAction());
        if (processTextMode) {
            processTextReadonly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false);
        }

        setContentView(R.layout.activity_recognize);

        // Don't vanish on an accidental tap outside while the user is dictating — close only via the X.
        setFinishOnTouchOutside(false);

        // Position the sheet at the bottom of the screen (floating window).
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.BOTTOM;

        btnCancel = findViewById(R.id.btnCancel);
        btnRecord = findViewById(R.id.btnRecord);
        orb = findViewById(R.id.orb);
        orb.setColors(themeColor(com.google.android.material.R.attr.colorPrimary),
                themeColor(com.google.android.material.R.attr.colorPrimaryContainer));
        processingBar = findViewById(R.id.processing_bar);
        statusText = findViewById(R.id.dialog_status);
        partialText = findViewById(R.id.dialog_partial);

        btnCancel.setOnClickListener(v -> {
            if (mWhisper != null) stopTranscription();
            setResult(RESULT_CANCELED, null);
            finish();
        });

        // Edge state: no recognition model present -> point the user to the catalog.
        if (!selectedTfliteFile.exists()) {
            showNoModelState();
            return;
        }

        String targetLang = intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE);
        langCode = sp.getString("language", "auto");
        int langToken = InputLang.getIdForLanguage(InputLang.getLangList(), langCode);
        if (targetLang != null) {
            langCode = targetLang.split("[-_]")[0].toLowerCase();  // support both de_DE and de-DE
            langToken = InputLang.getIdForLanguage(InputLang.getLangList(), langCode);
        }

        initModel(selectedTfliteFile, langToken);
        updateChip();

        mRecorder = new Recorder(this);
        mRecorder.setRmsListener(rms -> runOnUiThread(() -> orb.pushLevel(rms)));
        mRecorder.setListener(message -> {
            if (message.equals(Recorder.MSG_RECORDING)) {
                runOnUiThread(() -> setStatus(R.string.dialog_listening));
            } else if (message.equals(Recorder.MSG_RECORDING_DONE)) {
                HapticFeedback.vibrate(mContext);
                runOnUiThread(this::stopMicPulse);
                startTranscription();
            } else if (message.equals(Recorder.MSG_RECORDING_ERROR)) {
                HapticFeedback.vibrate(mContext);
                if (countDownTimer != null) countDownTimer.cancel();
                runOnUiThread(() -> {
                    stopMicPulse();
                    processingBar.setProgress(0);
                    processingBar.setVisibility(View.INVISIBLE);
                    setStatus(R.string.dialog_tap_to_talk); // let the user tap the orb to retry
                    Toast.makeText(mContext, R.string.error_no_input, Toast.LENGTH_SHORT).show();
                });
            }
        });

        // One unified mode: the dialog listens as soon as it opens and auto-stops on a speech pause
        // (VAD). Tap the orb to stop early or to listen again; the X (top-left) always cancels.
        btnRecord.setOnClickListener(v -> {
            if (mRecorder.isInProgress()) {
                mRecorder.stop();               // stop now -> transcribe what was captured
            } else if (mWhisper != null && !mWhisper.isInProgress()) {
                startListening();               // idle: start (or retry) listening
            }
        });

        if (checkRecordPermission()) startListening();
    }

    /** Begin a VAD-gated listening session (auto-stops on a speech pause). */
    private void startListening() {
        HapticFeedback.vibrate(this);
        setStatus(R.string.dialog_listening);
        mRecorder.initVad();   // auto-stop on silence
        mRecorder.start();
        startCountdown();
    }

    private void showNoModelState() {
        findViewById(R.id.dialog_main_group).setVisibility(View.GONE);
        processingBar.setVisibility(View.GONE);
        View noModel = findViewById(R.id.dialog_no_model_group);
        noModel.setVisibility(View.VISIBLE);
        findViewById(R.id.dialog_open_catalog).setOnClickListener(v -> {
            startActivity(new Intent(this, com.whispertflite.models.ModelCatalogActivity.class));
            setResult(RESULT_CANCELED, null);
            finish();
        });
    }

    private void startCountdown() {
        runOnUiThread(() -> {
            processingBar.setVisibility(View.VISIBLE);
            processingBar.setProgress(100);
        });
        countDownTimer = new CountDownTimer(30000, 1000) {
            @Override
            public void onTick(long l) {
                runOnUiThread(() -> processingBar.setProgress((int) (l / 300)));
            }
            @Override
            public void onFinish() {}
        };
        countDownTimer.start();
    }

    private void setStatus(int resId) {
        if (statusText != null) statusText.setText(resId);
    }

    private void updateChip() {
        TextView chip = findViewById(R.id.dialog_chip);
        String modelLabel = selectedTfliteFile.getName();
        for (ModelInfo m : ModelRegistry.all()) {
            if (m.filename.equals(selectedTfliteFile.getName())) {
                modelLabel = m.displayName;
                break;
            }
        }
        String lang = ("auto".equals(langCode) ? "AUTO" : langCode.toUpperCase());
        chip.setText(modelLabel + " · " + lang);
    }

    private void stopMicPulse() {
        if (orb != null) orb.setIdle();
    }

    private int themeColor(int attr) {
        return MaterialColors.getColor(orb, attr, Color.GRAY);
    }

    // Model initialization
    private void initModel(File modelFile, int langToken) {
        boolean isMultilingualModel = !(modelFile.getName().endsWith(ENGLISH_ONLY_MODEL_EXTENSION));
        String vocabFileName = isMultilingualModel ? MULTILINGUAL_VOCAB_FILE : ENGLISH_ONLY_VOCAB_FILE;
        File vocabFile = new File(sdcardDataFolder, vocabFileName);

        mWhisper = new Whisper(this);
        mWhisper.loadModel(modelFile, vocabFile, isMultilingualModel);
        mWhisper.setLanguage(langToken);
        mWhisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) { }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                runOnUiThread(() -> processingBar.setIndeterminate(false));

                String result = whisperResult.getResult();
                if (whisperResult.getLanguage().equals("zh")) {
                    boolean simpleChinese = sp.getBoolean("simpleChinese", false);
                    result = simpleChinese ? ZhConverterUtil.toSimple(result) : ZhConverterUtil.toTraditional(result);
                }
                if (result.trim().length() > 0) {
                    final String text = result.trim();
                    runOnUiThread(() -> partialText.setText(text));
                    saveHistory(text, whisperResult.getLanguage());
                    sendResult(text);
                }
            }
        });
    }

    private void saveHistory(String text, String lang) {
        // System dialog: honor historyEnabled (historyFromIme does NOT apply here).
        if (!sp.getBoolean("historyEnabled", true)) return;
        String modelId = selectedTfliteFile.getName();
        for (ModelInfo m : ModelRegistry.all()) {
            if (m.filename.equals(selectedTfliteFile.getName())) { modelId = m.id; break; }
        }
        try {
            HistoryDb.get(this).insert(text, lang, modelId, 0);
        } catch (Exception e) {
            Log.w(TAG, "history insert failed", e);
        }
    }

    private void sendResult(String result) {
        if (processTextMode) {
            if (processTextReadonly) {
                // Read-only selection: replacement is ignored, so just copy the text.
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("dictation", result));
                Toast.makeText(this, R.string.dialog_copied, Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK, null);
            } else {
                Intent replace = new Intent();
                replace.putExtra(Intent.EXTRA_PROCESS_TEXT, result);
                setResult(RESULT_OK, replace);
            }
            finish();
            return;
        }
        Intent sendResultIntent = new Intent();
        ArrayList<String> results = new ArrayList<>();
        results.add(result);
        sendResultIntent.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results);
        sendResultIntent.putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, new float[]{1.0f});
        setResult(RESULT_OK, sendResultIntent);
        finish();
    }

    private void startTranscription() {
        if (countDownTimer != null) countDownTimer.cancel();
        runOnUiThread(() -> {
            setStatus(R.string.dialog_processing);
            processingBar.setVisibility(View.VISIBLE);
            processingBar.setProgress(0);
            processingBar.setIndeterminate(true);
        });
        if (mWhisper != null) {
            mWhisper.setAction(Whisper.ACTION_TRANSCRIBE);
            mWhisper.start();
        }
    }

    private void stopTranscription() {
        runOnUiThread(() -> processingBar.setIndeterminate(false));
        mWhisper.stop();
    }

    private boolean checkRecordPermission() {
        int permission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, getString(R.string.need_record_audio_permission), Toast.LENGTH_SHORT).show();
        }
        return (permission == PackageManager.PERMISSION_GRANTED);
    }

    private void deinitModel() {
        if (mWhisper != null) {
            mWhisper.unloadModel();
            mWhisper = null;
        }
    }

    @Override
    public void onDestroy() {
        stopMicPulse();
        deinitModel();
        if (mRecorder != null && mRecorder.isInProgress()) {
            mRecorder.stop();
        }
        super.onDestroy();
    }
}
