# Final fix C report — serialize installed publication

Date: 2026-09-20
Branch: `codex/v2-installed-model-loading`
Base: `1bf9979ab4ef346112ce73753c24cbf6d93cd30d`
Commit subject: `fix(models): serialize installed publication`

## Outcome

Installed-model publication and evidence repair are now linearized for each validated owner, closing the manifest/catalog/evidence race (CWE-362). A finalizer validates its immutable batch and evidence before acquiring a bounded owner stripe, then retains that owner lock across bundle-manifest publication, bundle validation, and the transactional Ready catalog/evidence replacement.

Repair no longer trusts caller-supplied model or component rows and no longer uses one global mutex. It reads the current Ready model, linked components, raw evidence, and validated manifest under the same owner lock and hashes that exact artifact snapshot. Remote metadata lookup runs after releasing the publication lock. Before publishing repaired evidence, repair reacquires the owner lock, rereads every baseline input, and requires exact equality. Room then compare-and-sets against the raw evidence row observed in the first snapshot. A changed baseline or rejected CAS returns a newer complete exact descriptor when one is valid; otherwise it fails closed without a stale write.

## Implementation

- Added one Koin-singleton `InstalledModelPublicationCoordinator` shared by `ModelDownloadFinalizer` and `InstalledModelEvidenceRepairer`.
- The coordinator validates and case-normalizes owner IDs before selecting one of 64 fixed mutex stripes. Construction is bounded to at most 256 stripes; no external-ID map grows over time.
- Repair single-flight state is also bounded to one active entry per fixed stripe. Same-owner/same-mode callers share one lookup result; a stripe collision waits without retaining the publication lock.
- Finalizer evidence/topology validation now precedes every bundle-manifest side effect. The owner lock covers publish, post-publish validation, and transactional catalog/evidence commit as one process-local publication interval.
- Added a Room `snapshotReady` transaction that captures exactly one Ready owner row, at most 64 linked components, and the raw evidence row.
- Added Room evidence compare-and-set with insert-if-absent for a missing row and transactional observed-row equality before update for an existing row.
- Added explicit-manifest persisted-artifact resolution so repair hashes the manifest captured under the owner lock instead of rereading a potentially newer manifest.
- Changed repair input to owner ID plus generation mode; the repairer always rereads durable model/components rather than accepting stale caller state.
- Preserved cancellation through owner-lock waits, remote lookup, hashing, and CAS. Single-flight cleanup completes in `NonCancellable`, while the original cancellation is rethrown to leaders and followers.
- Kept the existing cross-store failure boundary fail closed. If manifest publication succeeds but catalog publication throws, no rollback is attempted; a subsequent repair sees the manifest/catalog mismatch and cannot publish or claim Ready evidence.
- Added concise root and `composeApp` Recent Changes entries. No schema version, persisted evidence format, remote API, logging, or native inference contract changed.

## RED evidence

The controlled-interleaving, DAO-CAS, owner-boundary, and DI regressions were added before production changes. The initial focused compile failed on the intentionally absent coordinator, Ready snapshot, evidence CAS, explicit-manifest resolver overload, revised repair constructor/API, and shared Koin binding. This established that the new tests could not pass against base `1bf9979`.

Initial focused command:

```text
./gradlew :composeApp:jvmTest \
  --tests '*ModelDownloadFinalizerTest' \
  --tests '*InstalledModelEvidenceRepairerTest' \
  --tests '*InstalledModelPublicationCoordinatorTest' \
  --tests '*AppDatabaseMigrationTest' \
  --tests '*InstalledModelLoadRequestResolverTest' \
  --tests '*AppModuleManifestSourceTest' \
  --tests '*LegacySuitabilityAdapterTest*' \
  --no-daemon
```

The shared Gradle wrapper lock was unavailable in the filesystem sandbox, so every substantive Gradle run was repeated with the approved shared-cache permission. This was an environment permission boundary, not a product-test failure.

## Controlled race and CAS coverage

Five controlled race tests pass:

1. `sameOwnerFinalizersHoldPublicationThroughCatalogCommit` forces the historical A-publish/A-validate/A-catalog-block/B-start schedule and proves B cannot publish until A's catalog commit completes; final manifest and catalog/evidence are B.
2. `differentOwnerFinalizersCanPublishConcurrently` holds two distinct owner publications at a barrier and observes two active publishers.
3. `repairBlockedInLookupCannotOverwriteNewerFinalizerPublication` captures A, blocks its metadata lookup, publishes complete exact B through the real finalizer, resumes A, and proves the repair returns B with zero stale CAS attempts.
4. `concurrentRepairsSerializeToOneLookupAndOnePersistedRecord` starts eight same-owner repairs and observes exactly one remote lookup, one accepted write, and maximum lookup concurrency one.
5. `differentOwnerRepairsCanPerformRemoteLookupConcurrently` holds two distinct owners in remote lookup simultaneously and observes maximum lookup concurrency two with one write per owner.

Four focused CAS/cancellation checks pass:

- missing row to insert, followed by database reopen;
- observed incomplete and corrupt rows to complete evidence;
- changed/newer row rejection without overwrite;
- injected CAS failure rejects while `CancellationException` escapes unchanged.

The coordinator boundary test also proves an invalid traversal-shaped owner is rejected before its critical section executes.

## Focused GREEN evidence

Final impacted application command:

```text
./gradlew :composeApp:jvmTest \
  --tests '*ModelDownloadFinalizerTest' \
  --tests '*InstalledModelEvidenceRepairerTest' \
  --tests '*InstalledModelPublicationCoordinatorTest' \
  --tests '*AppDatabaseMigrationTest' \
  --tests '*DownloadDatabaseTest' \
  --tests '*InstalledModelLoadRequestResolverTest' \
  --tests '*LocalArtifactIdentityResolverTest' \
  --tests '*AppModuleManifestSourceTest' \
  --tests '*PersistedModelEvidenceTest' \
  --tests '*LegacySuitabilityAdapterTest*' \
  --no-daemon
```

Result: PASS, 109/109 tests across 11 suites, zero skipped/failures/errors.

Underlying manifest-store command:

```text
./gradlew :huggingFaceManager:jvmTest \
  --tests '*ArtifactManifestStoreTest' \
  --tests '*ArtifactBundleManifestStoreTest' \
  --no-daemon
```

Result: PASS, 20/20 tests across two suites, zero skipped/failures/errors.

## Repository gate

`git diff --check` passes.

Final repository command:

```text
./gradlew verifyProject --no-daemon
```

Result: PASS in 45 seconds. The gate ran 1,043/1,043 JVM tests across 139 suites plus 5/5 native CTests, with zero skipped JVM tests and zero failures/errors. Gradle reported 41 actionable tasks: 15 executed and 26 up to date.

## Self-review

- Re-read the final-fix brief and traced finalizer, manifest, catalog, evidence, repair, resolver, DAO, and Koin ownership after implementation.
- Confirmed all finalizer bundle/catalog side effects occur inside the validated owner interval and immutable evidence validation occurs before manifest publication.
- Confirmed the repairer's first and second snapshots include model, linked components, manifest, and raw evidence, and artifact resolution hashes the explicitly captured manifest.
- Confirmed no network call occurs while the owner publication mutex is held.
- Confirmed all repair write paths require both exact baseline equality and Room CAS; complete-evidence reuse also reacquires and baseline-checks before returning.
- Confirmed baseline/CAS conflict handling rereads and revalidates current durable state rather than returning the stale candidate.
- Confirmed same-owner coalescing is bounded, unrelated tested owners progress concurrently, and there is no externally keyed unbounded map.
- Confirmed cancellation is rethrown and no payload, path, metadata, or exception detail is logged.
- Confirmed manifest/artifact/evidence validation remains strict and mismatch/failure behavior remains fail closed.
- Confirmed README scope is limited to the root and affected `composeApp` module, and `git diff --check` is clean.

## Concerns and verification boundary

- Fixed stripes intentionally bound memory. Two unrelated owners that hash to the same stripe can serialize, but there is no global repair/publication lock; the controlled distinct-owner tests prove independent stripes progress concurrently.
- A manifest-success/catalog-failure interval is not rolled back. This follows the brief's permitted fail-closed option: the mismatched durable state is unavailable until a later install retry repairs it, and stale repair evidence cannot make it Ready.
- Existing expect/actual, generic CMake architecture, missing `ccache`, and deprecated Compose-test warnings remain; no new warning is attributable to this fix.
- Physical-device acceptance was not repeated because this patch changes process coordination and Room publication only; controlled JVM interleavings, real Room reopen/CAS tests, manifest validation suites, native CTests, and the full repository gate cover the changed boundary.
