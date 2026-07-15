// JNI bridge for com.whispertflite.engine.WhisperCpp
#include <jni.h>
#include <string>
#include <thread>
#include <algorithm>
#include <atomic>
#include <unistd.h>
#include <android/log.h>

#include "whisper.h"

#define LOG_TAG "whisper_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Set by nativeCancel(); polled by whisper_full via the abort callback to stop a slow run early.
static std::atomic<bool> g_cancel{false};

static bool abort_callback(void *) {
    return g_cancel.load();
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

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // no GPU on this build

    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (ctx == nullptr) {
        LOGE("whisper_init_from_file_with_params returned null");
        throw_java(env, "failed to load whisper model");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_whispertflite_engine_WhisperCpp_nativeTranscribe(JNIEnv *env, jclass, jlong ctxPtr,
                                                          jfloatArray pcm16k, jstring lang,
                                                          jboolean translate) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx == nullptr) {
        throw_java(env, "whisper context is null");
        return nullptr;
    }
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
    // Let Java stop a slow run (large models take minutes on mobile CPUs).
    g_cancel.store(false);
    wparams.abort_callback  = abort_callback;
    wparams.abort_callback_user_data = nullptr;

    int rc = whisper_full(ctx, wparams, samples, (int) n_samples);
    env->ReleaseFloatArrayElements(pcm16k, samples, JNI_ABORT);

    if (g_cancel.load()) {
        return env->NewStringUTF(""); // cancelled by user: return what we have (nothing)
    }
    if (rc != 0) {
        LOGE("whisper_full failed rc=%d", rc);
        throw_java(env, "whisper_full failed");
        return nullptr;
    }

    std::string result;
    int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text != nullptr) {
            result += text;
        }
    }

    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_whispertflite_engine_WhisperCpp_nativeCancel(JNIEnv *, jclass) {
    g_cancel.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_whispertflite_engine_WhisperCpp_nativeRelease(JNIEnv *, jclass, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx != nullptr) {
        whisper_free(ctx);
    }
}
