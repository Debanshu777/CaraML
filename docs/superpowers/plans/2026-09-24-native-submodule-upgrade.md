# Native Submodule Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade CaraML to the 23 September 2026 llama.cpp and stable-diffusion.cpp snapshots while preserving deterministic admission, adding bounded engine identity, and exposing the new lazy-load and segmented-compute controls behind reversible internal policy.

**Architecture:** Pin the two engines as one compatibility pair, build stable-diffusion.cpp against llama.cpp's single GGML tree, and migrate the native/Kotlin wrappers without allowing upstream load-time auto-fit to overrule CaraML's admitted plan. Keep correctness patches and runtime evidence at the ownership boundaries, then activate new behavior through explicit config fields whose conservative defaults preserve the current execution path.

**Tech Stack:** Kotlin Multiplatform, Kotlin/JNI, Kotlin/Native cinterop, C++17, CMake, Gradle, llama.cpp, stable-diffusion.cpp, GGML.

**Spec:** `docs/native-submodule-upgrade/README.md`

## Global Constraints

- Pin llama.cpp exactly to `f46bc30cb6a7f68a67e34a00061e20a4ad1eff43` and stable-diffusion.cpp exactly to `c92d73c408515c94beef32161bb5960764fde7a0`.
- Preserve the public checkout-repair pair `876a4321163249c43ca4e986818fab5ab081f282` / `e31a86ce9110b11a98bd5990c329093244c2d1e3` as the rollback baseline.
- Keep `.gitmodules` on the existing public HTTPS remotes and never make CI follow a branch.
- Compile and link one GGML implementation from the build-owned patched llama.cpp source; stable-diffusion.cpp must use `SD_USE_UPSTREAM_GGML=ON`.
- Kotlin owns the admitted placement and budget. The values passed to `new_sd_ctx()` must keep `auto_fit = false`.
- New llama lazy loading defaults to `AUTO`; it is internal policy, not a user-editable unrestricted setting.
- Diffusion segmented compute and prefetch are separate controls; both default off until their capability gate is explicitly enabled.
- Diagnostics are bounded to stable labels and numeric summaries. Never include prompts, generated output, arbitrary paths, tokens, or raw driver messages.
- Keep FP8, INT8 convolution/rotation, SageAttention, audio inputs, and new model families disabled in this migration; they require separate descriptor and fixture plans.
- Do not delete private databases, downloaded models, or application data during upgrade or rollback.

## Review Focus

- A non-fetchable or wrong gitlink must fail `verifyNativeSubmodulePins` before a native build starts.
- A diffusion plan with segmentation disabled and prefetch disabled must remain monolithic after the wrapper migration.
- A plan admitted with a fixed backend/budget must reach `new_sd_ctx()` unchanged and with upstream `auto_fit` disabled.
- Invalid or unbounded upstream version strings must fail closed to the fixed pair identity rather than enter persistence keys.
- Non-F32 Vulkan group norm, norm, L2 norm, or timestep embedding must be rejected by the backend support predicate and safely fall back.

---

### Task 0: Verify and freeze the existing public-checkout repair

**Files:**
- Preserve and verify the current changes in `.github/workflows/ci.yml`, `README.md`, `runner/README.md`, `diffusionRunner/README.md`, `nativeEngine/README.md`, `libraries/patches/README.md`, the three native CMake integration files, and the two rollback gitlinks.
- Test: existing Gradle source-isolation, native, and project verification tasks.

**Interfaces:**
- Consumes: the already-present working-tree repair at llama.cpp `876a4321163249c43ca4e986818fab5ab081f282` and stable-diffusion.cpp `e31a86ce9110b11a98bd5990c329093244c2d1e3`.
- Produces: a separately reviewable, publicly fetchable rollback baseline with no September feature adoption.

- [ ] **Step 1: Review the existing repair diff without rewriting it**

Run:

```bash
git status --short
git diff -- .github/workflows/ci.yml README.md runner/README.md diffusionRunner/README.md nativeEngine/README.md libraries/patches/README.md nativeEngine/src/commonCpp/GgmlUnified.cmake nativeEngine/src/jvmMain/cpp/CMakeLists.txt runner/src/commonCpp/LlamaRunnerCommon.cmake diffusionRunner/src/commonCpp/DiffusionRunnerCommon.cmake
git diff --submodule=log -- libraries/llama.cpp libraries/stable-diffusion.cpp
```

Expected: only the documented top-level checkout, public gitlink, build-owned patch, and shared-GGML repair is present in this review unit.

- [ ] **Step 2: Prove the rollback submodules and patch source are clean**

Run:

```bash
git -C libraries/llama.cpp rev-parse HEAD
git -C libraries/stable-diffusion.cpp rev-parse HEAD
git -C libraries/llama.cpp status --porcelain
git -C libraries/stable-diffusion.cpp status --porcelain
./gradlew :nativeEngine:preparePatchedLlamaSource --no-daemon
```

Expected: exact rollback SHAs, empty submodule status, and a successful isolated patch preparation.

- [ ] **Step 3: Run the repaired baseline gate**

Run:

```bash
./gradlew verifyProject --no-daemon --no-parallel -Pkotlin.incremental=false
```

If the Gradle cache lock is denied by the sandbox, rerun with the required workspace permission; classify credential or filesystem denials as environment blockers, not test failures.

- [ ] **Step 4: Prove public top-level checkout in a clean temporary clone**

Clone the repository into a `mktemp -d` directory at the repair commit, run `git submodule update --init`, and assert both submodule HEADs equal the rollback pair. Do not use `--recursive`; the production build does not consume the historical nested stable-diffusion submodules.

- [ ] **Step 5: Commit the repair as its own boundary**

```bash
git add .github/workflows/ci.yml README.md runner/README.md diffusionRunner/README.md nativeEngine/README.md libraries/patches/README.md nativeEngine/src/commonCpp/GgmlUnified.cmake nativeEngine/src/jvmMain/cpp/CMakeLists.txt runner/src/commonCpp/LlamaRunnerCommon.cmake diffusionRunner/src/commonCpp/DiffusionRunnerCommon.cmake libraries/llama.cpp libraries/stable-diffusion.cpp
git commit -m "fix(ci): restore public native submodule checkout"
```

### Task 1: Freeze the paired candidate and rollback contract

**Files:**
- Create: `nativeEngine/src/nativeTest/scripts/native_submodule_pin_test.sh`
- Modify: `nativeEngine/build.gradle.kts`
- Modify: `docs/native-submodule-upgrade/README.md`
- Test: `nativeEngine/src/nativeTest/scripts/native_submodule_pin_test.sh`

**Interfaces:**
- Consumes: the top-level gitlinks and `.gitmodules` public HTTPS URLs.
- Produces: Gradle task `verifyNativeSubmodulePins` and an auditable candidate/rollback record.

- [ ] **Step 1: Write the failing pin test**

Create an executable shell test that resolves the repository root without accepting caller-controlled paths, then compares the staged/committed gitlinks and checked-out submodule HEADs:

```sh
EXPECTED_LLAMA=f46bc30cb6a7f68a67e34a00061e20a4ad1eff43
EXPECTED_DIFFUSION=c92d73c408515c94beef32161bb5960764fde7a0

test "$(git -C "$repo_root" ls-files --stage libraries/llama.cpp | awk '{print $2}')" = "$EXPECTED_LLAMA"
test "$(git -C "$repo_root" ls-files --stage libraries/stable-diffusion.cpp | awk '{print $2}')" = "$EXPECTED_DIFFUSION"
test "$(git -C "$repo_root/libraries/llama.cpp" rev-parse HEAD)" = "$EXPECTED_LLAMA"
test "$(git -C "$repo_root/libraries/stable-diffusion.cpp" rev-parse HEAD)" = "$EXPECTED_DIFFUSION"
test "$(git -C "$repo_root/libraries/llama.cpp" remote get-url origin)" = "https://github.com/ggerganov/llama.cpp.git"
test "$(git -C "$repo_root/libraries/stable-diffusion.cpp" remote get-url origin)" = "https://github.com/leejet/stable-diffusion.cpp.git"
```

Reject missing commits with `git -C <submodule> cat-file -e <sha>^{commit}` and reject dirty submodule worktrees with `git -C <submodule> status --porcelain`.

- [ ] **Step 2: Run the test to verify it fails on the rollback pins**

Run: `bash nativeEngine/src/nativeTest/scripts/native_submodule_pin_test.sh`

Expected: FAIL because the working tree still points to `876a432…` / `e31a86c…`.

- [ ] **Step 3: Pin the paired candidate**

Checkout the exact candidate commit in each submodule and stage only the two gitlinks when this task is committed:

```bash
git -C libraries/llama.cpp checkout --detach f46bc30cb6a7f68a67e34a00061e20a4ad1eff43
git -C libraries/stable-diffusion.cpp checkout --detach c92d73c408515c94beef32161bb5960764fde7a0
git add libraries/llama.cpp libraries/stable-diffusion.cpp
```

Add `verifyNativeSubmodulePins` as an `Exec` task in `nativeEngine/build.gradle.kts`; make `verifyProject` depend on it through the existing native verification wiring. Update the audit table to mark the candidate pair as selected while retaining the rollback pair.

- [ ] **Step 4: Run the pin and checkout checks**

Run:

```bash
bash nativeEngine/src/nativeTest/scripts/native_submodule_pin_test.sh
git submodule status
git -C libraries/llama.cpp merge-base --is-ancestor 876a4321163249c43ca4e986818fab5ab081f282 f46bc30cb6a7f68a67e34a00061e20a4ad1eff43
git -C libraries/stable-diffusion.cpp merge-base --is-ancestor e31a86ce9110b11a98bd5990c329093244c2d1e3 c92d73c408515c94beef32161bb5960764fde7a0
```

Expected: PASS; both submodules are detached at the exact public candidates and clean.

- [ ] **Step 5: Commit**

```bash
git add libraries/llama.cpp libraries/stable-diffusion.cpp nativeEngine/src/nativeTest/scripts/native_submodule_pin_test.sh nativeEngine/build.gradle.kts docs/native-submodule-upgrade/README.md
git commit -m "build(native): pin September engine pair"
```

### Task 2: Build stable-diffusion.cpp against the single upstream GGML tree

**Files:**
- Modify: `nativeEngine/src/commonCpp/GgmlUnified.cmake`
- Modify: `nativeEngine/src/jvmMain/cpp/CMakeLists.txt`
- Modify: platform `CMakeLists.txt` files under `nativeEngine/src/androidMain/cpp/` and `nativeEngine/src/iosMain/cpp/` only when their target wiring differs from JVM.
- Test: CMake configure/build driven by `nativeEngine/build.gradle.kts`.

**Interfaces:**
- Consumes: `LLAMA_SRC`, which points to `nativeEngine/build/patched-native-sources/llama.cpp`.
- Produces: one `ggml` target shared by `llama`, `stable-diffusion`, and both CaraML bridges.

- [ ] **Step 1: Add a configuration assertion before changing flags**

After the stable-diffusion target is created, assert that its selected private GGML include directory belongs to `${LLAMA_SRC}/ggml`:

```cmake
get_property(_sd_ggml_include TARGET stable-diffusion PROPERTY SD_GGML_PRIVATE_INCLUDE_DIR)
if(NOT _sd_ggml_include MATCHES "^${LLAMA_SRC}/ggml")
    message(FATAL_ERROR "stable-diffusion.cpp is not using CaraML's pinned llama.cpp GGML tree")
endif()
```

- [ ] **Step 2: Configure the candidate's supported upstream-GGML mode**

Set all three variables before `add_subdirectory("${SD_SRC}" ...)`:

```cmake
set(SD_USE_SYSTEM_GGML ON CACHE BOOL "" FORCE)
set(SD_USE_UPSTREAM_GGML ON CACHE BOOL "" FORCE)
set(SD_GGML_SOURCE_DIR "${LLAMA_SRC}/ggml" CACHE PATH "" FORCE)
```

Keep `SD_WEBP=OFF` and `SD_WEBM=OFF`. Remove the old manual `${LLAMA_SRC}` private include workaround after the candidate's `cmake/ggml.cmake` property proves the correct private include path.

- [ ] **Step 3: Configure a clean desktop native build**

Run: `./gradlew :nativeEngine:preparePatchedLlamaSource :nativeEngine:buildLlamaRunnerDesktop --no-daemon`

Expected: the first run may fail only on patch applicability; it must not report duplicate GGML targets or missing `ggml-impl.h`.

- [ ] **Step 4: Prove one-GGML linkage**

Run the platform-appropriate symbol inspection against the produced desktop bridge and confirm a single definition for representative `ggml_*` symbols. Then run:

```bash
./gradlew :nativeEngine:compileDiffusionRunnerNativeTestsDesktop --no-daemon
```

Expected: no duplicate symbols and no nested stable-diffusion GGML checkout dependency.

- [ ] **Step 5: Commit**

```bash
git add nativeEngine/src/commonCpp/GgmlUnified.cmake nativeEngine/src/jvmMain/cpp/CMakeLists.txt nativeEngine/src/androidMain/cpp/CMakeLists.txt nativeEngine/src/iosMain/cpp/CMakeLists.txt
git commit -m "build(native): share upstream GGML across engines"
```

### Task 3: Rebase and prove the three llama.cpp safety patches

**Files:**
- Modify: `libraries/patches/llama.cpp/0001-metal-pin-shading-language-version.patch`
- Modify: `libraries/patches/llama.cpp/0002-vulkan-norm-require-f32.patch`
- Modify: `libraries/patches/llama.cpp/0003-fit-memory-probe-raii.patch`
- Modify: `runner/src/nativeTest/cpp/llama_runner_hardening_test.cpp`
- Modify: `nativeEngine/src/jvmMain/cpp/CMakeLists.txt`
- Test: `runner/src/nativeTest/cpp/llama_runner_hardening_test.cpp`

**Interfaces:**
- Consumes: the candidate llama.cpp source copied by `preparePatchedLlamaSource`.
- Produces: independently applicable Metal language, Vulkan type-admission, and fit-probe cleanup patches.

- [ ] **Step 1: Add failing source/behavior regressions**

Extend the hardening target so it verifies:

```cpp
expect(metal_language_for(/* has_tensor = */ true, /* bf16 = */ true) == MetalLanguage::V4_0);
expect(metal_language_for(/* has_tensor = */ false, /* bf16 = */ true) >= MetalLanguage::V3_1);
expect(!vulkan_norm_supported(GGML_TYPE_F16, GGML_TYPE_F32));
expect(!vulkan_timestep_supported(GGML_TYPE_F32, GGML_TYPE_F16));
expect_fit_probe_failure_restores_logger_and_releases_model();
expect_fit_probe_failure_restores_logger_and_releases_context();
```

Implement the test seams as CaraML-only helpers under `CARAML_LLAMA_NATIVE_TESTING`; do not export them from production JNI.

- [ ] **Step 2: Rebase the Metal patch**

Patch `ggml_metal_compile_options_set_lang()` so tensor-capable devices retain upstream Metal 4.0, while non-tensor bf16-capable devices explicitly select 3.2 on macOS 15/iOS 18 and 3.1 on macOS 14/iOS 17. The helper must leave older unsupported systems unchanged.

- [ ] **Step 3: Rebase the Vulkan patch**

Keep candidate-contiguous input requirements and require F32 input/output for `GROUP_NORM`, `NORM`, `L2_NORM`, and `TIMESTEP_EMBEDDING` in `ggml_backend_vk_device_supports_op()`.

- [ ] **Step 4: Rebase the fit-probe RAII patch**

Wrap the candidate `common_get_device_memory_data_impl()` logger override, model, and context in non-copyable scoped owners. Cover failures from model load, context creation, CPU backend lookup, and memory probing; destruction order must release context before model and restore the previous logger last.

- [ ] **Step 5: Run patch and native regressions**

Run:

```bash
./gradlew :nativeEngine:preparePatchedLlamaSource --no-daemon
./gradlew :nativeEngine:testLlamaRunnerNativeDesktop --no-daemon
```

Expected: every patch applies to a fresh build-owned tree and the hardening binary passes. Temporarily omitting each patch must make its targeted regression fail.

- [ ] **Step 6: Commit**

```bash
git add libraries/patches/llama.cpp runner/src/nativeTest/cpp/llama_runner_hardening_test.cpp nativeEngine/src/jvmMain/cpp/CMakeLists.txt
git commit -m "fix(native): rebase engine safety patches"
```

### Task 4: Migrate the llama wrapper and expose bounded engine identity

**Files:**
- Modify: `runner/src/commonMain/kotlin/com/debanshu777/runner/NativeRunnerConfig.kt`
- Modify: `runner/src/commonMain/kotlin/com/debanshu777/runner/LlamaRunner.kt`
- Modify: `runner/src/commonCpp/llama_runner_core.h`
- Modify: `runner/src/commonCpp/llama_runner_core.cpp`
- Modify: `runner/src/commonCpp/llama_runner_jni.cpp`
- Modify: JVM, Android, and iOS `LlamaRunner` actual implementations and iOS C bridge.
- Modify: `runner/src/commonTest/kotlin/com/debanshu777/runner/LlamaPreflightResultTest.kt`
- Test: `runner/src/commonTest/kotlin/com/debanshu777/runner/LlamaPreflightResultTest.kt`

**Interfaces:**
- Consumes: `llama_version()`, `llama_lazy_mode`, the added `common_fit_extra_model *` argument, and `n_outputs_max_per_seq`.
- Produces: `LlamaLazyMode`, deliberate per-sequence output bounds, and `LlamaRunner.engineVersion()`.

- [ ] **Step 1: Write failing Kotlin contract tests**

Add:

```kotlin
assertEquals(LlamaLazyMode.AUTO, NativeRunnerConfig().lazyMode)
assertEquals(0, NativeRunnerConfig().nOutputsMaxPerSequence)
assertNull(parseBoundedLlamaEngineVersion("x".repeat(97)))
assertEquals("llama.cpp-b123", parseBoundedLlamaEngineVersion("b123"))
```

- [ ] **Step 2: Add explicit config types**

Define:

```kotlin
enum class LlamaLazyMode(val nativeValue: Int) { OFF(0), AUTO(1), ON(2) }
```

Add `lazyMode: LlamaLazyMode = AUTO` and `nOutputsMaxPerSequence: Int = 0` to `NativeRunnerConfig`. Validate the output bound in `0..nBatch` and reject `ON` when mmap is disabled.

- [ ] **Step 3: Migrate C++ fit and model/context params**

Pass `nullptr` for `common_fit_extra_model` until CaraML owns an MTP/draft model. Set:

```cpp
plan.model_params.lazy_mode = static_cast<llama_lazy_mode>(config.lazy_mode);
plan.context_params.n_outputs_max_per_seq = static_cast<uint32_t>(config.n_outputs_max_per_seq);
```

Preserve CaraML's existing stop, cancellation, streaming, and exact fit-plan behavior.

- [ ] **Step 4: Wire JNI and cinterop safely**

Resolve every added field ID before reading it; a missing field returns the existing generic native failure. Use fixed integer values in the iOS bridge and validate enum values before casting.

- [ ] **Step 5: Add bounded engine version access**

Expose `llama_runner_core_engine_version()` backed by `llama_version()`. Accept only `[A-Za-z0-9._+-]{1,72}` and return an empty result on invalid data. Kotlin returns nullable `String` and never logs the raw rejected value.

- [ ] **Step 6: Run runner gates**

Run:

```bash
./gradlew :runner:allTests :nativeEngine:testLlamaRunnerNativeDesktop --no-daemon
```

Expected: PASS with unchanged default generation semantics.

- [ ] **Step 7: Commit**

```bash
git add runner nativeEngine/src/jvmMain/cpp/CMakeLists.txt
git commit -m "feat(runner): migrate September llama APIs"
```

### Task 5: Split diffusion segmentation from prefetch and migrate video/model APIs

**Files:**
- Modify: `diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/DiffusionModelConfig.kt`
- Modify: `diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/DiffusionPreflightResult.kt`
- Modify: `diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunnerValidation.kt`
- Modify: `diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/VideoGenParams.kt`
- Create: `diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/VideoGenResult.kt`
- Modify: `diffusionRunner/src/commonCpp/diffusion_runner_core.h`
- Modify: `diffusionRunner/src/commonCpp/diffusion_runner_core.cpp`
- Modify: JNI, JVM, Android, and iOS diffusion bridges.
- Replace: `diffusionRunner/src/nativeTest/cpp/diffusion_stream_layers_test.cpp` with `diffusion_segmented_compute_test.cpp`.
- Modify: `nativeEngine/build.gradle.kts`
- Modify: `nativeEngine/src/jvmMain/cpp/CMakeLists.txt`
- Test: diffusion common tests and native segmented-compute test.

**Interfaces:**
- Consumes: `disable_prefetch`, `disable_segmented_compute`, `sd_get_model_version_name()`, and the added `generate_video(..., int *fps_out)` argument.
- Produces: independent `segmentedCompute` and `prefetch` state plus `VideoGenResult(frames, effectiveFps)`.

- [ ] **Step 1: Write failing config/result tests**

Pin the conservative defaults and invalid combinations:

```kotlin
assertFalse(DiffusionModelConfig(modelPath = "model").segmentedCompute)
assertFalse(DiffusionModelConfig(modelPath = "model").prefetch)
assertFails { validateDiffusionModelConfig(config(prefetch = true, segmentedCompute = false)) }
assertEquals(24, VideoGenResult(emptyList(), effectiveFps = 24).effectiveFps)
```

- [ ] **Step 2: Replace the ambiguous boolean**

Replace `streamLayers` with:

```kotlin
val segmentedCompute: Boolean = false,
val prefetch: Boolean = false,
```

Map them in C++ only after `sd_ctx_params_init()`:

```cpp
params.disable_segmented_compute = !config.segmented_compute;
params.disable_prefetch = !config.prefetch;
params.auto_fit = false;
```

Initialize `linear_scale`, `attn_scale`, `tokenizer`, `sage_attn`, and audio paths from upstream defaults and leave unsupported capabilities unassigned.

- [ ] **Step 3: Preserve the exact admitted plan**

Keep `resolve_model_plan()` as the sole placement calculation. Add a native test that captures the final `backend`, `params_backend`, `max_vram`, segmentation, prefetch, and `auto_fit` fields immediately before context construction and compares them with the admitted config.

- [ ] **Step 4: Migrate model and video results**

After load, copy the bounded `sd_get_model_version_name()` into native execution evidence. Pass an `int fps_out` to `generate_video`; return it through JNI and Kotlin with frames as `VideoGenResult`. Keep iOS video unsupported until its existing platform gate changes.

- [ ] **Step 5: Run diffusion gates**

Run:

```bash
./gradlew :diffusionRunner:allTests :nativeEngine:testDiffusionRunnerNativeDesktop --no-daemon
```

Expected: PASS; default configs remain monolithic with no prefetch, and exact-plan tests prove `auto_fit == false` at context construction.

- [ ] **Step 6: Commit**

```bash
git add diffusionRunner nativeEngine/build.gradle.kts nativeEngine/src/jvmMain/cpp/CMakeLists.txt
git commit -m "feat(diffusion): migrate segmented execution APIs"
```

### Task 6: Carry exact execution evidence and namespace persisted state to the engine pair

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/RunPlan.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/NativeRunPlanAdapter.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/inference/DiffusionInferenceRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/inference/LlamaInferenceRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/di/AppModule.kt`
- Modify: relevant recommendation/recovery tests.
- Test: `NativeRunPlanAdapterTest`, both inference repository tests, calibration and recovery tests.

**Interfaces:**
- Consumes: bounded engine versions and effective native execution state from Tasks 4–5.
- Produces: fixed pair ID `caraml-native-20260923-f46bc30-c92d73c` and typed admitted/effective comparisons.

- [ ] **Step 1: Write failing persistence-boundary tests**

Assert that records under `native-engine-v1` do not match the new pair ID, rollback records remain isolated, and invalid native version strings cannot replace the fixed ID.

```kotlin
assertNull(repository.correctionFor(key.copy(engineVersion = "native-engine-v1")))
assertEquals("caraml-native-20260923-f46bc30-c92d73c", currentEngineVersion)
```

- [ ] **Step 2: Replace the fixed v1 constant**

Use one bounded compile-time integration identity:

```kotlin
private const val NATIVE_LOAD_ENGINE_VERSION = "caraml-native-20260923-f46bc30-c92d73c"
```

Use upstream version accessors only for diagnostics and compatibility evidence, never directly as a database key.

- [ ] **Step 3: Model segmentation and prefetch independently**

Replace `layerStreaming` in the admitted diffusion plan with `segmentedCompute` and `prefetch`. Validation requires `prefetch => segmentedCompute`; the adapter maps both values exactly into `DiffusionModelConfig`.

- [ ] **Step 4: Compare admitted and effective state**

Extend the native fit report with the effective budget, canonical backend/device identity, component placement, segmentation, prefetch, and fallback state. Return `NativeLoadPreflight.Invalid` when any field differs from the admitted plan; do not retry that mismatch as OOM.

- [ ] **Step 5: Run ownership and persistence tests**

Run:

```bash
./gradlew :composeApp:commonTest --tests '*NativeRunPlanAdapterTest' --tests '*DiffusionInferenceRepositoryTest' --tests '*LlamaInferenceRepositoryTest' --tests '*CalibrationRepositoryTest' --tests '*LoadRecoveryRepositoryTest' --no-daemon
```

Expected: PASS; stale evidence is ignored without deleting user data.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain composeApp/src/commonTest
git commit -m "feat(native): namespace exact engine execution plans"
```

### Task 7: Activate the low-risk upstream improvements behind internal policy

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/inference/LlamaInferenceRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/RunPlanGenerator.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/RunPlanValidation.kt`
- Modify: `diffusionRunner/src/commonCpp/diffusion_runner_core.cpp`
- Modify: focused policy and recovery tests.

**Interfaces:**
- Consumes: conservative wrapper controls from Tasks 4–6.
- Produces: reversible lazy-load AUTO and diffusion segmentation/prefetch activation decisions.

- [ ] **Step 1: Write failing policy tests**

Cover these decisions:

```kotlin
assertEquals(LlamaLazyMode.AUTO, policy.llamaLazyMode(eligibleLargeMmapModel))
assertEquals(LlamaLazyMode.OFF, policy.llamaLazyMode(nonMmapModel))
assertTrue(policy.diffusionPlan(constrainedGpu).segmentedCompute)
assertFalse(policy.diffusionPlan(constrainedGpu).prefetch)
assertTrue(policy.diffusionPlan(profiledGpuWithHeadroom).prefetch)
```

Also assert that semantic model errors and corrupt metadata never select VAE tiling recovery.

- [ ] **Step 2: Enable llama AUTO policy**

Set `lazyMode=AUTO` only for mmap-backed local artifacts. Keep `ON` unreachable from product settings and preserve `OFF` for non-mmap paths.

- [ ] **Step 3: Enable diffusion segmentation before prefetch**

Allow segmentation when the admitted constrained-device plan includes a bounded accelerator budget. Enable prefetch only when the backend capability report has trustworthy live headroom after clamping; keep the two decisions independently reversible.

- [ ] **Step 4: Restrict VAE recovery to allocation failures**

Map only the stable native allocation classification to one tiled retry. Corruption, invalid metadata, unsupported operators, security validation, cancellation, and exact-plan mismatch fail immediately.

- [ ] **Step 5: Run policy/recovery gates**

Run:

```bash
./gradlew :composeApp:commonTest --tests '*RunPlanGeneratorTest' --tests '*RunPlanValidationTest' --tests '*LoadRecoveryRepositoryTest' --tests '*DiffusionInferenceRepositoryTest' --no-daemon
```

Expected: PASS with separate coverage for Metal/Vulkan/CPU policy inputs.

- [ ] **Step 6: Commit**

```bash
git add composeApp diffusionRunner/src/commonCpp/diffusion_runner_core.cpp
git commit -m "feat(native): gate lazy and segmented execution"
```

### Task 8: Complete platform validation and documentation

**Files:**
- Modify: `README.md`
- Modify: `composeApp/README.md`
- Modify: `runner/README.md`
- Modify: `diffusionRunner/README.md`
- Modify: `nativeEngine/README.md`
- Modify: `docs/native-submodule-upgrade/README.md`

**Interfaces:**
- Consumes: the completed paired migration.
- Produces: verification evidence, rollback commands, and concise Recent Changes entries.

- [ ] **Step 1: Run source hygiene and the repository gate**

Run:

```bash
git diff --check
./gradlew verifyProject --no-daemon --no-parallel -Pkotlin.incremental=false
```

Expected: PASS.

- [ ] **Step 2: Run desktop and Android packaging gates**

Run:

```bash
./gradlew :nativeEngine:testLlamaRunnerNativeDesktop :nativeEngine:testDiffusionRunnerNativeDesktop --no-daemon
./gradlew :composeApp:assembleDebug --no-daemon
```

Inspect the packaged APK for the expected JNI libraries and bridge symbols.

- [ ] **Step 3: Run Apple gates on macOS**

Run:

```bash
./gradlew :nativeEngine:mergeLlamaRunnerStaticIosArm64 :nativeEngine:mergeLlamaRunnerStaticIosSimulatorArm64 --no-daemon
```

Expected: both archives link with one GGML implementation. Run a physical-device load/generate/unload smoke test before enabling Metal-specific policy in production.

- [ ] **Step 4: Run lifecycle, recovery, correctness, and benchmark promotion checks**

For the rollback pair and candidate pair, use identical artifact digests, prompts/inputs, seeds, dimensions, steps, threads, context, batch, and device power state. Record cold/warm median and tail latency, peak RSS/VRAM, admitted/effective plan, fallback, and retry count. Repeat load/generate/cancel/unload and inject OOM, corrupt, incompatible, and invalid-metadata failures. Promotion requires equivalent or intentionally improved output before accepting a performance gain; keep raw prompts and generated content out of shared telemetry.

Expected: no NaN/blank output, no leak/race/stale logger, only allocation failures take the bounded recovery path, and regressions are reported separately by CPU, Metal, and Vulkan backend.

- [ ] **Step 5: Record capability and rollback evidence**

Update the migration README with passed, failed, blocked, and manual gates separately. Document rollback to `876a432…` / `e31a86c…`, the matching patch set, and the old fixed identity without deleting model or application data.

- [ ] **Step 6: Update the required Recent Changes sections**

Add concise entries only to the root, composeApp, runner, diffusionRunner, and nativeEngine READMEs. Do not claim physical-device or backend coverage that was not run.

- [ ] **Step 7: Commit**

```bash
git add README.md composeApp/README.md runner/README.md diffusionRunner/README.md nativeEngine/README.md docs/native-submodule-upgrade/README.md
git commit -m "docs(native): record September engine migration"
```

## Deferred follow-up plans

The following README phases intentionally remain separate because they require new artifact contracts or backend-specific evidence and can be reviewed independently:

1. Image preprocessing/reference-dimension/alpha APIs after representative fixtures exist.
2. Qwen Image 2.1 descriptor, artifact-bundle, memory-estimation, and UI capability gate.
3. LLaDA-Image and SenseNova U1.5 complete component contracts.
4. Wan S2V, LTX-2.5, and MiniMax-H3 audio/multi-artifact/cadence contracts.
5. FP8/INT8 and CUDA SageAttention pilots with per-backend correctness and performance baselines.
