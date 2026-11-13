---
description: Explore CaraML’s Kotlin Multiplatform modules, inference engines, native bridges, resource ownership, and experimental SDK limits.
---

# Architecture and SDK boundaries


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
├── native-runtime/      # Internal SDK native ownership guard
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
| `native-runtime` | None | Shared ownership gate for the two native adapters |
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
| Model Hub | Draft search → committed query → paged results, empty or error. Recommendation assessment has its own query session. Details requests cancel the previous load and check cancellation before publishing. A failed file-list request offers a retry. Download admission is bound to the active detail artifact and recommendation session. | `ModelViewModel`, `ModelHubResultsState`, `RecommendationQuerySession` |
| Download and install | Queued → running, paused or waiting for network → verifying → completed, cancelled or failed. User intent and worker leases drive restart recovery. Startup completes interrupted pause or resume transitions, and only a fresh enqueue can reactivate a cancelled batch. Verification publishes the artifact manifest before the local catalog entry. Publication reconciliation repairs incomplete catalog updates. | `DownloadCoordinator`, `DownloadBatchRunner`, `ModelDownloadFinalizer` |
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
