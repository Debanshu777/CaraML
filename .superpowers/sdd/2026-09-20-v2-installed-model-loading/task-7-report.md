# Task 7 report — V2-only installed model loading

## Status and commit

- Status: complete
- Commit subject: `refactor(inference): require v2 requests for installed models`
- Commit SHA: recorded in the Task 7 handoff; this report is included in that commit

## Files, interfaces, and deletions

- `InferenceRepository`, `LlamaInferenceRepository`, and `DiffusionInferenceRepository` now accept only `LoadRequest` for model loading.
- Removed both repositories' entity-only loaders, rollout-mode constructor dependencies, path/sidecar loading, and private legacy load helpers and caches.
- Added `loadInstalledModel`, a resolver-first seam that routes Text only to Llama and Image/Video only to Diffusion.
- `ChatViewModel` now injects `InstalledModelLoadRequestResolver`, exposes only `selectModel(model)`, and retains no transient request or rollout state.
- Search and Library selection now call `chatViewModel.selectModel(model)` and pop synchronously; `AppNavigation` holds no transient `LoadRequest`.
- Deleted `ModelLoadRouter`, `RecommendedModelLoadRequestResolver`, and their obsolete tests.
- Replaced router coverage with `InstalledModelLoadingTest`; updated the remaining `InferenceRepository` fake to implement the exact API.

## RED evidence

Command:

```text
./gradlew :composeApp:jvmTest --tests '*InstalledModelLoadingTest' --no-daemon
```

Result: expected compilation failure. `loadInstalledModel` was unresolved in all six new routing tests. The failure was caused by the missing production orchestration seam, not fixture setup or syntax.

## GREEN evidence

Required focused gate:

```text
./gradlew :composeApp:jvmTest --tests '*InstalledModelLoadingTest' --tests '*InstalledModelLoadRequestResolverTest' --tests '*LlamaInferenceRepositoryTest' --tests '*DiffusionInferenceRepositoryTest' --no-daemon
```

Result: PASS, 25 tests, 0 skipped, 0 failures, 0 errors. Breakdown: installed loading 6; installed request resolver 19. The current tree has no test classes matching the two repository-specific filters; exact repository admission/revalidation/recovery is exercised through the existing admission/session suites in the broader gate.

Broader impacted gate:

```text
./gradlew :composeApp:jvmTest --no-daemon
```

Result: PASS, 852 tests across 109 suites, 0 skipped, 0 failures, 0 errors. Relevant included suites: `LoadAdmissionControllerTest` 8, `LoadSessionCoordinatorTest` 3, `ManageContextUseCaseTest` 2, `NavigationTransitionPolicyTest` 3, and Koin/rollout `LegacySuitabilityAdapterTest` 11.

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

## Routing, error, and session semantics

- Every normal selection, including automatic/restored selection from the model flow, resolves through `InstalledModelLoadRequestResolver` with the current generation mode before a runner is touched.
- Text Ready results release Diffusion before exact Llama load; Image/Video Ready results unload Llama before exact Diffusion load. Non-Ready results invoke neither release nor inference.
- `NeedsNetwork` maps exactly to `Connect once to verify this installed model's metadata, then try again.`
- `NotAdmissible`, integrity `Rejected`, and unexpected `Failed` states use fixed copy without assessment reasons, paths, payloads, or exception text.
- Resolver cancellation propagates unchanged. Exact repository `AdmissionRequired` is returned unchanged and its continuation keeps the exact request while adding only the acknowledgement or accepted safer plan.
- The existing load-job cancellation and previous-job join remain in one shared start path, so a new resolution/native operation waits for an in-flight JNI call to finish before touching either runner.
- Exact repository admission, artifact revalidation, recovery markers, native preflight, selected-plan alternatives, cancellation cleanup, generation/context APIs, and unload implementations remain on the existing `LoadRequest` paths.

## Self-review

- Re-read Task 7 plan and brief against the final diff.
- Verified all selection entry points use only `selectModel(model)` and normal loads cannot bypass fresh installed resolution.
- Verified opposite-runner release is inside Ready-only loader callbacks and therefore cannot occur for network, admission, integrity, failure, or resolver-cancellation outcomes.
- Verified the interface fake does not reconstruct a request from an entity.
- Verified `RecommendationRolloutModeSource` remains only in unrelated presentation/recommendation features, not inference or installed loading.
- Verified no new dependency, secret, PII log, path-bearing error, or exception detail was introduced.

## Concerns and follow-up boundary

- The required repository-specific test filters currently match no test classes; the complete JVM suite and existing admission/session tests pass, but there is no direct native-runner repository fixture in this tree.
- Task 8 still owns README Recent Changes updates, the full `verifyProject` gate, Android installation, preserved-data repair, native device loading, and offline restart acceptance.
