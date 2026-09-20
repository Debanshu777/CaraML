# Final fix H1 report — current immutable artifact storage

Date: 2026-09-21
Branch: `codex/v2-installed-model-loading`
Base: `088d7a7`
Prior H1 commits: `2c2b6bc`, `51ac405`, `abb2c10`, `a5ca911`

## Outcome

Installed and in-flight artifacts now use one current storage contract:

```text
<validated repository root>/.caraml-artifacts/<canonical bundle digest>/<native layout path>
```

The bundle digest is derived from the complete immutable request set. `localRelativePath` is the only byte locator; `layoutRelativePath` is only a native-loader projection after the immutable generation has been validated. Unscoped manifests, destinations, and task rows are not compatible current state.

Two owners can retain different immutable revisions of the same repository-relative artifact without overwriting a path or catalog row. Owners may also share one exact component generation under different link roles. Removing or replacing one owner does not prune a generation while another exact link remains.

## Round-three closure

### Manifest recovery

- `ArtifactManifestStore.recover()` returns an explicit `NO_PENDING_TRANSACTION`, `RECOVERED`, or `QUARANTINED` result.
- An unreadable, structurally invalid, or noncanonical pending commit journal cannot be erased and cannot make a torn current manifest visible.
- A journal is discarded only when the current manifest independently validates and every entry is bound to a canonical immutable generation.
- A quarantined journal is a bounded stable marker: repeated reads/recovery do not mutate it or loop.
- Commit, prune, download, and checkpoint-discard mutation paths stop on `QUARANTINED`.
- Commit additionally binds the caller target to the entry's exact `localRelativePath`.

### Safe current-schema Room reads

- `observeForModel`, `getBatch`, and `recoverableBatches` reconstruct every batch through the complete typed request and exact canonical IDs.
- Malformed terminal rows are filtered without throwing. Malformed mutable rows are terminally quarantined once; the SQL transition is state-guarded, so Room observation cannot oscillate.
- Corruption in one batch does not terminate observation or reconciliation of valid batches.
- Evidence is always complete persisted evidence. Missing/all-null evidence is invalid current data and is never synthesized.

### Exact Model Details controls

- `ModelDetailContent` no longer receives or searches raw download snapshots.
- The ViewModel projects `DurableDownloadControlUiState(batchId, artifactId, batchState, artifactState)` only from the exact current request set.
- Pause, resume, cancel, and retry callbacks carry both exact IDs. The ViewModel revalidates the pair and command/state at invocation, so a stale rendered callback is harmless.
- Changing any external diffusion component revision removes the old batch's progress, completion, and controls even when the primary artifact is unchanged.

### Canonical request matching

- Exact request matching compares deterministic `downloadArtifactTaskId` multisets.
- The task identity includes repository, immutable revision, relative path, remote object, expected size, role, canonical destination, bundle, and primary flag.
- Display-only author/library/pipeline/context enrichment does not hide an active exact batch.
- Missing, duplicate, or storage-mutated task identities are rejected deterministically.

## Round-four closure

### iOS completed-download import quarantine

- `IosCompletedDownloadImporter` now treats `ArtifactManifestRecoveryResult.QUARANTINED` as a terminal generic verification failure before enabling any staged mutation.
- Its failure cleanup is armed only after recovery succeeds, so a quarantined recovery cannot discard or replace an existing checkpoint, open/copy the URLSession payload, sync bytes, or enter commit.
- The importer-level iOS regression uses a real `ArtifactManifestStore` over a deterministic filesystem and verifies that the journal, staged checkpoint, and native temporary payload remain byte-for-byte unchanged while no target or manifest is published.
- This closes CWE-754 (improper handling of an exceptional condition): untrusted journal state is no longer ignored before a destructive recovery path.

The direct mutation audit found no second unguarded entry point: download writes and explicit checkpoint discard reject `QUARANTINED`; transaction commit and prune re-check internally; failure recovery with a pending journal calls recovery without discard; cleanup requires `readValidated()` before prune, whose own mutation boundary re-checks; remaining artifact-manifest recovery callers are read-only.

## Database compatibility boundary

The user confirmed a fresh-install/current-schema boundary. Production migration compatibility is intentionally removed.

- Application database current schema: version 5.
- Download database current schema: version 3.
- Neither database registers upgrade or downgrade migrations.
- A stale schema is destructively recreated with Room's `fallbackToDestructiveMigration(dropAllTables = true)`.
- Same-version close/reopen is covered. Exact revision coexistence, owner-role projection, compare-and-remove behavior, corrupt-current-row isolation, and persisted evidence reopen remain covered.
- No old schema fixture, migration object, downgrade path, pre-H1 task quarantine migration, unscoped terminal exception, or settled unscoped manifest read path remains.

Source scan used for the migration boundary:

```text
rg -n '\bMigration\s*\(|MIGRATION_|addMigrations|withMigration|createAppDatabaseAtVersion|createDownloadDatabaseAtVersion' \
  composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/storage/AppDatabase.kt \
  composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/storage/DownloadDatabase.kt \
  composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/storage/AppDatabaseCurrentSchemaTest.kt \
  composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/download/storage/DownloadDatabaseTest.kt
```

Result: no matches. The destructive-fallback API is intentionally outside the forbidden pattern.

## TDD evidence

The review regressions were introduced before their production changes. The RED behaviors included:

- torn/tampered commit journals either being discarded without trustworthy current state or allowing later mutation;
- malformed persisted rows throwing from direct and observed read paths;
- the UI selecting raw batches and allowing stale controls after an external component revision changed;
- exact batches disappearing after display-only enrichment while ambiguous duplicate task identities remained matchable;
- unscoped legacy fixtures remaining constructible after the current-only compatibility decision.
- iOS completed-download import accepting quarantined recovery, deleting the existing staged checkpoint, copying and syncing a replacement, then failing only when commit re-ran recovery.

The GREEN coverage includes:

- every commit crash phase, unreadable and tampered `NEW_PUBLISHED`/`OLD_REMOVED` journals, mutation-free repeated quarantine, and valid scoped recovery;
- malformed mutable and terminal current rows across observe/get/recover/reopen, with per-batch isolation;
- exact language and diffusion progress/control projection, stale external-component rejection, callback replay rejection, and exact refresh;
- display enrichment, storage identity mutation, duplicate/missing task IDs, and deterministic ordering;
- primary files, external diffusion components, native directory generation roots, shared exact bytes with different owner roles, revision coexistence, reference-aware deletion, concurrent install, path traversal, case/length/bundle/suffix corruption, and restart/reopen.
- importer-level iOS quarantine rejection with staged bytes, journal bytes, native temporary payload, target absence, and manifest absence all asserted at the public import boundary.

Focused and broad results before the repository gate:

```text
huggingFaceManager:jvmTest                       108 / 108
composeApp focused DB/projection/UI aggregate     82 / 82
LocalArtifactIdentityResolverTest                 18 / 18
InstalledModelEvidenceRepairerTest                14 / 14
composeApp:jvmTest                             1,033 / 1,033
```

One converted test fixture initially appeared to hang. Systematic isolation showed that its fake `StoredArtifactSnapshot.changeStamp` embedded the now-long scoped path and violated the snapshot bound before concurrent lookups could reach their test latches. The fake stamp was made bounded and stable; no production timeout, sleep, validation weakening, or coroutine workaround was added.

## Security and failure behavior

- Repository IDs, revisions, object IDs, roles, paths, bundle IDs, counts, and lengths are validated before database, filesystem, scheduler, or native use.
- A layout path cannot locate bytes, and callers cannot choose a generation.
- Absolute paths, traversal, control characters, forged/case-variant bundles, malformed suffixes, and oversized paths fail closed.
- Invalid current DB rows cannot resume, claim, publish, import, or finalize a payload.
- Invalid pending journals cannot expose a torn manifest or authorize writes.
- Cancellation continues to propagate; UI/storage errors remain generic and do not expose local paths.

## Repository and platform verification

Final fresh gates:

```text
./gradlew verifyProject --no-daemon
  BUILD SUCCESSFUL in 53s
  composeApp JVM                 1,033 tests, 0 failures, 0 errors
  huggingFaceManager JVM           108 tests, 0 failures, 0 errors
  runner JVM                        29 tests, 0 failures, 0 errors
  diffusionRunner JVM               21 tests, 0 failures, 0 errors
  native artifact/diffusion           6 tests, 0 failures

./gradlew :composeApp:compileAndroidMain \
  :huggingFaceManager:compileKotlinIosSimulatorArm64 --no-daemon
  BUILD SUCCESSFUL in 44s

git diff --check
  clean
```

The compile output contains only the repository's existing Kotlin expect/actual beta warnings and one redundant-conversion warning in the iOS secure-root implementation; there are no compile errors.

Round-four fresh gates:

```text
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  ./gradlew :huggingFaceManager:iosSimulatorArm64Test \
  --tests 'com.debanshu777.huggingfacemanager.download.IosCompletedDownloadImporterTest.quarantinedManifestFailsBeforeMutatingTheExistingCheckpoint' \
  --no-daemon
  1 / 1 passed; BUILD SUCCESSFUL in 1m 16s

DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  ./gradlew :huggingFaceManager:jvmTest \
  :huggingFaceManager:compileKotlinIosArm64 --no-daemon
  108 / 108 JVM tests passed; iOS arm64 production compile passed; BUILD SUCCESSFUL in 31s

./gradlew verifyProject --no-daemon
  BUILD SUCCESSFUL in 55s
  1,191 JVM tests and 6 native tests remain green
```

The focused iOS test was executed on a temporary iOS 26.5 simulator, which was removed afterward. A separate pre-existing `DarwinSecureArtifactRootTest.rootReplacementAfterPinningCannotReceiveAWrite` invocation failed at secure-root construction on that simulator; the importer regression therefore uses the real manifest store over a deterministic filesystem to isolate importer ordering, and no full iOS-suite pass is claimed.

## Remaining manual boundary

No new physical-device download/load sequence was run for this follow-up. Automated coverage proves current-schema reopen, exact projection, platform binding, manifest recovery, reference-aware deletion, and native-layout resolution. Device notification/background behavior remains the existing manual integration boundary.
