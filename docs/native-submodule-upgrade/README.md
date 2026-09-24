# Native Submodule Upgrade Playbook

This document describes how CaraML can adopt recent `llama.cpp` and
`stable-diffusion.cpp` developments without weakening its native-build,
preflight, admission, recovery, or model-compatibility guarantees.

The recommended outcome is not simply "move both gitlinks to HEAD." It is a
paired, reversible migration that first preserves the build and runtime
contracts, then enables upstream improvements behind measured capability
gates.

> **Audit snapshot:** 23 September 2026. Upstream heads move quickly. Re-fetch
> and re-audit candidate commits before starting the migration; do not treat the
> SHAs in this document as automatically approved production pins.

## Executive recommendation

1. Merge the public-gitlink CI repair before starting this upgrade.
2. Upgrade `llama.cpp` and `stable-diffusion.cpp` together in an isolated
   migration branch or worktree.
3. Establish the shared-GGML build and wrapper API compatibility before
   enabling any new feature.
4. Preserve CaraML's deterministic preflight/admission decision. Upstream
   auto-fit may help compute the plan, but native loading must apply the exact
   admitted plan rather than making a second independent decision.
5. Adopt correctness and memory-management improvements before model-family or
   throughput features.
6. Keep every new capability reversible by engine version, backend, and model
   descriptor.

## Snapshot and change boundary

### Repository state at the audit

| Purpose | `llama.cpp` | `stable-diffusion.cpp` |
|---|---|---|
| Branch gitlink before CI repair | `2398f1352562e4013b225ffc0fd6ad69ce85ff0a` | `1b0158226967c12acbc2f2494d7d20a8de6cddb6` |
| Public checkout-repair baseline in the working tree | `876a4321163249c43ca4e986818fab5ab081f282` | `e31a86ce9110b11a98bd5990c329093244c2d1e3` |
| Selected 23 September migration candidate | `f46bc30cb6a7f68a67e34a00061e20a4ad1eff43` | `c92d73c408515c94beef32161bb5960764fde7a0` |
| Directional delta from public baseline | about 901 commits | about 95 commits |

The old llama gitlink is not available from the configured public upstream
remote, which is why recursive checkout failed before CI reached Gradle. The
public checkout repair and the broader native migration are separate changes:

- **CI repair:** restore reproducible public checkout with the smallest diff.
- **Native migration:** update engines, APIs, patches, behavior, tests, and
  feature gates as one reviewed project.

Do not combine them. A failed migration must be revertible without reopening
the checkout incident.

## What CaraML can gain

### Highest-value improvements

| Area | Upstream development | Expected CaraML benefit | Adoption priority |
|---|---|---|---|
| Stable memory placement | Single-GPU auto-fit, tiered parameter placement, explicit-backend preservation | Better fit decisions on constrained devices without overriding deliberate placement | P0 |
| Runtime memory safety | Live-free-memory re-clamping, capacity guards, better GPU-memory error propagation | Fewer optimistic admissions and clearer fallback reasons | P0 |
| Segmented diffusion execution | Automatic graph segmentation, next-segment prefetch, graph-cut reuse | Lower peak accelerator memory and less repeated planning overhead | P0 |
| VAE recovery | Allocation-specific tiled fallback | Recover from genuine OOM without retrying corrupt or incompatible models | P0 |
| Metal correctness | MoE overflow/NaN, flash-attention bounds/capability, OOM and resource-lifetime fixes | More reliable Apple inference before performance tuning | P0 |
| Vulkan correctness | Argsort race/OOB, buffer alignment, queue submission and capability fixes | More reliable Android and desktop GPU execution | P0 |
| Llama fit/load behavior | Stream- and KV-aware fit estimates, lower peak load RAM, integrated-GPU load mode, lazy tensor reading | Better LLM admission and large-model loading | P1 |
| Engine identity | Native engine/model version accessors | Correct cache and quarantine invalidation after an engine upgrade | P1 |
| Image input behavior | Explicit preprocessing, reference-dimension preservation, alpha-aware paths | Safer image editing and conditioning | P1 |
| New model families | Qwen Image 2.1, LLaDA-Image, SenseNova U1.5, Wan S2V, LTX-2.5, MiniMax-H3 | Broader product capability after metadata and artifact contracts exist | P1/P2 |

### Important constraint

`stable-diffusion.cpp` can now build against an upstream or system GGML tree.
This is a strong fit for CaraML's one-GGML architecture, but upstream-GGML mode
does not necessarily provide every behavior from stable-diffusion.cpp's patched
GGML copy. In particular, patched-only INT8 convolution/rotation and some FP8
paths may be rejected or converted.

The shared-GGML migration should therefore optimize for one consistent native
runtime first. FP8/INT8 acceleration should remain experimental until CaraML's
actual model inventory passes load, output-quality, and performance tests.

## Existing CaraML contract to preserve

The current ownership flow is:

```text
ModelDescriptor and artifact evidence
              |
              v
RunnerBackendCapabilitySource
              |
              v
Suitability / run-plan generation
              |
              v
LoadAdmissionController ----> LoadRecoveryRepository
              |                         |
              v                         v
     native preflight             quarantine/retry
              |
              v
exact llama/diffusion load configuration
```

Relevant implementation boundaries:

| Responsibility | Primary location |
|---|---|
| Shared GGML and platform native graph | `nativeEngine/src/commonCpp/GgmlUnified.cmake` |
| Llama source/build integration | `runner/src/commonCpp/LlamaRunnerCommon.cmake` |
| Llama fit and load plan | `runner/src/commonCpp/llama_runner_core.cpp` |
| Diffusion source/build integration | `diffusionRunner/src/commonCpp/DiffusionRunnerCommon.cmake` |
| Diffusion preflight and load plan | `diffusionRunner/src/commonCpp/diffusion_runner_core.cpp` |
| Diffusion component classification | `diffusionRunner/src/commonCpp/diffusion_runner_preflight_internal.h` |
| Cross-engine backend identity | `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/platform/RunnerBackendCapabilitySource.kt` |
| Admission decision | `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/LoadAdmissionController.kt` |
| Recovery and quarantine | `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/LoadRecoveryRepository.kt` |
| Model/artifact capabilities | `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/ModelDescriptor.kt` |
| Engine-version wiring | `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/di/AppModule.kt` |

### Non-negotiable planner rule

There must be one authoritative decision:

1. Probe backend identity, usable memory, model structure, and required
   components.
2. Produce one exact placement, budget, segmentation, and fallback plan.
3. Admit or reject that exact plan in Kotlin.
4. Apply the admitted plan in native code.
5. Return the actual placement and recovery behavior so it can be compared with
   the admitted plan.

Allowing upstream auto-fit to make a new decision during `new_sd_ctx()` after
CaraML already admitted a plan creates a time-of-check/time-of-use mismatch.
CaraML should reuse upstream's improved planning rules, freeze the result, then
continue loading with `auto_fit = false`.

## Recommended migration phases

Each phase has its own stop condition. Do not proceed because the code merely
compiles.

### Phase 0: finish and freeze the checkout repair

Before the migration:

- Merge a commit in which every gitlink is fetchable from its declared public
  HTTPS remote.
- Confirm recursive checkout in a clean clone.
- Record the repaired SHAs as the rollback baseline.
- Confirm the existing patch stack applies to the build-owned llama source.
- Run the repository's existing verification gate.

Exit evidence:

- Clean-clone recursive checkout succeeds.
- `./gradlew verifyProject --no-daemon` passes on the repaired baseline.
- The native build has not yet changed behavior.

### Phase 1: select a paired candidate

Select exact commits from a deliberate date, not floating branches. Review both
histories between the repaired baseline and candidate, including build files,
public structs, exported functions, GGML changes, and relevant backend code.

Useful read-only checks:

```bash
git status --short
git submodule status --recursive
git -C libraries/llama.cpp remote -v
git -C libraries/stable-diffusion.cpp remote -v
git -C libraries/llama.cpp log --oneline <baseline>..<candidate>
git -C libraries/stable-diffusion.cpp log --oneline <baseline>..<candidate>
```

Security and reproducibility requirements:

- Keep public, allowlisted HTTPS remotes in `.gitmodules`.
- Pin exact commit objects; never make CI follow a branch.
- Confirm both commit objects are available from the configured remotes before
  changing the gitlinks.
- Review nested submodule changes rather than enabling recursive content
  implicitly.
- Keep CaraML modifications in `libraries/patches/`, not as unpublished commits
  inside a submodule.

Exit evidence:

- A migration note records the baseline pair, candidate pair, upstream range,
  known API breaks, and rollback pair.
- Both candidate objects are fetchable in a clean environment.

### Phase 2: unify the GGML build contract

Adopt stable-diffusion.cpp's upstream-GGML mode before changing runtime
behavior:

```cmake
set(SD_USE_UPSTREAM_GGML ON)
set(SD_GGML_SOURCE_DIR "${LLAMA_CPP_DIR}")
```

The exact variables must be revalidated against the chosen candidate. Update
these files together:

- `nativeEngine/src/commonCpp/GgmlUnified.cmake`
- `runner/src/commonCpp/LlamaRunnerCommon.cmake`
- `diffusionRunner/src/commonCpp/DiffusionRunnerCommon.cmake`
- Platform `CMakeLists.txt` files under `nativeEngine/src/*Main/cpp/`

Required checks:

- Only one intended GGML implementation is compiled and linked.
- There are no duplicate GGML symbols.
- Stable's headers and private implementation assumptions match the selected
  llama GGML tree.
- Android Vulkan, Apple Metal, desktop CPU, and iOS static merging still select
  the intended backends.
- FP8/INT8 model compatibility is explicitly recorded rather than inferred.

Exit evidence:

- Desktop shared libraries, Android native ABIs, and both iOS static targets
  compile and link.
- Packaged artifacts contain the expected native libraries and exported bridge
  symbols.

### Phase 3: migrate wrapper APIs without enabling features

#### Llama wrapper

Expected migration areas:

| Upstream surface | CaraML action |
|---|---|
| `common_fit_params` adds an extra-model argument | Pass `nullptr` initially. Add a secondary model only after speculative/MTP ownership is designed. |
| `llama_model_params.lazy_mode` | Start with the upstream default or `AUTO`; hide it behind an internal capability flag. |
| `llama_context_params.n_outputs_max_per_seq` | Set deliberately or preserve the documented default; cover multi-sequence behavior. |
| Sampler/common helpers | Reconcile compile changes while preserving CaraML stop, cancellation, and streaming semantics. |
| `llama_version()` | Surface a bounded, sanitized engine identifier to Kotlin. |

Do not expose lazy mode directly as an unrestricted user setting. It affects
filesystem access patterns, load latency, memory residency, and generation-time
I/O stalls. First treat it as a device/model policy selected by CaraML.

#### Diffusion wrapper

Expected migration areas:

| Upstream surface | CaraML action |
|---|---|
| `stream_layers` replaced by segmented-compute controls | Replace the one boolean with independent prefetch and segmentation decisions. |
| `disable_prefetch` | `false` permits asynchronous next-segment transfer. |
| `disable_segmented_compute` | `false` permits automatic graph segmentation; `true` forces monolithic compute. |
| `max_vram` semantics | Treat it as a managed per-device budget; zero may use live free memory and is not a hard physical limit. |
| `auto_fit` defaults on | Continue applying CaraML's exact plan with `auto_fit = false`. |
| Tokenizer, SageAttention, scale, and audio fields | Initialize from upstream defaults; map only explicitly supported capabilities. |
| Video generation output FPS | Return the effective `fps_out` through native and Kotlin wrappers. |
| `sd_get_model_version_name()` | Add it to bounded diagnostics and engine/model identity. |

The old `stream_layers = false` value is ambiguous after migration. It could
mean either:

- keep segmentation but disable asynchronous prefetch, or
- disable segmentation entirely.

Do not silently pick one. Represent these as two internal settings and choose
defaults from measured backend behavior.

Exit evidence:

- Wrappers compile on every target.
- Existing configurations preserve baseline behavior.
- New fields are initialized through safe upstream defaults.
- No new model or optimization is enabled yet.

### Phase 4: rebase and prove the local patch stack

CaraML currently applies three llama patches to a Gradle-owned source copy.
Their disposition is:

| Patch | Decision | Required proof |
|---|---|---|
| `0001-metal-pin-shading-language-version.patch` | Rewrite; do not blindly remove | Allow Metal 4.0 where the tensor API is available, while preserving explicit 3.2/3.1 selection for older bf16-capable platforms. Keep a missing-pipeline regression. |
| `0002-vulkan-norm-require-f32.patch` | Rebase and retain | The audited upstream support predicates still did not enforce every CaraML F32 output constraint for group norm/timestep embedding. Prove unsupported types fall back safely. |
| `0003-fit-memory-probe-raii.patch` | Retain pending failure proof | Inject model, context, allocation, and logger failures. Remove only if upstream demonstrably releases transient handles and restores global logging on every path. |

Patch rules:

- Recreate patches against the selected upstream candidate; do not edit the
  pinned submodule to make a patch appear clean.
- Keep each patch single-purpose and independently testable.
- Document the upstream issue/commit that would allow later retirement.
- Treat a patch that applies successfully as syntactic evidence only.

Exit evidence:

- `./gradlew :nativeEngine:preparePatchedLlamaSource --no-daemon` succeeds from
  a clean build-owned source tree.
- Targeted regression tests fail without their patch and pass with it.
- Repeated failure paths show no leaked model/context/logger state.

### Phase 5: integrate memory and correctness improvements

#### Diffusion planner integration

Adopt these behaviors into CaraML's preflight/run-plan model:

- Clamp the requested device budget to trustworthy live free memory.
- Reject impossible or obviously invalid backend memory reports.
- Preserve explicit runtime and parameter-backend constraints.
- Prefer placement in a documented order such as diffusion model, text encoder,
  then VAE only if it matches the chosen upstream planner.
- Record which tensors/components are assigned to accelerator memory, host RAM,
  another device, or disk-backed storage.
- Enable segmented graph execution independently of prefetch.
- Reuse graph-cut plans when only CFG/scale values change and topology remains
  compatible.
- Retry VAE tiling only for classified allocation failures.
- Never convert model corruption, unsupported operations, or invalid metadata
  into an OOM retry.

#### Llama planner integration

Adopt and validate:

- Fit accounting for streams and unified KV/context allocation.
- MTP/extra-model allocation only when CaraML owns that secondary model.
- Reduced transient RAM during model load.
- Integrated-GPU-aware mmap/load policy.
- Lazy row loading for eligible large tensors under `AUTO` policy.

#### Runtime evidence

Extend the native result returned to Kotlin with bounded data sufficient to
verify the plan:

- engine version;
- selected backend and canonical device identity;
- admitted and effective memory budget;
- component/tensor placement summary;
- segmentation and prefetch state;
- VAE tiling/fallback state;
- stable, non-sensitive failure classification.

Do not log prompts, outputs, arbitrary paths, model tokens, or raw driver error
payloads. Keep diagnostics bounded and privacy-safe.

Exit evidence:

- Preflight and actual load agree on the selected plan.
- Mismatches fail closed with a typed error.
- Allocation failures use only the intended recovery path.
- Existing quarantine and explicit-retry tests continue to pass.

### Phase 6: version and roll out capabilities

Replace the fixed `native-engine-v1` identity with a stable bounded identifier
derived from the CaraML native integration version and the pinned engine pair.
Do not use an unbounded upstream description string directly as a persistence
key.

The new identity must namespace:

- load-recovery/quarantine entries;
- calibration data;
- model assessment caches;
- performance observations;
- compatibility results.

This prevents failures learned under the old native pair from incorrectly
blocking the new engine.

Recommended capability flags:

- engine-pair rollout;
- llama lazy-load `AUTO`;
- diffusion segmented compute;
- diffusion prefetch;
- backend-specific FP8;
- model-family support;
- preprocessing/alpha/reference-image behavior.

Flags should be independently reversible. A Vulkan regression should not
require disabling a Metal improvement or reverting model metadata changes.

Exit evidence:

- Old persisted evidence is ignored or migrated intentionally.
- Each capability can be disabled without changing submodule SHAs.
- Rollback to the baseline pair does not require a data wipe.

### Phase 7: add model families only through descriptors

New upstream recognition is necessary but not sufficient for CaraML support.
For each family, add:

1. An explicit architecture/capability identifier.
2. Required and optional artifact roles.
3. Expected formats and bounded sizes.
4. Preprocessing and reference-input rules.
5. Backend/quantization compatibility.
6. Memory estimation inputs.
7. Preflight evidence and failure classifications.
8. Download bundle/manifest handling.
9. UI capability exposure only after successful assessment.

Recommended ordering:

| Order | Capability | Recommendation |
|---|---|---|
| 1 | Image preprocessing, reference dimensions, alpha preservation | Enable first; it improves correctness for existing and future models. |
| 2 | Qwen Image 2.1 | Pilot behind a descriptor and artifact-bundle gate. |
| 3 | LLaDA-Image / SenseNova U1.5 | Evaluate only when their complete component contracts are modeled. |
| 4 | Generalized temporal VAE tiling | Benchmark with existing supported video paths. |
| 5 | Wan S2V / LTX-2.5 / MiniMax-H3 | Defer until audio input, multi-artifact download, cadence, and output contracts are production-ready. |
| Experimental | Native FP8 and CUDA SageAttention | Enable only on demonstrated compatible backends and model types. |

Never infer production support from a repository name, filename, or a successful
metadata parse alone.

## Validation and promotion matrix

### Required gates

| Gate | Coverage | Evidence |
|---|---|---|
| Checkout | Fresh clone and recursive submodules | Both exact commits fetched from declared remotes |
| Patch preparation | Clean build-owned source | All patches apply and targeted tests prove behavior |
| JVM project gate | Shared Kotlin/JVM behavior | `./gradlew verifyProject --no-daemon` |
| Desktop native | macOS Metal and Linux CPU where available | Compile/link, native regression suite, packaged-library smoke test |
| Android native | `arm64-v8a` and `x86_64`; Vulkan enabled/disabled | Compile/link, JNI symbols, APK packaging, device smoke tests |
| iOS native | Device and simulator ARM64 | Static merge, cinterop link, load/generate/unload smoke tests |
| Correctness | Representative LLM, image and video fixtures | No NaN/blank output; golden/tolerance checks; alpha/reference dimensions preserved |
| Memory | Constrained and fragmented environments | Peak RSS/VRAM, admitted/effective budget, placement and fallback reason |
| Lifecycle | Repeated load/generate/cancel/unload | No leak, race, stale native global, or logger contamination |
| Recovery | Injected OOM, corrupt and incompatible artifacts | OOM may retry safely; semantic/security failures stop immediately |
| Performance | Cold and warm runs | Load time, first-token/image latency, throughput, seconds/step, peak memory |
| Persistence | Upgrade and rollback engine identities | No cross-version quarantine or calibration poisoning |

### Suggested device coverage

- Apple Silicon generations available to the project, plus an iOS device and
  simulator.
- Android Adreno and Mali devices for Vulkan behavior.
- Intel Xe and NVIDIA Vulkan desktops where accessible.
- CPU-only desktop as the deterministic fallback baseline.

Backend results must remain separate. A Metal win is not evidence of an Android
Vulkan win.

### Benchmark protocol

For each candidate and baseline:

1. Use the same model artifact digest, prompt/input, seed, dimensions, steps,
   thread count, context, batch, and device power state.
2. Run cold-load and warm-load samples separately.
3. Record median and tail latency, not only the best run.
4. Record peak host and accelerator memory.
5. Record admitted plan, actual plan, fallback, and retry count.
6. Compare output correctness before declaring a performance gain.
7. Keep raw prompts and generated content out of shared telemetry.

## Definition of done

The paired upgrade is ready to promote only when all of the following are true:

- Both submodule commits are publicly fetchable and exactly pinned.
- Every supported target compiles, links, and packages the expected libraries.
- The local patch stack has behavioral regression proof.
- Preflight and actual load report the same placement and effective budget.
- Representative outputs are equivalent or intentionally improved.
- Peak memory and latency are no worse than the approved thresholds.
- Repeated lifecycle and failure-injection tests show no leak, race, or stale
  native state.
- Quarantine, calibration, and assessment data are namespaced to the new engine
  identity.
- New model families are descriptor- and artifact-gated.
- Every new optimization can be disabled independently.
- The old engine pair remains a tested rollback target.

## Rollback plan

Rollback must require only controlled configuration/source changes:

1. Disable the affected capability flag.
2. If necessary, restore the known-good paired gitlinks.
3. Restore the matching patch set and wrapper compatibility layer.
4. Rebuild native outputs from a clean build-owned source directory.
5. Keep new-version quarantine/calibration records isolated by engine identity;
   do not delete user model data.
6. Re-run the baseline project and platform gates.

Do not require reinstalling the application or deleting private databases to
complete an engine rollback.

## Proposed PR sequence

Keep review units narrow and independently reversible:

1. **Public gitlink repair** — checkout only; no broad engine behavior changes.
2. **Paired candidate + shared-GGML build** — CMake and compile compatibility.
3. **Wrapper API migration** — same behavior under the new structs/functions.
4. **Patch-stack rebase** — one commit per independently proven patch where
   practical.
5. **Planner and runtime evidence** — memory/correctness integration.
6. **Engine-version persistence boundary** — quarantine/calibration/cache keys.
7. **Capability pilots** — lazy load, segmentation/prefetch, preprocessing.
8. **Model-family PRs** — one family and its complete artifact contract per PR.

This sequence keeps a new model or optimization from obscuring a lower-level
native regression.

## Upstream source trail

Representative upstream commits examined during the audit:

### stable-diffusion.cpp

- [Single-GPU auto-fit and tiered placement](https://github.com/leejet/stable-diffusion.cpp/commit/80bac2d)
- [Preserve explicit backend assignments](https://github.com/leejet/stable-diffusion.cpp/commit/44dd137)
- [Robust GPU-memory reporting and encoding failures](https://github.com/leejet/stable-diffusion.cpp/commit/6dcb5bb)
- [Support upstream GGML builds](https://github.com/leejet/stable-diffusion.cpp/commit/1330ceb)
- [Qwen Image 2.1 support](https://github.com/leejet/stable-diffusion.cpp/commit/137f740)

### llama.cpp

- [Metal 4 tensor API](https://github.com/ggml-org/llama.cpp/commit/d5d993a09)
- [Metal MoE overflow/NaN correction](https://github.com/ggml-org/llama.cpp/commit/0a8b29a60)
- [Metal flash-attention mask bounds](https://github.com/ggml-org/llama.cpp/commit/fb34fc262)
- [Vulkan argsort race/OOB correction](https://github.com/ggml-org/llama.cpp/commit/481c65f09)
- [Integrated-GPU-aware automatic load mode](https://github.com/ggml-org/llama.cpp/commit/153d324bc)
- [Lazy tensor reading](https://github.com/ggml-org/llama.cpp/commit/fac889fb3)

These links are evidence anchors, not a substitute for reviewing the complete
range selected for the migration.
