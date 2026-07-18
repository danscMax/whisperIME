# Living Signal Liquid Glass Redesign

## Goal

Replace the rejected Aurora orb and fragmented controls with the approved Living Signal system across the standalone app, the speech-recognition provider sheet, the IME strip, onboarding, history, models, and settings. The result must remain a local-first Android Views application and preserve all recognition behavior.

## Approved visual language

- The signature element is **Living Signal**, not a sphere or a geometric logo.
- Ready is **Constellation**: a calm, sparse field of connected luminous points with a slow breath.
- Listening is **Open Iris**: an opened star/iris diaphragm that reacts immediately to RMS audio and decays smoothly.
- Processing is **Focused Iris**: the aperture contracts, rotates, and concentrates light toward the center.
- Result is **Signal Burst**: a short radiant star burst that settles into a calm result glow.
- Errors use the calm signal with a brief warm-red contraction; no unrelated spinner or progress strip is introduced.
- Dark mode uses near-black navy surfaces, cyan/blue/violet light, translucent glass, fine white borders, and soft depth. Light mode uses cool pearl surfaces with darker ink and restrained colored glow.
- Space Grotesk remains the interface font; Space Mono is reserved for time, engine, performance, and compact metadata.

## Architecture

`LivingSignalView` is a single custom Android `View` shared by all voice surfaces. It draws procedural layers with `Canvas`, `Path`, radial gradients, soft glows, and deterministic point geometry; it does not add a rendering dependency. A small pure-Java `LivingSignalEnvelope` owns clamping and asymmetric audio smoothing so the motion rules can be unit-tested without Android.

The renderer exposes `setSignalState(State)`, `pushLevel(float)`, `setColors(int, int)`, and lifecycle-safe animation. State transitions crossfade inside the same view. When system animations are disabled, it renders a legible static frame and still updates audio amplitude without continuous decorative motion.

Activities and services continue to own recognition state. Their existing state reducers call the shared renderer rather than controlling separate progress indicators. Recognition/model/history behavior, preference keys, and intent contracts do not change.

## Standalone screen

The standalone screen is organized into four stable zones:

1. A compact top bar with the product name and one overflow action.
2. One unified context control showing `model · language`; tapping it opens a liquid-glass configuration sheet for model, language, append, and translation mode.
3. A centered Living Signal and state label above a persistent transcript glass card. The transcript card does not jump between states.
4. A thumb-reachable glass dock with at most three persistent actions. Its contents are state-aware: history/record/settings when ready, cancel/stop/timer while listening, cancel/status while processing, and copy/new/share for a result.

All interactive targets are at least 48dp. The record action supports the existing press-and-hold behavior; visual state and copy communicate this clearly.

## RecognitionService provider sheet

`WhisperRecognizeActivity` remains a bottom floating activity so host applications retain their content and contract. Its sheet uses a drag handle, close action, compact Living Signal, state label, partial transcript, and one context pill. The old horizontal progress bar is removed. No-model state remains available inside the same glass sheet with a clear catalog action.

## IME strip

The IME becomes a compact glass voice dock:

- Living Signal is the mic/stop affordance and remains visible during processing.
- The center area holds state copy, partial feedback, model/language context, or the timer.
- The always-visible right actions are backspace, enter, and return to the keyboard.
- Translation, auto-language, and settings move into one overflow menu to prevent six cramped icon buttons.
- The old waveform, ring, and processing spinner are removed; state is carried by Living Signal.

## Secondary screens

- Onboarding uses the Living Signal in Ready state, one prominent recommended model card, and glass selection cards.
- Model catalog uses the same top bar, segmented glass filters, readable model cards, and a stable storage footer.
- History uses a glass search field and quieter transcript cards with clear metadata.
- App settings and RecognitionService settings use consistent glass groups, exposed dropdowns, switches, and the same navigation chrome. Legacy platform `Spinner` widgets are replaced with Material exposed dropdowns while preserving preference values.

## Interaction and state flow

Recognition state remains the source of truth:

`READY -> LISTENING -> PROCESSING -> RESULT`

Cancellation returns to `READY`; missing or empty audio enters `ERROR` and offers retry. RMS samples are clamped to `[0, 1]`. The envelope uses fast attack (`0.70`) and slow decay (`0.06`) per frame. Every state update changes the status copy, available actions, content descriptions, and Living Signal state together.

The configuration sheet writes existing model, language, append, translation, and Simplified Chinese preferences. Model switching stays on the existing serialized model executor. External recognition results and IME commits keep their current APIs and timing.

## Accessibility and resilience

- Touch targets are 48dp minimum and icon-only controls have localized content descriptions.
- Text and essential controls meet readable contrast in both themes; translucency never carries meaning by itself.
- State is expressed through motion, label, and action changes, not color alone.
- Continuous animation stops when detached and respects disabled system animations.
- Renderer input is defensive: non-finite or out-of-range levels are normalized safely.
- The layouts remain usable at the app's existing minimum API 28 and at narrow phone widths.

## Error handling

If the model is still loading, recording does not start and the existing localized message is shown. If no model exists, onboarding or the provider no-model state is used. Recorder errors return controls to a usable state and render the error signal. The custom renderer must never throw on zero size, lifecycle detach/reattach, or invalid audio input.

## Verification

- Pure JVM tests cover level normalization, fast attack, slow decay, and convergence.
- Existing unit tests remain green.
- Android resource processing and debug APK assembly must pass.
- Emulator smoke testing covers the standalone ready screen, listening transition where possible, configuration sheet, history, settings, model catalog, RecognitionService sheet, and IME rendering.
- Screenshots and logcat are inspected for clipping, crashes, missing resources, illegible contrast, and inconsistent state indicators.

## Constraints

- Keep `minSdk 28`, `compileSdk 35`, Java 11, XML layouts, Material 3, and the existing package/application IDs.
- Add no third-party rendering dependency.
- Do not change recognition engines, model files, preference keys, database schema, service metadata, or external intent contracts.
- Do not include gallery or emulator artifacts in the application package or repository history.
