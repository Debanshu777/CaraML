# Final fix H2 report — exact artifact load lifetime

Date: 2026-09-21
Branch: `codex/v2-installed-model-loading`
Base: `c7c66c1`

## Outcome

Final artifact validation, recovery-marker persistence, native open/load, and terminal marker cleanup now execute under the exact `ArtifactRootLockCoordinator` used by download commit, manifest recovery, iOS import, validation, and cleanup. An overlapping byte or manifest mutation cannot replace or prune any component after validation but before native ownership is established.

`LoadSessionCoordinator` receives the process-wide `ArtifactRootLifetime` singleton through mandatory DI. Its narrow public API delegates to the download subsystem's existing internal coordinator and key normalization; tests alone may inject a seam. Admission, resource assessment, native preflight, and any user confirmation remain outside the artifact lifetime. Once admission is Ready, the coordinator:

1. validates the complete component binding and supplies every resolver-proven repository root;
2. boundedly discovers roots from every structurally valid main, staged, and previous owner-bundle recovery candidate;
3. acquires expected plus candidate roots in the download coordinator's canonical sorted order;
4. recovery-validates and exactly compares the unchanged current owner bundle without recursively locking;
5. repeats final byte validation;
6. persists the load-recovery marker and enters the native loader;
7. completes success/failure/cancellation marker cleanup before releasing the roots.

The obsolete compose publication-stripe lifetime was removed. Loads now conflict with every real mutation path that touches a shared repository root; disjoint roots remain concurrent.

## Exact key derivation

Persisted request data is treated as untrusted before locking. Each resolved component must provide an exact repository, revision, manifest `remoteObjectId`, repository-relative path, byte count, non-null bundle, canonical scoped `localRelativePath`, canonical `layoutRelativePath`, normalized absolute local path, and a canonical `storageRoot` freshly rebound by resolver revalidation to the current trusted platform storage provider.

The loader reconstructs `DownloadArtifactIdentity` from the exact manifest remote ID, recomputes the complete request-set bundle digest, and re-derives each immutable storage location beneath its proven root. Under the acquired lifetime it compares the complete current entry multiset: role, repository, revision, repository path, exact remote ID, size, content hash, bundle, local path, and layout path. Git, LFS, and Xet identities remain distinct; no synthetic LFS value can replace a Xet ID during bundle reconstruction. Missing, stale-current, duplicate, oversized, forged-bundle, traversal/alias, storage-layout/root, aggregate-identity, and file/directory load-target inconsistencies fail closed as `ArtifactChanged` before final byte validation or native entry.

For a directory load target, the full component key set is acquired before validation starts. Every native-consumed layout path must bind exactly once beneath the validated generation root; external components receive their own storage lifetime keys as well.

## Lock order and cancellation

The load path acquires locks only as:

```text
process-wide load session -> sorted artifact roots
```

Download commit, recovery, validation, import, and cleanup acquire artifact roots directly. Finalization and removal acquire publication coordination before entering those root operations and never acquire the load-session mutex. The load path never acquires publication coordination, so no reverse edge exists.

Coroutine cancellation and native exceptions perform non-cancellable partial-state and marker cleanup while the artifact lifetime is still held, then release every root. No lifetime lock is retained after a failed or canceled load.

## TDD evidence

The review-follow-up RED run failed compilation because the shared artifact-root lifetime API and mandatory exact remote/root component fields did not exist. Git/LFS/Xet round-trip tests then exposed the synthesized Xet identity, while the real download/recovery races could not coordinate with the load lifetime. The GREEN implementation exposed only the existing root coordinator, preserved the exact manifest remote ID, and rebound current roots through the resolver.

Coverage now proves:

- a real `DownloadManager` replacement cannot request, change target bytes, or change the manifest while a validated consumer owns the root, then commits exactly once after release;
- pending-journal read recovery performs zero target, manifest, part, or journal mutation while the root lifetime is held, then recovers after release;
- a newer authoritative owner bundle rejects a stale request before byte hashing, marker persistence, or native entry even when its old immutable generation remains;
- current-bundle discovery includes and locks external candidate repositories before invoking the consumer;
- both the canonical `DownloadManager.validatedBundle` read and native lifetime recover a PREPARED owner-bundle publication under all main/staged/previous candidate roots, so a newly introduced external repository cannot cause the valid staged manifest to be discarded;
- all diffusion component roots are acquired before final validation starts, preventing mixed-generation observation;
- exact Git, LFS, and Xet manifest identities round-trip, while remote-ID or storage-root mutation rejects;
- missing bundle, forged bundle, duplicate key, more than 64 components, malformed scoped path, and inconsistent native target reject before marker/native entry;
- cancellation and native throw clean the marker and release the generation;
- a disjoint immutable generation remains concurrent;
- the existing session mutex still prevents cross-engine load/marker overlap.

## Verification

Focused matrix:

```text
DownloadManagerJvmTest                     23 / 23
LocalArtifactIdentityResolverTest          21 / 21
LoadSessionCoordinatorTest                 17 / 17
InstalledModelLoadRequestResolverTest      28 / 28
ModelDownloadFinalizerTest                 12 / 12
Chat load/retry focused tests              32 / 32
```

Repository gate:

```text
./gradlew verifyProject --no-daemon --no-parallel -Pkotlin.incremental=false
  BUILD SUCCESSFUL in 53s
  composeApp JVM                 1,050 tests, 0 failures, 0 errors
  huggingFaceManager JVM           113 tests, 0 failures, 0 errors
  runner JVM                        29 tests, 0 failures, 0 errors
  diffusionRunner JVM               21 tests, 0 failures, 0 errors
  native artifact/diffusion           6 tests, 0 failures
```

Platform compile:

```text
./gradlew :composeApp:compileAndroidMain --no-daemon --no-parallel -Pkotlin.incremental=false --quiet
  exit 0

DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  ./gradlew :composeApp:compileKotlinIosSimulatorArm64 \
  -x :nativeEngine:buildLlamaRunnerCMakeIosSimulatorArm64 \
  -x :nativeEngine:compileLlamaRunnerCMakeIosSimulatorArm64 \
  -x :nativeEngine:mergeLlamaRunnerStaticIosSimulatorArm64 \
  --no-daemon --no-parallel -Pkotlin.incremental=false --quiet
  blocked by pre-existing GgufMetadataInspector.kt common-source errors at lines 20 and 73
```

The iOS failure is unchanged and outside H2: `BufferedSource.use` has a receiver/type-inference mismatch and the nullable fallback cannot infer its type. H2 common production code compiled on JVM and Android; full iOS validation remains blocked until that known source issue is repaired.

## Documentation

Updated the root, `composeApp`, and `huggingFaceManager` Recent Changes sections.
