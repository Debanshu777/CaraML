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

Run: `./gradlew :composeApp:jvmTest`

---

## Device-Aware Recommendation Rollout

The release build remains in `LEGACY` mode. V2 assessment is available for shadow verification, but it is not yet the sole displayed category source because the required measured Android/iOS/Desktop device matrix and pinned benchmark-runner result have not been recorded. Do not remove the legacy calculator or rollout bridge until those gates are reviewed.

Profiles change plan selection without changing objective compatibility evidence. Risk tolerance reserves 25% (`CONSERVATIVE`), 15% (`BALANCED`, the default), or 5% (`EXPERIMENTAL`) of reliable memory. Optimization priority favors speed/efficiency, a balanced mix, or quality/context. Categories mean: `RECOMMENDED` has comfortable supported headroom; `USABLE` is expected to fit with less margin or a compromise; `RISKY` needs explicit acknowledgement; `NOT_SUITABLE` has no acceptable resource plan; `INCOMPATIBLE` is a hard engine/format mismatch; and `NEEDS_INFORMATION` means bounded trustworthy evidence is missing.

All repository IDs, revisions, relative paths, component counts, byte sizes, model shapes, workload dimensions, evidence, and native records are bounded before use. Unknown, inconsistent, duplicate, overflowing, or stale metadata fails closed instead of being guessed. Selection carries the exact assessed descriptor, recommendation, run plan, and verified local bytes into just-in-time native preflight; it never reconstructs identity from a database filename.

Calibration is local-only and numeric. It is stored in the disposable sibling `recommendation_cache.db`, never uploads prompts, paths, repository IDs, or user content, and cannot delete or migrate `caraml.db`. A schema mismatch or corrupt recommendation cache may reset that cache and rebuild conservative estimates; model/download records remain intact. Profile-only reranking stays cached and performs no metadata, filesystem, calibration, or native work on the Compose main thread.

Verification commands:

```bash
./gradlew :composeApp:jvmTest --tests '*Recommendation*' --tests '*ModelFitFixture*'
./gradlew :composeApp:jvmTest --tests '*RecommendationPerformanceTest*'
CARAML_NATIVE_PARITY=true ./gradlew :runner:jvmTest :diffusionRunner:jvmTest
```

The performance budgets are enforced only when both `CARAML_ENFORCE_RECOMMENDATION_PERF=true` and the documented bounded `CARAML_BENCHMARK_RUNNER_ID` are supplied on the pinned runner. Ordinary JVM runs report timing but are not release evidence.

---

## Recent Changes

<!-- Updated at end of each AI-assisted development session -->

- Expanded the documented [CaraML Prism design system](../docs/caraml-design-system.md) with information hierarchy, progressive disclosure, consistent rounded surfaces, compact reflow, and data-heavy toolbar guidance
- Compact Models now collapses device diagnostics into one remembered summary, uses rounded wrapping result panes and compact metrics, and prioritizes its command and first useful result
- Artifact now puts device-fit and download decisions ahead of collapsed technical metadata, with rounded focal/file surfaces and 360–412dp production previews including 200% text
- Active conversations now enter Focus Mode: persistent navigation and Create chrome disappear, the thread reclaims the canvas, and one accessible action opens the existing sidebar without replacing the production composer
- Rebuilt the shared UI as a calm sidebar-first local AI workbench: compact windows reveal a modal left panel without moving content, tablets use a compact rail, and wider workspaces use a labeled contextual sidebar
- Create now owns the full canvas with the baseline yellow/violet/green grain-backed atmosphere, a connected Text/Image/Video control, the production composer, truthful no-model action, and explicit generation states
- Model Hub now uses one flat compact registry hierarchy, while Details integrates model identity into the route canvas and preserves exact durable artifact controls without sacrificing compact or large-text reachability
- Android uses UIDT/foreground WorkManager notifications, iOS reconnects to a stable background URLSession, and Desktop recovers persisted download checkpoints at startup
- Settings now uses a dense list hierarchy with exclusive selections and disclosure rows; rich grain-backed gradients are limited to the Details overview and appearance preview, while the shared backdrop stays faint and interaction surfaces stay matte
- Final verification covers 788 Compose JVM tests, the 916-test repository gate plus five native tests, Android debug assembly, iOS simulator compilation, and inspected Pixel 9 compact Create, Models-empty, and Settings captures; populated model and Artifact states remain device-unverified because the live registry returned no models
- Added a strict 16-case recommendation corpus, analytical timing coverage, exact assessed-artifact selection handoff, and explicit release gating; unmeasured outcomes remain null and production remains `LEGACY`
- Recommendations now learn only from byte-bound descriptors, phase-specific raw memory evidence, full run-plan fingerprints, and trustworthy process-memory counters; stale identities and unresponsive native probes fail closed
- Exact model selections now derive directory targets only from verified storage roots, bind every native-consumed component path, keep multi-sequence planning analytical, and reject it at native admission
- Legacy or duplicate native device identities now remain low-confidence Unknown evidence unless exact llama/diffusion device-and-type intersection is provable
- Native capability evidence now routes by model kind and advertises GPU offload only when exact named compute backends are available to both llama.cpp and stable-diffusion.cpp
- Model Hub now loads and repairs diffusion installs from deterministic exact bundle identities, fails unknown image/video readiness closed, and ignores stale directory leftovers outside the aggregate manifest
- Model Hub now incrementally ranks bounded immutable variants for this device, preserves server order on demand, and reranks cached assessments for profile changes
- Recommendation profiles now persist atomically, default safely to Balanced, and appear through accessible Material 3 controls only in SHADOW/V2 rollout modes
- Model assessment now reuses bounded profile-neutral plan estimates with cancellation-safe single-flight coordination, fresh snapshot assembly, and privacy-safe staged rollout comparison
- Model compatibility and backend availability now initialize the pinned llama.cpp engine from the trusted platform library directory, exclude non-GPU devices from offload evidence, and fail closed to Unknown
- Diffusion load/generation now uses current conservative memory budgets, serialized native sessions, actionable safe failures, and a 512 MiB session media cache decoded off the Compose thread
- Chat now signals native diffusion cancellation before coroutine teardown, rejects unsupported iOS video early, and reports oversized llama prompts without corrupting prior conversation state
- `LlamaInferenceRepository` now serializes all native-session access, snapshots synchronous stats safely, retries stale cached fits, and preserves cancellation through load/reset paths
- Text streaming now accumulates lossless deltas and publishes at most every 50 ms; Compose collects streaming state once and renders Markdown only after completion
- Context reset, model download, and bulk-delete flows now report partial failure, prevent duplicate starts, clean up state in `finally`, and never swallow cancellation
- Diffusion preflight uses overflow-safe dimension math, sanitized diagnostics, and defensive progress-array reads
- `SuitabilityResult` now carries `warnings: List<String>`; `ModelSuitabilityCalculator.rateLlm` emits runnability warnings for IQ-quant CPU-only and hybrid-SSM models; `SuitabilityInfoSheet` renders a "Runnability" section with warning icon when present
- `LlamaInferenceRepository`: hybrid-SSM arch Vulkan denylist (`DENYLIST_HYBRID_SSM_VULKAN=true`) skips doomed GPU attempt on first load; combines with runtime `gpuIncompatible` self-learning set
- `generateResponse` emits `InferenceChunk`; removed `ReasoningModelClassifier` and structured-output prompt suffixes
- `DiffusionInferenceRepository.buildDiffusionModelConfig()` now always passes `diffusionConvDirect=true`; bypasses IM2COL path in ggml-vulkan that aborts when conv kernel type is not F32/F16
- `DiffusionInferenceRepository.buildDiffusionModelConfig()` now propagates `flowShift`, `freeParamsImmediately` (auto-enabled when weights ≥ 65% of memory budget), `taesdPath` (auto-resolves `madebyollin/taesd` when downloaded), and `vaeTiling` (auto-enabled when width × height > 512²)
- `ChatViewModel` image/video send paths now honor registry-pinned `sampleMethod` (via `SampleMethod.fromName`) and `seed` (falls back to current millis when unset)
- New `core/rating/` package: `ModelSuitabilityCalculator` (canonical llama.cpp BPW table, KV cache math with per-architecture shape lookup, HF Accelerate +20% overhead, GPU/CPU modifiers) + `SuitabilityChip`, `SuitabilityDot`, `SuitabilityInfoSheet` UI primitives
- Search list rows now show a color-coded fit chip (Poor/Average/Good/Best); variant picker shows per-variant dots using accurate on-disk size; tap chip opens shared bottom-sheet explainer with footprint breakdown, ratio vs RAM budget, device snapshot, legend, and caveats
- Added `SdArchitecture` enum (SD1/SDXL/SD3/FLUX/WAN_SMALL/WAN_LARGE with Q4 RAM profiles) and `SdArchitectureClassifier` (maps HF tags + model ID segments to architecture, priority: FLUX > SD3 > WAN_LARGE > WAN_SMALL > SDXL > SD1)
- New `ModelSuitabilityCalculator.rateDiffusion(hints, architecture, ...)` overload: weight resolution from totalComponentBytes > arch baseline × BPW ratio > generic 3.5 GB fallback; VAE offload −400 MB, flash attention −600 MB; GPU bumps DiT archs up, low perf-core count penalizes video archs
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
