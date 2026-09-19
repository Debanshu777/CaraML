# Task 6 report — fresh exact installed-model LoadRequest resolution

## Status and commit

- Status: complete
- Commit subject: `feat(models): resolve installed v2 load requests`
- Commit SHA: recorded in the Task 6 handoff; this report is included in that commit

## Files and interfaces

- Added `InstalledModelLoadResolution` and `InstalledModelLoadRequestResolver.resolve(model, expectedMode)`.
- Added `InstalledModelWorkloadFactory` and moved the Model Hub default workload values behind it.
- Added `LocalArtifactIdentityResolver.createLoadRequestFromVerifiedArtifact`, which accepts only a manifest-backed `HubCommit` artifact, revalidates its content, and builds without calling legacy resolution.
- Registered the persisted-evidence codec, exact installed metadata source, evidence repairer, workload factory, and installed resolver as Koin singletons.
- Added focused resolver and strict request-construction coverage; updated the root and composeApp Recent Changes sections.

## RED evidence

1. `./gradlew :composeApp:jvmTest --tests '*InstalledModelLoadRequestResolverTest' --no-daemon`
   - Failed compilation because `InstalledModelLoadResolution`, `InstalledModelLoadRequestResolver`, and `InstalledModelWorkloadFactory` did not exist.
2. Focused manifest-result regression
   - `repairInvalidMetadataStillSurfacesConcreteManifestRejection` failed because Task 5's generic invalid-metadata result hid the concrete stale-manifest failure.
3. Focused KV-plan regression
   - `explicitKvPresetRejectsAPlanWithDifferentCacheTypes` failed because a selected plan could disagree with the explicit workload KV types.
4. Focused no-legacy regression
   - `legacyContentArtifactIsRejectedBeforeAssessment` failed because the orchestration boundary did not yet explicitly reject `LocalContent` artifacts.

## GREEN evidence

- Required gate:
  - Command: `./gradlew :composeApp:jvmTest --tests '*InstalledModelLoadRequestResolverTest' --tests '*LocalArtifactIdentityResolverTest' --tests '*ModelAssessmentRepositoryTest' --no-daemon`
  - Result: PASS, 52 tests, 0 skipped, 0 failures, 0 errors.
  - Breakdown: resolver 16, artifact identity 16, assessment repository 20.
- Impacted evidence/metadata/Model Hub/Koin gate:
  - Command: `./gradlew :composeApp:jvmTest --tests '*InstalledModelEvidenceRepairerTest' --tests '*HuggingFaceModelMetadataSourceTest' --tests '*LegacySuitabilityAdapterTest*' --tests '*ModelViewModelRecommendationTest' --no-daemon`
  - Result: PASS, 50 tests, 0 skipped, 0 failures, 0 errors.
  - Breakdown: evidence repair 8, exact metadata 5, common Koin/rollout 11, JVM Koin/startup 1, Model Hub recommendation 25.
- `git diff --check`: PASS.

## Workload and settings mapping

- Text: requested/context tokens are `min(descriptor.contextLimit, 4096)`; descriptors below the 512-token minimum fail closed. Prompt and reserve are 256 each, batch is 256, micro-batch is 64, and sequence count is 1. Context and batch fallbacks remain enabled.
- AUTO KV: `KvCacheSelection.Auto`, all supported KV types, KV fallback enabled.
- Q4/F16: exact Q4_0 key plus F16 value, only those types allowed, KV fallback disabled.
- Q8/Q8: exact Q8_0 key/value, only Q8_0 allowed, KV fallback disabled.
- F16/F16: exact F16 key/value, only F16 allowed, KV fallback disabled.
- Image: 1024x1024, one frame, existing resolution/tiling/VRAM/layer-streaming fallback flags preserved.
- Video: 1024x576, 16 frames, existing resolution/frame/tiling/VRAM/layer-streaming fallback flags preserved.
- `useGpu=false` supplies a CPU-only hardware view to assessment and rejects any non-CPU selected plan. `useGpu=true` preserves the freshly captured backend set.
- `recommendationProfile` is read from the first current `AppSettings` emission and applied to each fresh personalization.

## Strict no-legacy proof

- Resolver orchestration calls only `resolvePersistedHub`; it never calls `resolve`.
- Artifacts must carry `RevisionIdentity.HubCommit`, exact model ownership, and the exact descriptor identity set before assessment.
- Strict request construction revalidates the already verified artifact and does not read a manifest again or create the legacy identity sidecar.
- Missing/stale manifest failures stop before assessment and map to `Rejected(ArtifactIdentityRejection)`.

## Result mapping

- Repair network retry: `NeedsNetwork`.
- Terminal repair/assessment reason: `NotAdmissible(reason)`.
- Manifest, artifact, owner, identity, mode-plan, key, or selected-plan binding failure: `Rejected(reason)`.
- Unexpected non-cancellation exception: `Failed`, without exception text, paths, or payloads.
- Cancellation from component lookup, repair, artifact resolution, snapshot, settings, assessment, or strict request construction escapes unchanged.

## Self-review

- Verified exact descriptor/artifact owner, kind, generation mode, identity-set, assessment-key, plan-key, selected assessment, workload, KV, and CPU-only bindings.
- Verified each call obtains fresh resources and settings before assessment/personalization; only immutable assessment estimates may be reused through existing complete cache keys.
- Preserved existing legacy request behavior for pre-Task-7 callers while preventing invalid bindings from triggering legacy resolution.
- No new dependency, logs, exception text, path disclosure, or persisted side effect was introduced.

## Concerns and follow-up boundary

- Task 7 still needs to route Chat exclusively through this resolver and remove the legacy loading entry points. The new resolver is registered but intentionally not yet the only Chat path.
- No physical-device/manual inference run was required for this orchestration-only task; JVM behavior and DI/startup wiring are covered by the gates above.
