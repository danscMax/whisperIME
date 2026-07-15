# WhisperIME Fork Redesign — Design Spec

Date: 2026-07-15
Status: approved by owner (brainstorm session)
Fork: https://github.com/danscMax/whisperIME (upstream: woheller69/whisperIME)

## Goal

Turn the utilitarian upstream app into a modern, beautiful, Material You voice-to-text
product while keeping all three integration surfaces (standalone app, IME, system
RecognitionService) fully functional. Personal daily driver first, public release second
(keep translations and minSdk 28 compatibility).

## Decisions (from brainstorm)

| Topic | Decision |
|---|---|
| Audience | Personal tool + public release (F-Droid-friendly: no proprietary deps) |
| Engines | Keep TFLite, add whisper.cpp (GGUF) behind a common engine abstraction |
| Redesign scope | All surfaces: main app, model catalog, onboarding, settings, IME strip, recognition dialog |
| Visual language | Material 3 (Material You), expressive |
| Palettes | All 4 shipped, user-selectable in settings: Teal (default), Terracotta, Indigo, Forest + Dynamic (Monet, Android 12+) with Teal fallback. Light/dark/system. |
| History | Yes — new feature, local only. IME writes by default (toggle in settings); password fields never recorded |
| Recording length | Unlimited: VAD splits speech at pauses into ≤30 s chunks, transcribed sequentially, text appended per chunk (pseudo-streaming) |
| Localization | All new strings shipped in EN base + values-ru immediately |
| App icon | New adaptive icon + monochrome (themed icons) |
| Quick tile + widget | Yes — new features |
| Extended sharing | Yes — share sheet + ACTION_PROCESS_TEXT where sensible |

## Architecture

### Engine abstraction (new)

```
interface AsrEngine {
    boolean load(ModelInfo model);          // heavy, off main thread
    void unload();
    WhisperResult transcribe(float[] pcm, String lang, boolean translate);
    boolean isLoaded();
}
```

- `TfliteEngine` — wraps existing `WhisperEngineJava` + `whisper-*.tflite` + vocab bin files. Unchanged behavior.
- `WhisperCppEngine` — JNI wrapper over whisper.cpp built via NDK/CMake (git submodule
  `native/whisper.cpp`, pinned release tag). GGUF models from `ggerganov/whisper.cpp`
  HF repo. ABIs: arm64-v8a, armeabi-v7a (+ x86_64 for emulator in debug).
- Engine chosen implicitly by the selected model's format (`.tflite` → TFLite, `.bin`/GGUF → cpp).
  No separate "engine picker" — the model catalog is the picker.
- All call sites (MainActivity, IME service, RecognitionService, RecognizeActivity) go
  through `AsrEngine`, never a concrete engine.

### Model catalog (replaces fixed 3-model Downloader)

- `ModelRegistry` — static list of `ModelInfo {id, displayName, engine, url, sizeBytes,
  sha?, languages, speedClass, qualityClass, filename}`.
  Initial set: existing 3 TFLite models + GGUF: tiny, base, small, medium-q5, large-v3-turbo-q5.
- `ModelDownloadManager` — per-model download with progress (bytes/total/speed), pause…
  actually: cancel + resume via HTTP Range (Hugging Face supports it); Wi-Fi-only pref;
  checksum-free (upstream has none) but size validation; notification while downloading.
- Storage: existing external files dir, plus `models/gguf/` subdir.
- Vocab .bin files remain a TFLite-only implicit dependency, downloaded with first TFLite model.

### History (new)

- Plain `SQLiteOpenHelper` (no Room — keep deps lean), table
  `history(id, text, lang, model_id, duration_ms, created_at)`.
- Written from every successful transcription in the standalone app and recognition
  dialog. IME writes too (pref-controlled, default on; privacy note in settings).
- UI: list with search, copy/share/delete per item, clear-all. Cap 500 entries (auto-prune).

### Theming

- Base: `Theme.Material3.DayNight.NoActionBar` + Material Color Utilities-style static
  schemes generated for the 4 seeds (values/colors-*.xml, themes overlay per palette).
- `DynamicColors.applyToActivitiesIfAvailable` when palette == Dynamic.
- Palette + light/dark/system prefs applied via activity theme overlays; IME/dialog
  surfaces read the same prefs.

### New surfaces

- **Quick Settings tile** (`TileService`, API 24+, we have 28) → opens the recognition
  bottom-sheet dialog.
- **Home widget** — single mic button (AppWidgetProvider) → same dialog.
- **Sharing**: result screen share button (ACTION_SEND); appear in text-selection
  toolbar via `ACTION_PROCESS_TEXT` (record → replace/insert text).

## Screens (mockups: .superpowers/brainstorm/703727-1784101304/content/all-screens-live.html)

1. **Main** — model+language chips card, big result card with typing-in animation and
   perf chip (elapsed s, × realtime), action row (copy/TTS/share), mic FAB; states:
   ready / recording (live waveform, timer, 30 s limit progress) / processing / error
   ("didn't catch that" + retry); history entry point in top bar.
2. **Onboarding** (first run) — hero icon, model choice (recommended base / small /
   tiny.en) then single "download & start" CTA; replaces old DownloadActivity flow.
3. **Model catalog** — filter chips (All/TFLite/whisper.cpp), model cards with
   engine badge, langs, size, quality/speed hints; states: active / downloaded /
   downloading (progress, speed, cancel) / available; storage footer + Wi-Fi-only toggle.
4. **Settings** — palette picker (4 swatches + Dynamic), theme mode, recognition language,
   VAD auto-stop, haptics, TTS, simplified Chinese, history on/off for IME, models link,
   enable-IME/voice-input helpers, about/licenses.
5. **IME strip** — states: idle (mic, hint, model chip, ⌫ ⏎ ⌨ ⚙ keys) / recording
   (stop button, waveform, timer) / processing (spinner, streaming draft text).
6. **Recognition dialog** — bottom sheet: pulsing mic, live partial text, model/lang chip;
   edge states: no model yet (→ catalog), mic busy error.

## Non-goals (this iteration)

- Full live transcription (incremental re-decode every 1-2 s). Chunk-wise output at speech
  pauses IS in scope; true live partials later.
- Cloud/remote engines. Model benchmarking. Backup/sync of history.

## Error handling

- Downloads: resume on retry, clear error surface on card, no silent failures.
- Engine load failure → user-visible message + fallback offer to switch model.
- Mic permission / mic busy → dedicated error states (already partially upstream).
- OOM guard: refuse to load models larger than heuristic free-RAM threshold with message.

## Testing / verification

- Build: assembleDebug per wave; install + smoke on emulator (Pixel 8 AVD, port 5560).
- Screenshots of every redesigned surface in light+dark, both a static palette and dynamic.
- Engine parity check: same 5 s RU clip through TFLite base and GGUF base, both return text.
- IME smoke: enable keyboard in emulator settings, dictate into a text field.

## Implementation waves

1. **Foundation**: palettes/themes, settings screen, restyle main screen (all states), history DB+UI.
2. **Models**: registry, download manager, catalog screen, onboarding; delete legacy Downloader flow.
3. **Engine**: whisper.cpp submodule + JNI + WhisperCppEngine + GGUF models in catalog.
4. **Surfaces**: IME strip redesign, recognition dialog redesign, quick tile, widget, sharing.
5. **Polish**: animations/transitions, adaptive icon, README, screenshots, release APK.
