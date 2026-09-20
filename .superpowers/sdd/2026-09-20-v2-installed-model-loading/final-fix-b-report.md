# Final fix B report — repair installed revisions exactly

Date: 2026-09-20
Branch: `codex/v2-installed-model-loading`
Base: `a47e18aebd0f11b040da5ba45a6797fe1041680e`
Commit subject: `fix(models): repair installed revisions exactly`

## Outcome

Installed evidence repair now resolves strict owner and external-component metadata at each installed repository's validated immutable revision. It never asks current HEAD for exact repair, rejects ambiguous repository/path or conflicting revision inputs before gateway access, and requires the returned detail SHA to match the requested revision before fetching trees, config, or building a descriptor.

Normal browse/search continues to use unqualified current-repository detail. Exact config lookup distinguishes an absent optional `config.json` from network, authentication, and server failures: 404 is non-blocking, while retryable failures return `InstalledDescriptorLookup.RetryableUnavailable`. Cancellation still escapes unchanged.

## Implementation

- Added a revision-qualified strict recommendation-detail overload through `RemoteHuggingFaceApiService`, `HuggingFaceRepository`, and `GetRecommendationModelDetailUseCase`.
- Built the pinned detail URL from the existing allowlisted Hugging Face origin, validated repository segments, and a validated immutable revision using Ktor `URLBuilder`/`appendPathSegments`.
- Derived one case-normalized revision identity per requested repository and rejected conflicting revisions plus duplicate-ambiguous repository/path identities before any gateway call.
- Routed exact owner detail/tree/config and every external diffusion detail/tree request through its own installed revision.
- Compared every returned detail SHA with the requested revision before accepting metadata; accepted case-only normalization is rewritten to the requested installed representation before descriptor construction.
- Added a narrowly emitted `DataError.Network.NotFound` category for optional config requests. Default list/search/detail 404 behavior remains `Unknown`, preserving ordinary browse behavior and user copy.
- Updated root, `composeApp`, and `huggingFaceManager` Recent Changes documentation.

## Public API and documentation impact

- `GetRecommendationModelDetailUseCase` now has `invoke(modelId, revision)` in addition to the existing current-HEAD overload.
- `HuggingFaceRepository` and `RemoteHuggingFaceApiService` expose the matching revision-qualified overload.
- `DataError.Network` adds `NotFound`; the manager emits it only when a request explicitly opts into distinguishing optional-resource 404 responses. Existing Hub list/search/detail calls retain their previous 404 mapping.
- No database, persisted evidence, download manifest, or inference API changed.

## RED evidence

Initial exact-revision regression command:

```text
./gradlew :huggingFaceManager:jvmTest --tests '*RemoteHuggingFaceApiServiceTest' \
  :composeApp:jvmTest --tests '*HuggingFaceModelMetadataSourceTest' --no-daemon
```

The first sandboxed invocation was blocked by the shared Gradle wrapper lock and was rerun with the required cache permission. The RED run then failed compilation only because the revision-qualified service method and gateway overload did not exist.

Optional-config category regression command:

```text
./gradlew :huggingFaceManager:jvmTest --tests '*ClientWrapperTest' \
  :composeApp:jvmTest --tests '*HuggingFaceModelMetadataSourceTest' --no-daemon
```

The RED run failed compilation because `DataError.Network.NotFound` did not exist. No category-mapping production code had been changed before this run.

## GREEN evidence

Focused exact lookup and URL regression:

```text
./gradlew :huggingFaceManager:jvmTest --tests '*RemoteHuggingFaceApiServiceTest' \
  :composeApp:jvmTest --tests '*HuggingFaceModelMetadataSourceTest' --no-daemon
```

Result: PASS.

Focused optional-config/default-404 regression after the final narrowing:

```text
./gradlew :huggingFaceManager:jvmTest \
  --tests '*ClientWrapperTest' \
  --tests '*BoundedResponseTest' \
  --tests '*RemoteHuggingFaceApiServiceTest' \
  :composeApp:jvmTest --tests '*HuggingFaceModelMetadataSourceTest' --no-daemon
```

Result: PASS.

Final impacted metadata, repair, bounded gateway, diffusion setup, and Model Hub browse gate:

```text
./gradlew :huggingFaceManager:jvmTest \
  --tests '*ClientWrapperTest' \
  --tests '*RemoteHuggingFaceApiServiceTest' \
  --tests '*BoundedResponseTest' \
  --tests '*SdCppCuratedCatalogTest' \
  :composeApp:jvmTest \
  --tests '*HuggingFaceModelMetadataSourceTest' \
  --tests '*InstalledModelEvidenceRepairerTest' \
  --tests '*ModelRecommendationServiceTest' \
  --tests '*ModelViewModelRecommendationTest' \
  --no-daemon
```

Result: PASS, 93/93 tests across eight suites, zero skipped/failures/errors.

## Repository gate

`git diff --check` passes.

An initial `./gradlew verifyProject --no-daemon` run before the final default-404 narrowing passed with 1,025/1,025 JVM tests across 138 suites and 5/5 native CTests. After the final narrowing, two fresh full-gate attempts both stopped in `:composeApp:jvmTest` after 892 passing tests because the unrelated `ChatViewModelRetryTest.retryCurrentModelResolvesFreshRequestAndNeverReusesTerminalRequest` observed the initial `ChatUiState.NoModels` instead of waiting for model initialization. The exact failing test passed 1/1 immediately in isolation, and the bounded `*ChatViewModel*Test` run also passed. No chat production or test file is changed by this fix.

The final full repository gate is therefore not green; the scoped impacted gate is green, and the remaining failure is recorded rather than treated as product evidence for this patch.

## Self-review

- Re-read the final-fix brief and checked every required exact request against the diff.
- Confirmed revision validation and repository/path ambiguity rejection occur before gateway access.
- Confirmed exact owner and external repositories use qualified detail and tree requests, and exact LLM config uses the installed owner revision.
- Confirmed returned SHA mismatch blocks before tree/config for the owner and before external tree construction for components.
- Confirmed current-HEAD browse still calls only the unqualified detail overload and retains its bounded-variant behavior.
- Confirmed optional config 404 is non-blocking only for the opt-in config request; no-internet, unauthorized, and server errors remain retryable for installed repair.
- Confirmed cancellation is rethrown, redirects remain disabled, the Hugging Face origin remains allowlisted, and no arbitrary URL input was introduced.
- Confirmed no secret, local path, response payload, or exception detail is logged.
- Confirmed `git diff --check` passes.

## Concerns and verification boundary

- The scheduler/test-order-sensitive `ChatViewModelRetryTest` prevents a final green `verifyProject` result even though it passes alone and no chat code changed. This remains the only unresolved verification concern.
- Existing expect/actual, CMake architecture, and deprecated Compose test API warnings remain; no new warning is attributable to this fix.
- Physical-device acceptance was not repeated because this patch changes remote metadata request qualification and is covered at the gateway/source/repair boundaries.
