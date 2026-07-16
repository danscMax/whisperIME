package com.whispertflite;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.whispertflite.asr.Recorder;
import com.whispertflite.asr.Whisper;
import com.whispertflite.asr.WhisperResult;
import com.whispertflite.history.HistoryDb;
import com.whispertflite.models.ModelInfo;
import com.whispertflite.models.ModelRegistry;
import com.whispertflite.utils.HapticFeedback;
import com.whispertflite.utils.InputLang;

import java.io.File;
import java.util.List;
import java.util.Locale;

public class WhisperInputMethodService extends InputMethodService {
    private static final String TAG = "WhisperInputMethodService";

    private enum UiState { IDLE, RECORDING, PROCESSING }

    private ImageButton btnRecord;
    private ImageButton btnKeyboard;
    private ImageButton btnTranslate;
    private ImageButton btnModeAuto;
    private ImageButton btnEnter;
    private ImageButton btnDel;
    private ImageButton btnSettings;
    private View recordRing;
    private CircularProgressIndicator processingSpinner;
    private TextView tvStatus;
    private TextView tvHint;
    private TextView tvModelChip;
    private TextView tvTimer;
    private LinearLayout idleGroup;
    private LinearLayout layoutButtons;
    private com.whispertflite.ui.WaveformView waveform;
    private com.whispertflite.ui.AuroraOrbView orb;
    private View rootView;

    private Recorder mRecorder = null;
    private Whisper mWhisper = null;
    private File sdcardDataFolder = null;
    private ModelInfo selectedModel = null;
    private String loadedModelId = null;
    private SharedPreferences sp = null;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Context mContext;

    private static boolean translate = false;
    private boolean modeAuto = false;

    // Recording/transcription session state.
    private volatile boolean recordingStopped = false;
    private long recordStartMs = 0;
    private long recordDurationMs = 0;
    private String lastLanguage = "";
    private int currentInputType = InputType.TYPE_NULL;
    private final StringBuilder imeDraft = new StringBuilder();

    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Runnable timerTick = new Runnable() {
        @Override
        public void run() {
            long el = (System.currentTimeMillis() - recordStartMs) / 1000;
            tvTimer.setText(String.format(Locale.US, "%d:%02d", el / 60, el % 60));
            timerHandler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate() {
        mContext = this;
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        deinitModel();
        if (mRecorder != null && mRecorder.isInProgress()) {
            mRecorder.stop();
        }
        super.onDestroy();
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        currentInputType = attribute.inputType;
        if (attribute.inputType == EditorInfo.TYPE_NULL) {
            Log.d(TAG, "Cancelling: onStartInput: inputType=" + attribute.inputType + ", package=" + attribute.packageName);
            deinitModel();
            if (mRecorder != null && mRecorder.isInProgress()) {
                mRecorder.stop();
            }
        }
    }

    @Override
    public void onStartInputView(EditorInfo attribute, boolean restarting) {
        currentInputType = attribute.inputType;
        selectedModel = resolveModel();

        if (selectedModel == null) {
            switchToPreviousInputMethod();  // no usable model: send the user to onboarding first
            Intent intent = new Intent(this, DownloadActivity.class);
            intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return;
        }

        if (mWhisper == null || !selectedModel.id.equals(loadedModelId)) {
            deinitModel();
            initModel();
        }
        updateModelChip();
        applyState(UiState.IDLE);
    }

    /** Selected model from prefs, else the first downloaded one; null when nothing is usable. */
    private ModelInfo resolveModel() {
        String id = sp.getString("selectedModelId", null);
        ModelInfo m = id != null ? ModelRegistry.byId(id) : null;
        if (m != null && isPresent(m)) return m;
        for (ModelInfo candidate : ModelRegistry.all()) {
            if (isPresent(candidate)) return candidate;
        }
        return null;
    }

    private boolean isPresent(ModelInfo m) {
        if (!new File(sdcardDataFolder, m.filename).exists()) return false;
        if (m.engine == ModelInfo.Engine.TFLITE
                && !new File(sdcardDataFolder, ModelRegistry.vocabFor(m)).exists()) return false;
        return true;
    }

    @SuppressLint({"ClickableViewAccessibility", "SetTextI18n"})
    @Override
    public View onCreateInputView() {  // runs before onStartInputView
        sp = PreferenceManager.getDefaultSharedPreferences(this);
        sdcardDataFolder = this.getExternalFilesDir(null);

        // The IME context has no activity theme; wrap it with the app theme + palette overlay so
        // Material attrs (colorPrimary/colorSurface/...) resolve. Follows values-night automatically.
        Context themed = themedContext();
        rootView = LayoutInflater.from(themed).inflate(R.layout.voice_service, null);

        btnRecord = rootView.findViewById(R.id.btnRecord);
        btnKeyboard = rootView.findViewById(R.id.btnKeyboard);
        btnTranslate = rootView.findViewById(R.id.btnTranslate);
        btnModeAuto = rootView.findViewById(R.id.btnModeAuto);
        btnEnter = rootView.findViewById(R.id.btnEnter);
        btnDel = rootView.findViewById(R.id.btnDel);
        btnSettings = rootView.findViewById(R.id.btnSettings);
        recordRing = rootView.findViewById(R.id.record_ring);
        processingSpinner = rootView.findViewById(R.id.processing_spinner);
        tvStatus = rootView.findViewById(R.id.tv_status);
        tvHint = rootView.findViewById(R.id.tv_hint);
        tvModelChip = rootView.findViewById(R.id.tv_model_chip);
        tvTimer = rootView.findViewById(R.id.tvTimer);
        idleGroup = rootView.findViewById(R.id.idle_group);
        layoutButtons = rootView.findViewById(R.id.layout_buttons);
        waveform = rootView.findViewById(R.id.waveform);
        orb = rootView.findViewById(R.id.orb);
        orb.setColors(themeColor(com.google.android.material.R.attr.colorPrimary),
                themeColor(com.google.android.material.R.attr.colorPrimaryContainer));

        btnTranslate.setImageResource(translate ? R.drawable.ic_english_on_36dp : R.drawable.ic_english_off_36dp);
        modeAuto = sp.getBoolean("imeModeAuto", false);
        btnModeAuto.setImageResource(modeAuto ? R.drawable.ic_auto_on_36dp : R.drawable.ic_auto_off_36dp);
        // Keys (keyboard-exit above all) stay visible in both modes: auto must never trap the user.
        checkRecordPermission();

        mRecorder = new Recorder(this);
        mRecorder.setRmsListener(rms -> handler.post(() -> {
            waveform.push(rms);
            orb.pushLevel(rms);
        }));
        mRecorder.setListener(new Recorder.RecorderListener() {
            @Override
            public void onUpdateReceived(String message) {
                if (message.equals(Recorder.MSG_RECORDING)) {
                    // Speech started; view already in RECORDING state.
                } else if (message.equals(Recorder.MSG_RECORDING_DONE)) {
                    HapticFeedback.vibrate(mContext);
                    recordingStopped = true;
                    recordDurationMs = System.currentTimeMillis() - recordStartMs;
                    if (modeAuto) {
                        startTranscription();
                        handler.post(() -> applyState(UiState.PROCESSING));
                    } else {
                        handler.post(() -> {
                            if (mWhisper != null && mWhisper.isInProgress()) applyState(UiState.PROCESSING);
                            else finalizeIme();
                        });
                    }
                } else if (message.equals(Recorder.MSG_RECORDING_ERROR)) {
                    HapticFeedback.vibrate(mContext);
                    handler.post(() -> {
                        applyState(UiState.IDLE);
                        tvStatus.setText(getString(R.string.error_no_input));
                        tvStatus.setVisibility(View.VISIBLE);
                        idleGroup.setVisibility(View.GONE);
                    });
                }
            }
        });

        applyState(UiState.IDLE);

        if (modeAuto) {
            HapticFeedback.vibrate(this);
            startRecording();
        }

        btnDel.setOnTouchListener(new View.OnTouchListener() {
            private Runnable initialDeleteRunnable;
            private Runnable repeatDeleteRunnable;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    getCurrentInputConnection().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                    initialDeleteRunnable = new Runnable() {
                        @Override
                        public void run() {
                            getCurrentInputConnection().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                            repeatDeleteRunnable = new Runnable() {
                                @Override
                                public void run() {
                                    getCurrentInputConnection().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                                    handler.postDelayed(this, 100);
                                }
                            };
                            handler.postDelayed(repeatDeleteRunnable, 100);
                        }
                    };
                    handler.postDelayed(initialDeleteRunnable, 500);
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    if (initialDeleteRunnable != null) handler.removeCallbacks(initialDeleteRunnable);
                    if (repeatDeleteRunnable != null) handler.removeCallbacks(repeatDeleteRunnable);
                    initialDeleteRunnable = null;
                    repeatDeleteRunnable = null;
                }
                return true;
            }
        });

        btnRecord.setOnTouchListener((v, event) -> {
            if (modeAuto) {
                // Hands-free: tap to start listening, tap again to stop (VAD also auto-stops).
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    if (mRecorder != null && mRecorder.isInProgress()) {
                        mRecorder.stop();
                    } else if (checkRecordPermission() && mWhisper != null && !mWhisper.isInProgress()) {
                        HapticFeedback.vibrate(this);
                        startRecording();
                    }
                }
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (checkRecordPermission()) {
                    if (mWhisper != null && !mWhisper.isInProgress()) {
                        HapticFeedback.vibrate(this);
                        startRecording();
                    } else {
                        handler.post(() -> {
                            tvStatus.setText(getString(R.string.please_wait));
                            tvStatus.setVisibility(View.VISIBLE);
                            idleGroup.setVisibility(View.GONE);
                        });
                    }
                }
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                if (mRecorder != null && mRecorder.isInProgress()) {
                    mRecorder.stop();
                }
            }
            return true;
        });

        btnKeyboard.setOnClickListener(v -> {
            if (mWhisper != null) stopTranscription();
            switchToPreviousInputMethod();
        });

        btnTranslate.setOnClickListener(v -> {
            translate = !translate;
            btnTranslate.setImageResource(translate ? R.drawable.ic_english_on_36dp : R.drawable.ic_english_off_36dp);
        });

        btnEnter.setOnClickListener(v ->
                getCurrentInputConnection().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)));

        btnSettings.setOnClickListener(v -> {
            Intent intent = new Intent(this, SettingsActivity.class);
            intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        });

        btnModeAuto.setOnClickListener(v -> {
            modeAuto = !modeAuto;
            sp.edit().putBoolean("imeModeAuto", modeAuto).apply();
            btnModeAuto.setImageResource(modeAuto ? R.drawable.ic_auto_on_36dp : R.drawable.ic_auto_off_36dp);
        });

        return rootView;
    }

    /** App theme wrapper carrying the palette overlay picked in preferences. */
    private Context themedContext() {
        Context base = new ContextThemeWrapper(this, R.style.Theme_Whisper_NoActionBar);
        String palette = sp.getString("palette", "teal");
        if ("dynamic".equals(palette)) {
            return DynamicColors.wrapContextIfAvailable(base);
        }
        int overlay;
        switch (palette) {
            case "terracotta": overlay = R.style.ThemeOverlay_Whisper_Terracotta; break;
            case "indigo":     overlay = R.style.ThemeOverlay_Whisper_Indigo;     break;
            case "forest":     overlay = R.style.ThemeOverlay_Whisper_Forest;     break;
            case "teal":
            default:           overlay = R.style.ThemeOverlay_Whisper_Teal;       break;
        }
        base.getTheme().applyStyle(overlay, true);
        return base;
    }

    private void startRecording() {
        recordingStopped = false;
        imeDraft.setLength(0);
        lastLanguage = "";
        if (modeAuto) {
            // Legacy hands-free path: single buffer, VAD auto-stop, then transcribe on DONE.
            mRecorder.setChunkListener(null);
            mRecorder.initVad();
        } else {
            // Manual path: unlimited chunked recording; each chunk streams into the field.
            setTranscriptionParams();
            mRecorder.setChunkListener(pcm -> {
                if (mWhisper != null) mWhisper.enqueueChunk(pcm);
            });
        }
        applyState(UiState.RECORDING);
        mRecorder.start();
    }

    private void setTranscriptionParams() {
        if (mWhisper == null) return;
        mWhisper.setAction(translate ? Whisper.ACTION_TRANSLATE : Whisper.ACTION_TRANSCRIBE);
        String langCode = sp.getString("language", "auto");
        int langToken = InputLang.getIdForLanguage(InputLang.getLangList(), langCode);
        mWhisper.setLanguage(langToken);
    }

    // Model initialization
    private void initModel() {
        File modelFile = new File(sdcardDataFolder, selectedModel.filename);
        boolean isMultilingual = !selectedModel.englishOnly;
        File vocabFile = selectedModel.engine == ModelInfo.Engine.TFLITE
                ? new File(sdcardDataFolder, ModelRegistry.vocabFor(selectedModel)) : null;

        mWhisper = new Whisper(this);
        mWhisper.loadModel(modelFile, vocabFile, isMultilingual);
        loadedModelId = selectedModel.id;
        Log.d(TAG, "Initialized: " + selectedModel.id + " (" + selectedModel.engine + ")");
        mWhisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) {
                if (message.equals(Whisper.MSG_PROCESSING)) {
                    if (recordingStopped) handler.post(() -> applyState(UiState.PROCESSING));
                } else if (message.equals(Whisper.MSG_PROCESSING_DONE)) {
                    if (recordingStopped && !mWhisper.isInProgress()) handler.post(() -> finalizeIme());
                }
            }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                String result = whisperResult.getResult();
                if (result == null) return;
                if (whisperResult.getLanguage().equals("zh")) {
                    boolean simpleChinese = sp.getBoolean("simpleChinese", false);
                    result = simpleChinese ? ZhConverterUtil.toSimple(result) : ZhConverterUtil.toTraditional(result);
                }
                lastLanguage = whisperResult.getLanguage();
                String trimmed = result.trim();
                if (trimmed.isEmpty()) return;

                boolean committed = getCurrentInputConnection() != null
                        && getCurrentInputConnection().commitText(trimmed + " ", 1);
                if (committed) imeDraft.append(trimmed).append(" ");
                // Stay on the strip after a result: the user leaves only via the keyboard key.
            }
        });
    }

    private void startTranscription() {
        setTranscriptionParams();
        if (mWhisper != null) mWhisper.start();
    }

    private void stopTranscription() {
        if (mWhisper != null) mWhisper.stop();
    }

    /** Recording stopped and the queue drained: log history once and return to idle. */
    private void finalizeIme() {
        String text = imeDraft.toString().trim();
        if (!text.isEmpty()
                && sp.getBoolean("historyEnabled", true)
                && sp.getBoolean("historyFromIme", true)
                && !isPasswordField(currentInputType)) {
            HistoryDb.get(mContext).insert(text, lastLanguage,
                    selectedModel != null ? selectedModel.id : "", recordDurationMs);
        }
        applyState(UiState.IDLE);
    }

    /** True for any password input variant; history must never capture these fields. */
    private boolean isPasswordField(int inputType) {
        int cls = inputType & InputType.TYPE_MASK_CLASS;
        int var = inputType & InputType.TYPE_MASK_VARIATION;
        if (cls == InputType.TYPE_CLASS_TEXT) {
            return var == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || var == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                    || var == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        }
        if (cls == InputType.TYPE_CLASS_NUMBER) {
            return var == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        }
        return false;
    }

    /** Single source of truth for the three IME states. */
    private void applyState(UiState state) {
        boolean recording = state == UiState.RECORDING;
        boolean processing = state == UiState.PROCESSING;

        idleGroup.setVisibility(recording || processing ? View.GONE : View.VISIBLE);
        waveform.setVisibility(recording ? View.VISIBLE : View.GONE);
        tvTimer.setVisibility(recording ? View.VISIBLE : View.GONE);
        recordRing.setVisibility(recording ? View.VISIBLE : View.GONE);
        processingSpinner.setVisibility(processing ? View.VISIBLE : View.GONE);
        btnRecord.setVisibility(processing ? View.INVISIBLE : View.VISIBLE);
        orb.setVisibility(processing ? View.INVISIBLE : View.VISIBLE);
        tvStatus.setVisibility(View.GONE);

        if (recording) {
            recordStartMs = System.currentTimeMillis();
            tvTimer.setText("0:00");
            waveform.clear();
            timerHandler.post(timerTick);
        } else {
            orb.setIdle();  // orb returns to calm breathing
            timerHandler.removeCallbacks(timerTick);
        }
    }

    @SuppressLint("SetTextI18n")
    private void updateModelChip() {
        if (tvModelChip == null || selectedModel == null) return;
        String langCode = sp.getString("language", "auto");
        tvModelChip.setText(selectedModel.displayName + " · " + langCode);
    }

    private int themeColor(int attr) {
        return MaterialColors.getColor(rootView, attr, Color.GRAY);
    }

    private boolean checkRecordPermission() {
        int permission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            tvStatus.setVisibility(View.VISIBLE);
            tvStatus.setText(getString(R.string.need_record_audio_permission));
            idleGroup.setVisibility(View.GONE);
        }
        return (permission == PackageManager.PERMISSION_GRANTED);
    }

    private void deinitModel() {
        if (mWhisper != null) {
            mWhisper.unloadModel();
            mWhisper = null;
        }
        loadedModelId = null;
    }
}
