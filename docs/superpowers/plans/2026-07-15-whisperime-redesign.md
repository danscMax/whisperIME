# WhisperIME Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the whisperIME fork into a modern Material You voice-to-text app: 4 selectable palettes + dynamic color, redesigned screens/states, per-model catalog with downloads, whisper.cpp as second engine, history, quick tile, widget, sharing.

**Architecture:** Keep the existing Java app structure (activities + services, ViewBinding, no Compose). Add an `AsrEngine` interface with two implementations (existing TFLite path, new whisper.cpp JNI). Replace the fixed 3-model Downloader with a `ModelRegistry` + `ModelDownloadManager`. All UI rebuilt on Material 3 with palette overlays.

**Tech Stack:** Java 11 (+existing Kotlin file), Material Components 1.12, TFLite 2.15, whisper.cpp (git submodule, CMake/NDK), SQLiteOpenHelper, Gradle 8.6 / AGP 8.2.2.

**Spec:** `docs/superpowers/specs/2026-07-15-whisperime-redesign-design.md`
**Mockups:** `.superpowers/brainstorm/703727-1784101304/content/all-screens-live.html` (палитра Teal = default)

## Global Constraints

- minSdk 28, targetSdk 35, package `org.woheller69.whisper`, namespace `com.whispertflite`.
- No new runtime dependencies beyond whisper.cpp submodule (F-Droid friendly; no Room, no Compose, no OkHttp — use HttpURLConnection as upstream does).
- All strings in `values/strings.xml` (EN base) AND duplicated in `values-ru/strings.xml` — never hardcoded; keep existing translations compiling (`lintOptions.disable 'MissingTranslation'` already set).
- No AI attribution anywhere (commits, code, docs).
- Every task ends with `assembleDebug` green; UI tasks also install + screenshot on emulator-5560.
- Build command (no gradlew scripts in repo):
  `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr" java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain assembleDebug --console=plain`
- Emulator smoke: `adb -s emulator-5560 install -r app/build/outputs/apk/debug/app-debug.apk`, launch, `exec-out screencap -p > shot.png`, READ the screenshot.
- Commit after every task on branch `feat/redesign`.

## Shared contracts (source of truth for all tasks)

```java
// com/whispertflite/engine/AsrEngine.java
public interface AsrEngine {
    boolean load(ModelInfo model, File modelFile, File vocabFile) throws IOException;
    void unload();
    boolean isLoaded();
    WhisperResult transcribe(float[] pcm16k, String langCode, boolean translate);
}
```

```java
// com/whispertflite/models/ModelInfo.java  (immutable value class)
public final class ModelInfo {
    public enum Engine { TFLITE, WHISPER_CPP }
    public final String id;           // "tflite-base-topworld", "gguf-large-v3-turbo-q5"
    public final String displayName;  // "base · TOP_WORLD"
    public final Engine engine;
    public final String url;          // direct HF resolve URL
    public final long sizeBytes;
    public final String filename;     // on-disk name in getExternalFilesDir(null)
    public final int languages;       // 78, 99, 1
    public final boolean englishOnly;
    public final int speedClass;      // 1 fast .. 3 slow
    public final int qualityClass;    // 1 basic .. 3 best
}
```

```java
// com/whispertflite/models/ModelState.java
public enum ModelState { AVAILABLE, DOWNLOADING, DOWNLOADED, ACTIVE }
```

```java
// com/whispertflite/models/ModelDownloadManager.java
public class ModelDownloadManager {
    public interface Listener {
        void onProgress(String modelId, long bytes, long total, long bytesPerSec);
        void onDone(String modelId);
        void onError(String modelId, String message);
    }
    public static ModelDownloadManager get(Context ctx);
    public void download(ModelInfo model);      // resumes via HTTP Range if partial file exists
    public void cancel(String modelId);         // keeps partial file for resume
    public boolean isDownloading(String modelId);
    public void addListener(Listener l); public void removeListener(Listener l);
    public ModelState stateOf(ModelInfo model); // ACTIVE if prefs selectedModelId==id && file exists
}
```

```java
// com/whispertflite/history/HistoryDb.java  (SQLiteOpenHelper, no Room)
public class HistoryDb {
    public static final class Entry {
        public long id; public String text; public String lang;
        public String modelId; public long durationMs; public long createdAt;
    }
    public static HistoryDb get(Context ctx);
    public long insert(String text, String lang, String modelId, long durationMs);
    public List<Entry> list(String query, int limit);   // query nullable
    public void delete(long id);
    public void clearAll();
    // auto-prune to 500 newest on insert
}
```

```java
// com/whispertflite/utils/ThemeUtils.java — extend existing class
public class ThemeUtils {
    public static void applyPalette(Activity a);      // call BEFORE setContentView; reads prefs
    public static void applyNightMode(Context c);     // system/light/dark from prefs
    // pref keys: "palette" = teal|terracotta|indigo|forest|dynamic ; "nightMode" = system|light|dark
}
```

Preference keys (SharedPreferences, default file): `palette`, `nightMode`,
`selectedModelId`, `wifiOnlyDownloads` (bool, default true), `historyEnabled` (bool, default true),
`historyFromIme` (bool, default true) — plus all existing upstream keys unchanged.

---

## Wave 1 — Foundation (palettes, main screen, settings, history)

### Task 1.1: Palette system + theme overlays

**Files:**
- Create: `app/src/main/res/values/colors_palettes.xml` (M3 tonal roles for 4 seeds: teal #006A60, terracotta #9C4234, indigo #3F51E0, forest #3E6837 — light+dark values for: primary, onPrimary, primaryContainer, onPrimaryContainer, secondaryContainer, onSecondaryContainer, surface, onSurface, surfaceVariant, onSurfaceVariant, outline, error)
- Create: `app/src/main/res/values-night/colors_palettes.xml`
- Modify: `app/src/main/res/values/themes.xml` — add `ThemeOverlay.Whisper.Teal/Terracotta/Indigo/Forest`
- Modify: `app/src/main/java/com/whispertflite/utils/ThemeUtils.java` — add `applyPalette`, `applyNightMode` per contract; dynamic palette uses `DynamicColors.applyToActivityIfAvailable`
- Modify: all 5 activities — call `ThemeUtils.applyPalette(this)` before `setContentView`

**Interfaces:** Produces `ThemeUtils.applyPalette/applyNightMode` used by every later UI task.

- [ ] Generate tonal values (Material theme builder tables) for the 4 seeds; light+night resource sets
- [ ] Theme overlays + ThemeUtils; wire into activities
- [ ] Build, install, screenshot MainActivity with palette pref manually set to each of 4 values (adb shell am force-stop between) — colors visibly change
- [ ] Commit "Palette system: 4 selectable M3 palettes + dynamic color"

### Task 1.2: Settings screen (new)

**Files:**
- Create: `app/src/main/java/com/whispertflite/SettingsActivity.java` + `app/src/main/res/layout/activity_settings.xml`
- Modify: `app/src/main/AndroidManifest.xml` (register activity)
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:** Consumes ThemeUtils; produces the settings surface later tasks link to (models entry hidden until Wave 2 — add the row in Task 2.3).

- [ ] Build per mockup «4 · Настройки»: palette picker (5 swatch chips incl. Dynamic), theme mode selector, then MaterialSwitch rows migrating ALL existing MainActivity checkboxes/prefs that are settings-like (haptics, TTS, simplified Chinese, VAD auto-stop if pref exists upstream), IME/voice-input enable helpers (launch existing system intents), about/GitHub row (reuse GithubStar)
- [ ] Palette/night change applies immediately (recreate())
- [ ] Build, install, screenshot light+dark; commit "Settings screen"

### Task 1.3: Main screen redesign — all four states

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml` (full rebuild per mockup «1 · Главный экран»)
- Modify: `app/src/main/java/com/whispertflite/MainActivity.java` (state machine READY/RECORDING/PROCESSING/RESULT/ERROR; keep all existing logic — recorder, whisper, TTS, chinese conversion — rewire to new views)
- Create: `app/src/main/java/com/whispertflite/ui/WaveformView.java` (custom View, ~80 lines: bars from live RMS, fed by existing Recorder buffer callback)
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:** Consumes ThemeUtils. Produces `WaveformView` (reused by IME in Task 4.1) with API `void push(float rms)` + `void clear()`.

- [ ] Top bar: title, history icon (stub → Task 1.4), settings icon → SettingsActivity
- [ ] Model+language chip card (existing spinners restyled as Material dropdowns), TFLite badge
- [ ] States per mockup: ready hint → recording (WaveformView + mm:ss timer, NO 30 s limit — see Task 1.5) → processing → result card (perf chip "2,1 с · ×3,3", actions copy/TTS/share) → error state ("Ничего не расслышал" + retry) — strings EN base + values-ru
- [ ] Move Append/Translate checkboxes into compact toggle row per mockup
- [ ] Build, install, dictate on emulator (mic = host loopback; if no audio, verify states via injected fake: record 2 s silence → error state), screenshot each reachable state; commit "Main screen redesign with explicit states"

### Task 1.4: History (DB + screen + wiring)

**Files:**
- Create: `app/src/main/java/com/whispertflite/history/HistoryDb.java` (per contract)
- Create: `app/src/main/java/com/whispertflite/history/HistoryActivity.java` + `layout/activity_history.xml` + `layout/item_history.xml` (RecyclerView; row: text 2-line ellipsis, meta line "модель · язык · дата", tap=copy, long-press menu share/delete; search field; clear-all in menu; empty state)
- Modify: `AndroidManifest.xml`, `MainActivity.java` (insert on success; history icon opens activity), `strings.xml`

**Interfaces:** Consumes HistoryDb contract. Wave 4 wires IME/dialog inserts.

- [ ] HistoryDb with prune-to-500; insert from MainActivity transcription success (respect `historyEnabled`)
- [ ] HistoryActivity per description; empty state illustration (emoji ok)
- [ ] Build, install, dictate/insert twice, screenshot list; commit "Recognition history"

### Task 1.5: Chunked unlimited recording (kills the 30 s limit)

**Files:**
- Modify: `app/src/main/java/com/whispertflite/asr/Recorder.java` — remove hard 30 s stop; use the existing webrtc VAD to detect speech pauses (~700 ms silence) and emit completed chunks (≤28 s hard cap per chunk: force-split mid-speech if exceeded) via new callback `onChunk(float[] pcm16k)` while recording continues
- Modify: `app/src/main/java/com/whispertflite/asr/Whisper.java` — queue chunks, transcribe sequentially on the existing worker thread, deliver each chunk's text via existing result callback with `append=true` semantics
- Modify: `MainActivity.java` — result card appends chunk texts live during recording ("pseudo-streaming"); recording timer keeps counting past 30 s

**Interfaces:** Consumes existing Recorder/Whisper pipeline. Produces chunked behavior ALL surfaces inherit (IME, dialog use the same classes).

- [ ] Implement per above; auto-stop-on-silence pref still honored (long final silence = stop, if enabled)
- [ ] Verify on emulator: dictate >40 s (or inject audio), text arrives in ≥2 portions, no crash
- [ ] Commit "Unlimited recording via VAD chunking"

## Wave 2 — Models (registry, downloads, catalog, onboarding)

### Task 2.1: ModelRegistry + ModelDownloadManager

**Files:**
- Create: `app/src/main/java/com/whispertflite/models/ModelInfo.java`, `ModelState.java`, `ModelRegistry.java`, `ModelDownloadManager.java` (contracts above)
- Registry content: 3 существующие TFLite (tiny.en 41MB, base TOP_WORLD 74MB, small TOP_WORLD 249MB — URLs из старого Downloader.java) + GGUF с `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/`: ggml-tiny.bin 75MB, ggml-base.bin 142MB, ggml-small.bin 466MB, ggml-medium-q5_0.bin 514MB, ggml-large-v3-turbo-q5_0.bin 547MB
- Vocab files (filters_vocab_en.bin / filters_vocab_multilingual.bin) — авто-догрузка при первом TFLite-скачивании (логика из старого Downloader)

**Interfaces:** Produces registry+manager consumed by catalog, onboarding, engines.

- [ ] Implement with HttpURLConnection, 8k buffer, Range resume, progress throttled to ≤4/sec, wifiOnly check (ConnectivityManager), foreground-less (activity-scoped listeners; download continues on background thread singleton while process lives)
- [ ] Unit-testable pure parts kept pure; quick self-check: `stateOf` transitions in a small instrumented-free JVM test is NOT required — verify via UI in 2.2
- [ ] Build; commit "Model registry and download manager"

### Task 2.2: Model catalog screen

**Files:**
- Create: `app/src/main/java/com/whispertflite/models/ModelCatalogActivity.java` + `layout/activity_model_catalog.xml` + `layout/item_model.xml`
- Modify: `AndroidManifest.xml`, `strings.xml`

- [ ] Per mockup «Каталог»: filter chips All/TFLite/whisper.cpp, cards (engine badge, langs, size, speed/quality hint), states ACTIVE (tonal card + «активна»)/DOWNLOADED («использовать» sets selectedModelId, «удалить»)/DOWNLOADING (LinearProgressIndicator, MB/MB, MB/s, cancel)/AVAILABLE («скачать»); footer: storage used + Wi-Fi-only switch
- [ ] Selecting GGUF model allowed but marked «движок появится в волне 3» chip until Wave 3 lands (hide GGUF cards behind BuildConfig flag `WHISPER_CPP_READY=false` — flip in 3.3)
- [ ] Build, install, download tiny.en live, screenshot states; commit "Model catalog"

### Task 2.3: Onboarding + legacy Downloader removal

**Files:**
- Rewrite: `app/src/main/java/com/whispertflite/DownloadActivity.kt` → onboarding per mockup (hero, 3 карточки: рекомендуем base / точнее small / английский tiny.en, CTA «Скачать и начать» с инлайн-прогрессом, «позже» → каталог)
- Delete: `app/src/main/java/com/whispertflite/utils/Downloader.java` + `layout/activity_download.xml` old content (replace)
- Modify: `MainActivity.java` (model-присутствие проверять через ModelRegistry/DownloadManager; «нет моделей» → онбординг), `SettingsActivity` (row «Модели и движки» → каталог), `WhisperRecognitionServiceSettingsActivity` + `WhisperRecognizeActivity` + `WhisperInputMethodService` — заменить обращения к константам старого Downloader/MainActivity на ModelRegistry
- [ ] Grep: no references to deleted Downloader remain (`grep -r "Downloader" app/src`)
- [ ] Build, wipe app data (`adb shell pm clear org.woheller69.whisper`), full first-run flow on emulator, screenshots; commit "Onboarding replaces fixed downloader"

## Wave 3 — whisper.cpp engine

### Task 3.1: Native build

**Files:**
- Add submodule: `native/whisper.cpp` (pin latest release tag)
- Create: `app/src/main/cpp/CMakeLists.txt`, `app/src/main/cpp/whisper_jni.cpp` (JNI: nativeInit(path)→ptr, nativeTranscribe(ptr, float[], lang, translate)→String, nativeRelease(ptr); use whisper_full with greedy defaults, 4 threads)
- Modify: `app/build.gradle` (externalNativeBuild cmake, abiFilters arm64-v8a armeabi-v7a + x86_64 debug-only)
- [ ] Build both ABIs green; commit "whisper.cpp native build"

### Task 3.2: WhisperCppEngine + AsrEngine adoption

**Files:**
- Create: `app/src/main/java/com/whispertflite/engine/AsrEngine.java` (contract), `WhisperCppEngine.java`, `TfliteEngine.java` (wrap existing WhisperEngineJava/Whisper pipeline)
- Modify: `asr/Whisper.java` (route by ModelInfo.engine), call sites unchanged externally
- [ ] Parity check: dictate same phrase with TFLite base and GGUF base on emulator — both non-empty; commit "Engine abstraction: TFLite + whisper.cpp"

### Task 3.3: Enable GGUF in catalog

- Flip `WHISPER_CPP_READY=true`, verify download+select+use large-turbo-q5 (or base for emulator speed), screenshot; commit "Enable whisper.cpp models"

## Wave 4 — Surfaces (IME, dialog, tile, widget, sharing)

### Task 4.1: IME strip redesign — `voice_service.xml` + `WhisperInputMethodService.java`: три состояния per mockup (idle: mic FAB, hint, model chip, keys ⌫ ⏎ ⌨ ⚙; recording: stop, WaveformView, timer; processing: spinner + chunk-wise draft), history insert (pref `historyFromIme`; NEVER when target field inputType is any password variant), commit.
### Task 4.2: Recognition dialog redesign — `WhisperRecognizeActivity` + `activity_recognize.xml`: bottom-sheet look per mockup, pulsing mic (scale animation), live partial text area, model/lang chip, «нет модели» state → каталог; same for `WhisperRecognitionService` UI-less paths; commit.
### Task 4.3: Quick Settings tile — `WhisperTileService` (TileService, manifest `BIND_QUICK_SETTINGS_TILE`), icon, launches WhisperRecognizeActivity; commit.
### Task 4.4: Home widget — `WhisperWidgetProvider` + `layout/widget_mic.xml` + `xml/widget_info.xml`: одна кнопка-микрофон → WhisperRecognizeActivity; commit.
### Task 4.5: Sharing — result share (ACTION_SEND) уже в 1.3; добавить `ACTION_PROCESS_TEXT` activity-alias на WhisperRecognizeActivity (returns replacement text), проверить из текстового поля; commit.

## Wave 5 — Polish

### Task 5.1: Motion — MaterialSharedAxis/fade transitions between activities, mic FAB pulse while recording, result text type-in animation (уже в 1.3 — проверить), state cross-fades; commit.
### Task 5.2: Adaptive icon (4 palette-agnostic: teal fg on dynamic bg), themed icon (monochrome); commit.
### Task 5.3: README rewrite (fork identity, features, screenshots из emulator обеих тем), fastlane screenshots refresh; commit.
### Task 5.4: Release build (`assembleRelease`, existing signing absent → unsigned ok), final full smoke: onboarding → download base → dictate → history → tile → widget → IME; fix regressions; commit.

## Verification checklist (final)

- [ ] Все 4 палитры + dynamic переключаются live, light+dark
- [ ] Каталог: download/cancel/resume/delete/switch между TFLite и GGUF
- [ ] История пишется из app/IME/dialog, поиск и очистка работают
- [ ] IME работает в стороннем приложении (эмулятор: Messages/поиск)
- [ ] Tile и widget открывают диалог; ACTION_PROCESS_TEXT виден в text-selection
- [ ] `git log` чистый, по коммиту на задачу, без AI-attribution
