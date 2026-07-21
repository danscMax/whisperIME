package com.whispertflite;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.preference.PreferenceManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.Pair;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.EditText;
import androidx.activity.OnBackPressedCallback;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.transition.Fade;
import androidx.transition.TransitionManager;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.google.android.material.button.MaterialButton;
import com.whispertflite.asr.Recorder;
import com.whispertflite.asr.RecordBuffer;
import com.whispertflite.asr.Whisper;
import com.whispertflite.asr.WhisperResult;
import com.whispertflite.history.HistoryDb;
import com.whispertflite.models.ModelDownloadManager;
import com.whispertflite.models.ModelInfo;
import com.whispertflite.models.ModelRegistry;
import com.whispertflite.ui.LivingSignalView;
import com.whispertflite.utils.HapticFeedback;
import com.whispertflite.utils.InputLang;
import com.whispertflite.utils.LanguagePairAdapter;
import com.whispertflite.utils.ThemeUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private Context mContext;
    private static final String TAG = "MainActivity";

    // whisper-small.tflite works well for multi-lingual
    public static final String MULTI_LINGUAL_EU_MODEL_FAST = "whisper-base.EUROPEAN_UNION.tflite";
    public static final String MULTI_LINGUAL_TOP_WORLD_FAST = "whisper-base.TOP_WORLD.tflite";
    public static final String MULTI_LINGUAL_TOP_WORLD_SLOW = "whisper-small.TOP_WORLD.tflite";
    public static final String MULTI_LINGUAL_MODEL_FAST = "whisper-base.tflite";
    public static final String MULTI_LINGUAL_MODEL_SLOW = "whisper-small.tflite";
    public static final String ENGLISH_ONLY_MODEL = "whisper-tiny.en.tflite";
    // English only model ends with extension ".en.tflite"
    public static final String ENGLISH_ONLY_MODEL_EXTENSION = ".en.tflite";
    public static final String ENGLISH_ONLY_VOCAB_FILE = "filters_vocab_en.bin";
    public static final String MULTILINGUAL_VOCAB_FILE = "filters_vocab_multilingual.bin";

    // NO_SPEECH = nothing was recognized (silence). Shares the error card's "try again" layout but uses
    // a calm orb, not the red error orb — "didn't catch that" is not a failure (E1).
    private enum UiState { READY, RECORDING, PROCESSING, RESULT, ERROR, NO_SPEECH }

    private EditText tvResult;
    private ImageButton btnRecord;
    private ImageButton btnInfo;
    private ImageButton btnOverflow;
    private com.google.android.material.chip.Chip translate;
    private LivingSignalView orb;
    private TextView tvReadyHint;
    private TextView tvTimer;
    private TextView perfChip;
    private LinearLayout layoutRecording;
    private LinearLayout layoutError;
    private LinearLayout layoutActions;
    private LinearLayout contextPanel;
    private MaterialButton btnContext;
    private TextView dockStatus;

    private Recorder mRecorder = null;
    private Whisper mWhisper = null;
    // Set when a chunk/run reports an engine failure (vs genuine silence) so finishToResult can tell
    // the user it failed instead of silently showing the "nothing heard" state (C4/C5).
    private boolean transcriptionFailed = false;
    // Last transcript already written to history; guards against double-logging when the result is
    // saved on leave (onPause) so hand-edits are captured instead of the pre-edit text (D6).
    private String lastLoggedText = "";

    private File sdcardDataFolder = null;
    private ModelInfo selectedModel = null;
    private SharedPreferences sp = null;
    private android.widget.AutoCompleteTextView spinnerModel;
    private android.widget.AutoCompleteTextView spinnerLanguage;
    private com.google.android.material.textfield.TextInputLayout tilModel;
    private com.google.android.material.textfield.TextInputLayout tilLanguage;
    private List<Pair<String, String>> languagePairs;
    private List<ModelInfo> currentDownloadedModels;
    // Serialize every model load/unload so concurrent switches (spinner + onResume) can't run
    // deinit/init in parallel and corrupt the engine lifecycle.
    private final java.util.concurrent.ExecutorService modelExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private TextView badgeEngine;
    private int langToken = -1;
    private long startTime = 0;
    private TextToSpeech tts;

    private UiState currentState = UiState.READY;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private long recordStartMs = 0;
    // Chunked recording: recording can outlive many transcriptions; the result state is reached
    // only once recording stopped AND the transcription queue has drained.
    private volatile boolean recordingStopped = false;
    private boolean resultFinalized = false;
    private String lastLanguage = "";
    private long recordDurationMs = 0;
    private final Runnable timerTick = new Runnable() {
        @Override
        public void run() {
            long el = (System.currentTimeMillis() - recordStartMs) / 1000;
            tvTimer.setText(String.format(Locale.US, "%d:%02d", el / 60, el % 60));
            timerHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onDestroy() {
        modelExecutor.shutdownNow();
        timerHandler.removeCallbacks(timerTick);   // self-reposting timer must not outlive the activity (F11)
        deinitModel();
        deinitTTS();
        if (mRecorder != null) {
            mRecorder.shutdown();   // ends the worker thread; leaked one per activity recreate
        }
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        stopProcessing();
        // Save the FINAL (possibly hand-edited) transcript when leaving a result — the pre-edit text
        // that finishToResult used to save meant edits never reached history (D6).
        if (currentState == UiState.RESULT && sp != null && sp.getBoolean("historyEnabled", true)) {
            String text = tvResult.getText().toString().trim();
            if (!text.isEmpty() && !text.equals(lastLoggedText)) {
                HistoryDb.get(mContext).insert(text, lastLanguage,
                        selectedModel != null ? selectedModel.id : "", recordDurationMs);
                lastLoggedText = text;
            }
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (orb != null) orb.resumeRender();   // re-arm the orb surface on resume
        // The catalog can add/remove/select models while we're backgrounded. Re-sync the spinner
        // and, if the active model changed, reload the engine — without this the main screen keeps
        // showing/using the old model until the app is restarted.
        if (spinnerModel == null || sp == null) return;
        List<ModelInfo> downloaded = loadDownloadedModels();
        if (downloaded.isEmpty()) { // every model was deleted in the catalog: back to onboarding
            startActivity(new Intent(this, DownloadActivity.class));
            finish();
            return;
        }
        String prefId = sp.getString("selectedModelId", selectedModel != null ? selectedModel.id : null);
        boolean modelChanged = selectedModel == null || !selectedModel.id.equals(prefId);
        boolean listChanged = !sameModelList(downloaded);
        if (!modelChanged && !listChanged) {
            updateContextPill();
            return;
        }

        selectedModel = resolveSelectedModel(downloaded);
        currentDownloadedModels = downloaded;
        setModelDropdown(downloaded, selectedModel); // onItemClick only fires on user taps — no rebuild race
        updateEngineBadge();
        applyLanguageEnabled();
        if (modelChanged) switchModelAsync();
    }

    private boolean sameModelList(List<ModelInfo> models) {
        if (currentDownloadedModels == null || currentDownloadedModels.size() != models.size()) return false;
        for (int i = 0; i < models.size(); i++) {
            if (!currentDownloadedModels.get(i).id.equals(models.get(i).id)) return false;
        }
        return true;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = this;
        ThemeUtils.applyPalette(this);
        ThemeUtils.applyGlass(this);
        setContentView(R.layout.activity_main);
        ThemeUtils.setStatusBarAppearance(this);
        sp = PreferenceManager.getDefaultSharedPreferences(this);
        checkInputMethodEnabled();

        orb = findViewById(R.id.orb);
        int[] orbTint = ThemeUtils.orbColors(this);
        orb.setColors(orbTint[0], orbTint[1]);
        tvReadyHint = findViewById(R.id.tvReadyHint);
        tvTimer = findViewById(R.id.tvTimer);
        perfChip = findViewById(R.id.perf_chip);
        layoutRecording = findViewById(R.id.layout_recording);
        layoutError = findViewById(R.id.layout_error);
        layoutActions = findViewById(R.id.layout_actions);
        contextPanel = findViewById(R.id.contextPanel);
        btnContext = findViewById(R.id.btnContext);
        dockStatus = findViewById(R.id.dockStatus);
        btnContext.setOnClickListener(v -> {
            boolean opening = contextPanel.getVisibility() != View.VISIBLE;
            TransitionManager.beginDelayedTransition((ViewGroup) contextPanel.getParent(),
                    new Fade().setDuration(180));
            contextPanel.setVisibility(opening ? View.VISIBLE : View.GONE);
            // Flip the chevron so the button reflects the panel state (MaterialButton has no
            // icon-rotation API — swap the up/down glyph instead).
            btnContext.setIconResource(opening ? R.drawable.ic_expand_less_24dp
                    : R.drawable.ic_expand_more_24dp);
        });

        translate = findViewById(R.id.mode_translate);
        // Persist the translate choice so every surface (including the provider dialog) can honor it (D9).
        translate.setChecked(sp.getBoolean("translate", false));
        translate.setOnCheckedChangeListener((b, checked) -> sp.edit().putBoolean("translate", checked).apply());

        sdcardDataFolder = this.getExternalFilesDir(null);

        // One-time cleanup of pre-redesign model files; ensure TFLite vocab is present.
        ModelDownloadManager dm = ModelDownloadManager.get(this);
        dm.cleanupObsoleteModels();
        dm.ensureVocabAssets();

        // Unified model list: downloaded registry models (both engines). No model -> onboarding.
        List<ModelInfo> downloadedModels = loadDownloadedModels();
        if (downloadedModels.isEmpty()) {
            startActivity(new Intent(this, DownloadActivity.class));
            finish();
            return;
        }
        selectedModel = resolveSelectedModel(downloadedModels);

        // Initialize the selected model off the UI thread: a GGUF model can be ~0.5 GB and would
        // otherwise ANR at launch. Serialized on modelExecutor with every later switch.
        modelExecutor.submit(this::initModel);

        btnInfo = findViewById(R.id.btnInfo);
        btnInfo.setOnClickListener(view -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/woheller69/whisperIME#Donate"))));

        findViewById(R.id.btnHistory).setOnClickListener(
                v -> startActivity(new Intent(this, com.whispertflite.history.HistoryActivity.class)));
        findViewById(R.id.btnSettings).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));

        // Simplified-Chinese toggle lives in the overflow menu (rarely used).
        btnOverflow = findViewById(R.id.btnOverflow);
        btnOverflow.setOnClickListener(this::showOverflowMenu);

        // Language exposed-dropdown. Items are language display names; index maps to languagePairs.
        tilLanguage = findViewById(R.id.layout_language);
        spinnerLanguage = findViewById(R.id.spnrLanguage);
        languagePairs = LanguagePairAdapter.getLanguagePairs(this);
        List<String> langNames = new ArrayList<>();
        for (Pair<String, String> p : languagePairs) langNames.add(p.second);
        spinnerLanguage.setAdapter(noFilterAdapter(spinnerLanguage, langNames));
        spinnerLanguage.setOnItemClickListener((parent, view, pos, id) -> {
            langToken = InputLang.getIdForLanguage(InputLang.getLangList(), languagePairs.get(pos).first);
            sp.edit().putString("language", languagePairs.get(pos).first).apply();
            updateContextPill();
        });

        badgeEngine = findViewById(R.id.badge_engine);
        tilModel = findViewById(R.id.tilModel);
        spinnerModel = findViewById(R.id.spnrTfliteFiles);
        currentDownloadedModels = downloadedModels;
        setModelDropdown(downloadedModels, selectedModel);
        updateEngineBadge();
        applyLanguageEnabled();
        spinnerModel.setOnItemClickListener((parent, view, pos, id) -> {
            if (currentDownloadedModels == null || pos >= currentDownloadedModels.size()) return;
            ModelInfo picked = currentDownloadedModels.get(pos);
            if (picked.id.equals(selectedModel.id)) return;
            // A switch tears down the engine; doing it mid-recording drops in-flight chunks into a
            // shutting-down instance. Refuse until idle and restore the visible selection (C10).
            if ((mRecorder != null && mRecorder.isInProgress())
                    || (mWhisper != null && mWhisper.isInProgress())) {
                Toast.makeText(this, R.string.please_wait, Toast.LENGTH_SHORT).show();
                setModelDropdown(currentDownloadedModels, selectedModel);
                return;
            }
            selectedModel = picked;
            sp.edit().putString("selectedModelId", selectedModel.id).apply();
            updateEngineBadge();
            applyLanguageEnabled();
            updateContextPill();
            // Loading a GGUF model (up to ~0.5 GB) blocks for seconds; switch off the UI thread.
            switchModelAsync();
        });

        // Record button: preserve upstream press-and-hold behavior.
        btnRecord = findViewById(R.id.btnRecord);
        btnRecord.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                Log.d(TAG, "Start recording...");
                orb.animate().scaleX(0.93f).scaleY(0.93f).setDuration(120).start(); // tactile press
                if (mWhisper == null) {
                    Toast.makeText(this, R.string.main_model_loading, Toast.LENGTH_SHORT).show();
                } else if (!mWhisper.isInProgress()) {
                    HapticFeedback.vibrate(this);
                    startRecording();
                } else (Toast.makeText(this,getString(R.string.please_wait),Toast.LENGTH_SHORT)).show();
            } else if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                orb.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                if (mRecorder != null && mRecorder.isInProgress()) {
                    Log.d(TAG, "Recording is in progress... stopping...");
                    stopRecording();
                }
            }
            return true;
        });

        tvResult = findViewById(R.id.tvResult);
        tvResult.setOnClickListener(view -> tvResult.setCursorVisible(true));
        getOnBackPressedDispatcher().addCallback(new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (tvResult.isCursorVisible()) tvResult.setCursorVisible(false);
                else finish();
            }
        });

        findViewById(R.id.fabCopy).setOnClickListener(v -> {
            String textToCopy = tvResult.getText().toString().trim();
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText(getString(R.string.model_output), textToCopy);
            clipboard.setPrimaryClip(clip);
        });
        findViewById(R.id.btnSpeak).setOnClickListener(v -> speak(tvResult.getText().toString()));
        findViewById(R.id.btnShare).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, tvResult.getText().toString());
            startActivity(Intent.createChooser(i, getString(R.string.main_cd_share)));
        });
        ((MaterialButton) findViewById(R.id.btnRetry)).setOnClickListener(v -> {
            tvResult.setText("");
            lastLoggedText = "";
            applyState(UiState.READY);
        });
        findViewById(R.id.btnClear).setOnClickListener(v -> {
            tvResult.setText("");   // deliberate wipe — recordings otherwise accumulate now (D2)
            lastLoggedText = "";
            applyState(UiState.READY);
        });

        // Audio recording functionality
        mRecorder = new Recorder(this);
        mRecorder.setRmsListener(rms -> runOnUiThread(() -> {
            if (currentState == UiState.RECORDING) orb.pushLevel(rms);
        }));
        // Each VAD chunk feeds the Whisper queue for live, sequential (pseudo-streaming) transcription.
        // Guard the mutable field: a model switch can null mWhisper while the recorder still emits a chunk.
        mRecorder.setChunkListener(pcm -> { Whisper w = mWhisper; if (w != null) w.enqueueChunk(pcm); });
        mRecorder.setListener(new Recorder.RecorderListener() {
            @Override
            public void onUpdateReceived(String message) {
                Log.d(TAG, "Update is received, Message: " + message);
                if (message.equals(Recorder.MSG_RECORDING)) {
                    // Do NOT wipe the field on a new recording — dictation-by-parts kept losing the
                    // previous result. New speech appends; the trash button clears deliberately (D2).
                } else if (message.equals(Recorder.MSG_RECORDING_DONE) || message.equals(Recorder.MSG_RECORDING_ERROR)) {
                    HapticFeedback.vibrate(mContext);
                    recordingStopped = true;
                    recordDurationMs = System.currentTimeMillis() - recordStartMs;
                    // Queued chunks may still be transcribing: PROCESSING until the queue drains,
                    // then finishToResult() (which shows ERROR if nothing was recognized).
                    runOnUiThread(() -> {
                        Whisper w = mWhisper;   // deinitModel() may null the field before this posts (F10)
                        if (w != null && w.isInProgress()) applyState(UiState.PROCESSING);
                        else finishToResult();
                    });
                }
            }
        });

        applyState(UiState.READY);

        if (GithubStar.shouldShowStarDialog(this)) GithubStar.starDialog(this, "https://github.com/woheller69/whisperIME");
        // Assume this Activity is the current activity, check record permission
        checkPermissions();
    }

    private void showOverflowMenu(View anchor) {
        // Frosted glass menu sheet instead of the flat Material popup, to match the dropdowns.
        PopupMenu menu = new PopupMenu(
                new android.view.ContextThemeWrapper(this, R.style.ThemeOverlay_Glass_Menu), anchor);
        menu.getMenu().add(0, 2, 0, R.string.info);
        android.view.MenuItem item = menu.getMenu().add(0, 1, 1, R.string.settings_simple_chinese);
        item.setCheckable(true);
        item.setChecked(sp.getBoolean("simpleChinese", false));
        menu.setOnMenuItemClickListener(mi -> {
            if (mi.getItemId() == 2) {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/woheller69/whisperIME#Donate")));
                return true;
            }
            if (mi.getItemId() == 1) {
                sp.edit().putBoolean("simpleChinese", !mi.isChecked()).apply();
                return true;
            }
            return false;
        });
        menu.show();
    }

    /** Single source of truth for view visibility across the five UI states. */
    private void applyState(UiState state) {
        currentState = state;
        boolean recording = state == UiState.RECORDING;
        boolean error = state == UiState.ERROR;
        boolean noSpeech = state == UiState.NO_SPEECH;
        boolean errorLike = error || noSpeech;   // both use the "try again" card
        boolean result = state == UiState.RESULT;
        boolean processing = state == UiState.PROCESSING;

        // Crossfade the swapping views inside the result card (state -> state, perf chip appearing).
        View resultLayout = findViewById(R.id.layout_result);
        TransitionManager.beginDelayedTransition((ViewGroup) resultLayout.getParent(),
                new Fade().setDuration(180));

        resultLayout.setVisibility(errorLike ? View.GONE : View.VISIBLE);
        layoutRecording.setVisibility(recording ? View.VISIBLE : View.GONE);
        layoutError.setVisibility(errorLike ? View.VISIBLE : View.GONE);
        layoutActions.setVisibility(result ? View.VISIBLE : View.GONE);
        // Keep the speed metric visible on a no-speech result too, so the user sees the model did run (E2).
        perfChip.setVisibility(result || noSpeech ? View.VISIBLE : View.GONE);
        // Transcript-panel placeholder shows only when the panel is empty (READY, nothing typed yet).
        boolean empty = tvResult.getText().length() == 0;
        if (tvReadyHint != null)
            tvReadyHint.setVisibility(state == UiState.READY && empty ? View.VISIBLE : View.GONE);
        // Editing affordance: show the caret on a result so it's discoverable as an editable field (D7).
        tvResult.setCursorVisible(result);
        dockStatus.setVisibility(result ? View.GONE : View.VISIBLE);
        dockStatus.setText(state == UiState.RECORDING ? R.string.main_state_listening
                : state == UiState.PROCESSING ? R.string.main_state_processing
                : errorLike ? R.string.main_retry
                : R.string.main_hold_signal);

        LivingSignalView.SignalState signalState = state == UiState.RECORDING
                ? LivingSignalView.SignalState.LISTENING
                : state == UiState.PROCESSING ? LivingSignalView.SignalState.PROCESSING
                : state == UiState.RESULT ? LivingSignalView.SignalState.RESULT
                : state == UiState.ERROR ? LivingSignalView.SignalState.ERROR
                : LivingSignalView.SignalState.READY;   // NO_SPEECH rides the calm READY orb (E1)
        orb.setSignalState(signalState);

        if (recording) {
            recordStartMs = System.currentTimeMillis();
            tvTimer.setText("0:00");
            timerHandler.post(timerTick);
        } else {
            timerHandler.removeCallbacks(timerTick);
        }
    }

    private void checkInputMethodEnabled() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        List<InputMethodInfo> enabledInputMethodList = imm.getEnabledInputMethodList();

        String myInputMethodId = getPackageName() + "/" + WhisperInputMethodService.class.getName();
        boolean inputMethodEnabled = false;
        for (InputMethodInfo imi : enabledInputMethodList) {
            if (imi.getId().equals(myInputMethodId)) {
                inputMethodEnabled = true;
                break;
            }
        }
        // Offer to enable the keyboard only ONCE (upstream forced this on every launch, which yanks
        // the user into system settings each time). After the first offer it's opt-in via Settings.
        if (!inputMethodEnabled && !sp.getBoolean("imeOfferShown", false)) {
            sp.edit().putBoolean("imeOfferShown", true).apply();
            startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
        }
    }

    // Model initialization
    /** Swap to {@link #selectedModel} without blocking the UI thread on the (possibly large) load. */
    private void switchModelAsync() {
        btnRecord.setEnabled(false);
        tilModel.setEnabled(false);
        btnContext.setEnabled(false);
        dockStatus.setText(R.string.main_model_loading);
        orb.setSignalState(LivingSignalView.SignalState.PROCESSING);
        modelExecutor.submit(() -> {
            deinitModel();
            initModel();
            runOnUiThread(() -> {
                btnRecord.setEnabled(true);
                tilModel.setEnabled(true);
                btnContext.setEnabled(true);
                applyState(currentState);
            });
        });
    }

    private void initModel() {
        File modelFile = new File(sdcardDataFolder, selectedModel.filename);
        boolean isMultilingualModel = !selectedModel.englishOnly;
        // Vocab files are required for TFLite models only; whisper.cpp loads from the GGUF alone.
        File vocabFile = selectedModel.engine == ModelInfo.Engine.TFLITE
                ? new File(sdcardDataFolder, ModelRegistry.vocabFor(selectedModel)) : null;

        // Build fully into a local, then publish: initModel runs on a background thread and can race
        // with deinitModel() (activity recreate for night mode / model switch) nulling the field.
        Whisper whisper = new Whisper(this);
        if (!whisper.loadModel(modelFile, vocabFile, isMultilingualModel)) {
            // Load failed — free the half-built instance and leave mWhisper null so a later switch/
            // resume retries instead of no-opping a "loaded" dead engine (C2); tell the user (C13).
            whisper.shutdown();
            runOnUiThread(() -> Toast.makeText(this, R.string.error_model_load, Toast.LENGTH_LONG).show());
            return;
        }
        Log.d(TAG, "Initialized: " + selectedModel.id + " (" + selectedModel.engine + ")");
        whisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) {
                Log.d(TAG, "Update is received, Message: " + message);
                if (message.equals(Whisper.MSG_PROCESSING)) {
                    startTime = System.currentTimeMillis();
                    // While still recording, chunks transcribe in the background: stay in RECORDING.
                    if (recordingStopped) runOnUiThread(() -> {
                        applyState(UiState.PROCESSING);
                        tilModel.setEnabled(false);
                    });
                } else if (message.equals(Whisper.MSG_PROCESSING_DONE)) {
                    if (recordingStopped && !mWhisper.isInProgress()) {
                        runOnUiThread(MainActivity.this::finishToResult);
                    }
                } else if (message.startsWith(Whisper.MSG_TRANSCRIBE_FAILED)
                        || message.startsWith(Whisper.MSG_LOAD_FAILED)
                        || message.equals(Whisper.MSG_ENGINE_NOT_INIT)) {
                    transcriptionFailed = true;   // finishToResult reports it instead of "no speech" (C4/C5)
                }
            }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                long timeTaken = System.currentTimeMillis() - startTime;
                Log.d(TAG, "Result: " + whisperResult.getResult() + " " + whisperResult.getLanguage() + " " + (whisperResult.getTask() == Whisper.Action.TRANSCRIBE ? "transcribing" : "translating"));

                // Update the speed metric for EVERY result, including an empty/no-speech one, so the user
                // can see the model actually ran even when nothing was recognized (E2).
                double procSec = timeTaken / 1000.0;
                double audioSec = audioDurationSeconds();
                double realtime = procSec > 0 ? audioSec / procSec : 0;
                runOnUiThread(() -> perfChip.setText(getString(R.string.main_perf_chip, procSec, realtime)));

                if (whisperResult.isError()) { transcriptionFailed = true; return; } // engine failure, not silence (C5)
                String raw = whisperResult.getResult();
                if (raw == null || raw.trim().isEmpty()) return; // empty chunk: skip; completion handled on drain

                lastLanguage = whisperResult.getLanguage();
                final String out;
                if (whisperResult.getLanguage().equals("zh") && whisperResult.getTask() == Whisper.Action.TRANSCRIBE) {
                    boolean simpleChinese = sp.getBoolean("simpleChinese", false);
                    out = simpleChinese ? ZhConverterUtil.toSimple(raw) : ZhConverterUtil.toTraditional(raw);
                } else {
                    out = raw;
                }

                // Append each chunk live (pseudo-streaming); history/TTS happen once in finishToResult().
                runOnUiThread(() -> tvResult.append(out));
            }
        });
        mWhisper = whisper; // publish only once fully constructed
    }

    private double audioDurationSeconds() {
        byte[] buf = RecordBuffer.getOutputBuffer();
        if (buf == null) return 0;
        return buf.length / 2.0 / 16000.0; // 16-bit mono @ 16 kHz
    }

    private void deinitModel() {
        if (mWhisper != null) {
            mWhisper.shutdown();
            mWhisper = null;
        }
    }

    private void speak(final String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (tts == null) {
            tts = new TextToSpeech(mContext, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    int result = tts.setLanguage(Locale.US);
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        runOnUiThread(() -> Toast.makeText(mContext, mContext.getString(R.string.tts_language_not_supported), Toast.LENGTH_SHORT).show());
                    } else {
                        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
                    }
                } else {
                    runOnUiThread(() -> Toast.makeText(mContext, mContext.getString(R.string.tts_initialization_failed), Toast.LENGTH_SHORT).show());
                }
            });
        } else {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    private void deinitTTS(){
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }

    /** Registry models present on disk (TFLite entries also require their vocab file). */
    private List<ModelInfo> loadDownloadedModels() {
        List<ModelInfo> out = new ArrayList<>();
        ModelDownloadManager dm = ModelDownloadManager.get(this);
        for (ModelInfo m : ModelRegistry.all()) {
            if (!dm.isPresent(m)) continue; // all files present (sherpa models have several)
            if (m.engine == ModelInfo.Engine.TFLITE
                    && !new File(sdcardDataFolder, ModelRegistry.vocabFor(m)).exists()) continue;
            out.add(m);
        }
        return out;
    }

    /** Selected model from prefs, falling back to the first available and persisting it. */
    private ModelInfo resolveSelectedModel(List<ModelInfo> downloaded) {
        String id = sp.getString("selectedModelId", null);
        ModelInfo m = id != null ? ModelRegistry.byId(id) : null;
        if (m == null || !downloaded.contains(m)) {
            m = downloaded.get(0);
            sp.edit().putString("selectedModelId", m.id).apply();
        }
        return m;
    }

    private void updateEngineBadge() {
        int badge = selectedModel.engine == ModelInfo.Engine.SHERPA ? R.string.main_badge_sherpa
                : selectedModel.engine == ModelInfo.Engine.WHISPER_CPP ? R.string.catalog_engine_whispercpp
                : R.string.main_badge_tflite;
        badgeEngine.setText(badge);
        updateContextPill();
    }

    /** Populate the model dropdown with the given models and show {@code selected} as the value. */
    private void setModelDropdown(List<ModelInfo> models, ModelInfo selected) {
        List<String> labels = new ArrayList<>();
        for (ModelInfo m : models) labels.add(modelLabel(m));
        spinnerModel.setAdapter(noFilterAdapter(spinnerModel, labels));
        spinnerModel.setText(modelLabel(selected), false); // false: set value without filtering the list
    }

    /**
     * ArrayAdapter for an exposed dropdown that ALWAYS shows every item. A plain adapter would filter
     * the popup by the field's current text (so opening it would show only the selected row); this
     * keeps the full list by returning all items from the filter and never shrinking the backing list.
     */
    private ArrayAdapter<String> noFilterAdapter(android.widget.AutoCompleteTextView host,
                                                 List<String> items) {
        final int accent = ThemeUtils.orbColors(this)[0];   // palette accent, so the current pick matches the UI
        return new ArrayAdapter<String>(this, R.layout.menu_item, new ArrayList<>(items)) {
            @Override public android.widget.Filter getFilter() {
                return new android.widget.Filter() {
                    @Override protected FilterResults performFiltering(CharSequence c) {
                        FilterResults r = new FilterResults();
                        r.values = items; r.count = items.size();
                        return r;
                    }
                    @Override protected void publishResults(CharSequence c, FilterResults r) {
                        notifyDataSetChanged(); // keep the full list; don't reduce it to matches
                    }
                };
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, convertView, parent);
                // Mark the active choice: palette accent + a check, so the open list shows the current pick.
                boolean selected = row.getText().toString().contentEquals(host.getText());
                row.setTextColor(selected ? accent
                        : ContextCompat.getColor(MainActivity.this, R.color.glass_ink));
                row.setCompoundDrawablesRelativeWithIntrinsicBounds(
                        0, 0, selected ? R.drawable.ic_check_20dp : 0, 0);
                return row;
            }
        };
    }

    /** Language selector is only meaningful for multilingual models; index maps to languagePairs. */
    private void applyLanguageEnabled() {
        if (!selectedModel.englishOnly) {
            tilLanguage.setEnabled(true);
            String langCode = sp.getString("language", "auto");
            int idx = 0;
            for (int i = 0; i < languagePairs.size(); i++) {
                if (languagePairs.get(i).first.equals(langCode)) { idx = i; break; }
            }
            langToken = InputLang.getIdForLanguage(InputLang.getLangList(), languagePairs.get(idx).first);
            spinnerLanguage.setText(languagePairs.get(idx).second, false);
        } else {
            langToken = -1;
            spinnerLanguage.setText(languagePairs.get(0).second, false); // "Detect language"
            tilLanguage.setEnabled(false);
        }
        updateContextPill();
    }

    private void updateContextPill() {
        if (btnContext == null || selectedModel == null) return;
        String model = selectedModel.label(this);
        CharSequence language = spinnerLanguage != null ? spinnerLanguage.getText() : "";
        btnContext.setText(getString(R.string.main_context_format, model, language));
    }

    private String modelLabel(ModelInfo m) {
        String engine = m.engine == ModelInfo.Engine.SHERPA ? getString(R.string.main_badge_sherpa)
                : m.engine == ModelInfo.Engine.WHISPER_CPP ? getString(R.string.catalog_engine_whispercpp)
                : getString(R.string.main_badge_tflite);
        // Friendly localized name + the engine badge (e.g. "Multilingual · fast (25 languages) · sherpa").
        String name = m.label(this);
        return name + " · " + engine;
    }

    private void checkPermissions() {
        List<String> perms = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECORD_AUDIO);
            Toast.makeText(this, getString(R.string.need_record_audio_permission), Toast.LENGTH_SHORT).show();
        }
        if (!perms.isEmpty()) {
            requestPermissions(perms.toArray(new String[] {}), 0);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Record permission is granted");
        } else {
            Log.d(TAG, "Record permission is not granted");
        }
    }

    // Recording calls
    private void startRecording() {
        checkPermissions();
        if (mWhisper == null) { // model still loading (async): ignore the tap
            Toast.makeText(this, R.string.main_model_loading, Toast.LENGTH_SHORT).show();
            return;
        }
        recordingStopped = false;
        resultFinalized = false;
        transcriptionFailed = false;
        lastLanguage = "";
        // Flags apply to every chunk of this session.
        mWhisper.setAction(translate.isChecked() ? Whisper.ACTION_TRANSLATE : Whisper.ACTION_TRANSCRIBE);
        mWhisper.setLanguage(langToken);
        applyState(UiState.RECORDING);
        mRecorder.start();
    }

    /** Recording stopped and the transcription queue drained: reveal the result and log history once. */
    private void finishToResult() {
        if (resultFinalized) return;
        resultFinalized = true;
        tilModel.setEnabled(true);
        String text = tvResult.getText().toString().trim();
        if (text.isEmpty()) {
            // Distinguish an engine failure from genuine silence so the user isn't left guessing (C5),
            // and show a calm "didn't catch that" for silence instead of the red error orb (E1).
            if (transcriptionFailed) {
                Toast.makeText(mContext, R.string.error_transcription, Toast.LENGTH_LONG).show();
                applyState(UiState.ERROR);
            } else {
                applyState(UiState.NO_SPEECH);
            }
            return;
        }
        applyState(UiState.RESULT);
        // History is logged on leave (onPause), not here, so a hand-edited result is what gets saved (D6).
        if (sp.getBoolean("speakResult", false)) speak(text);
    }

    private void stopRecording() {
        mRecorder.stop();
    }

    // Transcription calls
    private void stopProcessing() {
        if (mWhisper != null && mWhisper.isInProgress()) mWhisper.stop();
    }

}
