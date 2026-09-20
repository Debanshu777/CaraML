# Final fix B report — repair installed revisions exactly

Date: 2026-09-20
Branch: `codex/v2-installed-model-loading`
Base: `a47e18aebd0f11b040da5ba45a6797fe1041680e`
Commit subject: `fix(models): repair installed revisions exactly`

## Outcome

Installed evidence repair now resolves strict owner and external-component metadata at each installed repository's validated immutable revision. It never asks current HEAD for exact repair, rejects ambiguous repository/path or conflicting revision inputs before gateway access, and requires the returned detail SHA to match the requested revision before fetching trees, config, or building a descriptor.

Normal browse/search continues to use unqualified current-repository detail. Exact config lookup follows at most one 307 only when its bounded `Location` is the HTTPS `huggingface.co:443` resolve-cache path for the same repository, requested commit, and `config.json`; global redirects remain disabled. Cross-origin, credentialed, fragmented, missing, oversized, substituted, or chained redirects are rejected.

Exact repair now retries only transport, authentication, rate-limit, server, and timeout categories. Deterministic serialization, size, protocol/redirect, conflict, unknown-status, and missing required detail/tree failures reject with `INVALID_METADATA`; only an optional config 404 remains non-blocking. Cancellation still escapes unchanged.

## Implementation

- Added a revision-qualified strict recommendation-detail overload through `RemoteHuggingFaceApiService`, `HuggingFaceRepository`, and `GetRecommendationModelDetailUseCase`.
- Built the pinned detail URL from the existing allowlisted Hugging Face origin, validated repository segments, and a validated immutable revision using Ktor `URLBuilder`/`appendPathSegments`.
- Derived one case-normalized revision identity per requested repository and rejected conflicting revisions plus duplicate-ambiguous repository/path identities before any gateway call.
- Routed exact owner detail/tree/config and every external diffusion detail/tree request through its own installed revision.
- Compared every returned detail SHA with the requested revision before accepting metadata; accepted case-only normalization is rewritten to the requested installed representation before descriptor construction.
- Added a narrowly emitted `DataError.Network.NotFound` category for optional config requests. Default list/search/detail 404 behavior remains `Unknown`, preserving ordinary browse behavior and user copy.
- Added `DataError.Network.RateLimited`, mapped HTTP 429 explicitly, and classified only transport/auth/rate/server/timeout failures as retryable during exact repair.
- Added a config-only manual redirect step that accepts one bounded 307 after validating HTTPS, exact Hub origin and port, no userinfo/fragment, exact repository/revision/config path, and a bounded `Location`; a second redirect is never followed.
- Updated root, `composeApp`, and `huggingFaceManager` Recent Changes documentation.

## Public API and documentation impact

- `GetRecommendationModelDetailUseCase` now has `invoke(modelId, revision)` in addition to the existing current-HEAD overload.
- `HuggingFaceRepository` and `RemoteHuggingFaceApiService` expose the matching revision-qualified overload.
- `DataError.Network` adds `NotFound`; the manager emits it only when a request explicitly opts into distinguishing optional-resource 404 responses. Existing Hub list/search/detail calls retain their previous 404 mapping.
- `DataError.Network` also adds `RateLimited` for HTTP 429. Existing result shape and cancellation behavior are unchanged.
- `huggingFaceManager/README.md` now records the exact revision-qualified detail/tree/config use-case signatures and the config-only `NotFound` compatibility boundary.
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

Review-follow-up redirect regression command:

```text
./gradlew :huggingFaceManager:jvmTest \
  --tests 'com.debanshu777.huggingfacemanager.api.BoundedResponseTest' --no-daemon
```

Result: RED, 21 tests executed, with the new realistic 307 success, substituted/cross-origin redirect rejection, and second-redirect rejection cases failing against the redirect-disabled client.

Review-follow-up classification regression command:

```text
./gradlew :composeApp:jvmTest \
  --tests 'com.debanshu777.caraml.features.modelhub.domain.HuggingFaceModelMetadataSourceTest' \
  --no-daemon
```

Result: RED at test compilation because the explicit `DataError.Network.RateLimited` category did not exist. Production classification had not been changed before either follow-up RED run.

## GREEN evidence

Focused exact lookup and URL regression:

```text
./gradlew :huggingFaceManager:jvmTest --tests '*RemoteHuggingFaceApiServiceTest' \
  :composeApp:jvmTest --tests '*HuggingFaceModelMetadataSourceTest' --no-daemon
```

Result: PASS.

Final focused config redirect and exact error-classification gate:

```text
./gradlew :huggingFaceManager:jvmTest \
  --tests 'com.debanshu777.huggingfacemanager.api.BoundedResponseTest' \
  --tests 'com.debanshu777.huggingfacemanager.api.ClientWrapperTest' \
  :composeApp:jvmTest \
  --tests 'com.debanshu777.caraml.features.modelhub.domain.HuggingFaceModelMetadataSourceTest' \
  --no-daemon
```

Result: PASS, 41/41 tests across three suites, zero skipped/failures/errors.

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

Result: PASS, 102/102 tests across eight suites, zero skipped/failures/errors.

## Repository gate

`git diff --check` passes.

Final repository command:

```text
./gradlew verifyProject --no-daemon
```

Result: PASS, 1,034/1,034 JVM tests across 138 suites plus 5/5 native CTests, zero failures.

The previously observed `ChatViewModelRetryTest.retryCurrentModelResolvesFreshRequestAndNeverReusesTerminalRequest` full-suite failure was reassessed in this fresh full run and passed in normal suite order. Its earlier two failures still appear scheduler/test-order-sensitive: the exact test and bounded Chat suite had already passed independently, the fresh full suite now passes, and no Chat source or test file changed in this fix.

## Self-review

- Re-read the final-fix brief and checked every required exact request against the diff.
- Confirmed revision validation and repository/path ambiguity rejection occur before gateway access.
- Confirmed exact owner and external repositories use qualified detail and tree requests, and exact LLM config uses the installed owner revision.
- Confirmed returned SHA mismatch blocks before tree/config for the owner and before external tree construction for components.
- Confirmed current-HEAD browse still calls only the unqualified detail overload and retains its bounded-variant behavior.
- Confirmed optional config 404 is non-blocking only for the opt-in config request; transport, unauthorized, timeout, rate-limit, and server failures remain retryable, while deterministic detail/tree/config failures reject.
- Confirmed cancellation is rethrown, global redirects remain disabled, and the one config redirect requires a bounded same-Hub same-repository same-revision target before the second request is issued.
- Confirmed missing/malformed/cross-origin/insecure/credentialed/fragmented/substituted/chained config redirects return deterministic `Serialization` and are never fetched as arbitrary URLs.
- Confirmed no secret, local path, response payload, or exception detail is logged.
- Confirmed `git diff --check` passes.

## Concerns and verification boundary

- The scheduler/test-order-sensitive Chat retry test passed in the final full gate; its prior intermittent full-suite failures remain recorded as a non-causal flake concern. No Chat code was changed.
- Existing expect/actual, CMake architecture, and deprecated Compose test API warnings remain; no new warning is attributable to this fix.
- Physical-device acceptance was not repeated because this patch changes remote metadata request qualification and is covered at the gateway/source/repair boundaries.
