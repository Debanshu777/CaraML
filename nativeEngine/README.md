# nativeEngine

Central native build orchestration module for CaraML. One CMake graph per platform builds **GGML** (from `libraries/llama.cpp`), **llama.cpp**, and **stable-diffusion.cpp**, producing shared or static libraries consumed by `:runner` and `:diffusionRunner`.

---

## What This Module Does

1. **Applies patches** to vendored submodules before compilation (`applyNativePatches`)
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

# Android: output via AGP externalNativeBuild → composeApp APK
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
| `applyNativePatches` | Apply `.patch` files from `libraries/patches/<submodule>/` (idempotent via git am) |
| `revertNativePatches` | Revert all patches (run before bumping submodule SHA) |
| `compileLlamaRunnerDesktop` | Build desktop shared libs via CMake |
| `mergeLlamaRunnerStaticIosArm64` | Merge iOS arm64 `.a` files via `libtool -static` |
| `mergeLlamaRunnerStaticIosSimulatorArm64` | Merge iOS simulator arm64 `.a` files |

Android native build is triggered automatically by AGP `externalNativeBuild` during `:composeApp:assemble*`.

---

## Patch System

Patches live under `libraries/patches/<submodule>/` as numbered `.patch` files:

```
libraries/patches/
├── llama.cpp/
│   ├── 0001-fix-chat-template.patch
│   └── 0002-metal-compat.patch
└── stable-diffusion.cpp/
    └── 0001-ggml-max-name.patch
```

Patches are applied via `git am` inside each submodule directory. `applyNativePatches` is idempotent — already-applied patches are skipped. Always run `revertNativePatches` before updating submodule SHAs to avoid conflicts.

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

- Fix: SD Vulkan SIGABRT on Android — `SD_VULKAN` decoupled from `GGML_VULKAN` in Android `CMakeLists.txt`; `SD_VULKAN=OFF` means diffusion_runner is compiled without `SD_USE_VULKAN` so ggml-vulkan is not linked into it; `GGML_VULKAN=ON` is preserved for llama_runner (LLM inference); the `if(SD_VULKAN …)` guard on lines 156-170 now correctly prevents `SD_USE_VULKAN` from being defined when Vulkan is disabled for diffusion
- Fix: Vulkan-Android image-gen crash — diffusion runner now pins CLIP + VAE to the CPU backend when a Vulkan device is present (works around missing F16 softmax/norm pipelines in ggml-vulkan on Adreno/Mali); diffusion UNet still runs on Vulkan
- Fix: ggml-vulkan `supports_op` for `GROUP_NORM`/`NORM` now requires F32 type, preventing `GGML_ABORT` crash when SD2 models load with F16 weights on Vulkan
- `GgmlUnified.cmake` now builds both llama.cpp and stable-diffusion.cpp from single GGML
- Vulkan autodetect via NDK glslc (Android arm64)
- GPU acceleration enabled: Vulkan (Android), Metal (iOS/macOS)
- Patch system: `applyNativePatches` / `revertNativePatches` tasks
- `GGML_MAX_NAME` compatibility fix patch for stable-diffusion.cpp
