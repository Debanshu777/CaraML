# Final fix I report — bound iOS background responses

Date: 2026-09-20
Branch: `codex/v2-installed-model-loading`
Base: `84bb3fe3d5bffc33dcf56935d434b20d27d68e00`
Commit subject: `fix(downloads): bound iOS background responses`

## Outcome

iOS background downloads now carry their exact expected byte count in the URLSession-persisted task description and reconstruct the same bound synchronously after process relaunch. The real delegate cancels a task as soon as either the server-declared total or cumulative bytes written exceeds that exact size, including chunked/unknown-length responses. Equality remains valid.

Oversize rejection is terminal and generic (`INTEGRITY` -> the existing user-facing “Download could not be verified” message). The delegate never logs or surfaces the URL, local path, model identity, expected size, response detail, or exception detail.

## Implementation

- Replaced the two-field task description with one canonical, bounded `batchId:artifactId:expectedBytes` descriptor. Both IDs must be lowercase 64-character hex and the byte count must be canonical decimal in the same 1-byte through 1-PiB artifact range as the download identity contract.
- Added a pure common `IosDownloadResponseBound` state machine used directly by the production iOS scheduler. It tracks each platform task instance, reconstructs a missing in-memory entry from the persisted description, keeps a monotonic high-water mark, and records active, rejected, and explicitly stopped states.
- `didWriteData` now evaluates declared and written lengths even when `totalBytesExpectedToWrite` is unknown. The first violation synchronously calls `cancel()` and schedules one terminal integrity transition; duplicate rejection callbacks become inert.
- `didFinishDownloading` rechecks the response-declared length and the actual temporary-file size before capture/import, so a missing or coalesced progress callback cannot bypass the bound.
- `didCompleteWithError` consumes the state-machine disposition. A cancellation caused by the exact bound cannot race the terminal integrity write into a retryable network failure; pause/cancel completion remains inert.
- Pause, cancel, failed claim, and reconciliation mark their task instance stopped before platform cancellation, preventing late callbacks from restarting progress or failure work.
- Reconciliation parses every restored task description and compares its persisted expected bytes with the exact durable artifact snapshot before restoring its platform-task link. Missing, malformed, stale, or mismatched descriptors are canceled fail-closed; a known nonterminal mismatch is terminally rejected.
- Terminal failure cleanup removes resume data, captured completion files, and progress checkpoints. Explicit coroutine cancellation is rethrown; a concurrent user terminal transition is treated idempotently rather than overwritten.
- Updated the root and `composeApp` Recent Changes bullets. `huggingFaceManager`, runner, diffusion, and native-engine public contracts did not change.

## TDD evidence

The test was written before the state machine or descriptor existed:

```text
./gradlew :composeApp:jvmTest \
  --tests 'com.debanshu777.caraml.core.download.IosDownloadResponseBoundTest' \
  --no-daemon
```

The first sandboxed attempt stopped at the shared Gradle wrapper lock and was rerun with approved cache access. The RED run then failed compilation on the deliberately absent `IosDownloadResponseBound`, `IosDownloadBoundDecision`, `IosDownloadBoundCompletion`, and `IosBackgroundTaskDescriptor` symbols. No production implementation existed.

After the minimal implementation and production wiring, the same command passed 8/8 tests with zero skipped/failures/errors. The tests cover:

- declared response length above the exact artifact size;
- unknown/chunked length crossing the exact size;
- exact equality;
- duplicate and out-of-order callbacks without progress regression;
- process-restart reconstruction from the persisted description;
- cancellation before any progress callback and late-callback suppression;
- malformed/unbounded restored descriptions failing closed;
- invalid negative cumulative byte counts failing closed.

## Cross-platform evidence

```text
./gradlew :composeApp:compileAndroidMain --no-daemon
```

Result: PASS in 42 seconds, 25 actionable tasks (6 executed, 19 up to date). This confirms the shared pure state machine leaves Android main compilation intact.

```text
./gradlew :composeApp:compileKotlinIosSimulatorArm64 --no-daemon --max-workers=1
```

The first run exposed one Fix-I-local nullable `FileMetadata.size` type error plus the existing app-wide `GgufMetadataInspector.kt` Kotlin/Native failures. The local error was corrected and the gate rerun. The second run rebuilt the iOS native libraries and compiled the updated delegate far enough to report only the unchanged `GgufMetadataInspector.kt:20,73` `use`/nullable-generic errors. Therefore app-wide iOS compilation remains blocked outside Fix I; no Fix I source error remains in the compiler output.

## Repository gate

```text
git diff --check
./gradlew verifyProject --no-daemon
```

Result: PASS in 49 seconds. JVM: 1,104/1,104 tests across 144 suites with zero skipped/failures/errors (`composeApp` 963/120, `huggingFaceManager` 91/15, `runner` 29/6, `diffusionRunner` 21/3). Native: artifact-root CTest 1/1 and diffusion CTests 5/5. Gradle reported 41 actionable tasks (14 executed, 27 up to date). `git diff --check` passed.

## Security and exactness review

- URLSession description, callback totals, response metadata, and temporary-file metadata are treated as untrusted and strictly bounded before use.
- The exact byte limit is persisted by URLSession itself, so background callbacks do not wait for a Room lookup before enforcing the network bound.
- Durable reconciliation independently checks the persisted value against the authoritative artifact row before reconnecting task ownership.
- Unknown response length does not disable enforcement; cumulative bytes remain bounded.
- Equality is accepted and under-length completion remains subject to the existing exact importer verification.
- Oversize cancellation is synchronous at the delegate boundary; state persistence is idempotent and cannot be downgraded by the expected cancellation error callback.
- No custom URL acceptance, filesystem containment, hashing, or publication behavior was weakened.
- No URL, path, repository/model identity, byte count, response content, secret, or internal exception detail was added to logs or user-visible messages.

## Remaining verification boundary

- App-wide iOS simulator compile and Kotlin/Native test compilation are blocked by the pre-existing `GgufMetadataInspector.kt` portability error described above. That file is unchanged by Fix I.
- No iOS simulator/device runtime was launched. Background URLSession relaunch delivery requires an app lifecycle/runtime test; the deterministic delegate policy is instead exercised through the pure production state machine on the JVM, while real Foundation adapter wiring is compiler-checked up to the independent branch blocker.
- Existing expect/actual, missing `ccache`, and native toolchain warnings are unchanged.
