# Task 7 report — V2-only installed model loading

## Status and commit

- Status: complete
- Commit subject: `refactor(inference): require v2 requests for installed models`
- Commit SHA: `49009babcfadb39076f5225652751c4faa4afff1`
- Review-fix commit subject: `fix(inference): preserve cpu-only load plans`
- Review-fix commit SHA: recorded in the review-fix handoff; this report is included in that commit

## Files, interfaces, and deletions

- `InferenceRepository`, `LlamaInferenceRepository`, and `DiffusionInferenceRepository` now accept only `LoadRequest` for model loading.
- Removed both repositories' entity-only loaders, rollout-mode constructor dependencies, path/sidecar loading, and private legacy load helpers and caches.
- Added `loadInstalledModel`, a resolver-first seam that routes Text only to Llama and Image/Video only to Diffusion.
- `ChatViewModel` now injects `InstalledModelLoadRequestResolver`, exposes only `selectModel(model)`, and retains no transient request or rollout state.
- Search and Library selection now call `chatViewModel.selectModel(model)` and pop synchronously; `AppNavigation` holds no transient `LoadRequest`.
- Deleted `ModelLoadRouter`, `RecommendedModelLoadRequestResolver`, and their obsolete tests.
- Replaced router coverage with `InstalledModelLoadingTest`; updated the remaining `InferenceRepository` fake to implement the exact API.

## Review fix round

- Centralized the known CPU-only LLM architecture predicate in core recommendation code and reused it from installed-load resolution, suitability warnings, and exact Llama mapping.
- Hybrid-SSM descriptors now receive a CPU-only device snapshot before assessment and personalization, so the selected/offered plan is CPU rather than a repository-side mutation of an admitted GPU plan. The resolver also rejects a non-CPU selection for such a descriptor.
- Exact Llama mapping rejects any incompatible hybrid-SSM GPU request as invalid. An admitted CPU plan maps exactly to `nGpuLayers = 0`, `offloadKqv = false`, and `autoFit = false`; GPU plans preserve the base K/Q/V offload choice.
- The exact architecture comes from the descriptor-derived observation identity, not optional/stale entity metadata.
- Extracted the Chat runner-order and prior-job join/cancellation behavior into narrow internal seams used by both normal selection and admission continuation. Tests cover resolver-before-runner behavior, non-Ready no-load/no-unload, opposite-runner order, and cancellation after joining an in-flight non-cancellable native job.
- `ManageContextUseCaseTest` now returns a deterministic `ModelLoadResult.Success` from its exact fake rather than throwing an unused-path error.

## RED evidence

Command:

```text
./gradlew :composeApp:jvmTest --tests '*InstalledModelLoadingTest' --no-daemon
```

Result: expected compilation failure. `loadInstalledModel` was unresolved in all six new routing tests. The failure was caused by the missing production orchestration seam, not fixture setup or syntax.

Review-fix RED commands:

```text
./gradlew :composeApp:jvmTest --tests '*InstalledModelLoadRequestResolverTest' --tests '*NativeRunPlanAdapterTest' --tests '*LlamaInferenceRepositoryTest' --tests '*InstalledModelLoadingTest' --no-daemon
./gradlew :composeApp:jvmTest --tests '*NativeRunPlanAdapterTest' --no-daemon
```

Results: the first command failed compilation because the new exact-config, exact-mode, and previous-job seams did not exist. After the initial implementation, the second command ran 6 tests and failed only `gpuPlanPreservesExplicitlyDisabledKqvOffload`, proving that the first adapter draft incorrectly enabled a base-disabled GPU flag. Narrowing the mapping to force off only for CPU made that regression green.

## GREEN evidence

Required focused gate:

```text
./gradlew :composeApp:jvmTest --tests '*InstalledModelLoadingTest' --tests '*InstalledModelLoadRequestResolverTest' --tests '*LlamaInferenceRepositoryTest' --tests '*DiffusionInferenceRepositoryTest' --no-daemon
```

Result after review fixes: PASS, 30 tests, 0 skipped, 0 failures, 0 errors. Breakdown: installed loading 8; installed request resolver 20; exact Llama repository/config mapping 2. The current tree has no test class matching the Diffusion repository filter; Diffusion exact-path coverage remains in the existing admission/session suites and full JVM gate.

Impacted architecture/config gate:

```text
./gradlew :composeApp:jvmTest --tests '*NativeRunPlanAdapterTest' --tests '*ModelSuitabilityCalculatorTest' --no-daemon
```

Result: PASS, 33 tests, 0 skipped, 0 failures, 0 errors. Breakdown: native run-plan adapter 6; suitability calculator 27.

Broader impacted gate:

```text
./gradlew :composeApp:jvmTest --no-daemon
```

Result after review fixes: PASS, 859 tests across 110 suites, 0 skipped, 0 failures, 0 errors. Relevant included suites: `InstalledModelLoadingTest` 8, `InstalledModelLoadRequestResolverTest` 20, `LlamaInferenceRepositoryTest` 2, `NativeRunPlanAdapterTest` 6, `LoadAdmissionControllerTest` 8, `LoadSessionCoordinatorTest` 3, and `ManageContextUseCaseTest` 2.

Hygiene:

```text
git diff --check
```

Result: PASS.

## No-legacy proof

Command:

```text
rg -n "loadModel\(model: LocalModelEntity|legacyLoad|selectedLoadRequest|ModelLoadRouter|RecommendedModelLoadRequestResolver" composeApp/src/commonMain
```

Result: no matches (exit 1, empty output).

One-source architecture scan:

```text
rg -n '"(qwen3next|qwen35|jamba|mamba|ssm|recurrent_gemma|granite_hybrid)"|CPU_ONLY_LLM_ARCHITECTURES|requiresCpuOnlyLlmExecution' composeApp/src/commonMain composeApp/src/commonTest
```

Result: the complete CPU-only compatibility set is declared only in `LlmArchitectureCapability.kt`, while production compatibility consumers call the shared predicate. Other matches are focused test/fixture inputs and independent footprint-family matching; no duplicate CPU-only compatibility set remains.

## Routing, error, and session semantics

- Every normal selection, including automatic/restored selection from the model flow, resolves through `InstalledModelLoadRequestResolver` with the current generation mode before a runner is touched.
- Text Ready results release Diffusion before exact Llama load; Image/Video Ready results unload Llama before exact Diffusion load. Non-Ready results invoke neither release nor inference.
- `NeedsNetwork` maps exactly to `Connect once to verify this installed model's metadata, then try again.`
- `NotAdmissible`, integrity `Rejected`, and unexpected `Failed` states use fixed copy without assessment reasons, paths, payloads, or exception text.
- Resolver cancellation propagates unchanged. Exact repository `AdmissionRequired` is returned unchanged and its continuation keeps the exact request while adding only the acknowledgement or accepted safer plan.
- The existing load-job cancellation and previous-job join remain in one shared start path, so a new resolution/native operation waits for an in-flight JNI call to finish before touching either runner.
- Cancellation is checked immediately after joining the prior job, so a cancelled replacement cannot proceed to resolve, unload, or load after a non-cancellable native operation returns.
- Hybrid-SSM GPU incompatibility is represented before assessment; the exact repository validates the admitted architecture/backend pair and never silently rewrites it.
- CPU admission is enforced losslessly at the native boundary, including disabling K/Q/V offload and automatic GPU fitting.
- Exact repository admission, artifact revalidation, recovery markers, native preflight, selected-plan alternatives, cancellation cleanup, generation/context APIs, and unload implementations remain on the existing `LoadRequest` paths.

## Self-review

- Re-read Task 7 plan and brief against the final diff.
- Verified all selection entry points use only `selectModel(model)` and normal loads cannot bypass fresh installed resolution.
- Verified opposite-runner release is inside Ready-only loader callbacks and therefore cannot occur for network, admission, integrity, failure, or resolver-cancellation outcomes.
- Verified the CPU-only predicate is shared by assessment shaping, suitability messaging, and exact repository validation without a dependency cycle.
- Verified CPU plan mapping cannot retain GPU layers, K/Q/V offload, or GPU auto-fit, and non-CPU mapping does not enable a base-disabled flag.
- Verified both the normal selection path and `AdmissionRequired` continuation use the same opposite-runner ordering seam.
- Verified the interface fake does not reconstruct a request from an entity.
- Verified `RecommendationRolloutModeSource` remains only in unrelated presentation/recommendation features, not inference or installed loading.
- Verified no new dependency, secret, PII log, path-bearing error, or exception detail was introduced.

## Concerns and follow-up boundary

- The Diffusion repository-specific filter still matches no test class. This fix round adds direct exact Llama configuration coverage; existing Diffusion admission/session coverage and the complete JVM suite remain green.
- Task 8 still owns README Recent Changes updates, the full `verifyProject` gate, Android installation, preserved-data repair, native device loading, and offline restart acceptance.
