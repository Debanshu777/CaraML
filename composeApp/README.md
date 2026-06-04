# composeApp

Shared Compose Multiplatform UI application targeting **Android**, **iOS (arm64 + simulator)**, and **Desktop (JVM)**. Contains all UI, ViewModels, repositories, and DI configuration.

---

## Platforms

| Target | Entry Point |
|--------|-------------|
| Android | `androidMain/…/MainActivity.kt` + `CaraMLApplication.kt` |
| iOS | `iosMain/…/MainViewController.kt` (used from `iosApp/` Xcode project) |
| JVM Desktop | `jvmMain/…/main.kt` |

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
- `DetailsScreen.kt` — model detail + GGUF file picker
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

Sealed class `AppScreen` defines all routes. `AppNavigation.kt` builds the `NavHost`. Animated drawer (`AnimatedDrawerScaffold`) provides side-panel navigation on all platforms.

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
└── features/chat/domain/
    ├── LocalModelGenerationClassifierTest.kt
    └── ReasoningModelClassifierTest.kt
```

Run: `./gradlew :composeApp:jvmTest`

---

## Recent Changes

<!-- Updated at end of each Claude Code session -->

- `DiffusionInferenceRepository.buildDiffusionModelConfig()` reverted: removed `diffusionConvDirect=true` from both selfContained and component branches (was a failed Vulkan workaround); selfContained branch also had spurious `offloadToCpu` propagation removed
- `DiffusionInferenceRepository.buildDiffusionModelConfig()` selfContained branch now propagates `offloadToCpu` from `recommendedParams` (was missing, so registry `offloadToCpu=true` had no effect)
- `DiffusionInferenceRepository.buildDiffusionModelConfig()` now always passes `diffusionConvDirect=true`; bypasses IM2COL path in ggml-vulkan that aborts when conv kernel type is not F32/F16
- `DiffusionInferenceRepository.buildDiffusionModelConfig()` now propagates `flowShift`, `freeParamsImmediately` (auto-enabled when weights ≥ 65% of memory budget), `taesdPath` (auto-resolves `madebyollin/taesd` when downloaded), and `vaeTiling` (auto-enabled when width × height > 512²)
- `ChatViewModel` image/video send paths now honor registry-pinned `sampleMethod` (via `SampleMethod.fromName`) and `seed` (falls back to current millis when unset)
- New `core/rating/` package: `ModelSuitabilityCalculator` (canonical llama.cpp BPW table, KV cache math with per-architecture shape lookup, HF Accelerate +20% overhead, GPU/CPU modifiers) + `SuitabilityChip`, `SuitabilityDot`, `SuitabilityInfoSheet` UI primitives
- Search list rows now show a color-coded fit chip (Poor/Average/Good/Best); variant picker shows per-variant dots using accurate on-disk size; tap chip opens shared bottom-sheet explainer with footprint breakdown, ratio vs RAM budget, device snapshot, legend, and caveats
- Added `SdArchitecture` enum (SD1/SDXL/SD3/FLUX/WAN_SMALL/WAN_LARGE with Q4 RAM profiles) and `SdArchitectureClassifier` (maps HF tags + model ID segments to architecture, priority: FLUX > SD3 > WAN_LARGE > WAN_SMALL > SDXL > SD1)
- New `ModelSuitabilityCalculator.rateDiffusion(hints, architecture, ...)` overload: weight resolution from totalComponentBytes > arch baseline × BPW ratio > UNKNOWN; VAE offload −400 MB, flash attention −600 MB; GPU bumps DiT archs up, low perf-core count penalizes video archs
- `ModelListItem` now branches on `pipelineTag` — diffusion models use `SdArchitectureClassifier` + `rateDiffusion()` instead of `rateLlm()`
- `ModelDetailContent` overallResult uses diffusion branch for text-to-image/video: sums required component `sizeHint` values from `installBundleState` for accurate byte estimate
- Details screen now shows overall model chip and per-GGUF-file rating dots; chip taps open the shared info sheet (hoisted at screen scope, matches search pattern)
- Fixed stacked footprint bar in `SuitabilityInfoSheet` — children now use `weight(frac) + fillMaxHeight()` instead of `fillMaxWidth(fraction)` so weights/KV/overhead segments render side-by-side at full bar height
- Device card on Models screen extended with rating legend swatches
- Quantization tag regex consolidated into `ModelSuitabilityCalculator.parseQuantTag()` (was duplicated in `VariantPickerRow`); `formatBytesHuman` extracted to `core/rating/ui/Format.kt`
- 15-test unit suite in `commonTest/core/rating/` validates BPW math, bucket boundaries, modifier behavior, and reference scenarios (Llama-3.1-8B, 70B Q4, diffusion bundles)
- `DeviceCapabilities` migrated from Koin-bound interface+impls to `expect class` / `actual class` per platform; Koin binding moved to common `appModule`
- `DeviceHints` now surfaced in Models screen storage card: collapsible "Device" section shows perf cores, total cores, RAM budget, GPU backend, and perf core mask (Android)
- Diffusion inference wired to chat UI (in progress, feature/diffusion-chat-window branch)
- GPU performance tracking in `InferenceMetrics`
- Smart diffusion model install (component bundle tracking)
- Structured reasoning / thinking block parsing and rendering
- Dynamic Material 3 theme with seed color and palette style picker
