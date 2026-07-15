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

add_subdirectory(${WHISPER_DIR} ${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp)

add_library(whisper_jni SHARED whisper_jni.cpp)

find_library(log-lib log)
target_link_libraries(whisper_jni whisper ${log-lib})
