---
description: Explore CaraML’s Kotlin Multiplatform modules, inference engines, native bridges, resource ownership, and experimental SDK limits.
---

# Architecture and SDK boundaries


Model Hub Model search and Downloads use a shared `ModelRow` renderer. Pure mappers convert network and storage records into `ModelRowUiState`; the renderer consumes presentation values and callbacks without depending on those data sources. Recommendation metadata enrichment remains in the existing domain flow.

## Module structure

```text
CaraML/
├── core-api/            # Experimental neutral SDK contracts
├── core/                # SDK lifecycle and resource admission
├── storage/             # Optional SDK storage implementations
├── model-discovery/     # Provider-neutral discovery + built-in Hugging Face
├── plugin-llama/        # llama SDK + Kotlin/JNI/cinterop bridge
├── plugin-onnx/         # Android/JVM/iOS ONNX causal text runtime
├── plugin-diffusion/    # Diffusion SDK + Kotlin/JNI/cinterop bridge
├── native-runtime/      # Native ownership guard + JVM library resolver
├── app/                # CaraML app consumers, separate from the SDK
│   ├── composeApp/     # Shared UI, app logic and platform implementations
│   ├── androidApp/     # Android launcher and APK packaging
│   ├── desktopApp/     # Desktop launcher, hot reload and packaging
│   └── iosApp/         # Xcode host consuming the ComposeApp framework
├── nativeEngine/        # CMake build orchestration for all native libs
└── libraries/
    ├── llama.cpp/       # Git submodule: ggerganov/llama.cpp
    ├── stable-diffusion.cpp/ # Git submodule: leejet/stable-diffusion.cpp
    └── patches/         # Applied patches to vendored libraries
```

Each engine plugin owns its bridge as ordinary source packages; there are no separate
`runner` or `diffusionRunner` Gradle projects. The native CMake producer stays shared
so both engines use one GGML implementation. API contracts, core orchestration,
optional storage/discovery, and the process-wide native gate retain separate boundaries.
The former `huggingFaceManager` project is also removed: Hub APIs live in discovery,
while the existing native storage, background download and curated install integrations
live in the app. The application projects are grouped under `app/`; `composeApp`
holds shared app code and the platform hosts own their launchers and packaging.
Other providers implement `ModelProvider`
and optionally `ArtifactSource`; they do not need a separate Gradle project.

See each module's own README for details.

## Package Conventions

First-party Kotlin packages use `com.debanshu777.caraml` as their common root.
Source directories mirror package declarations in every source set.

| Owner | Package below the common root |
|-------|-------------------------------|
| Shared app and launchers | `core.*`, `features.*` and app entry points |
| App transfer / diffusion installation | `core.download.transfer`, `core.download.diffusion` |
| Generated Compose resources | `generated.resources` |
| SDK contracts / lifecycle | `sdk.api`, `sdk.core` |
| SDK storage / native ownership | `sdk.storage`, `sdk.runtime` |
| Discovery / Hugging Face implementation | `sdk.discovery`, `sdk.discovery.huggingface` |
| Engine adapters / native bridges | `sdk.plugin.llama`, `sdk.plugin.diffusion`, each with a `bridge` subpackage; `sdk.plugin.onnx` owns ONNX text inference |

Android library namespaces follow their owning module's package. The native
producer uses `com.debanshu777.caraml.nativeengine`; the shared UI library uses
`com.debanshu777.caraml.shared` to keep its generated Android classes distinct
from the launcher. Application IDs, bundle IDs, engine IDs and persisted formats
are stable identifiers and do not change when source packages move. Rebuild
native binaries with their Kotlin bridges when JNI packages change.

---



## SDK Boundaries and Ownership

The SDK is an experimental Kotlin Multiplatform implementation. Extension contracts
are marked `ExperimentalCaraMLApi`; source and binary compatibility are not yet
stable. CaraML still uses its app-owned inference repositories and durable download
pipeline, so reorganizing modules has not completed its migration to the SDK facades.

| Module | Production project dependencies | Responsibility |
|--------|---------------------------------|----------------|
| `core-api` | None | Neutral engine, artifact, storage, resource and provider contracts |
| `core` | `core-api` | `InferenceClient`, assessment, admission and session lifecycle |
| `storage` | `core-api` | Optional in-memory and Okio artifact stores |
| `model-discovery` | `core-api` | Provider-neutral browsing/transfers and Hugging Face implementation |
| `native-runtime` | None | Shared ownership gate and JVM library resolution for the two native adapters |
| `plugin-llama`, `plugin-diffusion` | `core-api`, `native-runtime` | Engine adapters and their own JNI/cinterop bridges |

`plugin-onnx` owns the supported ONNX causal text runtime and its Android/JVM/iOS integration. See the [ONNX module](https://github.com/Debanshu777/CaraML/blob/main/plugin-onnx/README.md) for bundle and execution-provider constraints.

The portable API, core and runtime have no Compose, Koin, Room, DataStore or HTTP
dependency. SDK modules must not depend on `app/`. Hosts supply configuration,
artifact access and resource readings, and retain ownership of UI, preferences,
application schemas, migrations, OS scheduling, permissions and notifications.
Custom engines, providers and stores implement the neutral contracts; discovery
and default storage are optional for local-file inference. `nativeEngine` builds
the selected native code, with GGML shared by both engines.

## Current SDK Limits

- **Storage and downloads:** the file store coordinates one instance, with no
  cross-process locking or orphan-stage recovery. Independently supplied task and
  artifact stores do not provide an atomic publication transaction. Durable task
  persistence, restart reconciliation and scheduling migration remain unfinished.
- **Inference and resources:** context repair, resumable turns, recovery outcome
  persistence and an iOS resource-source factory remain unfinished. The diffusion
  adapter supports one local model file and a single PNG result; bundle/video
  support and measured media budgets are not established for the SDK adapter.
- **Native integration:** the Kotlin runtime gate covers one linked runtime, not
  direct runner callers or separate classloaders. Independent engine packaging,
  coexistence with other native SDKs, optimized R8/AAB consumers and complete
  installed-device behavior/performance still require release validation.
- **Consumer migration and release:** CaraML upgrade/data-preservation checks,
  extension conformance, API evolution and the supported target matrix remain
  release work. Publication and standalone artifact consumers are deferred.

Module-specific contracts and limits live in the [core API](https://github.com/Debanshu777/CaraML/blob/main/core-api/README.md),
[core](https://github.com/Debanshu777/CaraML/blob/main/core/README.md), [storage](https://github.com/Debanshu777/CaraML/blob/main/storage/README.md),
[discovery](https://github.com/Debanshu777/CaraML/blob/main/model-discovery/README.md), [native runtime](https://github.com/Debanshu777/CaraML/blob/main/native-runtime/README.md),
[llama](https://github.com/Debanshu777/CaraML/blob/main/plugin-llama/README.md) and [diffusion](https://github.com/Debanshu777/CaraML/blob/main/plugin-diffusion/README.md) READMEs
and API KDocs. The following data flows describe the current CaraML application.

### Data Flow — LLM Inference

```
ChatViewModel
  → GenerateResponseUseCase
    → RoutingInferenceRepository
      ├── LlamaInferenceRepository → LlamaRunner (expect/actual)
      │   ├── Android/JVM: JNI → llama_runner.so
      │   └── iOS: cinterop → libllama_runner_merged.a → llama.cpp
      └── OnnxInferenceRepository → plugin-onnx → ONNX Runtime
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

## Model discovery pages

The app’s language-model browse and committed search share `getModelPage`. Its Hugging Face adapter requests `/models-json` with `withCount=true` and zero-based `p`; format filters map to `library`. A bounded projection validates page metadata and repository identities, normalizes mixed boolean/string gating and optional parameter counts, and retains author display metadata. The continuation remains query-bound even though the remote service uses integer pages.

The paging source deduplicates repositories, retries failed pages, and keeps the existing 256-repository session bound. Server totals, loaded repositories and assessed repositories are separate quantities. Refresh starts at page zero because catalog rankings can change between requests. The recommendation service retains its independent detail/tree/config acquisition, immutable revisions, concurrency bound and assessment budget. Missing search-row tags or GGUF/safetensors objects do not trigger blanket detail fetches.

## State and ownership

Several workflows have named phases, with transition rules distributed across
view models, coroutine jobs, persistence and native ownership.

| Workflow | States and transitions | Owner |
| --- | --- | --- |
| Navigation | Drawer mode selects Chat, Images or Videos. Model Hub pushes a Details entry and Back returns to the Hub. Opening an installed model selects its matching workspace. | Navigation back stack and `GenerationModeController` |
| Chat selection and load | Inventory initialization → selection → loading → ready, error, missing components or admission prompt. Removing the selected model triggers a fallback when another compatible model is installed. Load attempts bind to the current model and mode. | `ChatViewModel`, `LoadSessionCoordinator` |
| Chat generation | Ready → preparing or compacting context → streaming tokens or rendering media → completed, stopped or failed. A generation job and streaming state track the active turn. Media is retained only when the active assistant message accepts it. | `ChatViewModel`, inference repositories |
| Settings | Ordinary inference edits transform the persisted snapshot inside a DataStore transaction. Profile edits pass through optimistic and queued states before persistence. Onboarding and calibration have separate completion state. | `SettingsViewModel`, `DefaultSettingsRepository` |
| Calibration | Idle → eligibility check → native probe → recorded result, deferral or failure. A cancelled probe must confirm that native work stopped before its gate is released. | `QuickCalibrationRunner`, `CalibrationRepository` |
| Model Hub | Draft search → committed query → paged results, empty or error. Recommendation assessment has its own query session; late browse pages may update search rows without taking ownership away from active details. Details screen entry reuses the active remote request for the same repository and browse mode and independently refreshes local installation evidence. New detail requests and newer local reads invalidate stale installation projections. A different route or explicit retry cancels the previous load and checks cancellation before publishing. An optional child request reads a bounded README document and derives its overview excerpt at the same revision; it clears on navigation and never gates file-list loading or recommendation evidence. A failed file-list request offers a retry. Download admission is bound to the active detail artifact and recommendation session. | `ModelViewModel`, `ModelHubResultsState`, `RecommendationQuerySession` |
| Downloads | A separate Navigation3 route shows durable queue state and observed installed models. Search remains in the back stack with its saved scroll and visit state. A successful model-detail visit is recorded in DataStore by repository and browse mode. Search projects committed identities and current file shapes from local inventory, ignoring usage-only changes. Full manifest reads run on IO; display counts do not rehash payloads. | `AppScreen.Downloads`, `DownloadedModelsViewModel`, `DefaultSettingsRepository` |
| Download and install | Queued → running, paused or waiting for network → verifying → completed, cancelled or failed. User intent, worker leases and a durable cancellation-cleanup marker drive restart recovery. Startup completes interrupted pause/resume transitions and retries unfinished cancellation cleanup, including terminal worker records. Only a fresh enqueue can reactivate a cancelled batch after cleanup. Scheduling commands persist intent under lifecycle coordination, await platform restoration outside that lock, then recheck current intent before dispatch. Newer pause/cancel commands remain effective during startup. Explicit enqueue can reactivate a completed batch for reinstall; startup recovery and insert-only scheduling preserve completed work. Verification publishes the artifact manifest before the local catalog entry. Publication reconciliation repairs incomplete catalog updates. | `DownloadCoordinator`, `DownloadBatchRunner`, `ModelDownloadFinalizer` |
| App inference | Admission precedes native allocation. Ready models may be reused. The Llama repository retains ownership if unload throws, and attempts teardown again before another allocation. An interrupted load marker is checked at startup before another attempt. | Inference repositories, `NativeSessionGate` |
| SDK inference | Assessment holds an artifact lease. Loading consumes the plan and transfers ownership to a session. Generation serializes operations and releases operation admission before terminal delivery. Failed session teardown quarantines the owner, artifact lease and reservation. The diffusion SDK probe holds the shared gate for the duration of synchronous preflight. Diffusion stops progress polling before publication and keeps the native gate through failed release. | `InferenceClient`, `InferenceSession`, `NativeRuntimeGate` |

---



### NativeRunnerConfig

Adapts inference parameters (thread count, GPU layers, context size, KV cache quantization) based on `DeviceCapabilities` — each platform reports its own CPU count, RAM, GPU type.

### GgmlUnified.cmake

Single CMake script builds GGML once from `libraries/llama.cpp`, then both `llama.cpp` and `stable-diffusion.cpp` share it (guarded by `if (NOT TARGET ggml)`). Prevents duplicate symbol errors from linking two GGML builds.

### Patch System

Numbered llama.cpp patches under `libraries/patches/llama.cpp/` are applied to a Gradle-owned source copy by `:nativeEngine:preparePatchedLlamaSource`; the pinned upstream submodule remains immutable.

### iOS Static Lib Merge

iOS requires a single merged `.a` archive (Metal, Accelerate, and GGML frameworks don't support dynamic linking on iOS). The `mergeLlamaRunnerStaticIosArm64` Gradle task runs `libtool -static` to produce `libllama_runner_merged.a`.

---

`AdaptiveNavigation` owns one movable route subtree and saved-state holder above its modal/rail/sidebar branches. The subtree keeps its composition identity and nested restoration keys across window-layout changes, while its current content callback updates with the new constraints.

Model details uses the same frosted scaffold configuration as search, including the shared Soft effects fallback. Measured title and header positions control a compact header title without changing the header height. The details pager shares its scroll state with a sticky tab row rendered outside the scrolling haze source; its placeholder preserves content position without duplicating tab semantics. Quantization labels are display-only, used when unique among the shown files; downloads and selection retain exact artifact identities. Publisher excerpts and compact labels do not feed recommendation evidence.

Search passes a small serializable `ModelDetailsPreview` with display fields and a source-specific transition key. It contains no revision or artifact identity. Details keeps one overview composition through loading, success, and errors, and loads download metadata through the existing API path. `SharedTransitionLayout` wraps `NavDisplay`; avatar/title modifiers use its animated entry scope and are disabled by the reduced-motion policy. Recent keys include browse mode to avoid duplicate matches. The [Navigation 3 shared-element guide](https://developer.android.com/develop/ui/compose/animation/shared-elements/navigation) describes this integration.

### Model-card documents

The Hugging Face compatibility API returns `ModelCardDocument` (repository, revision, Markdown body, optional excerpt) from a bounded raw README request. `ModelViewModel` owns its independent cancellable loading/retry state. It normalizes and parses the Markdown on a background dispatcher before publishing Ready with the document and prepared renderer state. The pager consumes that state directly, so recreating its page does not repeat parsing. The navigation host retains the ViewModel across configuration changes; `ensureDetail` preserves the active remote request and loaded document while refreshing local installation evidence; `loadDetail` remains an explicit remote refresh/retry. Document content does not participate in artifact classification or fit assessment. See the [model details guide](models.md#model-details) for rendering support and limits.

### Shared implementations and discovery ownership

Android and JVM share their Java-compatible download manager and llama/diffusion bridge implementations through `src/javaMain/kotlin`. Target-specific native loading remains small and keeps the existing JNI class names and descriptors. iOS keeps its cinterop implementation.

Neutral Hugging Face search requests a bounded projection containing `sha`, `library_name`, and `pipeline_tag`; resolution and transfer retain that immutable commit. The compatibility factory owns its platform and derived clients and exposes `close()`. Direct service/wrapper construction borrows the supplied host client; closing the derived service never closes the host. The app ties factory ownership to Koin disposal.

### Recent model history

Settings persist a versioned display-only history (identity, browse kind, author, task and small metrics), capped at 20 entries. Legacy full-detail entries migrate through a narrow decoder; an atomic compare-and-rewrite preserves newer visits. JSON transforms run on a background dispatcher, and unrelated preference changes do not trigger history decoding. Invalid blobs remain untouched on reads; a new visit saves the first unreadable original separately and restores a usable history. History writes are ordered within the ViewModel scope so later visits cannot be overtaken, while file discovery remains independent.
