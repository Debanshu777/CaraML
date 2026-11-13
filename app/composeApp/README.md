# composeApp

Shared Compose Multiplatform app code targeting **Android**, **iOS (arm64 + simulator)**, and **Desktop (JVM)**. Contains UI, ViewModels, repositories, DI and platform implementations. The sibling platform hosts own launchers and final packaging; see [app structure and commands](../README.md).

---

## Platforms

| Target | Entry Point |
|--------|-------------|
| Android | `../androidApp/src/main/…/MainActivity.kt` + `CaraMLApplication.kt`; shared actuals in `src/androidMain` |
| iOS | `src/iosMain/…/MainViewController.kt`, used from the sibling `../iosApp/` Xcode project |
| JVM Desktop | `../desktopApp/src/main/…/main.kt`; shared actuals in `src/jvmMain` |

---

## Package Structure

```
com.debanshu777.caraml/
├── App.kt                        # Root Composable (NavHost entrypoint)
├── core/
│   ├── benchmark/                # BenchmarkTestSuite, BenchmarkUtils
│   ├── data/
│   │   ├── Inference/            # InferenceRepository interface + impls
│   │   │   ├── InferenceRepository.kt
│   │   │   ├── LlamaInferenceRepository.kt
│   │   │   └── DiffusionInferenceRepository.kt
│   │   ├── settings/             # SettingsRepository, DefaultSettingsRepository
│   │   └── theme/                # ThemeRepository, DefaultThemeRepository
│   ├── di/                       # Koin: AppModule.kt, KoinInit.kt
│   ├── domain/                   # ModelReadinessReconciler
│   ├── drawer/                   # Animated navigation drawer components
│   ├── navigation/               # AppScreen (sealed routes), AppNavigation
│   ├── platform/                 # expect interfaces: DeviceCapabilities, PlatformLog, PlatformPaths
│   ├── settings/                 # DataStore interface, AppSettings
│   ├── storage/
│   │   ├── localmodel/           # LocalModelEntity, LocalModelDao, LocalModelRepository, GgufHeaderReader
│   │   ├── component/            # DownloadedComponentEntity, ComponentRepository (multi-file diffusion)
│   │   └── AppDatabase.kt        # Room database definition
│   ├── theme/                    # CaraMLTheme, ThemeViewModel, tokens (Typography, Shapes, Spacing, Motion)
│   └── ui/graphics/              # DecodeImageBitmap (expect/actual)
└── features/
    ├── chat/                     # LLM + diffusion inference UI
    ├── modelhub/                 # HuggingFace model browse/download UI
    └── settings/                 # App settings UI
```

---

## Features

### chat

LLM and diffusion inference chat interface.

**Key files:**
- `ChatViewModel.kt` — inference orchestration, model lifecycle, state management
- `ChatUiState.kt` — UI state sealed class
- `GenerateResponseUseCase.kt` — routes to LLM or diffusion based on selected model type
- `GenerateResponseUseCase.kt` — streaming `Flow<String>` token consumption
- `LocalModelGenerationClassifier.kt` — classifies model type from metadata
- `ReasoningModelClassifier.kt` — detects thinking/reasoning model variants
- `ManageContextUseCase.kt` — context window management, truncation
- `ChatScreen.kt` — main chat Composable
- `MessageBubble.kt` — markdown-rendered message display
- `ChatMarkdownTypography.kt` — Material3 typography mapped to markdown styles
- `GenerationStatsBar.kt` — live tokens/sec, TTFT, context % display

**Inference flow:**
```
ChatViewModel.sendMessage()
  → GenerateResponseUseCase.invoke()
    → LlamaInferenceRepository / DiffusionInferenceRepository
      → LlamaRunner / DiffusionRunner (native)
        → token Flow<String> → UI state update
```

**Reasoning / thinking blocks:**
Responses from reasoning models are parsed into `<thinking>…</thinking>` + answer segments and rendered separately in the bubble.

---

### modelhub

HuggingFace Hub integration for model discovery and download.

**Key files:**
- `ModelViewModel.kt` — search, filter (pipeline tag, sort), download orchestration
- `DownloadedModelsViewModel.kt` — locally stored model management
- `SearchScreen.kt` — search bar + model list
- `DetailsScreen.kt` — model detail + GGUF files / complete ONNX bundle variants
- `InstallBundleCard.kt` — smart install for diffusion model component bundles
- `GgufFileListItem.kt` — per-file download with live progress
- `ModelHubBrowseMode.kt` — LLM vs diffusion browse mode

**Download flow:**
```
ModelViewModel.downloadModel()
  → DownloadManager.download() (platform-specific Ktor)
    → Flow<DownloadProgressDTO> → UI progress
      → LocalModelRepository.insert()
        → Room DB
```

Multi-component diffusion models use `ComponentRepository` to track individual file downloads (VAE, CLIP, UNet, etc.) linked to a parent model entry.

---

### settings

App preferences: theme mode (light/dark/system), color palette style, other inference defaults.

**Key files:**
- `SettingsViewModel.kt` — reads/writes via `SettingsRepository` + `ThemeRepository`
- `AppearanceSection.kt` — theme picker UI

---

## Core Infrastructure

### Dependency Injection (Koin)

- `AppModule.kt` — common module: repositories, use cases, ViewModels
- `AppModule.android.kt / ios.kt / jvm.kt` — platform-specific bindings (DataStore, DB driver, DeviceCapabilities)
- `KoinInit.kt` — `startKoin` wrapper called from each platform entry point

### Navigation (Navigation3)

Sealed class `AppScreen` defines all routes. `AppNavigation.kt` builds the `NavHost`. `AppDrawerShell` keeps navigation sidebar-first on every platform: a modal left panel below 600dp, a compact rail from 600–839dp, and a persistent labeled sidebar from 840dp.

### Database (Room)

`AppDatabase` holds:
- `LocalModelDao` / `LocalModelEntity` — downloaded model records (path, size, type, GGUF metadata)
- `DownloadedComponentDao` / `DownloadedComponentEntity` — individual diffusion component files
- `ModelComponentLinkEntity` — many-to-many link between model + components

`GgufHeaderReader` parses GGUF file headers to extract metadata (quantization, layer count, context length) without loading the model.

### Theming

Full Material You implementation via materialKolor:
- `CaraMLTheme` — wraps `MaterialTheme` with dynamic color derived from seed color
- `ThemeViewModel` — persists theme prefs via `ThemeRepository` (DataStore)
- `ThemeMode` — LIGHT / DARK / SYSTEM
- `ThemePaletteStyle` — seed color style variants
- Design tokens: `AppTypography`, `AppShapes`, `AppSpacing`, `AppMotionScheme`

### Platform Abstractions (expect/actual)

| Interface | Purpose |
|-----------|---------|
| `DeviceCapabilities` | CPU count, RAM, GPU type → `NativeRunnerConfig` |
| `PlatformLog` | Logging to platform console |
| `PlatformPaths` | App data directory for model file storage |
| `DataStore` | DataStore instance creation |
| `DecodeImageBitmap` | Decode PNG/JPEG bytes to `ImageBitmap` |
| `TokenTimer` | High-precision token timing |

---

## Tests

```
commonTest/
├── benchmark/BenchmarkUtilsTest.kt
├── core/rating/
│   ├── DiffusionStepPolicyTest.kt
│   ├── ModelSuitabilityCalculatorTest.kt
│   ├── SdArchitectureClassifierTest.kt
│   └── SdArchitectureTest.kt
└── features/chat/domain/
    └── LocalModelGenerationClassifierTest.kt
```

Run: `./gradlew :app:composeApp:jvmTest`

---

## Device-Aware Recommendation Rollout

The release build remains in `LEGACY` mode. V2 assessment is available for shadow verification, but it is not yet the sole displayed category source because the required measured Android/iOS/Desktop device matrix and pinned benchmark-runner result have not been recorded. Do not remove the legacy calculator or rollout bridge until those gates are reviewed.

Profiles change plan selection without changing objective compatibility evidence. Risk tolerance reserves 25% (`CONSERVATIVE`), 15% (`BALANCED`, the default), or 5% (`EXPERIMENTAL`) of reliable memory. Optimization priority favors speed/efficiency, a balanced mix, or quality/context. Categories mean: `RECOMMENDED` has comfortable supported headroom; `USABLE` is expected to fit with less margin or a compromise; `RISKY` needs explicit acknowledgement; `NOT_SUITABLE` has no acceptable resource plan; `INCOMPATIBLE` is a hard engine/format mismatch; and `NEEDS_INFORMATION` means bounded trustworthy evidence is missing.

All repository IDs, revisions, relative paths, component counts, byte sizes, model shapes, workload dimensions, evidence, and native records are bounded before use. Unknown, inconsistent, duplicate, overflowing, or stale metadata fails closed instead of being guessed. Selection carries the exact assessed descriptor, recommendation, run plan, and verified local bytes into just-in-time native preflight; it never reconstructs identity from a database filename.

Calibration is local-only and numeric. It is stored in the disposable sibling `recommendation_cache.db`, never uploads prompts, paths, repository IDs, or user content, and cannot delete or migrate `caraml.db`. A schema mismatch or corrupt recommendation cache may reset that cache and rebuild conservative estimates; model/download records remain intact. Profile-only reranking stays cached and performs no metadata, filesystem, calibration, or native work on the Compose main thread.

Verification commands:

```bash
./gradlew :app:composeApp:jvmTest --tests '*Recommendation*' --tests '*ModelFitFixture*'
./gradlew :app:composeApp:jvmTest --tests '*RecommendationPerformanceTest*'
CARAML_NATIVE_PARITY=true ./gradlew :plugin-llama:jvmTest :plugin-diffusion:jvmTest
```

The performance budgets are enforced only when both `CARAML_ENFORCE_RECOMMENDATION_PERF=true` and the documented bounded `CARAML_BENCHMARK_RUNNER_ID` are supplied on the pinned runner. Ordinary JVM runs report timing but are not release evidence.

---

## Recent Changes

- Made model selection and loading a single transition, fenced cancelled detail requests, and applied inference settings edits inside DataStore transactions. Cancelled media rolls back if its chat turn loses ownership. Download startup reconciles interrupted pause and resume commands, and platform scheduling failures leave queued transfers retryable. Llama retains ownership when unload throws.
- Corrected ONNX resident-session fixtures to use `<models>/owner/onnx` and clean up the whole temporary tree, fixing Linux CI loads; JVM test logs now include full failure details.
- Added SD-Turbo GGUF discovery, fixed nested-checkpoint installation, and kept image/video models visible alongside frequently used chat models.
- Added [platform setup](../../docs/getting-started.md), [contribution guidance](../../CONTRIBUTING.md), and a [real-device demo](../../docs/demo.md).
- Enabled Android Vulkan image plans and model-specific sampling; see [tested models and limits](../../benchmarks/diffusion/README.md).
- Added format-aware GGUF/ONNX discovery, downloads, routing, streaming, and cancellation, with idle same-model session reuse. [Device measurements](../../benchmarks/onnx/README.md).
- Reduced installed-model preparation work and isolated streaming state to the active chat row. [Performance evidence and caveats](../../benchmarks/android/README.md).
