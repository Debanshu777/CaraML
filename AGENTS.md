# Project Agent Instructions

Shared guidance for AI coding agents working in this repository.

## Project Overview

**CaraML** — KMP app for on-device AI inference, targeting Android, iOS, Desktop (JVM). Integrates llama.cpp (LLM) + stable-diffusion.cpp (image/video). HuggingFace Hub for model discovery + downloads.

## Build Commands

```bash
# Android
./gradlew :composeApp:assembleDebug

# Desktop (JVM) - run directly
./gradlew :composeApp:run

# iOS - open iosApp/ in Xcode, or build via Gradle

# Native engine builds (required before first run)
./gradlew :nativeEngine:compileLlamaRunnerDesktop          # Desktop native libs
./gradlew :nativeEngine:mergeLlamaRunnerStaticIosArm64     # iOS native libs

# Tests
./gradlew verifyProject                                   # Preferred local/CI JVM gate
./gradlew :composeApp:allTests                             # All platform tests
./gradlew :composeApp:jvmTest                              # JVM tests only
```

## Module Structure

- **`:composeApp`** - Main multiplatform Compose UI (Android, iOS arm64/simulator, JVM Desktop)
- **`:huggingFaceManager`** - HuggingFace API client, model search/list, download manager with progress tracking
- **`:runner`** - Kotlin wrapper around native llama.cpp inference (expect/actual + JNI/cinterop)
- **`:diffusionRunner`** - Kotlin wrapper around native stable-diffusion.cpp (expect/actual + JNI/cinterop)
- **`:nativeEngine`** - CMake build orchestration for native C/C++ libs; produces static libs for iOS + shared libs for JVM

## Architecture

### Source Sets
Shared logic in `commonMain`. Platform-specific: `expect`/`actual` with `androidMain`, `iosMain`, `jvmMain`.

### Feature Organization (composeApp)
MVVM under `com.debanshu777.caraml.features.{feature}/`:
- `data/` - Data models, repositories
- `domain/` - Business logic, use cases
- `presentation/` - ViewModels, Composable screens and components

Three features: **chat** (LLM/diffusion inference UI), **modelhub** (browse/search/download HuggingFace models), **settings**.

### Core Infrastructure (composeApp)
- **DI**: Koin (`core/di/AppModule.kt` + platform-specific `AppModule.{platform}.kt`)
- **Navigation**: androidx.navigation3 with sealed `AppScreen` routes (`core/navigation/`)
- **Database**: Room with `LocalModelEntity` for downloaded model metadata (`core/storage/`)
- **Preferences**: DataStore (`core/settings/`)
- **Inference**: `InferenceRepository` interface with `LlamaInferenceRepository` and `DiffusionInferenceRepository` implementations (`core/data/Inference/`)

### Native Integration
- **iOS**: cinterop `.def` files linking to merged static libraries; linker flags for Metal/Accelerate frameworks
- **Android/JVM**: JNI bindings loaded via `System.loadLibrary()`
- **Git submodules**: `libraries/llama.cpp`, `libraries/stable-diffusion.cpp`

### Key Data Flows
- **Inference**: `ChatViewModel` -> `GenerateResponseUseCase` -> `InferenceRepository` -> `LlamaRunner` (native) -> token `Flow<String>`
- **Downloads**: `ModelViewModel` -> `DownloadManager` -> Ktor HTTP -> `Flow<DownloadProgressDTO>` -> `LocalModelRepository` insert

## Key Technical Details

- **Kotlin**: 2.4.0, **Compose Multiplatform**: 1.11.1, **build JDK**: 21+
- **Android bytecode targets**: 21 (`composeApp`), 17 (supporting KMP libraries)
- **AGP**: 9.2.1, **KSP**: 2.3.9
- **Android SDK**: minSdk 28, compileSdk/targetSdk 36
- **Koin**: 4.2.2, **Ktor BOM**: 3.5.0, **Room**: 2.8.4, **kotlinx-coroutines**: 1.11.0
- **Navigation3**: 1.1.1, **Material3**: 1.10.0-alpha05, **DataStore**: 1.2.1
- **materialKolor**: 4.1.1 (dynamic color theming)
- **Ktor** for HTTP (OkHttp on Android, Darwin on iOS, CIO on JVM)
- `NativeRunnerConfig` adapts inference parameters (threads, GPU layers, context size, KV cache quantization) based on `DeviceCapabilities`
- Platform entry points: `MainActivity.kt` (Android), `main.kt` (JVM), `MainViewController.kt` (iOS)
- Native builds require CMake, full JDK with JNI headers (desktop), macOS for iOS targets
- Gradle JVM args: `-Xmx6144M`, Kotlin daemon: `-Xmx2048M` (configured in `gradle.properties`)

## README Update Policy

At session end, update `## Recent Changes` in:
- `README.md` (root)
- `composeApp/README.md`
- `huggingFaceManager/README.md`
- `runner/README.md`
- `diffusionRunner/README.md`
- `nativeEngine/README.md`

Update only relevant module READMEs. Keep bullets concise. Replace stale entries.
