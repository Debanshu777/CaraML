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

## Module Structure

```
CaraML/
├── composeApp/          # Shared Compose UI + MVVM features
├── runner/              # Kotlin wrapper for llama.cpp (LLM inference)
├── diffusionRunner/     # Kotlin wrapper for stable-diffusion.cpp
├── huggingFaceManager/  # HuggingFace API client + download manager
├── nativeEngine/        # CMake build orchestration for all native libs
├── libraries/
│   ├── llama.cpp/       # Git submodule: ggerganov/llama.cpp
│   ├── stable-diffusion.cpp/ # Git submodule: leejet/stable-diffusion.cpp
│   └── patches/         # Applied patches to vendored libraries
└── iosApp/              # Native iOS Xcode project (SwiftUI wrapper)
```

See each module's own README for details.

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
git submodule update --init --recursive
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
./gradlew :composeApp:assembleDebug

# Desktop — build and run directly
./gradlew :composeApp:run

# iOS — open iosApp/ in Xcode
```

### Android with Vulkan GPU (experimental)

```bash
./gradlew :composeApp:assembleDebug -PENABLE_VULKAN_ANDROID=true
```

Requires Vulkan SDK and `glslc` in PATH. Expected 5–8× token-per-second improvement on compatible GPUs (Mali, Adreno). See [`docs/vulkan-android-build-strategy.md`](./docs/vulkan-android-build-strategy.md).

---

## Architecture

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
./gradlew :composeApp:allTests    # All platform tests
./gradlew :composeApp:jvmTest     # JVM tests only
```

GitHub Actions runs `verifyProject` for pull requests and pushes to `main`.

Test coverage includes benchmark helpers, model-generation classification, suitability ratings, architecture detection, and diffusion step policy.

---

## Key Concepts

### NativeRunnerConfig

Adapts inference parameters (thread count, GPU layers, context size, KV cache quantization) based on `DeviceCapabilities` — each platform reports its own CPU count, RAM, GPU type.

### GgmlUnified.cmake

Single CMake script builds GGML once from `libraries/llama.cpp`, then both `llama.cpp` and `stable-diffusion.cpp` share it (guarded by `if (NOT TARGET ggml)`). Prevents duplicate symbol errors from linking two GGML builds.

### Patch System

Patches under `libraries/patches/<submodule>/` are applied before native compilation (`applyNativePatches` Gradle task) and reverted cleanly before submodule bumps (`revertNativePatches`).

### iOS Static Lib Merge

iOS requires a single merged `.a` archive (Metal, Accelerate, and GGML frameworks don't support dynamic linking on iOS). The `mergeLlamaRunnerStaticIosArm64` Gradle task runs `libtool -static` to produce `libllama_runner_merged.a`.

---

## Recent Changes

<!-- This section is updated at the end of each AI-assisted development session -->

- Rebuilt CaraML as a sidebar-first Prism workbench: compact windows use a stationary-content modal panel, tablets use a compact rail, and wider workspaces use a labeled sidebar with contextual generation modes
- Reframed Create around one focused command composer and explicit activity states; generation modes remain local state while the Create destination stays selected
- Reworked Model Hub as a compact registry and made Details artifact-first with reachable metadata, exact artifact actions, and durable pause/resume/cancel/retry state
- Model downloads now use a persistent resumable queue with exact-artifact verification; Android uses UIDT/foreground notifications, iOS reconnects to a background URLSession, and Desktop resumes on relaunch
- Durable downloads now retain codec-validated exact descriptor evidence for complete artifact-set matches and bind its digest into batch identity; uncertain descriptors remain enrichment-only
- Existing Ready installs repair missing descriptor metadata once through revision-qualified owner/component lookups pinned to each installed commit, persist the complete evidence for offline reuse, and reject ambiguous identities without touching model files
- Inference loading now accepts only a freshly assessed exact `LoadRequest` rebuilt from the complete published bundle, including external-repository artifacts, plus current device/settings state and a newly personalized plan; explicit KV presets and GPU opt-out remain authoritative
- Restored-model native-preflight failures now offer an explicit same-model retry that captures fresh device/settings evidence, revalidates the exact artifact, and requires approval before using a safer CPU plan
- Reorganized Settings as a dense, accessible list with exclusive selections, disclosure rows, and the app's single contextual appearance preview
- Added a restrained static ambient field and two deliberate grain-backed focal gradients—Details overview and the Settings appearance preview—while keeping navigation, lists, filters, cards, and the composer matte
- Verified the sidebar shell with focused and repository-wide JVM/native gates, Android debug assembly, and iOS simulator compilation; the fresh physical-device visual and animation matrix remains unverified because the Pixel was no longer attached at the final checkpoint
- Added versioned recommendation/native fixture gates, opt-in real-runner parity, pinned CI jobs, and exact artifact-bound model selection; production remains on the legacy display path until measured physical-device and pinned-runner release evidence exists
- Added opt-in device calibration with byte-bound descriptor identity, phase-specific raw memory baselines, full run-plan fingerprints, real process-memory provenance, and fail-closed quarantine after unresponsive native probes
- Model loads now derive typed directory targets only from verified storage roots, bind every native-consumed path to revalidated bytes, and keep multi-sequence plans analytical until strict native admission
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
- Fix: SD Vulkan SIGABRT on Mali-G715/Adreno — `SD_VULKAN` decoupled from `GGML_VULKAN` in Android CMakeLists; `SD_VULKAN=OFF` compiles stable-diffusion.cpp without `SD_USE_VULKAN`, preventing `GGMLRunner` from initializing Vulkan for image generation; `GGML_VULKAN` stays ON for LLM inference; root cause was `ggml_extend.hpp:1967` unconditionally offloading UNet params to Vulkan at inference time regardless of config flags
- Fix: bk-sdm-tiny model registry now sets `prediction=0` (EPS) explicitly, preventing `is_using_v_parameterization_for_sd2()` probe
- Fix: `DiffusionInferenceRepository` selfContained branch now propagates `offloadToCpu` from `recommendedParams`
- Fix: Vulkan SIGABRT during UNet compute — `diffusion_conv_direct=true` now forced for all models (bypasses IM2COL path)
- Fix: Vulkan crash during image generation — CLIP + VAE now auto-pinned to CPU backend on Vulkan-Android
- Diffusion optimization pass: SD-Turbo / SDXL-Turbo / LCM-LoRA registry entries now ship correct distilled defaults (4–6 steps, cfg=1.0–1.5, euler_a / lcm sampler), registry-pinned sampler/seed honored in ChatViewModel, flow_shift + free_params_immediately + VAE tiling + optional TAESD path wired through DiffusionModelConfig → JNI/iOS FFI → stable-diffusion.cpp
- Fix: Vulkan crash on SD2 models — ggml-vulkan GROUP_NORM `supports_op` now requires F32, preventing SIGABRT when loading F16-weight models
- Model suitability rating (Poor/Average/Good/Best) with color-coded chips, per-variant dots, and bottom-sheet algorithm explainer
- GPU acceleration and inference performance optimizations (Vulkan Android, Metal iOS)
- Smart install system and live progress tracking for diffusion models
- Structured reasoning support with thinking blocks and markdown rendering
- Native patch system and improved chat template handling
- Dynamic Material 3 theme system and design tokens
- Diffusion chat window UI (in progress)
