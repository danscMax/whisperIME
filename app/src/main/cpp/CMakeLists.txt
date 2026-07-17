cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Always optimize the native code, even in the debug APK variant. Without this, assembleDebug
# builds ggml/whisper at -O0 and inference is 10-30x slower (base model ~70s/chunk on-device).
# Directory-scoped, so it must precede add_subdirectory(whisper.cpp) below.
add_compile_options(-O3 -ffast-math -fno-finite-math-only -DNDEBUG)

# whisper.cpp submodule lives at repo_root/native/whisper.cpp
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/whisper.cpp)

# Trim whisper.cpp to just the library for a mobile build.
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)  # NDK has no libgomp; whisper uses its own threading
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)  # cross-compile: never probe host CPU
set(GGML_CCACHE            OFF CACHE BOOL "" FORCE)

# Runtime CPU dispatch. Without this ggml compiles a single baseline-ARMv8.0 CPU backend: the
# int8-dotprod and fp16 matmul kernels are #ifdef-gated on CPU features and get compiled out, so
# every Q5/F16 matmul runs the scalar fallback while TFLite (ruy) picks dotprod at runtime — that
# is the whole reason whisper.cpp was slower. ALL_VARIANTS builds one .so per feature tier
# (android_armv8.0, android_armv8.2+dotprod, +fp16, +i8mm, ...) and the backend registry dlopen's
# and scores the best one for the actual device — fast on modern SoCs, still runs on pre-2017 ARM.
# Requires shared libs + backend-DL; the backends are loaded from the app's lib dir by WhisperCpp
# (get_executable_path() points at /system/bin on Android, so the default search never finds them).
#
# 64-bit only: the ALL_VARIANTS Android backends are aarch64 feature tiers and won't compile on the
# 32-bit armeabi-v7a target (armv8 intrinsics on an armv7 march). armv7 has no dotprod anyway, so it
# keeps the single static backend. WhisperCpp's load-from-path is a harmless no-op there.
if(NOT ANDROID_ABI STREQUAL "armeabi-v7a")
    set(BUILD_SHARED_LIBS      ON  CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL        ON  CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS  ON  CACHE BOOL "" FORCE)
endif()

add_subdirectory(${WHISPER_DIR} ${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp)

add_library(whisper_jni SHARED whisper_jni.cpp)

find_library(log-lib log)
target_link_libraries(whisper_jni whisper ${log-lib})
