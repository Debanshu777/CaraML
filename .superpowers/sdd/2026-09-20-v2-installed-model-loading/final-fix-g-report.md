# Final fix G report — honor the assessed diffusion runtime backend

Date: 2026-09-20
Branch: `codex/v2-installed-model-loading`
Base: `d71f28c1570b68d96e574aee2f79902aeed9533d`
Commit subject: `fix(diffusion): honor assessed runtime backend`

## Outcome

Every executable `DiffusionRunPlan` now selects an explicit stable-diffusion.cpp runtime backend: CPU, Metal, Vulkan, or CUDA. Runtime graph placement is no longer inferred from the engine's GPU-first default and remains distinct from parameter residency (`offloadToCpu`). The same closed value crosses common Kotlin, Android/JVM JNI, iOS cinterop, and the native core before both preflight and load.

Diffusion admission now accepts a native `Fit` only when its backend set, device types, per-component runtime placement, parameter placement, and effective layer-streaming state exactly match the assessed plan. Any missing, ambiguous, unsupported, or differently resolved placement fails closed as `NATIVE_PREFLIGHT_INVALID`; there is no silent GPU/CPU reinterpretation.

Vulkan's existing CLIP/VAE CPU safety requirement is now represented in generated assessed plans and their memory estimates. Native safety pinning applies to explicit Vulkan execution (and unresolved legacy auto-fit) rather than to CUDA or Metal merely because an unrelated Vulkan device is registered.

## Implementation

- Added the closed `DiffusionRuntimeBackend` config contract with a conservative CPU default.
- Mapped `BackendKind.CPU`, `METAL`, `VULKAN`, and `CUDA` exactly in `NativeRunPlanAdapter`; `OTHER` is unrepresentable and rejected.
- Added the matching bounded native enum and explicit stable-diffusion.cpp `backend` assignment (`cpu`, `metal`, `vulkan`, or `cuda`).
- Passed the runtime backend through JNI-owned config and both iOS load/preflight FFI conversion sites.
- Kept parameter placement independent: `offloadToCpu` alone produces the `params_backend` CPU assignment.
- Made bundled preflight classification apply the resolved default assignment to every component, including otherwise unclassified tensors, so a CPU plan cannot retain `DEFAULT`/GPU-first placement.
- Added exact repository admission validation for component roles, one-device GPU masks, backend kinds, CPU masks, parameter residency, and effective streaming.
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

Ambient-device exactness follow-up used the native command above and failed compilation because the policy seam did not yet exist. The test requires an explicit CUDA plan to remain CUDA even when a Vulkan device is also registered.

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

Result: PASS in 23 seconds, 34/34 tests across four suites, with zero skipped/failures/errors (generator 14, adapter 9, repository 4, runner config 7).

Native regression gate:

```text
./gradlew :nativeEngine:testDiffusionRunnerNativeDesktop --no-daemon
```

Result: PASS, 5/5 CTests, including `diffusion_runtime_backend_test`.

## Cross-platform build evidence

```text
./gradlew :nativeEngine:compileLlamaRunnerDesktop --no-daemon
./gradlew :diffusionRunner:compileKotlinIosSimulatorArm64 --no-daemon
./gradlew :androidApp:assembleDebug --no-daemon
```

Results:

- Desktop JNI/native library: PASS in 4 minutes 48 seconds.
- iOS simulator static library, cinterop, and Kotlin compile: PASS in 8 minutes 19 seconds.
- Android arm64-v8a/x86_64 JNI build and debug APK: PASS in 8 minutes 2 seconds; 111 actionable tasks.

## Repository gate

```text
git diff --check
./gradlew verifyProject --no-daemon
```

Result: PASS in 18 seconds on the final source. JVM: 1,080/1,080 tests across 143 suites, zero skipped/failures/errors (`composeApp` 940/119 suites, `huggingFaceManager` 91/15, `runner` 29/6, `diffusionRunner` 20/3). Native: artifact-root CTest 1/1 and diffusion CTests 5/5. Gradle reported 41 actionable tasks: 13 executed and 28 up to date. `git diff --check` passed.

## Security and exactness review

- External plan/backend values remain typed and bounded; unknown `OTHER` placement is rejected before native entry.
- JNI rejects null/invalid enum values, native validation bounds the integer enum, and iOS passes the same stable ordinal contract.
- CPU plans always send `backend=cpu`; parameter offload cannot accidentally select runtime compute.
- GPU plans require exactly their assessed backend kind and one selected runtime device per GPU component.
- CPU components require a zero backend mask; GPU components require exactly one in-range backend bit.
- Preflight report drift never falls back to an ambient/default backend and never reaches native load.
- No model path, prompt, backend diagnostics, or exception detail was added to logs or user-facing errors.

## Remaining verification boundary

- A successful real model load/generation remains a physical-device acceptance item because the repository does not pin a small redistribution-safe successful diffusion fixture.
- Existing upstream C/C++ deprecation and missing-override warnings, Kotlin expect/actual warnings, and missing `ccache` warning are unchanged.
