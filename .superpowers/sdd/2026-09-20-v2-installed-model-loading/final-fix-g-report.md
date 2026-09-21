# Final fix G report — honor the assessed diffusion runtime backend

Date: 2026-09-20
Branch: `codex/v2-installed-model-loading`
Base: `d71f28c1570b68d96e574aee2f79902aeed9533d`
Commit subject: `fix(diffusion): honor assessed runtime backend`
Review follow-up subject: `fix(diffusion): verify exact native placement`
Review round 2 follow-up subject: `fix(diffusion): bind preflight evidence to sources`

## Outcome

Every executable `DiffusionRunPlan` now selects an explicit stable-diffusion.cpp runtime backend: CPU, Metal, Vulkan, or CUDA. Runtime graph placement is no longer inferred from the engine's GPU-first default and remains distinct from parameter residency (`offloadToCpu`). The same closed value crosses common Kotlin, Android/JVM JNI, iOS cinterop, and the native core before both preflight and load.

Diffusion admission now accepts a native `Fit` only when its backend set, device types, configured source role/path ordinals, per-subdivision runtime placement, parameter placement, and effective layer-streaming state exactly match the assessed plan. Missing, extra, duplicate, swapped, ambiguous, unsupported, or differently resolved evidence fails closed as `NATIVE_PREFLIGHT_INVALID`; there is no silent GPU/CPU reinterpretation. Configured source identity is represented independently from native tensor subdivision: bundle-internal diffusion/VAE/text/other evidence remains tied to the bundle, while an external TAESD remains a distinct configured source whose runtime subdivision is VAE.

Vulkan's existing CLIP/VAE CPU safety requirement is now represented in generated assessed plans and their memory estimates. Native safety pinning applies to explicit Vulkan execution or an auto-fit component actually resolved to Vulkan; unrelated registered Vulkan devices cannot CPU-pin CUDA or Metal assignments.

## Implementation

- Added the closed `DiffusionRuntimeBackend` config contract with a conservative CPU default and explicit stable native values `CPU=0`, `METAL=1`, `VULKAN=2`, `CUDA=3`.
- Mapped `BackendKind.CPU`, `METAL`, `VULKAN`, and `CUDA` exactly in `NativeRunPlanAdapter`; `OTHER` is unrepresentable and rejected.
- Added the matching bounded native enum and explicit stable-diffusion.cpp `backend` assignment (`cpu`, `metal`, `vulkan`, or `cuda`).
- Passed the runtime backend through JNI-owned config and both iOS load/preflight FFI conversion sites without relying on Kotlin enum ordinals.
- Kept parameter placement independent: `offloadToCpu` alone produces the `params_backend` CPU assignment.
- Made bundled preflight classification apply the resolved default assignment to every component, including otherwise unclassified tensors, so a CPU plan cannot retain `DEFAULT`/GPU-first placement.
- Derived the exact configured role/path declaration order and made repository admission require a unique, complete source-role and source-ordinal binding before checking one-device GPU masks, backend kinds, CPU masks, parameter residency, and effective streaming.
- Extended the fixed JNI/iOS preflight payload with separate source role/ordinal and subdivision role fields. The bounded decoder requires the exact declared source set, permits only unique subdivisions per source, rejects duplicate or contradictory source bindings, and preserves explicit integer encoding on both platform bridges.
- Loads bundle tensor evidence separately from configured external sources. Bundle subdivisions may be diffusion, VAE, text encoder, or other and must contain exactly one diffusion primary; every split/external source emits one source-bound record. TAESD keeps source role `TAESD` while using the VAE runtime subdivision, so bundle-internal VAE/TAE tensors cannot satisfy it.
- Routed load through one production `resolve_and_apply_model_plan` function and added a native-only seam that captures the exact `sd_ctx_params_t.backend` pointer value assigned by that function.
- Replaced ambient Vulkan registry detection with per-component inspection of the resolved auto-fit assignment.
- Made Vulkan plan generation explicitly set `keepClipOnCpu` and `keepVaeOnCpu`, preserving the existing mobile F16 safety behavior while making it assessable and verifiable.
- Added config, generator, adapter, repository, and native CTest regressions; the native verification target now contains five stable-diffusion preflight tests.
- Updated the root, `composeApp`, `diffusionRunner`, and `nativeEngine` Recent Changes sections and removed stale ambient-Vulkan wording.

## RED evidence

Initial Kotlin contract/admission command:

```text
./gradlew :composeApp:jvmTest \
  --tests '*NativeRunPlanAdapterTest' \
  --tests '*DiffusionInferenceRepositoryTest' \
  --no-daemon
```

After the sandboxed wrapper-lock attempt was rerun with shared-cache access, compilation failed because `DiffusionModelConfig.runtimeBackend` and `exactDiffusionNativePreflight` did not exist. No production implementation had been added.

Initial native command:

```text
./gradlew :nativeEngine:testDiffusionRunnerNativeDesktop --no-daemon
```

The new native regression failed compilation because the runtime-backend constants and explicit assignment helper did not exist.

Vulkan plan-invariant command:

```text
./gradlew :composeApp:jvmTest --tests '*DiffusionRunPlanGeneratorTest' --no-daemon
```

Result: expected RED, 14 tests executed and 1 failed because generated Vulkan plans did not declare their native CLIP/VAE CPU placement.

Review follow-up RED used the focused Kotlin command below and failed compilation because the config-aware matcher signature and explicit `nativeValue` contract did not yet exist. The native command failed compilation because the production-assignment capture and component-selected Vulkan policy seams did not yet exist.

The strengthened swapped-component regression then produced 1 expected failure in 9 repository tests when CLIP and VAE had identical GPU placement: set equality alone accepted swapped role/path ordinals. Exact configured declaration-order binding made it green.

Mutation proof temporarily removed the sole production `params.backend = plan.runtime_spec.c_str()` assignment. `diffusion_runtime_backend_test` failed 1/5 with `production context-backend resolution failed`; restoring that line returned the gate to 5/5. This proves the native test executes the production resolution/assignment path rather than a parallel formatter.

Review round 2 began with repository/decoder tests for lossless source/subdivision evidence. The focused Kotlin compile failed because `DiffusionPreflightComponent` did not yet expose `sourceRole`, `sourceOrdinal`, or `subdivisionRole`. The native gate separately failed compilation because its evidence structure and classifier lacked source binding. After the main implementation, a final split-plus-TAESD regression failed compilation because the shared `declared_source_subdivision_role` production mapper did not yet exist; adding and using that mapper in both split and bundle-external paths made the native gate green.

## Focused GREEN evidence

```text
./gradlew :composeApp:jvmTest \
  --tests '*DiffusionRunPlanGeneratorTest' \
  --tests '*NativeRunPlanAdapterTest' \
  --tests '*DiffusionInferenceRepositoryTest' \
  :diffusionRunner:jvmTest \
  --tests '*DiffusionRunnerValidationTest' \
  --no-daemon
```

Result: PASS in 15 seconds, 39/39 tests across four suites, with zero skipped/failures/errors (generator 14, adapter 9, repository 9, runner config 7).

Native regression gate:

```text
./gradlew :nativeEngine:testDiffusionRunnerNativeDesktop --no-daemon
```

Result: PASS in 18 seconds, 5/5 CTests, including `diffusion_runtime_backend_test`.

Review round 2 focused source/subdivision gate:

```text
./gradlew :composeApp:jvmTest --tests '*DiffusionInferenceRepositoryTest' \
  :diffusionRunner:jvmTest --tests '*DiffusionPreflightResultTest' --no-daemon
```

Result: PASS in 45 seconds, 32/32 tests with zero skipped/failures/errors (repository 19, decoder 13). The native regression gate separately passed 5/5 CTests, including bundle-internal source binding and TAESD-to-VAE subdivision mapping.

## Cross-platform build evidence

```text
./gradlew :nativeEngine:compileLlamaRunnerDesktop --no-daemon
./gradlew :diffusionRunner:compileKotlinIosSimulatorArm64 --no-daemon
./gradlew :androidApp:assembleDebug --no-daemon
```

Results:

- Desktop JNI/native library: PASS in 20 seconds.
- Scoped diffusion-runner iOS simulator static library, cinterop, and Kotlin compile: PASS in 28 seconds.
- Android arm64-v8a/x86_64 JNI build and debug APK: PASS in 1 minute 27 seconds; 111 actionable tasks.

Review round 2 reran the same gates on the lossless ABI: desktop PASS in 23 seconds, scoped iOS simulator PASS in 52 seconds, and Android arm64-v8a/x86_64 debug APK PASS in 1 minute 15 seconds (111 actionable tasks).

## Repository gate

```text
git diff --check
./gradlew verifyProject --no-daemon
```

Review round 2 result: PASS in 44 seconds on the final source. JVM: 1,096/1,096 tests, zero skipped/failures/errors (`composeApp` 955, `huggingFaceManager` 91, `runner` 29, `diffusionRunner` 21). Native: artifact-root CTest 1/1 and diffusion CTests 5/5. Gradle reported 41 actionable tasks: 15 executed and 26 up to date. `git diff --check` passed.

## Security and exactness review

- External plan/backend values remain typed and bounded; unknown `OTHER` placement is rejected before native entry.
- JNI rejects null/invalid enum values, native validation bounds the integer enum, and JNI/iOS pass the same explicit stable integer contract.
- Configured paths remain private; source ordinals bind the exact deterministic role/path declaration list without logging or returning paths, while a separate subdivision field carries native tensor/runtime structure.
- The decoder and repository independently reject missing, extra, duplicate, contradictory, and swapped sources. Bundle-internal VAE evidence cannot impersonate an external TAESD source.
- CPU plans always send `backend=cpu`; parameter offload cannot accidentally select runtime compute.
- GPU plans require exactly their assessed backend kind and one selected runtime device per GPU component.
- CPU components require a zero backend mask; GPU components require exactly one in-range backend bit.
- Preflight report drift never falls back to an ambient/default backend and never reaches native load.
- No model path, prompt, backend diagnostics, or exception detail was added to logs or user-facing errors.

## Remaining verification boundary

- A successful real model load/generation remains a physical-device acceptance item because the repository does not pin a small redistribution-safe successful diffusion fixture.
- The broader `:composeApp:compileKotlinIosSimulatorArm64` gate reaches and builds the native diffusion libraries, then fails in unchanged `GgufMetadataInspector.kt` at lines 20 and 73 because its JVM-style `use`/nullable generic expression does not compile for Kotlin/Native. The scoped diffusion-runner iOS gate above passes; resolving that independent app-wide portability issue is outside Fix G.
- Existing upstream C/C++ deprecation and missing-override warnings, Kotlin expect/actual warnings, and missing `ccache` warning are unchanged.
