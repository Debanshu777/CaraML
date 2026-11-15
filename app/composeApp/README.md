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
- `SearchScreen.kt` — search, filters, recently viewed models, and downloaded-variant counts
- `DownloadsScreen.kt` — transfer queue and searchable local models
- `DetailsScreen.kt` — frosted navigation with a scroll-aware model title, a persistent overview, Variants/Model card/Technical details tabs, grouped metadata, a revision-pinned README reader, and GGUF files / complete ONNX bundle variants
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

Recent visits persist at most 20 compact display snapshots and show the newest four. The versioned history format retains stable mode identities and migrates the previous full-detail format without overwriting a concurrent visit. History JSON processing runs on `Dispatchers.Default`; unrelated preference updates do not reparse it. Readable history remains available if its optional rewrite fails. An explicit visit repairs unreadable history while retaining its first original value under a recovery preference. History recording belongs to the ViewModel scope and does not delay file discovery.

Download controls use exact persisted batch lookup independently of the bounded queue display. A cancelled batch with unfinished checkpoint cleanup remains visible and offers **Retry cancellation**; it cannot resume or retry its transfer until cleanup completes.

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

## Model Hub avatars

Search cards load author avatars through Coil Compose with SVG decoding and Ktor networking. Android uses OkHttp, iOS Darwin and desktop CIO; dependency versions live in `gradle/libs.versions.toml`. Avatars share the model monogram’s square frame, rounded corners, rotation and task colors. `ModelMonogramFrame`, `ModelMonogram` and `ModelAuthorAvatar` accept `size: Dp = 36.dp`; pass, for example, `size = 48.dp` to choose the frame dimensions. Artwork fills the frame; initial typography and the 12 dp corner radius retain their existing sizes. The monogram remains visible while loading or after failure. Recommendation metadata acquisition runs independently of image loading.

## Recent Changes

- Fixed the iOS restoration/command lock cycle and stale installation flags on retained detail re-entry. Regression tests cover startup order, newer stop intent, cancellation while waiting, deleted installations, same-batch reinstall, and stale local reads. Consolidated persisted-snapshot validation, removed unused DAO operations, migrated removal tests to the production selected-row transaction, and replaced legacy layout composition locals with explicit arguments.

- Replaced full-detail recent-history persistence with a compact, versioned settings DTO, safe legacy migration, background JSON processing, and independent writes. Reused the root settings ViewModel in Models. Exact persisted download controls and cancellation-cleanup recovery are covered alongside history migration/concurrency regressions. Removed unused Model Hub toolbar/header/context/summary/sheet and navigation wrappers; retained shared byte formatting and moved applicable UI assertions onto current filter/device/row surfaces.

- Download manifest checks dispatch filesystem work to IO; count badges check published identities and current file sizes, ignore usage-only catalog changes, and read each repository once per snapshot. Download database upgrades preserve records/checkpoints and quarantine unsafe legacy work. Removal binds companions by artifact identity, including ONNX files in the main repository. Obsolete Model Hub state and unused widgets were removed; historical test helpers now live in test sources. Model-card normalization uses the common Kotlin `StringBuilder.setRange` API for iOS compatibility.

- Exposed `size: Dp` on the shared monogram frame and its monogram/avatar wrappers, retaining the 36 dp default.

- Model details adds a full Model card tab with images, wrapping tables, and revision-aware relative links. Three swipeable text tabs retain the complete overview and pin below the top bar while scrolling. Switching pinned tabs opens the selected section at its beginning. The header shares search’s frosting and reveals the model name when the expanded title scrolls out of view. Technical facts remain grouped. Screen recreation reuses active requests and loaded data, and tab/scroll state survives changes between compact, rail, and sidebar navigation; the ViewModel prepares Markdown once per fetched document for reuse across tab changes. README retry remains independent of downloads. See the [model details guide](../../docs/models.md#model-details).

- Dark mode now uses progressively lighter raised surfaces, subtle shared borders, dark button shadows, and a quieter background wash across the app. Light-mode colours are unchanged.

- Models now separates subdued Recently viewed cards from an explicit Explore models or Search results heading, with tighter search spacing and consistent, lightly raised icon buttons. Icon buttons retain a thin 1dp contour and 2dp depth while sharing text buttons’ ink shadow. Both sizes use the shared subtle rim in dark mode.

- Search keeps its state when Downloads opens. Filters sits inside the full-width field, Downloads in the Models app bar, and Device in the sidebar. Up to four recent model visits appear in a grid, and catalog search shows complete local variant counts.
- Unified Model search and Downloads rendering in `ModelRow`, with pure presentation-state mappers and shared selection, repair, recommendation and avatar behavior.

- Centered model-row avatars and selection icons vertically beside the row content.

- Model row dividers start at the title column, after the avatar, and extend through the list’s trailing gutter to the content-pane edge.

- Model rows show compatibility as a tinted icon-and-text tag alongside metrics, wrapping as needed. Details share the same green/amber/red/neutral verdict styling.

- Model rows use accessible status icons and task tags; details retain matching icons with explanatory text.

- Added offline `ModelHubCard` previews for light/dark themes and narrow screens at 200% text size, including metrics, repair and selection states.

- Model search and Downloads share one compact row layout with icon metrics, secondary author/filename text and subtle dividers. Tap a supported row to open it; Downloads retains selection, readiness and “Finish setup” actions without a redundant “Use model” button.

- Migrated language-model discovery to page-based Hugging Face results and match counts; added author avatars with loading/error fallbacks while retaining independent recommendation metadata enrichment.

- Added `org.jetbrains.compose.ui:ui-tooling` to `composeApp` `androidMain` dependencies to resolve Android Studio Compose Preview rendering issues (`ComposeViewAdapter` missing class exception).
- Made model selection and loading a single transition, fenced cancelled detail requests, and applied inference settings edits inside DataStore transactions. Cancelled media rolls back if its chat turn loses ownership. Download startup reconciles interrupted pause and resume commands, and platform scheduling failures leave queued transfers retryable. Llama retains ownership when unload throws.
- Corrected ONNX resident-session fixtures to use `<models>/owner/onnx` and clean up the whole temporary tree, fixing Linux CI loads; JVM test logs now include full failure details.
- Added SD-Turbo GGUF discovery, fixed nested-checkpoint installation, and kept image/video models visible alongside frequently used chat models.
- Added [platform setup](../../docs/getting-started.md), [contribution guidance](../../CONTRIBUTING.md), and a [real-device demo](../../docs/demo.md).
- Enabled Android Vulkan image plans and model-specific sampling; see [tested models and limits](../../benchmarks/diffusion/README.md).
- Added format-aware GGUF/ONNX discovery, downloads, routing, streaming, and cancellation, with idle same-model session reuse. [Device measurements](../../benchmarks/onnx/README.md).
- Reduced installed-model preparation work and isolated streaming state to the active chat row. [Performance evidence and caveats](../../benchmarks/android/README.md).
