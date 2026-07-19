package com.whispertflite;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.whispertflite.asr.Recorder;
import com.whispertflite.asr.WarmWhisper;
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
import java.util.List;
import java.util.Locale;

public class WhisperInputMethodService extends InputMethodService {
    private static final String TAG = "WhisperInputMethodService";

    private enum UiState { IDLE, RECORDING, PROCESSING }

    private ImageButton btnRecord;
    private ImageButton btnKeyboard;
    private ImageButton btnEnter;
    private ImageButton btnDel;
    private ImageButton btnMore;
    private TextView tvStatus;
    private TextView tvHint;
    private TextView tvModelChip;
    private TextView tvTimer;
    private LinearLayout idleGroup;
    private LinearLayout layoutButtons;
    private LivingSignalView orb;
    private View rootView;

    private Recorder mRecorder = null;
    /** Appearance the current input view was inflated with; see onStartInputView. */
    private String viewAppearance;
    private Whisper mWhisper = null;
    private File sdcardDataFolder = null;
    private ModelInfo selectedModel = null;
    private String loadedModelId = null;
    private SharedPreferences sp = null;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Context mContext;

    private static boolean translate = false;
    private boolean modeAuto = false;

    // Auto (VAD) mode grabs the mic on open; delay it a beat so the strip isn't recording the room the
    // instant it appears (D12). Cancelled if the view goes away before it fires.
    private static final long AUTO_START_DELAY_MS = 700;

    // Curated quick-pick languages for the IME menu (D11); the full 100-language list stays in the app.
    private static final String[] QUICK_LANGS = {"auto", "en", "ru", "es", "de", "fr", "zh", "ja", "pt", "it"};
    private final Runnable autoStartRunnable = () -> {
        HapticFeedback.vibrate(mContext);
        startRecording();
    };

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
        handler.removeCallbacks(autoStartRunnable);   // don't fire a pending auto-start after teardown (D12)
        deinitModel();
        if (mRecorder != null) {
            mRecorder.shutdown();   // ends the worker thread; stop() alone left it parked (leak)
        }
        super.onDestroy();
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        currentInputType = attribute.inputType;
        if (attribute.inputType == EditorInfo.TYPE_NULL) {
            Log.d(TAG, "Cancelling: onStartInput: inputType=" + attribute.inputType + ", package=" + attribute.packageName);
            handler.removeCallbacks(autoStartRunnable);
            deinitModel();
            if (mRecorder != null && mRecorder.isInProgress()) {
                mRecorder.stop();
            }
        }
    }

    @Override
    public void onStartInputView(EditorInfo attribute, boolean restarting) {
        // The service outlives the settings screen and gets no configuration change out of the
        // app's own theme preference, so without this the dock keeps the look it was inflated
        // with until the process dies.
        if (!ThemeUtils.appearanceSignature(this).equals(viewAppearance)) {
            setInputView(onCreateInputView());
        }
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
        for (ModelInfo.Asset a : m.files) { // all files present (sherpa models have several)
            if (!new File(sdcardDataFolder, a.relPath).exists()) return false;
        }
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
        Context themed = ThemeUtils.serviceContext(this);
        viewAppearance = ThemeUtils.appearanceSignature(this);
        rootView = LayoutInflater.from(themed).inflate(R.layout.voice_service, null);

        btnRecord = rootView.findViewById(R.id.btnRecord);
        btnKeyboard = rootView.findViewById(R.id.btnKeyboard);
        btnEnter = rootView.findViewById(R.id.btnEnter);
        btnDel = rootView.findViewById(R.id.btnDel);
        btnMore = rootView.findViewById(R.id.btnMore);
        tvStatus = rootView.findViewById(R.id.tv_status);
        tvHint = rootView.findViewById(R.id.tv_hint);
        tvModelChip = rootView.findViewById(R.id.tv_model_chip);
        tvTimer = rootView.findViewById(R.id.tvTimer);
        idleGroup = rootView.findViewById(R.id.idle_group);
        layoutButtons = rootView.findViewById(R.id.layout_buttons);
        orb = rootView.findViewById(R.id.orb);
        int[] orbTint = ThemeUtils.orbColors(this);
        orb.setColors(orbTint[0], orbTint[1]);

        modeAuto = sp.getBoolean("imeModeAuto", false);
        // Keys (keyboard-exit above all) stay visible in both modes: auto must never trap the user.
        checkRecordPermission();

        // The recorder belongs to the service, not the view: onCreateInputView runs again when
        // the appearance changes, and a second Recorder would leave the first holding the mic.
        if (mRecorder == null) mRecorder = new Recorder(this);
        mRecorder.setRmsListener(rms -> handler.post(() -> {
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
            handler.removeCallbacks(autoStartRunnable);
            handler.postDelayed(autoStartRunnable, AUTO_START_DELAY_MS);   // D12: brief delay, not instant
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

        btnEnter.setOnClickListener(v ->
                getCurrentInputConnection().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)));

        btnMore.setOnClickListener(this::showImeMenu);

        return rootView;
    }

    private void showImeMenu(View anchor) {
        PopupMenu menu = new PopupMenu(ThemeUtils.serviceContext(this), anchor);
        android.view.MenuItem translateItem = menu.getMenu().add(0, 1, 0, R.string.translate_short);
        translateItem.setCheckable(true);
        translateItem.setChecked(translate);
        android.view.MenuItem autoItem = menu.getMenu().add(0, 2, 1, R.string.auto_button);
        autoItem.setCheckable(true);
        autoItem.setChecked(modeAuto);
        // Quick language pick without leaving the keyboard (D11); takes effect on the next dictation.
        android.view.SubMenu langMenu = menu.getMenu().addSubMenu(0, 4, 2, R.string.language);
        String currentLang = sp.getString("language", "auto");
        for (int i = 0; i < QUICK_LANGS.length; i++) {
            String label = QUICK_LANGS[i].equals("auto")
                    ? getString(R.string.auto_lang) : QUICK_LANGS[i].toUpperCase();
            android.view.MenuItem li = langMenu.add(2, 100 + i, i, label);
            li.setCheckable(true);
            li.setChecked(QUICK_LANGS[i].equals(currentLang));
        }
        langMenu.setGroupCheckable(2, true, true);
        menu.getMenu().add(0, 3, 3, R.string.settings_title);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getGroupId() == 2) {
                int idx = item.getItemId() - 100;
                if (idx >= 0 && idx < QUICK_LANGS.length) {
                    sp.edit().putString("language", QUICK_LANGS[idx]).apply();
                }
                return true;
            }
            if (item.getItemId() == 1) {
                translate = !translate;
                updateModelChip();
                return true;
            }
            if (item.getItemId() == 2) {
                modeAuto = !modeAuto;
                sp.edit().putBoolean("imeModeAuto", modeAuto).apply();
                updateModelChip();
                return true;
            }
            if (item.getItemId() == 3) {
                Intent intent = new Intent(this, SettingsActivity.class);
                intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            }
            return false;
        });
        menu.show();
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
        final String wantId = selectedModel.id;

        // Warm hit (the process already holds this model — used earlier here or by the recognize dialog):
        // attach instantly, no wait.
        if (WarmWhisper.isWarm(modelFile)) {
            attachModel(WarmWhisper.get(this, modelFile, vocabFile, isMultilingual), wantId);
            return;
        }
        // Cold: loading a 640 MB model takes ~1 s. Do it OFF the main thread so the keyboard strip appears
        // immediately (was a synchronous freeze on the first open), with a "loading" status until ready.
        handler.post(() -> {
            tvStatus.setText(getString(R.string.dialog_loading));
            tvStatus.setVisibility(View.VISIBLE);
            idleGroup.setVisibility(View.GONE);
        });
        new Thread(() -> {
            Whisper w = WarmWhisper.get(this, modelFile, vocabFile, isMultilingual);
            handler.post(() -> {
                if (w == null) {
                    Log.e(TAG, "Model load failed: " + wantId);
                    tvStatus.setText(getString(R.string.error_model_load));
                    tvStatus.setVisibility(View.VISIBLE);
                    idleGroup.setVisibility(View.GONE);
                    return;
                }
                if (!wantId.equals(selectedModel.id)) return;   // model switched while loading — drop this
                attachModel(w, wantId);
                applyState(UiState.IDLE);
            });
        }).start();
    }

    /** Point the keyboard at the (warm) shared Whisper and install our status/result listener. */
    private void attachModel(Whisper w, String modelId) {
        mWhisper = w;
        loadedModelId = modelId;
        Log.d(TAG, "Initialized: " + modelId);
        mWhisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) {
                if (message.equals(Whisper.MSG_PROCESSING)) {
                    if (recordingStopped) handler.post(() -> applyState(UiState.PROCESSING));
                } else if (message.equals(Whisper.MSG_PROCESSING_DONE)) {
                    if (recordingStopped && !mWhisper.isInProgress()) handler.post(() -> finalizeIme());
                } else if (message.startsWith(Whisper.MSG_TRANSCRIBE_FAILED)
                        || message.startsWith(Whisper.MSG_LOAD_FAILED)
                        || message.equals(Whisper.MSG_ENGINE_NOT_INIT)) {
                    // Surface a failed run instead of leaving the strip stuck in PROCESSING (C4).
                    handler.post(() -> {
                        applyState(UiState.IDLE);
                        tvStatus.setText(getString(R.string.error_transcription));
                        tvStatus.setVisibility(View.VISIBLE);
                        idleGroup.setVisibility(View.GONE);
                    });
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

                InputConnection ic = getCurrentInputConnection();
                if (ic == null) return;

                // Smart spacing (D5): one space between chunks; a leading space before the first chunk
                // only when the existing field text doesn't already end in whitespace/opening punctuation.
                char prev;
                if (imeDraft.length() == 0) {
                    CharSequence before = ic.getTextBeforeCursor(1, 0);
                    prev = (before != null && before.length() > 0) ? before.charAt(0) : ' ';
                } else {
                    prev = imeDraft.charAt(imeDraft.length() - 1);
                }
                if (needsSpace(prev, trimmed)) imeDraft.append(' ');
                imeDraft.append(trimmed);

                // Composing text (D4/D1): the running transcript stays revisable in place and updates
                // live as chunks arrive, instead of being hard-committed per chunk. finalizeIme() commits.
                ic.setComposingText(imeDraft, 1);
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
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.finishComposingText();   // commit the revisable transcript as final (D4)
        String text = imeDraft.toString().trim();
        if (!text.isEmpty()
                && sp.getBoolean("historyEnabled", true)
                && sp.getBoolean("historyFromIme", true)
                && !isPasswordField(currentInputType)) {
            HistoryDb.get(mContext).insert(text, lastLanguage,
                    selectedModel != null ? selectedModel.id : "", recordDurationMs);
        }
        imeDraft.setLength(0);   // next dictation starts a fresh composing region
        applyState(UiState.IDLE);
    }

    /** True if a separating space belongs between {@code prev} and the start of {@code next} (D5). */
    private static boolean needsSpace(char prev, String next) {
        if (next.isEmpty() || Character.isWhitespace(prev)) return false;
        switch (next.charAt(0)) {   // never put a space before closing punctuation
            case '.': case ',': case '!': case '?': case ';': case ':':
            case ')': case ']': case '}': case '%':
                return false;
            default:
                return true;
        }
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

        idleGroup.setVisibility(View.VISIBLE);
        tvTimer.setVisibility(recording ? View.VISIBLE : View.GONE);
        btnRecord.setEnabled(!processing);
        btnRecord.setAlpha(processing ? 0.55f : 1f);
        tvStatus.setVisibility(View.GONE);
        tvHint.setText(recording ? R.string.dialog_listening
                : processing ? R.string.dialog_processing : R.string.ime_hint);
        orb.setSignalState(recording ? LivingSignalView.SignalState.LISTENING
                : processing ? LivingSignalView.SignalState.PROCESSING
                : LivingSignalView.SignalState.READY);

        if (recording) {
            recordStartMs = System.currentTimeMillis();
            tvTimer.setText("0:00");
            timerHandler.post(timerTick);
        } else {
            timerHandler.removeCallbacks(timerTick);
        }
    }

    @SuppressLint("SetTextI18n")
    private void updateModelChip() {
        if (tvModelChip == null || selectedModel == null) return;
        String langCode = sp.getString("language", "auto");
        String mode = modeAuto ? getString(R.string.auto_button) : getString(R.string.dialog_hold_to_speak);
        String action = translate ? getString(R.string.translate_short) : langCode;
        tvModelChip.setText(selectedModel.displayName + " · " + action + " · " + mode);
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
            mWhisper.stop();   // abort any in-flight run, but keep the model warm (shared, not shut down)
            // Drop our listener so this service isn't retained through the long-lived warm instance.
            mWhisper.setListener(new Whisper.WhisperListener() {
                @Override public void onUpdateReceived(String message) { }
                @Override public void onResultReceived(WhisperResult whisperResult) { }
            });
            mWhisper = null;
        }
        loadedModelId = null;
    }
}
