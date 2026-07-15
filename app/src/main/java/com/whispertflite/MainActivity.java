package com.whispertflite;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.EditText;
import androidx.activity.OnBackPressedCallback;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.transition.Fade;
import androidx.transition.TransitionManager;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.whispertflite.asr.Recorder;
import com.whispertflite.asr.RecordBuffer;
import com.whispertflite.asr.Whisper;
import com.whispertflite.asr.WhisperResult;
import com.whispertflite.history.HistoryDb;
import com.whispertflite.models.ModelDownloadManager;
import com.whispertflite.models.ModelInfo;
import com.whispertflite.models.ModelRegistry;
import com.whispertflite.ui.WaveformView;
import com.whispertflite.utils.HapticFeedback;
import com.whispertflite.utils.InputLang;
import com.whispertflite.utils.LanguagePairAdapter;
import com.whispertflite.utils.ThemeUtils;

import org.woheller69.freeDroidWarn.FreeDroidWarn;

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

    private enum UiState { READY, RECORDING, PROCESSING, RESULT, ERROR }

    private EditText tvResult;
    private FloatingActionButton btnRecord;
    private ImageButton btnInfo;
    private ImageButton btnOverflow;
    private Chip append;
    private Chip translate;
    private LinearProgressIndicator processingBar;
    private WaveformView waveform;
    private TextView tvTimer;
    private TextView perfChip;
    private LinearLayout layoutRecording;
    private LinearLayout layoutError;
    private LinearLayout layoutActions;

    private Recorder mRecorder = null;
    private Whisper mWhisper = null;

    private File sdcardDataFolder = null;
    private ModelInfo selectedModel = null;
    private SharedPreferences sp = null;
    private Spinner spinnerModel;
    private Spinner spinnerLanguage;
    private TextView badgeEngine;
    private int langToken = -1;
    private long startTime = 0;
    private TextToSpeech tts;

    private UiState currentState = UiState.READY;
    private ObjectAnimator fabPulse;
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
        stopFabPulse();
        deinitModel();
        deinitTTS();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        stopProcessing();
        super.onPause();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = this;
        ThemeUtils.applyNightMode(this);
        ThemeUtils.applyPalette(this);
        setContentView(R.layout.activity_main);
        ThemeUtils.setStatusBarAppearance(this);
        checkInputMethodEnabled();
        sp = PreferenceManager.getDefaultSharedPreferences(this);

        processingBar = findViewById(R.id.processing_bar);
        waveform = findViewById(R.id.waveform);
        tvTimer = findViewById(R.id.tvTimer);
        perfChip = findViewById(R.id.perf_chip);
        layoutRecording = findViewById(R.id.layout_recording);
        layoutError = findViewById(R.id.layout_error);
        layoutActions = findViewById(R.id.layout_actions);

        append = findViewById(R.id.mode_append);
        translate = findViewById(R.id.mode_translate);

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

        // Initialize the selected model.
        initModel();

        btnInfo = findViewById(R.id.btnInfo);
        btnInfo.setOnClickListener(view -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/woheller69/whisperIME#Donate"))));

        findViewById(R.id.btnHistory).setOnClickListener(
                v -> startActivity(new Intent(this, com.whispertflite.history.HistoryActivity.class)));
        findViewById(R.id.btnSettings).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));

        // Simplified-Chinese toggle lives in the overflow menu (rarely used).
        btnOverflow = findViewById(R.id.btnOverflow);
        btnOverflow.setOnClickListener(this::showOverflowMenu);

        spinnerLanguage = findViewById(R.id.spnrLanguage);
        List<Pair<String, String>> languagePairs = LanguagePairAdapter.getLanguagePairs(this);
        LanguagePairAdapter languagePairAdapter = new LanguagePairAdapter(this, android.R.layout.simple_spinner_item, languagePairs);
        languagePairAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLanguage.setAdapter(languagePairAdapter);

        spinnerLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                langToken = InputLang.getIdForLanguage(InputLang.getLangList(),languagePairs.get(i).first);
                SharedPreferences.Editor editor = sp.edit();
                editor.putString("language",languagePairs.get(i).first);
                editor.apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });

        badgeEngine = findViewById(R.id.badge_engine);
        spinnerModel = findViewById(R.id.spnrTfliteFiles);
        spinnerModel.setAdapter(getModelArrayAdapter(downloadedModels));
        spinnerModel.setSelection(Math.max(0, downloadedModels.indexOf(selectedModel)), false);
        updateEngineBadge();
        applyLanguageEnabled(languagePairAdapter);
        spinnerModel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                ModelInfo picked = (ModelInfo) parent.getItemAtPosition(position);
                // Skip the initial callback fired during setup (model already loaded).
                if (picked.id.equals(selectedModel.id) && mWhisper != null) return;
                deinitModel();
                selectedModel = picked;
                sp.edit().putString("selectedModelId", selectedModel.id).apply();
                initModel();
                updateEngineBadge();
                applyLanguageEnabled(languagePairAdapter);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Handle case when nothing is selected, if needed
            }
        });

        // Record button: preserve upstream press-and-hold behavior.
        btnRecord = findViewById(R.id.btnRecord);
        btnRecord.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                Log.d(TAG, "Start recording...");
                if (!mWhisper.isInProgress()) {
                    if (sp.getBoolean("hapticFeedback", true)) HapticFeedback.vibrate(this);
                    startRecording();
                } else (Toast.makeText(this,getString(R.string.please_wait),Toast.LENGTH_SHORT)).show();
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
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
            applyState(UiState.READY);
        });

        // Audio recording functionality
        mRecorder = new Recorder(this);
        mRecorder.setRmsListener(rms -> runOnUiThread(() -> {
            if (currentState == UiState.RECORDING) waveform.push(rms);
        }));
        // Each VAD chunk feeds the Whisper queue for live, sequential (pseudo-streaming) transcription.
        mRecorder.setChunkListener(pcm -> mWhisper.enqueueChunk(pcm));
        mRecorder.setListener(new Recorder.RecorderListener() {
            @Override
            public void onUpdateReceived(String message) {
                Log.d(TAG, "Update is received, Message: " + message);
                if (message.equals(Recorder.MSG_RECORDING)) {
                    if (!append.isChecked()) runOnUiThread(() -> tvResult.setText(""));
                } else if (message.equals(Recorder.MSG_RECORDING_DONE) || message.equals(Recorder.MSG_RECORDING_ERROR)) {
                    if (sp.getBoolean("hapticFeedback", true)) HapticFeedback.vibrate(mContext);
                    recordingStopped = true;
                    recordDurationMs = System.currentTimeMillis() - recordStartMs;
                    // Queued chunks may still be transcribing: PROCESSING until the queue drains,
                    // then finishToResult() (which shows ERROR if nothing was recognized).
                    runOnUiThread(() -> {
                        if (mWhisper.isInProgress()) applyState(UiState.PROCESSING);
                        else finishToResult();
                    });
                }
            }
        });

        applyState(UiState.READY);

        FreeDroidWarn.showWarningOnUpgrade(this, BuildConfig.VERSION_CODE);
        if (GithubStar.shouldShowStarDialog(this)) GithubStar.starDialog(this, "https://github.com/woheller69/whisperIME");
        // Assume this Activity is the current activity, check record permission
        checkPermissions();
    }

    private void showOverflowMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        android.view.MenuItem item = menu.getMenu().add(0, 1, 0, R.string.settings_simple_chinese);
        item.setCheckable(true);
        item.setChecked(sp.getBoolean("simpleChinese", false));
        menu.setOnMenuItemClickListener(mi -> {
            sp.edit().putBoolean("simpleChinese", !mi.isChecked()).apply();
            return true;
        });
        menu.show();
    }

    /** Single source of truth for view visibility across the five UI states. */
    private void applyState(UiState state) {
        currentState = state;
        boolean recording = state == UiState.RECORDING;
        boolean error = state == UiState.ERROR;
        boolean result = state == UiState.RESULT;
        boolean processing = state == UiState.PROCESSING;

        // Crossfade the swapping views inside the result card (state -> state, perf chip appearing).
        View resultLayout = findViewById(R.id.layout_result);
        TransitionManager.beginDelayedTransition((ViewGroup) resultLayout.getParent(),
                new Fade().setDuration(180));

        resultLayout.setVisibility(recording || error ? View.GONE : View.VISIBLE);
        layoutRecording.setVisibility(recording ? View.VISIBLE : View.GONE);
        layoutError.setVisibility(error ? View.VISIBLE : View.GONE);
        layoutActions.setVisibility(result ? View.VISIBLE : View.GONE);
        perfChip.setVisibility(result ? View.VISIBLE : View.GONE);
        processingBar.setVisibility(processing ? View.VISIBLE : View.INVISIBLE);

        if (recording) {
            btnRecord.setImageResource(R.drawable.ic_stop_24dp);
            btnRecord.setBackgroundTintList(ColorStateList.valueOf(themeColor(com.google.android.material.R.attr.colorError)));
            recordStartMs = System.currentTimeMillis();
            tvTimer.setText("0:00");
            waveform.clear();
            timerHandler.post(timerTick);
            startFabPulse();
        } else {
            btnRecord.setImageResource(R.drawable.ic_mic_48dp);
            btnRecord.setBackgroundTintList(ColorStateList.valueOf(themeColor(com.google.android.material.R.attr.colorPrimary)));
            timerHandler.removeCallbacks(timerTick);
            stopFabPulse();
        }
    }

    /** Gentle repeating pulse on the mic FAB while recording; cancelled on any other state. */
    private void startFabPulse() {
        stopFabPulse();
        fabPulse = ObjectAnimator.ofPropertyValuesHolder(btnRecord,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f));
        fabPulse.setDuration(600);
        fabPulse.setRepeatMode(ObjectAnimator.REVERSE);
        fabPulse.setRepeatCount(ObjectAnimator.INFINITE);
        fabPulse.start();
    }

    private void stopFabPulse() {
        if (fabPulse != null) {
            fabPulse.cancel();
            fabPulse = null;
        }
        if (btnRecord != null) {
            btnRecord.setScaleX(1f);
            btnRecord.setScaleY(1f);
        }
    }

    private int themeColor(int attr) {
        return MaterialColors.getColor(this, attr, Color.GRAY);
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
        if (!inputMethodEnabled) {
            Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
            startActivity(intent);
        }
    }

    // Model initialization
    private void initModel() {
        File modelFile = new File(sdcardDataFolder, selectedModel.filename);
        boolean isMultilingualModel = !selectedModel.englishOnly;
        // Vocab files are required for TFLite models only; whisper.cpp loads from the GGUF alone.
        File vocabFile = selectedModel.engine == ModelInfo.Engine.TFLITE
                ? new File(sdcardDataFolder, ModelRegistry.vocabFor(selectedModel)) : null;

        mWhisper = new Whisper(this);
        mWhisper.loadModel(modelFile, vocabFile, isMultilingualModel);
        Log.d(TAG, "Initialized: " + selectedModel.id + " (" + selectedModel.engine + ")");
        mWhisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) {
                Log.d(TAG, "Update is received, Message: " + message);
                if (message.equals(Whisper.MSG_PROCESSING)) {
                    startTime = System.currentTimeMillis();
                    // While still recording, chunks transcribe in the background: stay in RECORDING.
                    if (recordingStopped) runOnUiThread(() -> {
                        applyState(UiState.PROCESSING);
                        spinnerModel.setEnabled(false);
                    });
                } else if (message.equals(Whisper.MSG_PROCESSING_DONE)) {
                    if (recordingStopped && !mWhisper.isInProgress()) {
                        runOnUiThread(MainActivity.this::finishToResult);
                    }
                }
            }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                long timeTaken = System.currentTimeMillis() - startTime;
                Log.d(TAG, "Result: " + whisperResult.getResult() + " " + whisperResult.getLanguage() + " " + (whisperResult.getTask() == Whisper.Action.TRANSCRIBE ? "transcribing" : "translating"));

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

                double procSec = timeTaken / 1000.0;
                double audioSec = audioDurationSeconds();
                double realtime = procSec > 0 ? audioSec / procSec : 0;

                // Append each chunk live (pseudo-streaming); history/TTS happen once in finishToResult().
                runOnUiThread(() -> {
                    tvResult.append(out);
                    perfChip.setText(getString(R.string.main_perf_chip, procSec, realtime));
                });
            }
        });
    }

    private double audioDurationSeconds() {
        byte[] buf = RecordBuffer.getOutputBuffer();
        if (buf == null) return 0;
        return buf.length / 2.0 / 16000.0; // 16-bit mono @ 16 kHz
    }

    private void deinitModel() {
        if (mWhisper != null) {
            mWhisper.unloadModel();
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
        for (ModelInfo m : ModelRegistry.all()) {
            if (!new File(sdcardDataFolder, m.filename).exists()) continue;
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
        badgeEngine.setText(selectedModel.engine == ModelInfo.Engine.WHISPER_CPP
                ? R.string.catalog_engine_whispercpp : R.string.main_badge_tflite);
    }

    /** Language selector is only meaningful for multilingual models. */
    private void applyLanguageEnabled(LanguagePairAdapter adapter) {
        if (!selectedModel.englishOnly) {
            spinnerLanguage.setEnabled(true);
            String langCode = sp.getString("language", "auto");
            spinnerLanguage.setSelection(adapter.getIndexByCode(langCode));
        } else {
            spinnerLanguage.setSelection(0);
            spinnerLanguage.setEnabled(false);
        }
    }

    private @NonNull ArrayAdapter<ModelInfo> getModelArrayAdapter(List<ModelInfo> models) {
        ArrayAdapter<ModelInfo> adapter = new ArrayAdapter<ModelInfo>(this, android.R.layout.simple_spinner_item, models) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                ((TextView) view.findViewById(android.R.id.text1)).setText(modelLabel(getItem(position)));
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                ((TextView) view.findViewById(android.R.id.text1)).setText(modelLabel(getItem(position)));
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private String modelLabel(ModelInfo m) {
        String engine = m.engine == ModelInfo.Engine.WHISPER_CPP
                ? getString(R.string.catalog_engine_whispercpp) : getString(R.string.main_badge_tflite);
        return m.displayName + " · " + engine;
    }

    private void checkPermissions() {
        List<String> perms = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECORD_AUDIO);
            Toast.makeText(this, getString(R.string.need_record_audio_permission), Toast.LENGTH_SHORT).show();
        }
        if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) && (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)){
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
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
        recordingStopped = false;
        resultFinalized = false;
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
        spinnerModel.setEnabled(true);
        String text = tvResult.getText().toString().trim();
        if (text.isEmpty()) {
            applyState(UiState.ERROR);
            return;
        }
        applyState(UiState.RESULT);
        if (sp.getBoolean("historyEnabled", true)) {
            HistoryDb.get(mContext).insert(text, lastLanguage, selectedModel.id, recordDurationMs);
        }
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
