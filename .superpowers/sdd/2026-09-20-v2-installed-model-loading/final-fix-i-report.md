# Final fix I report — bound iOS background responses

Date: 2026-09-20
Branch: `codex/v2-installed-model-loading`
Base: `84bb3fe3d5bffc33dcf56935d434b20d27d68e00`
Commits: `fd3a298` (`fix(downloads): bound iOS background responses`), `e0a3e51` (`fix(downloads): persist iOS overflow rejection`), plus the round-two ownership follow-up in this commit

## Outcome

iOS background downloads now persist the exact expected byte count and their active, rejected, or stopped disposition in `NSURLSessionTask.taskDescription`. The real delegate rejects a task as soon as either the server-declared length or cumulative bytes written exceeds the exact expected size, including unknown-length/chunked responses. Equality remains valid.

An overflow writes a canonical rejected description before calling `cancel()`. If the app dies before the asynchronous Room transition completes, URLSession restores that rejected description and `didCompleteWithError` repairs the durable record as terminal `INTEGRITY`; the cancellation can no longer be downgraded to retryable `NETWORK`. The existing UI maps the terminal failure to the generic “Download failed” state.

Only an `ACTIVE` task description now owns a background-download slot. A still-enumerated `STOPPED` task is canceled/ignored during reconciliation and cannot block resume or relaunch replacement. Restore registration also uses a constant-space generation barrier, so completion between a URLSession task snapshot and registration cannot resurrect a completed map entry or overwrite a replacement.

## Review follow-up closure

### Crash-window durability

- Replaced the unversioned description with the bounded canonical form `1:<a|r|s>:<batchId>:<artifactId>:<expectedBytes>`.
- Both identifiers remain lowercase 64-character hex; expected bytes remain canonical decimal in the 1-byte through 1-PiB range. The complete description is restricted to 135–150 characters and round-trips through its encoder before acceptance.
- The delegate uses the production `persistIosRejectionBeforeCancellation` adapter, which assigns the rejected description before invoking cancellation. Its ordering is covered by a common test.
- `didWriteData`, `didFinishDownloading`, reconciliation, and `didCompleteWithError` all recognize restored rejection. Rejection and malformed/prior-format descriptions with validated identifiers terminally fail with `INTEGRITY`, including when the earlier database write was lost.
- Previous two-field and three-field descriptions are never accepted to continue or resume. Their strictly validated opaque identifiers may only be recovered to locate and terminally fail the matching durable record.
- A rejected disposition has precedence over stale active memory and cannot be downgraded by a concurrent pause/stop.

### Bounded lifecycle

- `stop()` no longer inserts state when `complete()` has already removed that task generation. It may return a stopped snapshot for the platform task, but the in-memory map remains empty.
- A 256-generation ordering regression proves `register -> complete -> stop` can repeat without retaining stopped tombstones or blocking the next generation.
- Scheduler ownership lookup returns a key only for canonical `ACTIVE` descriptions. `STOPPED`, `REJECTED`, malformed, and old descriptions neither satisfy `isActive` nor block `enqueue`; reconciliation cancels stopped platform tasks and the common reconciler deterministically schedules a replacement when durable intent is `RUN`.
- Reconciliation captures one opaque restore-generation token before `getAllTasks`. Any serialized completion replaces that token; a later registration from the stale snapshot is ignored. The state machine retains only the current token, requires no ordered/reusable task-ID assumption, and explicit registration of a newly created task is unaffected.
- Completion of an old stopped platform task is terminal for only that task ID and returns before durable-store mutation, so it cannot clear, fail, or otherwise mutate a resumed replacement.
- Duplicate and out-of-order progress callbacks remain inert, terminal cleanup is idempotent, and explicit coroutine cancellation still propagates.

## Implementation

- The pure common `IosDownloadResponseBound` remains the policy used by the real Foundation delegate. It validates restored descriptions synchronously, maintains a monotonic byte high-water mark, and returns a rejected descriptor for any invalid total, declared length above the bound, or written count above the bound.
- Unknown response length is represented only by `-1`; other negative declared lengths fail closed. Exactly equal declared or written lengths continue.
- Pause, cancel, failed claim, and stale-task reconciliation persist `STOPPED` before platform cancellation. A prior `REJECTED` marker wins over `STOPPED`.
- Reconciliation validates active restored descriptors against the durable artifact’s exact expected bytes before reconnecting platform ownership. Rejected, stopped, malformed, old, stale, or mismatched tasks are canceled fail-closed; only an active descriptor is considered ownership by enqueue and liveness checks.
- Terminal bounded-response cleanup removes resume data, captured completion files, progress checkpoints, and the platform task ID. Concurrent terminal transitions remain idempotent.
- No URL, filesystem path, model identity, expected size, response detail, exception detail, token, or secret is logged or surfaced.

## TDD evidence

The initial RED run failed compilation because the descriptor and response-bound state machine did not exist. The first review follow-up added process-recreation, disposition, adapter-ordering, legacy-fail-closed, and lifecycle-race tests before production changes; its RED run failed compilation on the intentionally absent `IosBackgroundTaskDisposition`, `IosBackgroundTaskKey`, `INVALID` completion, persisted-description completion, and terminal-recovery APIs. The round-two tests were also written first: their RED run failed compilation on the intentionally absent `iosActiveTaskKey`, `captureRestoreRegistrationGeneration`, and `registerRestored` APIs.

Focused command:

```text
./gradlew :composeApp:jvmTest \
  --tests 'com.debanshu777.caraml.core.download.IosDownloadResponseBoundTest' \
  --no-daemon
```

Final result: PASS, 23/23 tests, zero skipped/failures/errors. Coverage includes:

- declared oversize, unknown-length crossing, exact equality, and invalid negative counts;
- duplicate/out-of-order callbacks without progress regression;
- exact-bound reconstruction after process relaunch;
- overflow -> rejected task-description snapshot -> fresh-process cancellation completion;
- rejected durable state overriding stale active memory;
- malformed and old descriptions failing closed, never becoming a success/continuation path;
- canonical version, disposition, identifier, decimal, size, and total-description bounds;
- tested persist-before-cancel adapter ordering;
- pause/stop late-callback suppression and rejected-over-stopped precedence;
- 256 complete-before-stop generations with no state resurrection.
- still-enumerated stopped tasks not owning/blocking resume, plus active/rejected scheduler-key filtering;
- relaunch with old-stop completion both before and after replacement registration;
- active snapshot -> completion -> stale restore registration suppression;
- 10,000 completion generations retaining constant state while a fresh task ID still registers;
- fresh-process restoration with a new generation token.

## Platform and repository evidence

```text
./gradlew :composeApp:compileAndroidMain --no-daemon
```

PASS in 1 minute 7 seconds; 25 actionable tasks (6 executed, 19 up to date). This confirms the common policy leaves Android compilation unaffected.

```text
./gradlew :composeApp:compileKotlinIosSimulatorArm64 --no-daemon --max-workers=1
```

The final attempt rebuilt the native iOS libraries and reached application Kotlin compilation. It failed in 51 seconds after 29 actionable tasks and reported only the pre-existing, out-of-scope `GgufMetadataInspector.kt:20,73` Kotlin/Native `use`/nullable-generic errors. No Fix-I source appeared in compiler diagnostics. App-wide iOS compilation therefore remains blocked independently of this change.

```text
git diff --check
./gradlew verifyProject --no-daemon
```

`verifyProject` PASS in 46 seconds. JVM: 1,119/1,119 tests across 144 suites with zero skipped/failures/errors (`composeApp` 978/120, `huggingFaceManager` 91/15, `runner` 29/6, `diffusionRunner` 21/3). Native: artifact-root CTest 1/1 and diffusion CTests 5/5. Gradle reported 41 actionable tasks (14 executed, 27 up to date). `git diff --check` passed.

## Security review

- URLSession descriptions and callback totals are untrusted and strictly length-, alphabet-, format-, and range-validated before use.
- Invalid and prior formats cannot resume work. Identifier recovery is bounded and terminal-only, preventing an unbounded or malformed value from becoming a download capability.
- The durable rejection reason is assigned before cancellation, closing the process-death window without waiting for Room or exposing internals.
- Restore-race suppression uses a lock-serialized, referential generation token with constant retained state. It never trusts task-ID ordering and stale registration cannot replace a current task state.
- Unknown response length cannot disable the cumulative-byte bound; equality is accepted and under-length completion remains subject to exact importer verification.
- Failure remains generic. No URLs, paths, repository/model names, sizes, response content, secrets, or stack traces were added to logs or user-visible messages.

## Remaining verification boundary

- App-wide iOS simulator compile and Kotlin/Native test compilation remain blocked by the unchanged `GgufMetadataInspector.kt` portability errors above.
- No iOS simulator/device runtime was launched. Background URLSession relaunch delivery still needs lifecycle testing on an Apple runtime; deterministic restoration, terminal disposition, cancellation ordering, and lifecycle races are covered through the production common policy/adapter used by the real delegate.
- Existing expect/actual, missing `ccache`, OpenSSL/OpenGL, and native-toolchain warnings are unchanged.
