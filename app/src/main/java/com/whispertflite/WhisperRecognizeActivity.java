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
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.os.Build;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.whispertflite.asr.Recorder;
import com.whispertflite.asr.Whisper;
import com.whispertflite.asr.WhisperResult;
import com.whispertflite.history.HistoryDb;
import com.whispertflite.models.ModelInfo;
import com.whispertflite.models.ModelRegistry;
import com.whispertflite.ui.LivingSignalView;
import com.whispertflite.utils.HapticFeedback;
import com.whispertflite.utils.InputLang;
import com.whispertflite.utils.ThemeUtils;

import java.io.File;
import java.util.ArrayList;

public class WhisperRecognizeActivity extends AppCompatActivity {
    private static final String TAG = "WhisperRecognizeActivity";
    private ImageButton btnRecord;
    private ImageButton btnCancel;
    private LivingSignalView orb;
    private TextView statusText;
    private TextView partialText;
    private Recorder mRecorder = null;
    private Whisper mWhisper = null;
    private File sdcardDataFolder = null;
    private File selectedTfliteFile = null;
    private SharedPreferences sp = null;
    private Context mContext;
    private String langCode = "auto";
    private boolean modeAuto = false;   // false = push-to-talk (default), true = hands-free VAD

    // ACTION_PROCESS_TEXT support: dictation replaces the selected text.
    private boolean processTextMode = false;
    private boolean processTextReadonly = false;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = this;
        ThemeUtils.applyPalette(this);
        ThemeUtils.applyGlass(this);
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
        getWindow().setAttributes(params);
        applyBlurBehind();

        btnCancel = findViewById(R.id.btnCancel);
        btnRecord = findViewById(R.id.btnRecord);
        orb = findViewById(R.id.orb);
        int[] orbTint = ThemeUtils.orbColors(this);
        orb.setColors(orbTint[0], orbTint[1]);
        statusText = findViewById(R.id.dialog_status);
        partialText = findViewById(R.id.dialog_partial);

        btnCancel.setOnClickListener(v -> {
            if (mWhisper != null) stopTranscription();
            setResult(RESULT_CANCELED, null);
            finish();
        });

        // Return to typing: cancel the voice request (the caller's keyboard comes back) and offer
        // the input-method switcher so the user can land on a text keyboard directly.
        ImageButton btnKeyboard = findViewById(R.id.btnKeyboard);
        btnKeyboard.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
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
                runOnUiThread(() -> {
                    orb.setSignalState(LivingSignalView.SignalState.ERROR);
                    setStatus(modeAuto ? R.string.dialog_tap_to_talk : R.string.dialog_hold_to_speak);
                    Toast.makeText(mContext, R.string.error_no_input, Toast.LENGTH_SHORT).show();
                });
            }
        });

        // Two modes, same as the IME: push-to-talk (default) — hold the orb, release to transcribe;
        // auto (hands-free) — tap to start, VAD auto-stops on a speech pause, tap again to stop early.
        modeAuto = sp.getBoolean("imeModeAuto", false);
        TextView modeToggle = findViewById(R.id.dialog_mode);
        modeToggle.setOnClickListener(v -> {
            if (mRecorder.isInProgress()) mRecorder.stop();
            modeAuto = !modeAuto;
            sp.edit().putBoolean("imeModeAuto", modeAuto).apply();
            applyModeUi();
        });

        btnRecord.setOnTouchListener((v, event) -> {
            if (modeAuto) {
                // Hands-free: tap toggles listening; VAD also auto-stops.
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    if (mRecorder.isInProgress()) mRecorder.stop();
                    else if (mWhisper != null && !mWhisper.isInProgress()) startListening(true);
                }
                return true;
            }
            // Push-to-talk: record while held, transcribe on release.
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (mWhisper != null && !mWhisper.isInProgress()) startListening(false);
            } else if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                if (mRecorder.isInProgress()) mRecorder.stop();
            }
            return true;
        });

        applyModeUi();
        // Auto mode starts listening on open; push-to-talk waits calmly for the user to hold.
        if (modeAuto && checkRecordPermission()) startListening(true);
    }

    /** Reflect the current mode on the toggle pill and the idle prompt (when not busy). */
    private void applyModeUi() {
        TextView modeToggle = findViewById(R.id.dialog_mode);
        modeToggle.setText(modeAuto ? R.string.dialog_mode_auto : R.string.dialog_mode_hold);
        modeToggle.setBackgroundResource(
                modeAuto ? R.drawable.living_glass_pill_warm : R.drawable.living_glass_pill);
        if (mRecorder == null || !mRecorder.isInProgress()) {
            orb.setSignalState(LivingSignalView.SignalState.READY);
            setStatus(modeAuto ? R.string.dialog_tap_to_talk : R.string.dialog_hold_to_speak);
            if (partialText != null) partialText.setVisibility(View.GONE);  // no dead band at idle
        }
    }

    /**
     * Start a capture. Auto mode gates it with VAD (auto-stop on silence); push-to-talk records
     * continuously until {@link Recorder#stop()} on release.
     */
    private void startListening(boolean auto) {
        HapticFeedback.vibrate(this);
        setStatus(R.string.dialog_listening);
        orb.setSignalState(LivingSignalView.SignalState.LISTENING);
        if (auto) mRecorder.initVad();   // auto-stop on silence
        mRecorder.start();
    }

    private void showNoModelState() {
        findViewById(R.id.dialog_main_group).setVisibility(View.GONE);
        View noModel = findViewById(R.id.dialog_no_model_group);
        noModel.setVisibility(View.VISIBLE);
        findViewById(R.id.dialog_open_catalog).setOnClickListener(v -> {
            startActivity(new Intent(this, com.whispertflite.models.ModelCatalogActivity.class));
            setResult(RESULT_CANCELED, null);
            finish();
        });
    }

    private void setStatus(int resId) {
        if (statusText != null) statusText.setText(resId);
    }

    private void updateChip() {
        TextView chip = findViewById(R.id.dialog_chip);
        String fileName = selectedTfliteFile.getName();
        String modelLabel = fileName;
        for (ModelInfo m : ModelRegistry.all()) {
            // Match on the basename: gguf models carry a "gguf/…" path in m.filename, while the
            // selected file reports only its name, so a plain equals never hit and the raw
            // "ggml-tiny-q5_1.bin" leaked into the chip.
            if (new File(m.filename).getName().equals(fileName)) {
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

    /**
     * Frost the host app behind the sheet with cross-window blur (Android 12+, GPU-permitting).
     * See https://source.android.com/docs/core/display/window-blurs. When the system disables
     * blur (battery saver, unsupported GPU) we deepen the scrim instead so the sheet stays legible.
     */
    private java.util.function.Consumer<Boolean> blurListener;

    private void applyBlurBehind() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                | WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams p = getWindow().getAttributes();
        p.setBlurBehindRadius(40);
        getWindow().setAttributes(p);
        updateScrimForBlur(getWindowManager().isCrossWindowBlurEnabled());
        blurListener = this::updateScrimForBlur;   // keep the ref so onDestroy can remove it (no leak)
        getWindowManager().addCrossWindowBlurEnabledListener(getMainExecutor(), blurListener);
    }

    private void updateScrimForBlur(boolean blurEnabled) {
        WindowManager.LayoutParams p = getWindow().getAttributes();
        p.dimAmount = blurEnabled ? 0.20f : 0.55f;
        getWindow().setAttributes(p);
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
                String result = whisperResult.getResult();
                if (whisperResult.getLanguage().equals("zh")) {
                    boolean simpleChinese = sp.getBoolean("simpleChinese", false);
                    result = simpleChinese ? ZhConverterUtil.toSimple(result) : ZhConverterUtil.toTraditional(result);
                }
                if (result.trim().length() > 0) {
                    final String text = result.trim();
                    runOnUiThread(() -> {
                        partialText.setVisibility(View.VISIBLE);
                        partialText.setText(text);
                    });
                    saveHistory(text, whisperResult.getLanguage());
                    sendResult(text);
                } else {
                    // Nothing recognized (silence, or an all-marker result cleaned to empty):
                    // don't finish — return to the calm idle prompt so the user can try again.
                    runOnUiThread(WhisperRecognizeActivity.this::applyModeUi);
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
        runOnUiThread(() -> {
            setStatus(R.string.dialog_processing);
            orb.setSignalState(LivingSignalView.SignalState.PROCESSING);
        });
        if (mWhisper != null) {
            mWhisper.setAction(Whisper.ACTION_TRANSCRIBE);
            mWhisper.start();
        }
    }

    private void stopTranscription() {
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
            // shutdown() (not unloadModel()) also stops the worker thread; unloadModel leaves it
            // parked. Matches the fix already shipped in the two services.
            mWhisper.shutdown();
            mWhisper = null;
        }
    }

    @Override
    public void onDestroy() {
        stopMicPulse();
        deinitModel();
        if (blurListener != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getWindowManager().removeCrossWindowBlurEnabledListener(blurListener);
        }
        if (mRecorder != null) {
            mRecorder.shutdown();   // ends the worker thread; stop() alone left it parked (leak)
        }
        super.onDestroy();
    }
}
