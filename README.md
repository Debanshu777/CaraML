# CaraML

**On-device AI inference for Android, iOS, and Desktop (JVM)**

CaraML runs large language models and image generation models entirely on-device using [llama.cpp](https://github.com/ggerganov/llama.cpp) and [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp). Models are browsed and downloaded directly from HuggingFace Hub. No cloud backend required.

---

## Features

- **LLM Chat** — streaming token generation with structured reasoning support (thinking blocks), markdown rendering, context tracking
- **Image Generation** — Stable Diffusion via stable-diffusion.cpp (text-to-image, configurable sampling)
- **Model Hub** — browse, search, filter, and download GGUF models from HuggingFace; smart install bundles for diffusion models
- **GPU Acceleration** — Vulkan (Android), Metal (iOS/macOS)
- **Dynamic Theming** — Material You / Material 3 with materialKolor
- **Fully Offline** — all inference runs on-device after download

---

## Platforms

| Platform | Status | GPU |
|----------|--------|-----|
| Android  | ✅ | Vulkan (experimental) |
| iOS      | ✅ | Metal |
| Desktop (JVM/macOS) | ✅ | Metal |
| Desktop (JVM/Linux) | ✅ | CPU only |

---

## Module Structure

```
CaraML/
├── composeApp/          # Shared Compose UI + MVVM features
├── runner/              # Kotlin wrapper for llama.cpp (LLM inference)
├── diffusionRunner/     # Kotlin wrapper for stable-diffusion.cpp
├── huggingFaceManager/  # HuggingFace API client + download manager
├── nativeEngine/        # CMake build orchestration for all native libs
├── libraries/
│   ├── llama.cpp/       # Git submodule: ggerganov/llama.cpp
│   ├── stable-diffusion.cpp/ # Git submodule: leejet/stable-diffusion.cpp
│   └── patches/         # Applied patches to vendored libraries
└── iosApp/              # Native iOS Xcode project (SwiftUI wrapper)
```

See each module's own README for details.

---

## Prerequisites

- **JDK 21+** with JNI headers (required for desktop native build)
- **CMake 3.22.1+**
- **Android NDK** (for Android builds)
- **Xcode 16+** and macOS (for iOS builds)
- **Vulkan SDK** + `glslc` in PATH (for Android GPU build only)

---

## Build

### 1. Initialize submodules

```bash
git submodule update --init --recursive
```

### 2. Build native libraries

```bash
# Desktop (required before first run)
./gradlew :nativeEngine:compileLlamaRunnerDesktop

# iOS static libs
./gradlew :nativeEngine:mergeLlamaRunnerStaticIosArm64
./gradlew :nativeEngine:mergeLlamaRunnerStaticIosSimulatorArm64
```

### 3. Build & run

```bash
# Android (debug APK)
./gradlew :composeApp:assembleDebug

# Desktop — build and run directly
./gradlew :composeApp:run

# iOS — open iosApp/ in Xcode
```

### Android with Vulkan GPU (experimental)

```bash
./gradlew :composeApp:assembleDebug -PENABLE_VULKAN_ANDROID=true
```

Requires Vulkan SDK and `glslc` in PATH. Expected 5–8× token-per-second improvement on compatible GPUs (Mali, Adreno). See [`docs/vulkan-android-build-strategy.md`](./docs/vulkan-android-build-strategy.md).

---

## Architecture

### Data Flow — LLM Inference

```
ChatViewModel
  → GenerateResponseUseCase
    → LlamaInferenceRepository
      → LlamaRunner (expect/actual)
        ├── Android/JVM: JNI → llama_runner.so
        └── iOS: cinterop → libllama_runner_merged.a
            → llama.cpp (C++)
```

### Data Flow — Image Generation

```
ChatViewModel
  → DiffusionInferenceRepository
    → DiffusionRunner (expect/actual)
      ├── Android/JVM: JNI → diffusion_runner.so
      └── iOS: cinterop → libllama_runner_merged.a
          → stable-diffusion.cpp (C++)
```

### Data Flow — Model Download

```
ModelViewModel
  → DownloadManager (expect/actual, Ktor HTTP)
    → HuggingFace API
      → Flow<DownloadProgressDTO>
        → LocalModelRepository
          → Room database
```

### Source Set Organization

All shared logic in `commonMain`. Platform-specific code uses `expect`/`actual`:

```
src/
├── commonMain/   # Shared interfaces, business logic, Compose UI
├── androidMain/  # Android platform impls
├── iosMain/      # iOS cinterop impls
└── jvmMain/      # Desktop JVM impls
```

### Feature Organization (MVVM)

```
features/{feature}/
├── data/         # Models, repositories
├── domain/       # Business logic, use cases
└── presentation/ # ViewModels, Composables, components
```

Three features: **chat**, **modelhub**, **settings**.

---

## Tech Stack

| Layer | Library / Version |
|-------|-------------------|
| Language | Kotlin 2.3.10 |
| UI | Compose Multiplatform 1.10.1 |
| DI | Koin 4.2.0-RC1 |
| HTTP | Ktor 3.4.1 |
| Database | Room 2.8.4 |
| Preferences | DataStore 1.1.7 |
| Navigation | Navigation3 1.0.0-alpha06 |
| Theming | Material3 1.10.0-alpha05 + materialKolor 4.1.1 |
| Coroutines | kotlinx-coroutines 1.10.2 |
| Build | AGP 8.13.2, KSP 2.3.5 |
| Android SDK | minSdk 28, compileSdk 36 |
| iOS | min 17.2 |
| JVM target | 21 |
| Inference | llama.cpp (latest), stable-diffusion.cpp (latest) |

---

## Tests

```bash
./gradlew :composeApp:allTests    # All platform tests
./gradlew :composeApp:jvmTest     # JVM tests only
```

Test coverage includes: `BenchmarkUtils`, `LocalModelGenerationClassifier`, `ReasoningModelClassifier`.

---

## Key Concepts

### NativeRunnerConfig

Adapts inference parameters (thread count, GPU layers, context size, KV cache quantization) based on `DeviceCapabilities` — each platform reports its own CPU count, RAM, GPU type.

### GgmlUnified.cmake

Single CMake script builds GGML once from `libraries/llama.cpp`, then both `llama.cpp` and `stable-diffusion.cpp` share it (guarded by `if (NOT TARGET ggml)`). Prevents duplicate symbol errors from linking two GGML builds.

### Patch System

Patches under `libraries/patches/<submodule>/` are applied before native compilation (`applyNativePatches` Gradle task) and reverted cleanly before submodule bumps (`revertNativePatches`).

### iOS Static Lib Merge

iOS requires a single merged `.a` archive (Metal, Accelerate, and GGML frameworks don't support dynamic linking on iOS). The `mergeLlamaRunnerStaticIosArm64` Gradle task runs `libtool -static` to produce `libllama_runner_merged.a`.

---

## Recent Changes

<!-- This section is updated at the end of each Claude Code session -->

- GPU acceleration and inference performance optimizations (Vulkan Android, Metal iOS)
- Smart install system and live progress tracking for diffusion models
- Structured reasoning support with thinking blocks and markdown rendering
- Native patch system and improved chat template handling
- Dynamic Material 3 theme system and design tokens
- Diffusion chat window UI (in progress)
