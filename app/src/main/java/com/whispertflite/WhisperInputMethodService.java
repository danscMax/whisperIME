package com.whispertflite;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
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

    // Read from / written to the SHARED "translate" pref so every surface honours the same choice (D9);
    // MainActivity writes the same key. Loaded on view init, persisted on toggle.
    private boolean translate = false;
    private boolean modeAuto = false;

    // Auto (VAD) mode grabs the mic on open; delay it a beat so the strip isn't recording the room the
    // instant it appears (D12). Cancelled if the view goes away before it fires.
    private static final long AUTO_START_DELAY_MS = 700;

    // Curated quick-pick languages for the IME menu (D11); the full 100-language list stays in the app.
    private static final String[] QUICK_LANGS = {"auto", "en", "ru", "es", "de", "fr", "zh", "ja", "pt", "it"};
    private int autoStartRetries = 0;
    // Assigned in onCreate (a field initializer can't self-reference for the cold-start retry below).
    private Runnable autoStartRunnable;

    private void autoStartTick() {
        if (!modeAuto) return;
        if (mRecorder != null && mRecorder.isInProgress()) return;   // already listening
        if (!checkRecordPermission()) return;
        if (mWhisper == null || mWhisper.isInProgress()) {
            // Model still warming (cold start): retry a few times before giving up to a manual tap.
            if (autoStartRetries++ < 6) handler.postDelayed(autoStartRunnable, 300);
            return;
        }
        autoStartRetries = 0;
        HapticFeedback.vibrate(mContext);
        startRecording();
    }

    /** Kick off hands-free listening if auto mode is on. Safe to call on every keyboard show — the
     *  runnable self-guards against a double start / an unready model. */
    private void maybeAutoStart() {
        if (!modeAuto) return;
        autoStartRetries = 0;
        handler.removeCallbacks(autoStartRunnable);
        handler.postDelayed(autoStartRunnable, AUTO_START_DELAY_MS);
    }

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
        autoStartRunnable = this::autoStartTick;
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
        configureImeWindow();
        maybeAutoStart();   // hands-free: start listening on every keyboard show when auto mode is on
    }

    /**
     * Frosted glass: blur the host content behind the translucent dock so it reads as real glass
     * (API 31+, and only when the system allows cross-window blur — battery saver / low-end devices
     * disable it, and then the translucent frost alone still looks right). Best-effort; never throws.
     */
    private void configureImeWindow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        android.app.Dialog dlg = getWindow();
        android.view.Window w = dlg != null ? dlg.getWindow() : null;
        if (w == null) return;
        try {
            android.view.WindowManager wm = getSystemService(android.view.WindowManager.class);
            if (wm != null && wm.isCrossWindowBlurEnabled()) {
                w.setBackgroundBlurRadius(Math.round(48 * getResources().getDisplayMetrics().density));
            }
        } catch (Throwable ignored) { }
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
        // Tint the action keys with the palette accent so the chosen palette visibly reaches the strip
        // (the glass ink alone ignored it). The overflow/keyboard keys stay dimmer (secondary).
        int accent = orbTint[0];
        btnDel.setColorFilter(accent);
        btnEnter.setColorFilter(accent);
        btnKeyboard.setColorFilter(accent);
        btnMore.setColorFilter(accent);

        modeAuto = sp.getBoolean("imeModeAuto", false);
        translate = sp.getBoolean("translate", false);   // shared with the app (D9)
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
                    // Both modes capture one buffer -> transcribe it once here (was: push-to-talk streamed
                    // chunks incrementally, which is gone).
                    startTranscription();
                    handler.post(() -> applyState(UiState.PROCESSING));
                } else if (message.equals(Recorder.MSG_RECORDING_ERROR)) {
                    HapticFeedback.vibrate(mContext);
                    handler.post(() -> {
                        applyState(UiState.IDLE);
                        setImeStatus(R.string.error_no_input, true);
                        idleGroup.setVisibility(View.GONE);
                    });
                }
            }
        });

        applyState(UiState.IDLE);

        // Auto-start is driven from onStartInputView (every keyboard show), not here — onCreateInputView
        // is cached and runs at most once per process, so posting it here missed later opens (D12).

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
                            setImeStatus(R.string.please_wait, false);
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

    private PopupWindow imeMenuWindow;

    /** Show a tv_status line. Errors are error-red; neutral messages (loading/please-wait) use the
     *  normal ink so a routine "Loading model…" never reads as a failure (F05). Colour is set every
     *  time because the one TextView is reused across error and neutral states. */
    private void setImeStatus(int msgRes, boolean error) {
        tvStatus.setText(getString(msgRes));
        // glass_danger is the app's own error red (== ?attr/colorError in the Glass theme, both light/night);
        // reference it directly rather than resolving a framework attr that only bridges by convention.
        int c = androidx.core.content.ContextCompat.getColor(
                this, error ? R.color.glass_danger : R.color.glass_ink);
        tvStatus.setTextColor(c);
        tvStatus.setVisibility(View.VISIBLE);
    }

    /** Custom glass overflow menu (replaces the native PopupMenu): translate / auto toggles, a quick
     *  language row and settings — styled in-theme and popping up from the bottom strip. */
    private void showImeMenu(View anchor) {
        Context themed = ThemeUtils.serviceContext(this);
        View v = LayoutInflater.from(themed).inflate(R.layout.ime_more_menu, null);

        final View checkTranslate = v.findViewById(R.id.check_translate);
        checkTranslate.setVisibility(translate ? View.VISIBLE : View.INVISIBLE);
        v.findViewById(R.id.row_translate).setOnClickListener(x -> {
            translate = !translate;
            sp.edit().putBoolean("translate", translate).apply();   // shared with the app (D9)
            updateModelChip();
            checkTranslate.setVisibility(translate ? View.VISIBLE : View.INVISIBLE);
        });

        final View checkAuto = v.findViewById(R.id.check_auto);
        checkAuto.setVisibility(modeAuto ? View.VISIBLE : View.INVISIBLE);
        v.findViewById(R.id.row_auto).setOnClickListener(x -> {
            modeAuto = !modeAuto;
            sp.edit().putBoolean("imeModeAuto", modeAuto).apply();
            updateModelChip();
            checkAuto.setVisibility(modeAuto ? View.VISIBLE : View.INVISIBLE);
            if (modeAuto) {
                if (imeMenuWindow != null) imeMenuWindow.dismiss();   // let the user see listening begin
                maybeAutoStart();
            } else if (mRecorder != null && mRecorder.isInProgress()) {
                mRecorder.stop();   // leaving auto mid-listen: stop cleanly
            }
        });

        buildLangChips(themed, v.findViewById(R.id.lang_chips));

        v.findViewById(R.id.row_settings).setOnClickListener(x -> {
            if (imeMenuWindow != null) imeMenuWindow.dismiss();
            Intent intent = new Intent(this, SettingsActivity.class);
            intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        });

        // Fixed width: WRAP_CONTENT let the language HorizontalScrollView measure to its full content
        // (all 10 chips), blowing the menu far wider than the screen so it drifted off the left edge and
        // clipped the language row. Pin the width instead — the chips scroll inside it.
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int menuW = Math.min(Math.round(280 * dm.density), dm.widthPixels - Math.round(24 * dm.density));

        PopupWindow pw = new PopupWindow(v, menuW,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000)); // outside-tap dismiss
        pw.setClippingEnabled(false);   // the strip is short — let the menu pop up beyond its bounds, not clip
        pw.setElevation(24f);
        imeMenuWindow = pw;
        // The strip sits at the bottom, so pop UP: measure height at the fixed width, then place above the
        // anchor, right-aligned to it.
        v.measure(View.MeasureSpec.makeMeasureSpec(menuW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int mh = v.getMeasuredHeight();
        pw.showAsDropDown(anchor, anchor.getWidth() - menuW, -(mh + anchor.getHeight()), Gravity.NO_GRAVITY);
    }

    /** Quick-language pills; the current one is warm-highlighted. Takes effect on the next dictation (D11). */
    private void buildLangChips(Context themed, LinearLayout chips) {
        chips.removeAllViews();
        String cur = sp.getString("language", "auto");
        int[] pal = ThemeUtils.orbColors(this);   // palette accent, so the active chip matches the orb/toggles
        float d = getResources().getDisplayMetrics().density;
        int padH = Math.round(d * 13), padV = Math.round(d * 6), gap = Math.round(d * 6);
        for (int i = 0; i < QUICK_LANGS.length; i++) {
            final String lang = QUICK_LANGS[i];
            boolean active = lang.equals(cur);
            TextView chip = new TextView(themed);
            chip.setText(lang.equals("auto") ? getString(R.string.auto_lang) : lang.toUpperCase());
            chip.setTextSize(13f);
            chip.setPadding(padH, padV, padH, padV);
            chip.setBackgroundResource(R.drawable.living_glass_pill);
            // mutate() so tinting the active chip doesn't leak onto the shared pill drawable.
            android.graphics.drawable.Drawable bg = chip.getBackground();
            if (bg != null) {
                bg = bg.mutate();
                chip.setBackground(bg);
                bg.setTintList(active ? android.content.res.ColorStateList.valueOf(
                        (0x33 << 24) | (pal[0] & 0x00FFFFFF)) : null);   // soft palette-accent wash when active
            }
            chip.setTextColor(active ? pal[0] : ContextCompat.getColor(this, R.color.glass_ink));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(gap);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(x -> {
                sp.edit().putString("language", lang).apply();
                buildLangChips(themed, chips);   // re-highlight in place
            });
            chips.addView(chip);
        }
    }


    private void startRecording() {
        recordingStopped = false;
        imeDraft.setLength(0);
        lastLanguage = "";
        mRecorder.setChunkListener(null);   // both modes capture ONE buffer; no chunk streaming
        setTranscriptionParams();
        if (modeAuto) {
            // Hands-free: single buffer with Silero VAD auto-stop, then transcribe on DONE.
            mRecorder.initVad();
        }
        // Push-to-talk (else): single buffer, NO VAD. The hold IS the speech signal, so we must not
        // VAD-gate it (that dropped real audio to a false "no voice input") nor stream chunks (that
        // split phrases mid-word). Record the whole hold, transcribe once on release.
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
            setImeStatus(R.string.dialog_loading, false);
            idleGroup.setVisibility(View.GONE);
        });
        new Thread(() -> {
            Whisper w = WarmWhisper.get(this, modelFile, vocabFile, isMultilingual);
            handler.post(() -> {
                if (w == null) {
                    Log.e(TAG, "Model load failed: " + wantId);
                    setImeStatus(R.string.error_model_load, true);
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
                        setImeStatus(R.string.error_transcription, true);
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
        String action = translate ? getString(R.string.translate_short) : langCode;
        // Keep the chip short: model + language only. The mode (auto/hold) is shown by the orb behaviour
        // and the overflow menu; repeating it here made the chip a long, redundant line.
        tvModelChip.setText(selectedModel.displayName + " · " + action);
    }

    private boolean checkRecordPermission() {
        int permission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            setImeStatus(R.string.need_record_audio_permission, true);
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
