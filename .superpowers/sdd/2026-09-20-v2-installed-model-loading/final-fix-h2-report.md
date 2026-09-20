# Final fix H2 report — exact artifact load lifetime

Date: 2026-09-21
Branch: `codex/v2-installed-model-loading`
Base: `c7c66c1`

## Outcome

Final artifact validation, recovery-marker persistence, native open/load, and terminal marker cleanup now execute under the same bounded artifact-generation coordination used by finalization and removal. An overlapping publication or deletion cannot replace or prune any component after validation but before native ownership is established.

`LoadSessionCoordinator` receives the process-wide `InstalledModelPublicationCoordinator` through mandatory DI. Admission, resource assessment, native preflight, and any user confirmation remain outside the artifact lifetime. Once admission is Ready, the coordinator:

1. derives the complete exact component storage-key set;
2. acquires every artifact stripe in the coordinator's canonical sorted order;
3. repeats final byte validation;
4. persists the load-recovery marker;
5. enters the native loader;
6. completes success/failure/cancellation marker cleanup before releasing the generation.

The load lifetime deliberately excludes the owner stripe. A different immutable generation can still finalize or delete concurrently unless a bounded stripe collision occurs, while any shared exact component serializes the operations.

## Exact key derivation

Persisted request data is treated as untrusted before locking. Each resolved component must provide an exact repository, revision, remote object, repository-relative path, byte count, non-null bundle, canonical scoped `localRelativePath`, canonical `layoutRelativePath`, and normalized absolute local path.

The loader reconstructs `DownloadArtifactIdentity`, recomputes the complete request-set bundle digest, and re-derives the current immutable storage location. Missing, duplicate, oversized, forged-bundle, traversal/alias, storage-layout, aggregate-identity, and file/directory load-target inconsistencies fail closed as `ArtifactChanged` before final validation or native entry.

For a directory load target, the full component key set is acquired before validation starts. Every native-consumed layout path must bind exactly once beneath the validated generation root; external components receive their own storage lifetime keys as well.

## Lock order and cancellation

The load path acquires locks only as:

```text
process-wide load session -> sorted artifact stripes
```

Finalization and removal acquire sorted publication stripes and never acquire the load-session mutex. Repair's single-flight state is separate and its publication phase also does not enter native loading, so no reverse edge exists.

Coroutine cancellation and native exceptions perform non-cancellable partial-state and marker cleanup while the artifact lifetime is still held, then release every stripe. No lifetime lock is retained after a failed or canceled load.

## TDD evidence

The first RED run failed compilation because the mandatory shared publication coordinator and lifetime API did not exist. After the initial critical section was green, a second independent RED regression proved that a self-consistent but forged bundle/path generation still reached final validation and native load. Recomputing the full canonical bundle digest closed that gap.

Coverage now proves:

- overlapping mutation waits throughout final validation and native load;
- a real `ModelDownloadFinalizer` cannot enter manifest publication while exact native loading owns the generation;
- all diffusion component stripes are acquired before final validation starts;
- missing bundle, forged bundle, duplicate key, more than 64 components, malformed scoped path, and inconsistent native target reject before marker/native entry;
- cancellation and native throw clean the marker and release the generation;
- a disjoint immutable generation remains concurrent;
- the existing session mutex still prevents cross-engine load/marker overlap.

## Verification

Focused matrix:

```text
InstalledModelPublicationCoordinatorTest    8 / 8
LoadSessionCoordinatorTest                 14 / 14
DiffusionInferenceRepositoryTest           19 / 19
LlamaInferenceRepositoryTest                2 / 2
ModelDownloadFinalizerTest                 13 / 13
AppDatabaseCurrentSchemaTest               10 / 10
```

Repository gate:

```text
./gradlew verifyProject --no-daemon
  BUILD SUCCESSFUL in 1m 12s
  composeApp JVM                 1,045 tests, 0 failures, 0 errors
  huggingFaceManager JVM           108 tests, 0 failures, 0 errors
  runner JVM                        29 tests, 0 failures, 0 errors
  diffusionRunner JVM               21 tests, 0 failures, 0 errors
  native artifact/diffusion           6 tests, 0 failures
```

Platform compile:

```text
./gradlew :composeApp:compileAndroidMain --no-daemon --quiet
  exit 0

DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  ./gradlew :composeApp:compileKotlinIosSimulatorArm64 \
  -x :nativeEngine:buildLlamaRunnerCMakeIosSimulatorArm64 \
  -x :nativeEngine:compileLlamaRunnerCMakeIosSimulatorArm64 \
  -x :nativeEngine:mergeLlamaRunnerStaticIosSimulatorArm64 \
  --no-daemon --quiet
  blocked by pre-existing GgufMetadataInspector.kt common-source errors at lines 20 and 73
```

The iOS failure is unchanged and outside H2: `BufferedSource.use` has a receiver/type-inference mismatch and the nullable fallback cannot infer its type. H2 common production code compiled on JVM and Android; full iOS validation remains blocked until that known source issue is repaired.

## Documentation

Updated the root and `composeApp` Recent Changes sections. No download API changed, so `huggingFaceManager/README.md` did not require another entry.
