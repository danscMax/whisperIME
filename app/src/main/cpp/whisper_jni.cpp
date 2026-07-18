// JNI bridge for com.whispertflite.engine.WhisperCpp
#include <jni.h>
#include <string>
#include <thread>
#include <algorithm>
#include <atomic>
#include <mutex>
#include <dlfcn.h>
#include <unistd.h>
#include <android/log.h>

#include "whisper.h"
#include "ggml-backend.h"

#define LOG_TAG "whisper_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

// Per-context handle: cancel targets exactly one run and can't be clobbered by another context's reset
// (the old global flag had a lost-cancel race). The pointer to this struct is what Java holds as its
// "ctxPtr" and passes back to every native call (C3).
struct WhisperCtx {
    whisper_context *ctx;
    std::atomic<bool> cancel;
    explicit WhisperCtx(whisper_context *c) : ctx(c), cancel(false) {}
};

// Polled by whisper_full through abort_callback_user_data — the WhisperCtx of the running transcription.
static bool abort_callback(void *user_data) {
    auto *wc = reinterpret_cast<WhisperCtx *>(user_data);
    return wc != nullptr && wc->cancel.load();
}

// The CPU backend is built as feature-tiered variant .so (GGML_CPU_ALL_VARIANTS) that the ggml
// registry dlopen's and scores at runtime. Its default search — get_executable_path() — is
// /system/bin on Android, never the app's lib dir, so nothing loads unless we point it there.
// dladdr on our own symbol yields the path to libwhisper_jni.so, whose directory *is* the app's
// nativeLibraryDir where every bundled variant sits — no Context plumbing needed. This only holds
// with android:extractNativeLibs="true" in the manifest: otherwise the variant .so stay compressed
// inside the APK (mapped, not on disk) and a directory scan finds nothing. Runs once.
static void load_backends_once() {
    static std::once_flag flag;
    std::call_once(flag, [] {
        Dl_info info;
        if (dladdr(reinterpret_cast<void *>(&abort_callback), &info) && info.dli_fname) {
            std::string dir(info.dli_fname);
            auto slash = dir.find_last_of('/');
            if (slash != std::string::npos) {
                dir.resize(slash);
                ggml_backend_load_all_from_path(dir.c_str());
            }
        }
        // Log which CPU variant the registry scored highest — the whole point of the dispatch is
        // that this is a feature-accelerated tier (dotprod/fp16 on ARM, AVX2 on x86), not baseline.
        // Also a field diagnostic: it tells us the active kernel set on any user's device.
        ggml_backend_dev_t cpu = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
        LOGI("ggml backends registered: %zu; CPU device: %s (%s)",
             ggml_backend_reg_count(),
             cpu ? ggml_backend_dev_name(cpu) : "none",
             cpu ? ggml_backend_dev_description(cpu) : "-");
    });
}

// True once a CPU backend device is available. whisper_init calls ggml_backend_dev_backend_reg on
// the CPU device and ggml_abort()s (uncatchable SIGABRT) if there is none, so gate on this first
// and surface a catchable error instead of taking the whole process down.
static bool cpu_backend_available() {
    return ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU) != nullptr;
}

static void throw_java(JNIEnv *env, const char *msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) {
        env->ThrowNew(cls, msg);
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_whispertflite_engine_WhisperCpp_nativeInit(JNIEnv *env, jclass, jstring modelPath) {
    if (modelPath == nullptr) {
        throw_java(env, "modelPath is null");
        return 0;
    }
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) {
        throw_java(env, "failed to read modelPath");
        return 0;
    }

    load_backends_once();  // register the CPU backend variants before the first model load
    if (!cpu_backend_available()) {
        env->ReleaseStringUTFChars(modelPath, path);
        LOGE("no CPU backend registered; refusing to init (would ggml_abort)");
        throw_java(env, "whisper.cpp CPU backend unavailable");
        return 0;
    }

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // no GPU on this build
    // B3: flash_attn is deliberately left off. It is primarily a GPU optimization; on this CPU-only
    // build the ggml CPU support is limited and there is evidence it can change output quality
    // (whisper.cpp issue #3020). Without an on-device A/B showing a win it stays disabled — enabling it
    // blindly would risk accuracy for no measured speedup.

    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (ctx == nullptr) {
        LOGE("whisper_init_from_file_with_params returned null");
        throw_java(env, "failed to load whisper model");
        return 0;
    }
    return reinterpret_cast<jlong>(new WhisperCtx(ctx));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_whispertflite_engine_WhisperCpp_nativeTranscribe(JNIEnv *env, jclass, jlong ctxPtr,
                                                          jfloatArray pcm16k, jstring lang,
                                                          jboolean translate, jstring prompt) {
    auto *wc = reinterpret_cast<WhisperCtx *>(ctxPtr);
    if (wc == nullptr || wc->ctx == nullptr) {
        throw_java(env, "whisper context is null");
        return nullptr;
    }
    whisper_context *ctx = wc->ctx;
    if (pcm16k == nullptr) {
        throw_java(env, "pcm buffer is null");
        return nullptr;
    }

    jsize n_samples = env->GetArrayLength(pcm16k);
    jfloat *samples = env->GetFloatArrayElements(pcm16k, nullptr);
    if (samples == nullptr) {
        throw_java(env, "failed to read pcm buffer");
        return nullptr;
    }

    // "auto" -> language detection (null lets whisper decide)
    std::string langStr;
    const char *langC = nullptr;
    if (lang != nullptr) {
        const char *l = env->GetStringUTFChars(lang, nullptr);
        if (l != nullptr) {
            langStr = l;
            env->ReleaseStringUTFChars(lang, l);
            if (!langStr.empty() && langStr != "auto") {
                langC = langStr.c_str();
            }
        }
    }

    // Optional vocabulary bias (A3): initial_prompt primes the decoder toward the user's names/terms.
    std::string promptStr;
    if (prompt != nullptr) {
        const char *p = env->GetStringUTFChars(prompt, nullptr);
        if (p != nullptr) {
            promptStr = p;
            env->ReleaseStringUTFChars(prompt, p);
        }
    }

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    // hardware_concurrency() is unreliable on Android (often 0); sysconf is the online core count.
    int cores = (int) sysconf(_SC_NPROCESSORS_ONLN);
    if (cores <= 0) cores = (int) std::thread::hardware_concurrency();
    if (cores <= 0) cores = 4;
    wparams.n_threads       = std::max(2, std::min(8, cores - 1)); // leave one core for UI/audio
    wparams.translate       = translate == JNI_TRUE;
    wparams.language        = langC; // null => auto-detect
    wparams.print_progress  = false;
    wparams.print_realtime  = false;
    wparams.print_special   = false;
    wparams.print_timestamps = false;
    wparams.no_context      = true;
    wparams.single_segment  = false;
    wparams.suppress_nst    = true;   // suppress non-speech tokens ([BLANK_AUDIO], music) — anti-hallucination (A2/A11)
    if (!promptStr.empty()) wparams.initial_prompt = promptStr.c_str();   // vocabulary bias (A3)
    // Let Java stop a slow run (large models take minutes on mobile CPUs) — per-context flag (C3).
    wc->cancel.store(false);
    wparams.abort_callback  = abort_callback;
    wparams.abort_callback_user_data = wc;

    // Cap the encoder context to the actual audio length (+ margin) instead of always encoding a full
    // 30 s window: whisper's encoder runs over `audio_ctx` frames (~50 per second of audio), so short
    // VAD chunks skip most of the encoder for a large latency win.
    // ponytail: the 256-frame (~5 s) pad is a calibration knob — widen it if word tails get cut on
    // real speech (needs an on-device WER check); capped at the model's 1500, floored for tiny blips.
    int audio_ctx = (int) ((n_samples / 16000.0) * 50.0 + 0.5) + 256;
    if (audio_ctx > 1500) audio_ctx = 1500;
    if (audio_ctx < 256)  audio_ctx = 256;
    wparams.audio_ctx = audio_ctx;

    int rc = whisper_full(ctx, wparams, samples, (int) n_samples);
    env->ReleaseFloatArrayElements(pcm16k, samples, JNI_ABORT);

    if (wc->cancel.load()) {
        return env->NewStringUTF(""); // cancelled by user: return what we have (nothing)
    }
    if (rc != 0) {
        LOGE("whisper_full failed rc=%d", rc);
        throw_java(env, "whisper_full failed");
        return nullptr;
    }

    std::string result;
    int n_segments = whisper_full_n_segments(ctx);
    // A2 (silence-conditional suppression, ref arXiv 2501.11378): whisper's own no-speech drop fires
    // only when no_speech_prob is high AND avg_logprob is low, so a *confident* silence hallucination
    // ("Bye.", "I'm", "Thank you") still leaks through. Gate on no_speech_prob alone — if not a single
    // segment looks like real speech, the whole result is a silence/noise hallucination: return empty.
    bool any_speech = false;
    for (int i = 0; i < n_segments; ++i) {
        if (whisper_full_get_segment_no_speech_prob(ctx, i) < 0.6f) any_speech = true;
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text != nullptr) {
            result += text;
        }
    }
    if (n_segments > 0 && !any_speech) {
        LOGI("suppressed no-speech hallucination across %d segment(s)", n_segments);
        return env->NewStringUTF("");
    }

    return env->NewStringUTF(result.c_str());
}

// ISO code of the language whisper detected on the last run (used for "auto" so downstream Chinese
// simplified/traditional conversion still works). Reads the context's stored auto-detect result.
extern "C" JNIEXPORT jstring JNICALL
Java_com_whispertflite_engine_WhisperCpp_nativeDetectedLang(JNIEnv *env, jclass, jlong ctxPtr) {
    auto *wc = reinterpret_cast<WhisperCtx *>(ctxPtr);
    if (wc == nullptr || wc->ctx == nullptr) return env->NewStringUTF("");
    whisper_context *ctx = wc->ctx;
    int id = whisper_full_lang_id(ctx);
    const char *code = id >= 0 ? whisper_lang_str(id) : nullptr;
    return env->NewStringUTF(code ? code : "");
}

extern "C" JNIEXPORT void JNICALL
Java_com_whispertflite_engine_WhisperCpp_nativeCancel(JNIEnv *, jclass, jlong ctxPtr) {
    auto *wc = reinterpret_cast<WhisperCtx *>(ctxPtr);
    if (wc != nullptr) wc->cancel.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_whispertflite_engine_WhisperCpp_nativeRelease(JNIEnv *, jclass, jlong ctxPtr) {
    auto *wc = reinterpret_cast<WhisperCtx *>(ctxPtr);
    if (wc != nullptr) {
        if (wc->ctx != nullptr) whisper_free(wc->ctx);
        delete wc;
    }
}
