# nativeEngine

Central **native build** module for CaraML: one CMake graph per platform builds GGML (via `libraries/llama.cpp`) and the `llama_runner` JNI/FFI target. Kotlin wrapper APIs stay in `:runner` (and future `:diffusionRunner`).

## Layout constants

Gradle output paths are defined in `build.gradle.kts` as `CaramlNativeLayout`:

- `llama-runner-ios/<sdk>/<kotlinArch>/` — iOS CMake + merged `libllama_runner_merged.a`
- `llama-runner-desktop/<macos|linux|windows>/` — desktop `libllama_runner` shared library

If you change these, update `:composeApp` linker and `java.library.path` logic to match.

## Adding another GGML-based runner (e.g. diffusion)

1. Add upstream sources under `libraries/` (submodule).
2. Extend CMake in this module: either replace `GgmlLlama.cmake` with a unified script that builds GGML once, then `add_subdirectory(llama.cpp)` and the new project (both must support `if (NOT TARGET ggml)`). ✅ Implemented as `GgmlUnified.cmake`.
3. Add a new `add_library(sd_runner …)` in each of `src/androidMain/cpp/CMakeLists.txt`, `jvmMain/cpp/CMakeLists.txt`, `iosMain/cpp/CMakeLists.txt`.
4. On iOS, append the new static libs to the existing `libtool -static` merge task in `build.gradle.kts` (single merged archive).
5. Create a thin KMP module (e.g. `:diffusionRunner`) with Kotlin + glue `.cpp` under its tree; reference those sources from `nativeEngine` CMake via paths under the repo root. Add `implementation(project(":nativeEngine"))` on Android/JVM only if needed for packaging; do **not** add an `iosMain` dependency on `:nativeEngine` from a target that lacks shared `ios` targets unless you use a different Gradle pattern.

## Non-GGML native code

Use a **separate** Gradle module and CMake project (e.g. ONNX-only). Do not add it here unless it must link with the same GGML objects.
