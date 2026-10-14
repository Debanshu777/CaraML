# CaraML

**On-device AI inference for Android, iOS, and Desktop (JVM)**

CaraML runs large language models and image generation models entirely on-device using [llama.cpp](https://github.com/ggerganov/llama.cpp) and [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp). Models are browsed and downloaded directly from HuggingFace Hub. No cloud backend required.

---

## Features

- **LLM Chat** — streaming token generation with structured reasoning support (thinking blocks), markdown rendering, context tracking
- **Image Generation** — Stable Diffusion via stable-diffusion.cpp (text-to-image, configurable sampling)
- **Model Hub** — browse, search, filter, and download GGUF models from HuggingFace; smart install bundles for diffusion models
- **GPU Acceleration** — Vulkan (Android), Metal (iOS/macOS)
- **Dynamic Theming** — Material You / Material 3 with materialKolor
- **Fully Offline** — all inference runs on-device after download

---

## Platforms

| Platform | Status | GPU |
|----------|--------|-----|
| Android  | ✅ | Vulkan (experimental) |
| iOS      | ✅ | Metal |
| Desktop (JVM/macOS) | ✅ | Metal |
| Desktop (JVM/Linux) | ✅ | CPU only |

---

## Current Android Benchmarks

Measured on a **Google Pixel 9 running Android 17**, on **October 15, 2026**.
All inference runs locally through llama.cpp. Native placement logs verified
Vulkan GPU offload for every model below.

| Model | Quantization | CPU (tokens/sec) | Vulkan (tokens/sec) | GPU layers |
|-------|--------------|----------------:|-------------------:|-----------:|
| SmolLM2 135M Instruct | Q4_K_M | 62.50–64.57 | **73.18–73.40** | 31 |
| Gemma 3 270M IT | Q4_K_M | 41.64–42.65 | **42.96–43.20** | 19 |
| Qwen3 0.6B | Q8_0 | 18.91–26.31 | **22.74–22.81** | 29 |
| LFM2 350M | Q4_K_M | 44.68–70.04 | **54.84–57.98** | 17 |
| Qwen2.5 0.5B Instruct | Q4_K_M | 24.57–28.59 | **35.99–36.33** | 25 |

These are sustained **decode** rates, excluding prompt processing and the first
token, shown as the range across two passing runs per backend. Each run generated
at least 512 tokens using matching prompts and settings: greedy sampling,
4,096-token context, 4 generation / 4 batch threads, batch 256, microbatch 64,
and F16 KV cache. The app ran in the foreground with no active downloads;
runs started at nominal or mild thermal status. The debug APK used release-built
native code.

All five models passed load, generation, cancellation, unload, reload, and recovery
checks on the final installed build. GPU acceleration helps some models more than
others; thermal state and OS caching affect the results. These measurements are
the current project baseline, rather than a maximum-throughput claim or a measure
of answer quality. They cover the PR #17 working tree with local fixes and existing
edits; they are not a clean release-build certification.

Earlier failed or inactive-GPU trials are excluded from these ranges. Remaining
observations include generic native warnings, a three-model chat picker limit,
and context-dependent answer errors on small models. Raw device captures and
benchmark logs are kept outside the repository.

---

## Module Structure

```
CaraML/
├── core-api/            # Experimental neutral SDK contracts
├── core/                # SDK lifecycle and resource admission
├── storage/             # Optional SDK storage implementations
├── model-discovery/     # Provider-neutral discovery + built-in Hugging Face
├── plugin-llama/        # llama SDK + Kotlin/JNI/cinterop bridge
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
| Engine adapters / native bridges | `sdk.plugin.llama`, `sdk.plugin.diffusion`, each with a `bridge` subpackage |

Android library namespaces follow their owning module's package. The native
producer uses `com.debanshu777.caraml.nativeengine`; the shared UI library uses
`com.debanshu777.caraml.shared` to keep its generated Android classes distinct
from the launcher. Application IDs, bundle IDs, engine IDs and persisted formats
are stable identifiers and do not change when source packages move. Rebuild
native binaries with their Kotlin bridges when JNI packages change.

---

## Prerequisites

- **JDK 21+** with JNI headers (required for desktop native build)
- **CMake 3.22.1+**
- **Android NDK** (for Android builds)
- **Xcode 16+** and macOS (for iOS builds)
- **Vulkan SDK** + `glslc` in PATH (for Android GPU build only)

---

## Build

### 1. Initialize submodules

```bash
git submodule update --init
```

### 2. Build native libraries

```bash
# Desktop (required before first run)
./gradlew :nativeEngine:compileLlamaRunnerDesktop

# iOS static libs
./gradlew :nativeEngine:mergeLlamaRunnerStaticIosArm64
./gradlew :nativeEngine:mergeLlamaRunnerStaticIosSimulatorArm64
```

### 3. Build & run

```bash
# Android (debug APK)
./gradlew :app:androidApp:assembleDebug

# Desktop — build and run directly
./gradlew :app:desktopApp:run

# iOS — open app/iosApp/ in Xcode
```

### Android Vulkan GPU build

```bash
./gradlew :app:androidApp:assembleDebug

# Explicit CPU-only build
./gradlew :app:androidApp:assembleDebug -PcaramlAndroidBackend=cpu
```

Llama builds include Vulkan on arm64 and CPU support on x86_64. The build requires
the NDK `glslc` plus Vulkan/SPIR-V headers (`brew install vulkan-headers spirv-headers`
on macOS; `apt-get install libvulkan-dev spirv-headers` on Linux). Device performance
depends on the model and GPU; CPU remains selectable at runtime. See
[native build configuration](nativeEngine/README.md#android-cmakeliststxt).

---

## Architecture

### SDK Boundaries and Ownership

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

The portable API, core and runtime have no Compose, Koin, Room, DataStore or HTTP
dependency. SDK modules must not depend on `app/`. Hosts supply configuration,
artifact access and resource readings, and retain ownership of UI, preferences,
application schemas, migrations, OS scheduling, permissions and notifications.
Custom engines, providers and stores implement the neutral contracts; discovery
and default storage are optional for local-file inference. `nativeEngine` builds
the selected native code, with GGML shared by both engines.

### Current SDK Limits

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

Module-specific contracts and limits live in the [core API](core-api/README.md),
[core](core/README.md), [storage](storage/README.md),
[discovery](model-discovery/README.md), [native runtime](native-runtime/README.md),
[llama](plugin-llama/README.md) and [diffusion](plugin-diffusion/README.md) READMEs
and API KDocs. The following data flows describe the current CaraML application.

### Data Flow — LLM Inference

```
ChatViewModel
  → GenerateResponseUseCase
    → LlamaInferenceRepository
      → LlamaRunner (expect/actual)
        ├── Android/JVM: JNI → llama_runner.so
        └── iOS: cinterop → libllama_runner_merged.a
            → llama.cpp (C++)
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

---

## Tech Stack

| Layer | Library / Version |
|-------|-------------------|
| Language | Kotlin 2.4.0 |
| UI | Compose Multiplatform 1.11.1 |
| DI | Koin 4.2.2 |
| HTTP | Ktor 3.5.0 |
| Database | Room 2.8.4 |
| Preferences | DataStore 1.2.1 |
| Navigation | Navigation3 1.1.1 |
| Theming | Material3 1.10.0-alpha05 + materialKolor 4.1.1 |
| Coroutines | kotlinx-coroutines 1.11.0 |
| Build | JDK 21+, AGP 9.2.1, KSP 2.3.9 |
| Android SDK | minSdk 28, compileSdk 36 |
| iOS | min 17.2 |
| Android bytecode | 21 (`composeApp`), 17 (supporting KMP libraries) |
| Inference | llama.cpp (latest), stable-diffusion.cpp (latest) |

---

## Tests

```bash
./gradlew verifyProject          # Preferred local/CI JVM gate
./gradlew verifySdk -PsdkOnly=true   # Portable SDK without app/native producers
./gradlew :app:composeApp:allTests    # All platform tests
./gradlew :app:composeApp:jvmTest     # JVM tests only
```

GitHub Actions runs `verifyProject` for pull requests and pushes to `main`.

Test coverage includes benchmark helpers, model-generation classification, suitability ratings, architecture detection, and diffusion step policy.

`verifySdk` checks portable JVM tests, common metadata compilation and dependency
boundaries. `verifyProject` adds engine/app JVM suites, desktop launcher compilation,
native pin verification and selected CTests. Android assembly, iOS compilation,
packaging and real-model/device checks remain separate.

```bash
# Opt-in engine JNI fixtures; downloads and verifies fixtures when needed
CARAML_NATIVE_PARITY=true ./gradlew :plugin-llama:jvmTest :plugin-diffusion:jvmTest

# Platform and packaged filesystem checks
./gradlew :app:androidApp:assembleDebug
./gradlew :app:composeApp:compileKotlinIosArm64 :app:composeApp:compileKotlinIosSimulatorArm64
./gradlew :app:desktopApp:artifactFsInstalledImageSmoke
```

### Verification Status

The 2026-10-14 package migration passed 1,492 JVM tests, seven native CTests,
SDK-only verification, desktop JNI fixtures with CheckJNI, Android assembly for
arm64-v8a/x86_64, both iOS Kotlin targets and the packaged desktop filesystem smoke
check. Packaged Android and desktop JNI exports matched the renamed classes.
The iOS checks reused existing engine archives and did not run an installed app.

That run's full `verifyProject` gate failed only at the pre-existing llama.cpp
remote allowlist check. The checkout remote and guard were left unchanged. These
checks do not establish SDK release readiness; the limits above remain open.

---

## Key Concepts

### NativeRunnerConfig

Adapts inference parameters (thread count, GPU layers, context size, KV cache quantization) based on `DeviceCapabilities` — each platform reports its own CPU count, RAM, GPU type.

### GgmlUnified.cmake

Single CMake script builds GGML once from `libraries/llama.cpp`, then both `llama.cpp` and `stable-diffusion.cpp` share it (guarded by `if (NOT TARGET ggml)`). Prevents duplicate symbol errors from linking two GGML builds.

### Patch System

Numbered llama.cpp patches under `libraries/patches/llama.cpp/` are applied to a Gradle-owned source copy by `:nativeEngine:preparePatchedLlamaSource`; the pinned upstream submodule remains immutable.

### iOS Static Lib Merge

iOS requires a single merged `.a` archive (Metal, Accelerate, and GGML frameworks don't support dynamic linking on iOS). The `mergeLlamaRunnerStaticIosArm64` Gradle task runs `libtool -static` to produce `libllama_runner_merged.a`.

---

## Recent Changes

- Published the current Pixel 9 CPU/Vulkan generation benchmark for five LLMs, with quantization, sustained decode ranges and test conditions. Raw validation artifacts are excluded from the repository.

- Enabled and verified the Android Vulkan profile on Pixel 9, repaired shader-generator/universal-ABI builds, and removed the per-layer GGUF metadata path's forced CPU fallback under guarded native memory admission. See [current Android benchmarks](#current-android-benchmarks) for measured performance.

- Validated five LLM families on a connected Pixel 9 through verified downloads, text generation, cancellation and reload; corrected false native progress errors and missing cancellation stop flags. The initial run used a debug CPU build with existing local edits.

- Retired the standalone architecture, evaluation and implementation-evidence documents after consolidating SDK boundaries, current limits and verification guidance into the READMEs and API KDocs. Removed stale report links and aligned the Android Vulkan command with Gradle.

- Standardized first-party packages under `com.debanshu777.caraml`, with reusable libraries under `.sdk`; aligned source directories, imports, JNI symbols, iOS bindings and generated resources.

- Grouped the application under `app/` with conventional `composeApp`, `androidApp`, `desktopApp` and `iosApp` names. Extracted the desktop launcher/packaging and updated Gradle, Xcode, CI and source-path checks. See [app structure and commands](app/README.md).

- Removed the unused root sample/cache folders and moved icon generation into `app/composeApp/tools/`.

- Consolidated the Hugging Face API into `model-discovery`; kept existing platform download/storage and diffusion install integrations in the app. Updated app/CI dependencies with custom-provider isolation coverage; the subsequent package cleanup aligns source and JNI names with their owners.

- Merged `runner` into `plugin-llama` and `diffusionRunner` into `plugin-diffusion`, including native sources, platform bindings, tests and consumer keep rules; updated app dependencies and CI commands.
- Fixed scoped HTTP streaming, redirect credentials, failed-load ownership, concurrent shutdown, closed-stage cleanup, Android runtime library packaging and Release Unicode checks. See [verification status](#verification-status) for the scope of validation.


- Added experimental core, discovery and storage modules, SDK-only verification, optional engine adapters and native feasibility hardening. [Current SDK limits](#current-sdk-limits) track unfinished work; CaraML migration is not complete.

- CI fixes preserve model-search focus across viewport changes and avoid a GCC 13 compiler crash in native preflight error handling.

- Xcode’s Kotlin framework build phase locates JDK 21 and Homebrew native tools directly; no separate build helper script is required.

- Text model loading includes download-state repairs, verified artifacts and guarded native sampling.

- Chat, Images, and Videos now have separate drawer destinations; chat restores its model picker and labeled Send/Stop without inline mode tabs.

- Chat generation now uses available native context with bounded reasoning and explicit incomplete/no-answer states; conversation controls and technical details stay compact.

- Native preflight identifies Gemma assistant models that require a target context; chat reports the actual admission reason instead of labeling every temporary failure as memory/thermal pressure.

- Installed GGUF models with incomplete app metadata use bounded native model/memory preflight, retaining exact artifact verification and load recovery. Known architectures can use verified unified-memory Vulkan; unknown or CPU-only architectures retain CPU loading.

- Persistent rail/sidebar navigation now paints through the landscape leading safe area while keeping controls and route content at their prior safe positions.

- On short landscape windows, Models scrolls its title, tabs, and search field while a status-bar glass layer fades over the moving content. Safe scroll padding keeps the first control clear of the frost without reserving an empty app bar.

- Fixed app bars and composers sample the actual scrolling backdrop and fade continuously into the content. Bottom safe areas now use scroll padding instead of clipping the backdrop above the gesture bar; final actions scroll clear of the glass. Rendered checks cover both edges and touch targets.

- Added shared sticky headers with full-width frosted edges, removed redundant result/file dividers, rebuilt Device info around storage/memory/compute, and corrected the drawer preview crop. SVG action assets now generate one shared icon set (`python3 app/composeApp/tools/generate_app_icons.py`). Layout evidence: `app/composeApp/build/reports/layout-qa-2026-10-06/`.

- Rebalanced the shared typography hierarchy: calmer 24sp page titles, 18sp item titles, readable 14sp descriptions, and consistent chat/Settings roles. Typography before/after evidence is under `app/composeApp/build/reports/typography-qa-2026-10-06/`.

- Design QA simplified Settings and device headers, removed repeated loading/error/generation labels, clarified model browsing actions, and made file selection and 200% text settings easier to use. Before/after Pixel and Compose evidence is generated under `app/composeApp/build/reports/design-qa-2026-10-06/`.

- Unified action buttons around the raised Pocket pal style, with visible quick-tap press-depth motion, disabled and reduced-motion feedback, and matching compact/icon variants across Create, Models, and Settings.

- Corrected sidebar fidelity against the approved mobile reference: semibold labels, outline icons, tilted logo, full-width header, full-page reveal, and back-only navigation on Device info.

- Introduced Pocket pal + Aurora: bundled Bricolage Grotesque/DM Sans, warm light/dark surfaces, and shared orange/yellow/lilac/mint brand tokens.
- Mobile navigation now reveals a rounded, scaled page over the sidebar; Create and Models stay above bottom Settings, with reduced-motion and optional soft effects.
- Model Hub uses flat model rows, inline filters and queue controls, a dedicated device panel, and recoverable search/library/download states; memory estimates remain distinct from unverified compatibility.
- Create follows the approved mobile layout with an inline empty composer; conversations show sender identity, real generation phases, and recovery actions. Keyboard resizing and large-text drafts preserve reachable controls and visible replies.
- Appearance settings persist Keep things still and Soft glass. The character currently uses shared Compose; authored Rive playback remains pending a `.riv` asset and platform adapters.

<!-- This section is updated at the end of each AI-assisted development session -->

- Audited llama.cpp parameter mappings, corrected CPU placement/affinity, cache validation and conversation finalization, and reduced single-output GPU scratch allocation.
- Automatic llama.cpp fitting now accepts full GPU offload and fits the requested context.
- Installed GGUF inspection now accepts tokenizer metadata through a bounded 8 MiB header scan, allowing the existing MiniCPM5 Q4_K_M model to load on Pixel 9
- Model Hub keeps a shared device profile, shows download status below Discover filters, debounces search, and applies draft size limits, auto-loads nearby Paging 3 results, and puts GGUF download controls directly in file rows; verified local GGUF headers can repair installed Text evidence offline
- Model browsing now separates projector, adapter, primary, and unverified GGUF files, shows provisional resource guidance when GGUF compatibility metadata is missing, supports exact grouped downloads for selected shards, and keeps transfer separate from strict load admission

- CI now installs the Android CMake version pinned by the native module; Windows publication uses pinned-directory native renames, large-text empty states remain scroll-reachable across desktop fonts, and diffusion verification uses host-neutral max-VRAM fixtures while avoiding a GCC 13 aggregate-assignment compiler crash
- Completed download records now reconcile their immutable publication before reuse, so removed models can be installed again without redownloading still-published shared components
- Generated-media stores now coordinate active sessions and writes process-wide, evict expired/LRU abandoned sessions, and enforce one aggregate 1 GiB cap; CI also assembles the Android app
- Native engines now use the exact 23 September pair (`llama.cpp` `f46bc30`, stable-diffusion.cpp `c92d73c`) with one shared patched GGML, exact admitted/effective plan checks, and version-namespaced persistence
- Android packages both runners for arm64-v8a/x86_64, while iOS device and simulator archives merge both engines with exactly one GGML implementation
- Artifact now presents identity and the download decision before collapsed technical metadata, with rounded focal/file surfaces and production-component previews at 360–412dp including 200% text
- Flattened Model Hub into a compact registry and integrated Details into the route canvas while preserving exact artifact actions and durable pause/resume/cancel/retry state
- Model downloads now use a persistent resumable queue with exact-artifact verification; Android uses UIDT/foreground notifications, iOS reconnects to a background URLSession, and Desktop resumes on relaunch
- Download admission now evaluates the final immutable request set against canonical published targets and real manifest checkpoints; revision replacement has bounded headroom plus durable, reference-aware, retryable cleanup so repeated installs do not leak superseded generations
- iOS background completions now validate the actual HTTPS response before capture, persist a bounded exact-task provenance envelope for crash recovery, and coalesce callback/startup import and finalization behind URLSession restoration
- Android download startup now has one reconciliation barrier shared by UIDT and WorkManager, collapses duplicate owners in favor of the exact UIDT, persists generation-bound Task Manager stops as resumable pauses, and checkpoints cancelled artifact leases atomically
- Exact model loads now recover and compare the authoritative current owner bundle while holding every expected and candidate repository root on the download subsystem's shared lock, then retain that lifetime through byte revalidation, recovery-marker cleanup, and native open/load; exact Git, LFS, and Xet object IDs are preserved
- Installed artifacts now live only in validated immutable bundle generations; the unused application database starts at schema version 1, corrupt current rows and journals fail closed, Model Details controls bind to the exact current batch/task identity, iOS background payloads require the same exact persisted binding, and reference-aware deletion preserves every still-linked owner
- Reworked Model Hub as a compact registry and made Details artifact-first with reachable metadata, exact artifact actions, and durable pause/resume/cancel/retry state
- Model downloads now use a persistent resumable queue with exact-artifact verification; Android uses UIDT/foreground notifications, iOS restores exact response bounds plus terminal cancellation reasons across relaunch while stopped tasks relinquish ownership before replacement scheduling, and Desktop resumes on relaunch
- Durable downloads retain exact descriptor evidence and bind its digest into batch identity; persisted payloads use capped allocation-free UTF-8 preflight before hashing or parsing, while uncertain descriptors remain enrichment-only
- Existing Ready installs repair missing descriptor metadata once through revision-qualified owner/component lookups pinned to each installed commit, persist the complete evidence for offline reuse, and reject ambiguous identities without touching model files
- Exact repair follows only the Hub's one validated same-repository/same-commit config redirect and rejects deterministic detail/tree/config failures instead of retrying them
- Installed manifest, Ready catalog, and evidence publication now share bounded owner-scoped coordination; repair performs network lookup outside the owner lock, then rechecks the exact durable baseline and uses Room compare-and-set so stale work cannot overwrite a newer installation
- Case-distinct owner repairs never coalesce; a cancelled repair generation permits at most one shared successor, a second leader cancellation terminates that generation for every follower, and Model Hub has no direct Ready-publication fallback
- Inference loading now accepts only a freshly assessed exact `LoadRequest` rebuilt from the complete published bundle, including external-repository artifacts, plus current device/settings state and a newly personalized plan; explicit KV presets and GPU opt-out remain authoritative
- Installed-model switching now releases resident LLM and diffusion runners before the final resource snapshot and assessment; every confirmation/retry/alternative is bound end-to-end to the exact rendered action, load generation, model, and mode, while CPU alternatives require explicit non-CPU candidate provenance and separate assessment
- Installed artifact resolution now requires the authoritative immutable Hub manifest; local-content sidecar identities and the resolving request overload have been removed
- Restored-model native-preflight failures now offer an explicit same-model retry that captures fresh device/settings evidence, revalidates the exact artifact, and requires approval before using a safer CPU plan
- Quarantined retry, model switching, no-model transitions, and ViewModel teardown now share one atomic runner owner and predecessor barrier, so admitted native work finishes before one full teardown or the next exact load
- Reorganized Settings as a dense, accessible list with exclusive selections, disclosure rows, and the app's single contextual appearance preview
- Added a restrained static ambient field and two deliberate grain-backed focal gradients—Details overview and the Settings appearance preview—while keeping navigation, lists, filters, cards, and the composer matte
- Verified the merged design and durable-download behavior with the repository JVM/native gate, Android assembly, iOS simulator compilation, and a Pixel 9 background download through Ready, safer-plan native load, and local generation
- Added versioned recommendation/native fixture gates, opt-in real-runner parity, pinned CI jobs, and exact artifact-bound model selection; production remains on the legacy display path until measured physical-device and pinned-runner release evidence exists
- Added opt-in device calibration with byte-bound descriptor identity, phase-specific raw memory baselines, full run-plan fingerprints, real process-memory provenance, and fail-closed quarantine after unresponsive native probes
- Model loads now derive typed directory targets only from verified storage roots, bind every native-consumed path to revalidated bytes, and keep multi-sequence plans analytical until strict native admission
- Assessed diffusion backends now use stable CPU/Metal/Vulkan/CUDA ABI values across JNI and iOS; native preflight losslessly binds each configured source role/path ordinal separately from bundle-internal tensor subdivisions, keeps external TAESD evidence distinct, and verifies planned placement exactly
- Added bounded, side-effect-free stable-diffusion.cpp preflight with typed component/backend evidence and one shared auto-fit plan for inspection and load
- Diffusion installs now use deterministic manifest-proven checkpoint/directory identities, recover interrupted bundles independently of tree order, and verify portable multi-config Desktop filesystem runtimes before packaging
- Added bounded, device-aware model recommendations with immutable Hugging Face metadata, incremental assessment, and profile-local reranking
- Added persisted Balanced-by-default recommendation profiles with atomic onboarding, rollout-gated Material 3 controls, and rapid-update-safe settings state
- Added bounded profile-neutral model assessment caching, fresh device-snapshot assembly, and privacy-safe debug shadow comparison with release-safe legacy rollout
- Hardened side-effect-free llama.cpp preflight with a thread-independent stream session lease, exact pinned quantization labels, trusted capability evidence, exception-safe transient cleanup, and build-owned patched sources that keep the pinned submodule immutable
- Contained P0/P1 inference failures with live memory admission, cancellable race-safe diffusion handles, failure-atomic llama prompts, bounded disk-backed generated media, and explicit iOS video capability gating
- Hardened inference and downloads against crashes: serialized native LLM access, contained C++ exceptions at JNI/iOS boundaries, handled missing native libraries, validated generation inputs, and preserved coroutine cancellation
- Downloads are now path-contained and transactional across Android, iOS, and Desktop: HTTP status/length checks, `.part` staging, atomic commit where supported, cleanup on interruption, and coalesced progress updates
- Reduced streaming overhead with native deltas, 20 Hz immutable UI snapshots, a single streaming-state collector, and deferred Markdown rendering until generation completes
- Added stale `params_fit` cache invalidation, bounded Hugging Face request inputs, safe context-reset failure reporting, and resilient bulk deletion
- Expanded regression coverage for download integrity/traversal, native-session exclusion, streaming resync/throttling, diffusion bounds, cancellation, and API path construction
- Added a least-privilege GitHub Actions JVM test gate and weekly Dependabot updates; actions are pinned to immutable release commits
- Removed the obsolete Obsidian MCP config and persisted Graphify's `libraries/` exclusion
- Consolidated shared Claude Code and Codex project guidance into `AGENTS.md`; `CLAUDE.md` is now a thin import wrapper containing only Claude-specific model policy
- Added a Graphify knowledge graph for app-owned modules, with interactive HTML, GraphRAG JSON, labeled communities, and an audit report; vendored `libraries/` sources are excluded
- Inference perf: native delta accessors plus bounded UI snapshots avoid per-token cumulative copying and repeated Markdown parsing
- Inference perf: hybrid-SSM arch Vulkan denylist (qwen35, jamba, mamba, etc.) skips doomed first-load GPU attempt; suitability sheet now shows runnability warnings for IQ-quant + CPU-only and hybrid-SSM models
- Reasoning/content split now uses llama.cpp native `common_chat_parse` (per-model chat template), replacing the custom GBNF grammar and name-based classifier
- Fix: bk-sdm-tiny model registry now sets `prediction=0` (EPS) explicitly, preventing `is_using_v_parameterization_for_sd2()` probe
- Fix: `DiffusionInferenceRepository` selfContained branch now propagates `offloadToCpu` from `recommendedParams`
- Fix: Vulkan SIGABRT during UNet compute — `diffusion_conv_direct=true` now forced for all models (bypasses IM2COL path)
- Diffusion optimization pass: SD-Turbo / SDXL-Turbo / LCM-LoRA registry entries now ship correct distilled defaults (4–6 steps, cfg=1.0–1.5, euler_a / lcm sampler), registry-pinned sampler/seed honored in ChatViewModel, flow_shift + free_params_immediately + VAE tiling + optional TAESD path wired through DiffusionModelConfig → JNI/iOS FFI → stable-diffusion.cpp
- Fix: Vulkan crash on SD2 models — ggml-vulkan GROUP_NORM `supports_op` now requires F32, preventing SIGABRT when loading F16-weight models
- Model suitability rating (Poor/Average/Good/Best) with color-coded chips, per-variant dots, and bottom-sheet algorithm explainer
- GPU acceleration and inference performance optimizations (Vulkan Android, Metal iOS)
- Smart install system and live progress tracking for diffusion models
- Structured reasoning support with thinking blocks and markdown rendering
- Native patch system and improved chat template handling
- Dynamic Material 3 theme system and design tokens
- Diffusion chat window UI (in progress)
