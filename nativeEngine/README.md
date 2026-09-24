# nativeEngine

Central native build orchestration module for CaraML. One CMake graph per platform builds **GGML** (from `libraries/llama.cpp`), **llama.cpp**, and **stable-diffusion.cpp**, producing shared or static libraries consumed by `:runner` and `:diffusionRunner`.

---

## What This Module Does

1. **Prepares a patched copy** of llama.cpp without mutating the pinned submodule (`preparePatchedLlamaSource`)
2. **Invokes CMake** per platform with the right toolchain and flags
3. **Merges static libs** for iOS (single `libllama_runner_merged.a` per arch)
4. **Exports layout constants** (`CaramlNativeLayout`) so `:composeApp` and `:runner` know where to find the built libraries

This module has **no Kotlin source code**. All functionality is in `build.gradle.kts` and CMake files.

---

## Output Layout

```
nativeEngine/build/
├── llama-runner-ios/
│   ├── iosArm64/Release/           libllama_runner_merged.a
│   └── iosSimulatorArm64/Release/  libllama_runner_merged.a
└── llama-runner-desktop/
    ├── macos/                       libllama_runner.dylib, libdiffusion_runner.dylib
    └── linux/                       libllama_runner.so, libdiffusion_runner.so

# Android: output via AGP externalNativeBuild → androidApp APK
composeApp/src/androidMain/jniLibs/arm64-v8a/
    llama_runner.so
    diffusion_runner.so
```

If you change these paths, update `:composeApp` linker flags and `java.library.path` accordingly.

---

## CMake Structure

```
src/
├── commonCpp/
│   └── GgmlUnified.cmake           # Master script: builds GGML once, shared by both runners
├── androidMain/cpp/
│   └── CMakeLists.txt              # Android: ARM64 + Vulkan, creates .so files
├── iosMain/cpp/
│   └── CMakeLists.txt              # iOS: Metal, creates .a files
└── jvmMain/cpp/
    └── CMakeLists.txt              # Desktop: macOS (Metal) + Linux (CPU), creates .dylib/.so
```

### GgmlUnified.cmake

Builds GGML **once** from `libraries/llama.cpp`, then both `llama.cpp` and `stable-diffusion.cpp` reuse it:

```cmake
if (NOT TARGET ggml)
    add_subdirectory(${LLAMA_CPP_DIR} ...)
endif()
# Both runners link: ggml ggml-cpu [ggml-metal|ggml-vulkan]
```

This prevents duplicate symbol errors from linking two independent GGML builds.

### Android CMakeLists.txt

- Detects `ANDROID_ABI` (arm64-v8a, x86_64)
- Configures `GGML_SYSTEM_ARCH`, KleidiAI, OpenMP
- Auto-detects Vulkan via NDK `glslc`; manages Vulkan headers
- JNI headers from `JAVA_HOME`
- Output: `llama_runner.so`, `diffusion_runner.so`
- Enable Vulkan: `-PENABLE_VULKAN_ANDROID=true` Gradle flag

### iOS CMakeLists.txt

- Enables `SD_METAL` for Metal GPU
- Disables Vulkan
- Includes `LlamaRunnerCommon.cmake` + `DiffusionRunnerCommon.cmake` from respective modules
- Output: `libllama_runner.a`, `libdiffusion_runner.a` (merged by Gradle)

### Desktop CMakeLists.txt

- Detects `APPLE` for Metal config
- JNI headers from `JAVA_HOME`
- Output: `libllama_runner.dylib` (macOS), `libllama_runner.so` (Linux), same for diffusion

---

## Gradle Tasks

| Task | Description |
|------|-------------|
| `preparePatchedLlamaSource` | Recreate a build-owned llama.cpp source tree and apply numbered patches |
| `compileLlamaRunnerDesktop` | Build desktop shared libs via CMake |
| `verifyNativePreflightFixtures` | Strictly acquire/generate digest-addressed native fixtures under fixed HTTPS hosts and decoded-size caps |
| `mergeLlamaRunnerStaticIosArm64` | Merge iOS arm64 `.a` files via `libtool -static` |
| `mergeLlamaRunnerStaticIosSimulatorArm64` | Merge iOS simulator arm64 `.a` files |

Android native build is triggered automatically by AGP `externalNativeBuild` during `:composeApp:assemble*`.

---

## Patch System

Active llama.cpp patches live under `libraries/patches/llama.cpp/` as numbered `.patch` files:

```
libraries/patches/
└── llama.cpp/
    ├── 0001-metal-pin-shading-language-version.patch
    ├── 0002-vulkan-norm-require-f32.patch
    └── 0003-fit-memory-probe-raii.patch
```

`preparePatchedLlamaSource` recreates `nativeEngine/build/patched-native-sources/llama.cpp` from the pinned upstream tree, then applies each patch with `git apply`. Submodule worktrees stay immutable, so a submodule bump requires only checking that this task still succeeds.

---

## Adding a New GGML-Based Runner

1. Add upstream sources as a git submodule under `libraries/`
2. Extend `GgmlUnified.cmake` to `add_subdirectory()` the new project after GGML is built
3. Add `add_library(new_runner …)` in each platform `CMakeLists.txt`
4. On iOS, append new static libs to the `libtool -static` merge task
5. Create a new KMP module (e.g. `:newRunner`) with Kotlin + JNI/cinterop glue referencing source files in this module via absolute paths

## Non-GGML Native Code

Create a **separate** Gradle module + CMake project. Do not add here unless it must link against GGML objects.

---

## Recent Changes

<!-- Updated at end of each Claude Code session -->

- Desktop static dependencies now build as position-independent code for Linux shared-library linking, the Android-root fixture uses the host temporary directory, and Windows directory creation uses best-effort metadata flushing when supported while preserving pinned no-reparse checks
- Native builds pin llama.cpp `f46bc30` and stable-diffusion.cpp `c92d73c`, compile both runners against one patched llama GGML tree, and verify the exact public gitlinks before project checks
- Android's Vulkan build gates Intel Xe cooperative-matrix shaders on `glslc` capability; arm64-v8a/x86_64 APK packaging and iOS device/simulator one-GGML archive merges pass locally
- Secure artifact storage now supports root-pinned append-only reopening for resumable transfers, with native regression coverage for concatenation and symlink/root replacement rejection
- Android `artifact_fs` now opens the trusted app-owned models root directly instead of traversing `/`; a host regression covers search-only ancestors and final-root symlink rejection
- Added a strict versioned native-fixture manifest, fixed-host HTTPS acquisition with redirect/size/digest enforcement, generated corrupt fixtures, and isolated opt-in runner parity CI; normal JVM verification performs no fixture download
- Desktop native hardening now verifies bounded typed calibration inputs, token-owned lock-free cancellation, and timed operation admission; iOS builds export the same calibrated backend bridge
- `verifyProject` now builds and executes all five stable-diffusion native preflight regressions through CTest, including the production `sd_ctx_params_t.backend` assignment path, source-bound bundle subdivisions, external TAESD placement, and component-selected Vulkan safety
- Stable-diffusion native builds now expose bounded metadata-only preflight and use the same pinned backend-fit resolver for preflight and actual context creation
- Desktop native tests cover bundled-role placement, pinned max-VRAM assignment, effective streaming constraints, and failure-atomic context publication
- Desktop native verification now covers stream-scoped unload exclusion, cross-thread token calls, lock-free cancellation, exact quantization labels, and transient-handle cleanup
- Desktop builds now find CMake through validated explicit/PATH executables and place and assert `artifact_fs` at one configuration-independent path before packaging and installed-image smoke tests
- The bounded `artifact_fs` JNI target supplies strict UTF-8 POSIX `openat` and Windows no-reparse operations, creates fixed roots durably from a pinned platform parent, and is packaged into Desktop application images and Android APKs
- Native bridge verification now covers Android arm64/x86_64 and iOS simulator builds; app-owned JNI/iOS exports contain C++ exceptions before they cross language boundaries
- Stable-diffusion native config now carries stable CPU/Metal/Vulkan/CUDA integers across desktop JNI and iOS FFI; production resolution assigns that exact runtime to `sd_ctx_params_t.backend`
- Fix: ggml-vulkan `supports_op` for `GROUP_NORM`/`NORM` now requires F32 type, preventing `GGML_ABORT` crash when SD2 models load with F16 weights on Vulkan
- `GgmlUnified.cmake` now builds both llama.cpp and stable-diffusion.cpp from single GGML
- Vulkan autodetect via NDK glslc (Android arm64)
- GPU acceleration enabled: Vulkan (Android), Metal (iOS/macOS)
- Numbered llama.cpp patches are applied only to a shared build-owned source copy used by Desktop, Android, and iOS builds
- `GGML_MAX_NAME` compatibility fix patch for stable-diffusion.cpp
