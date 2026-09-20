# Final Fix M Report — Android durable job ownership and cancellation

## Scope

Resolved whole-branch review findings 3–5 without changing iOS transfer behavior or artifact publication/admission policy.

## Root causes

- `DownloadRuntime.start()` launched reconciliation without an awaitable readiness boundary, so Android work could claim artifacts before process-recreation repair finished.
- `AndroidDownloadScheduler.reconcile()` was empty and `isActive()` inherited `false`; reconciliation therefore rewrote live `RUNNING` work and enqueued it again.
- UIDT enqueue rebuilt and called `JobScheduler.schedule()` even when the exact batch already had a pending job, and API 34 fallback could create a WorkManager owner without a post-failure UIDT ownership check.
- `AndroidUidtDownloadService` mutated its owner map from both JobService callbacks and an IO coroutine using only `jobId`, so a stopped generation could remove or finish a replacement.
- `DownloadBatchRunner` attempted checkpoint and lease cleanup from a cancelled coroutine context. Cancellation during/after a Room claim could leave `RUNNING` state or a lease until expiry.

## Implementation

- `DownloadRuntime` owns one lazy `Deferred` startup reconciliation. Both the UIDT service and WorkManager worker await it before `DownloadBatchRunner.run()`; enqueue itself remains non-awaiting, avoiding a startup cycle.
- Android ownership decisions are serialized. API 34+ adopts an exact pending/running UIDT or active unique WorkManager chain. If startup discovers both, it keeps the UIDT and awaits cancellation of the unique worker before opening the barrier. A late Task Manager stop marker is consumed under the same lock before any replacement enqueue. Generation-less persisted jobs are cancelled and replaced by the current generation-bound path rather than executed through a legacy path. UIDT is scheduled only when neither current owner exists; after a definite UIDT failure the scheduler performs a fresh UIDT and WorkManager query before using the unique WorkManager fallback. API 28–33 uses the unique worker.
- Reconciliation does not mutate or enqueue an actively owned batch. On API 34+, an orphaned `RUNNING` batch with neither UIDT nor WorkManager ownership becomes durable `PAUSE`, requiring explicit user resume. Other platforms/current pre-34 behavior retries.
- UIDT execution is represented by an identity token containing job ID, exact batch, and exact `JobParameters` identity. Registry mutations are synchronized. Completion removes and calls `jobFinished()` on `Dispatchers.Main.immediate` only when that token remains current; stop or replacement makes old completion inert.
- Every UIDT carries a generated owner ID that is atomically bound to its Room artifacts before scheduling. `STOP_REASON_USER` synchronously commits a bounded app-private marker before returning no-reschedule; live handling or the next startup then performs an exact-generation Room CAS to `PAUSE`, clears leases, and compare/removes that marker. A delayed old stop cannot pause or cancel a resumed replacement. App cancellation also does not reschedule. Constraint, preemption, timeout, and other system stops cancel the runner and request retry.
- Room has one cancellation CAS keyed by exact artifact and lease owner. It derives `PAUSED`, `CANCELLED`, or `FAILED_RETRYABLE` from durable batch intent while clearing the lease in the same statement. Runner cancellation performs that checkpoint in bounded `NonCancellable` work and attempts exact lease release in `finally`, preserving the original `CancellationException`.

## TDD evidence

RED was observed before production edits:

- JVM test compilation failed on missing `awaitStartupReconciliation`, `OrphanedDownloadDisposition`, and `checkpointCancellation`.
- Android host test compilation failed on missing ownership controller/backends, UIDT owner registry/token, stop policy, generation-bound marker/store APIs, and current-owner metadata validation.

Focused GREEN command:

```text
./gradlew :composeApp:testAndroidHostTest --tests '*AndroidDownloadOwnershipTest' \
  :composeApp:jvmTest --tests '*DownloadRuntimeReconciliationTest' \
  --tests '*DownloadBatchRunnerTest' \
  --tests '*DownloadDatabaseTest.cancellationCheckpointAtomicallyTransitionsClaimAndReleasesExactLease' \
  --tests '*DownloadDatabaseTest.userStopPausesOnlyTheExactBoundPlatformGeneration' \
  --no-daemon
```

Result: `BUILD SUCCESSFUL`; 25 tests, 0 failures:

- Android ownership: 13
- runtime/reconciliation: 3
- runner: 7
- real Room cancellation and platform-generation CAS: 2

Repository gates:

```text
./gradlew verifyProject :androidApp:assembleDebug --no-daemon
```

Result: `BUILD SUCCESSFUL`; 1,220 JVM tests and 6 native tests passed, and the 175 MB debug APK assembled.

An earlier unfiltered `:composeApp:testAndroidHostTest` run was not a clean project gate: it executed 927 inherited common/UI tests in the Android stub host and reported 214 unrelated failures, predominantly unmocked Compose/Android APIs (`NullPointerException` and `android.util.Log not mocked`). The final scoped `AndroidDownloadOwnershipTest` host suite passed 13/13.

## Android contract note

The implementation follows the Android UIDT contract: UIDT is Android 14+; `onStopJob()` exposes `getStopReason()` for system callbacks; exact WorkManager ownership is queried from the unique work chain. Android also documents that Task Manager Stop can terminate the app without calling `onStopJob()`. The orphaned-UIDT startup policy is therefore intentionally conservative: a missing API 34 owner becomes a durable user-resumable pause instead of being restarted automatically.

Official references:

- https://developer.android.com/develop/background-work/background-tasks/uidt
- https://developer.android.com/reference/android/app/job/JobParameters
- https://developer.android.com/reference/kotlin/androidx/work/WorkManager

## Limitations

- When Android delivers `onStopJob()`, Task Manager Stop is synchronously marked before return and completed through an exact-generation Room CAS. If Android kills the process without that callback, an already-`RUNNING` orphan is converted to `PAUSE` during next startup; the platform provides no callback in which to mark a not-yet-claimed queued generation.
- Host tests cover scheduler/owner races deterministically. A physical-device Task Manager stop remains a final manual lifecycle check because the platform intentionally kills the process.
