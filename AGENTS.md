# Project Agent Instructions

Shared guidance for AI coding agents working in this repository.

## Project Overview

**CaraML** — KMP app for on-device AI inference, targeting Android, iOS, Desktop (JVM). Integrates llama.cpp (GGUF chat), ONNX Runtime (supported text bundles), and stable-diffusion.cpp (image generation). Hugging Face Hub provides model discovery and downloads. See `docs/models.md` for supported formats and current limits.

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
- **`:plugin-llama`**, **`:plugin-onnx`**, **`:plugin-diffusion`** - Engine adapters and their platform bridges
- **`:core-api`**, **`:core`**, **`:storage`**, **`:native-runtime`** - SDK contracts, lifecycle, storage and native ownership
- **`:nativeEngine`** - CMake build orchestration for native C/C++ libs; produces static libs for iOS + shared libs for JVM

## Architecture

### Source Sets
Shared logic in `commonMain`. Platform-specific: `expect`/`actual` with `androidMain`, `iosMain`, `jvmMain`.

### Packages
Use `com.debanshu777.caraml` for all first-party Kotlin packages and mirror declarations in source directories.
App code stays under `core`/`features`; reusable libraries use `sdk.api`, `sdk.core`, `sdk.storage`, `sdk.runtime`, `sdk.discovery`, and `sdk.plugin.{llama,onnx,diffusion}`.
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
- **Inference**: `InferenceRepository` interface with `LlamaInferenceRepository`, `OnnxInferenceRepository`, and `DiffusionInferenceRepository` implementations (`core/data/inference/`)

### Native Integration
- **iOS**: cinterop `.def` files linking to merged static libraries; linker flags for Metal/Accelerate frameworks
- **Android/JVM**: JNI bindings loaded via `System.loadLibrary()`
- **Git submodules**: `libraries/llama.cpp`, `libraries/stable-diffusion.cpp`

### Key Data Flows
- **Inference**: `ChatViewModel` -> `GenerateResponseUseCase` -> `InferenceRepository` -> `LlamaRunner` (native) -> token `Flow<String>`
- **Downloads**: `ModelViewModel` -> `DownloadManager` -> Ktor HTTP -> `Flow<DownloadProgressDTO>` -> `LocalModelRepository` insert

## Key Technical Details

- **Build JDK**: 21+. Read dependency versions from `gradle/libs.versions.toml`; avoid copying version tables into this guide.
- **Android SDK and bytecode targets**: read the relevant module's `build.gradle.kts`; platform setup is documented in `docs/getting-started.md`.
- **Ktor** for HTTP (OkHttp on Android, Darwin on iOS, CIO on JVM)
- `NativeRunnerConfig` adapts inference parameters (threads, GPU layers, context size, KV cache quantization) based on `DeviceCapabilities`
- Platform entry points: `MainActivity.kt` (Android), `main.kt` (JVM), `MainViewController.kt` (iOS)
- Native builds require CMake, full JDK with JNI headers (desktop), macOS for iOS targets
- Gradle JVM args: `-Xmx6144M`, Kotlin daemon: `-Xmx2048M` (configured in `gradle.properties`)

## Documentation and README Update Policy

**Every change set that modifies code, tests, build configuration, dependencies, or developer tooling must include documentation and README updates.** Do this as part of the same task and commit/PR, without waiting for a separate user request. A code change is not complete until its documentation is reconciled with the final implementation.

Before handing off a change:

1. Review the final diff and identify its user-facing and developer-facing effects, including changed behavior, defaults, APIs, setup commands, platform support, and limitations.
2. Update the root `README.md`: revise affected explanations/examples and add or replace a concise `## Recent Changes` entry describing the completed change.
3. Update the relevant `docs/` pages using the map below. For internal refactors or test-only changes, document the developer impact and verification in `docs/development.md`; do not invent user-visible features or performance claims.
4. Update each affected module's README, including its `## Recent Changes` section. Discover these from the changed paths rather than relying on a fixed list. This includes app, SDK, engine, discovery, storage, native-runtime, native-build, and benchmark READMEs when their areas change. Leave unrelated module READMEs alone.
5. Keep commands, paths, links, examples, support matrices, and limitations consistent across those files. Replace obsolete guidance; keep Recent Changes concise instead of accumulating a session log. Link to version catalogs and benchmark evidence rather than duplicating values or claiming unmeasured improvements.
6. Update `AGENTS.md` when the change alters project structure, conventions, build/test commands, or agent workflow. `CLAUDE.md` imports this file; keep shared rules here.

| Change | Documentation to update |
| --- | --- |
| Features, platforms, or product behavior | `docs/index.md`, relevant guide, root/app README |
| Setup, dependencies, configuration, or build commands | `docs/getting-started.md`, `docs/troubleshooting.md`, affected module README |
| Models, formats, engines, or inference limits | `docs/models.md`, engine/module README |
| SDK APIs, module boundaries, or data flows | `docs/architecture.md`, affected SDK/module README |
| Tests, refactors, CI, or contributor/agent workflow | `docs/development.md`, `CONTRIBUTING.md` when contributor steps change, affected README |
| Performance or benchmark tooling | `docs/benchmarks.md`, relevant benchmark README, measured evidence when available |
| UI flows, demo, screenshots, or artwork | `docs/demo.md`, affected guides/media and capture notes; use actual app captures for app behavior |
| Documentation tooling or publishing | `docs/maintaining-docs.md` |

After installing `tools/docs/requirements.txt`, validate documentation changes from the repository root:

```bash
python -m mkdocs build --strict
python tools/docs/check_site.py
git diff --check
```

Review the rendered pages when layout or media changes. In the final response or PR description, identify the documentation updated and the checks actually run; report any checks that could not run. Do not substitute a promise to update docs later for the required edits, or mark a code change as needing no documentation update.
