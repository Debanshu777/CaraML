# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**CaraML** is a Kotlin Multiplatform (KMP) application for on-device AI inference, targeting Android, iOS, and Desktop (JVM). It integrates llama.cpp for LLM inference and stable-diffusion.cpp for image/video generation, with HuggingFace Hub integration for model discovery and downloads.

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
./gradlew :composeApp:allTests                             # All platform tests
./gradlew :composeApp:jvmTest                              # JVM tests only
```

## Module Structure

- **`:composeApp`** - Main multiplatform Compose UI app (Android, iOS arm64/simulator, JVM Desktop)
- **`:huggingFaceManager`** - Multiplatform library: HuggingFace API client, model search/list, download manager with progress tracking
- **`:runner`** - Kotlin wrapper around native llama.cpp inference (expect/actual + JNI/cinterop)
- **`:diffusionRunner`** - Kotlin wrapper around native stable-diffusion.cpp (expect/actual + JNI/cinterop)
- **`:nativeEngine`** - CMake build orchestration for all native C/C++ libraries; produces static libs for iOS and shared libs for JVM

## Architecture

### Source Sets
All shared logic lives in `commonMain`. Platform-specific code uses Kotlin's `expect`/`actual` pattern with corresponding `androidMain`, `iosMain`, `jvmMain` folders.

### Feature Organization (composeApp)
Features follow MVVM under `com.debanshu777.caraml.features.{feature}/`:
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

- **Kotlin**: 2.3.10, **Compose Multiplatform**: 1.10.1, **JVM target**: 21
- **AGP**: 8.13.2, **KSP**: 2.3.5
- **Android SDK**: minSdk 28, compileSdk/targetSdk 36
- **Koin**: 4.2.0-RC1, **Ktor BOM**: 3.4.1, **Room**: 2.8.4, **kotlinx-coroutines**: 1.10.2
- **Navigation3**: 1.0.0-alpha06, **Material3**: 1.10.0-alpha05, **DataStore**: 1.1.7
- **materialKolor**: 4.1.1 (dynamic color theming)
- **Ktor** for HTTP (OkHttp on Android, Darwin on iOS, CIO on JVM)
- `NativeRunnerConfig` adapts inference parameters (threads, GPU layers, context size, KV cache quantization) based on `DeviceCapabilities`
- Platform entry points: `MainActivity.kt` (Android), `main.kt` (JVM), `MainViewController.kt` (iOS)
- Native builds require CMake, full JDK with JNI headers (desktop), and macOS for iOS targets
- Gradle JVM args: `-Xmx6144M`, Kotlin daemon: `-Xmx2048M` (configured in `gradle.properties`)

## README Update Policy

At the **end of every Claude Code session**, update the `## Recent Changes` section in these files to reflect what changed during the session:
- `README.md` (root)
- `composeApp/README.md`
- `huggingFaceManager/README.md`
- `runner/README.md`
- `diffusionRunner/README.md`
- `nativeEngine/README.md`

Only update the module READMEs that are relevant to changes made. Keep bullet points concise. Replace stale entries rather than growing the list unboundedly.

## Claude Model Usage Policy

- **Opus 4.6** — orchestration only (planning, task delegation, high-level decisions)
- **Sonnet 4.6 / Haiku 4.5** — all execution work: research, code generation, web search, file edits, subagent tasks
- Do NOT use Opus for direct code generation or search tasks; delegate those to Sonnet/Haiku agents
