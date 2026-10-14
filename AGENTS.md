# Project Agent Instructions

Shared guidance for AI coding agents working in this repository.

## Project Overview

**CaraML** — KMP app for on-device AI inference, targeting Android, iOS, Desktop (JVM). Integrates llama.cpp (LLM) + stable-diffusion.cpp (image/video). HuggingFace Hub for model discovery + downloads.

## Build Commands

```bash
# Android
./gradlew :app:androidApp:assembleDebug

# Desktop (JVM) - run directly
./gradlew :app:desktopApp:run

# iOS - open app/iosApp/ in Xcode, or build via Gradle

# Native engine builds (required before first run)
./gradlew :nativeEngine:compileLlamaRunnerDesktop          # Desktop native libs
./gradlew :nativeEngine:mergeLlamaRunnerStaticIosArm64     # iOS native libs

# Tests
./gradlew verifyProject                                   # Preferred local/CI JVM gate
./gradlew :app:composeApp:allTests                             # All platform tests
./gradlew :app:composeApp:jvmTest                              # JVM tests only
```

## Module Structure

- **`app/`** - Application group, separate from the reusable SDK
- **`:app:composeApp`** - Shared Compose UI, app logic and platform implementations (Android, iOS arm64/simulator, JVM)
- **`:app:androidApp`** - Android launcher and APK packaging
- **`:app:desktopApp`** - Desktop launcher, hot reload and distribution packaging
- **`app/iosApp`** - Xcode host consuming the shared `ComposeApp` framework
- **`:model-discovery`** - Provider-neutral discovery and Hugging Face API; legacy platform downloads live in `:app:composeApp`
- **`:plugin-llama`**, **`:plugin-diffusion`** - Engine adapters and their Kotlin/JNI/cinterop bridges
- **`:core-api`**, **`:core`**, **`:storage`**, **`:native-runtime`** - SDK contracts, lifecycle, storage and native ownership
- **`:nativeEngine`** - CMake build orchestration for native C/C++ libs; produces static libs for iOS + shared libs for JVM

## Architecture

### Source Sets
Shared logic in `commonMain`. Platform-specific: `expect`/`actual` with `androidMain`, `iosMain`, `jvmMain`.

### Packages
Use `com.debanshu777.caraml` for all first-party Kotlin packages and mirror declarations in source directories.
App code stays under `core`/`features`; reusable libraries use `sdk.api`, `sdk.core`, `sdk.storage`, `sdk.runtime`, `sdk.discovery`, and `sdk.plugin.{llama,diffusion}`.
Provider implementations belong under `sdk.discovery.huggingface`; engine bridges use each plugin's `bridge` subpackage.
Update JNI symbols/descriptors, cinterop definitions and consumer keep rules together when moving bridge packages. Preserve persisted IDs and application/bundle IDs.

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
- `app/README.md`
- `app/composeApp/README.md`
- `model-discovery/README.md`
- `plugin-llama/README.md`
- `plugin-diffusion/README.md`
- `nativeEngine/README.md`

Update only relevant module READMEs. Keep bullets concise. Replace stale entries.
