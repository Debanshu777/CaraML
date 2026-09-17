# Durable Model Downloads Design

**Date:** 2026-09-17
**Status:** Proposed for implementation
**Scope:** Model Hub downloads on Android, iOS, and Desktop

## Problem

CaraML currently exposes `DownloadManager.download()` as a cold Ktor `Flow`. `ModelViewModel` collects that flow from `viewModelScope` and performs bundle publication plus local-model database insertion after the final progress event. This makes the screen's ViewModel the owner of a potentially multi-hour transfer. Navigation, process death, suspension, or an OS stop can interrupt the owner even though a staged `.part` file exists.

The current transfer engine already has important integrity properties that must not regress:

- the remote artifact is identified by repository, immutable revision, relative path, expected bytes, and remote object identity;
- destinations are resolved beneath a secure model root;
- bytes are written to staging, not directly exposed as an installed model;
- byte count and optional SHA-256 are verified;
- a manifest transaction atomically publishes verified artifacts;
- incomplete, failed, or cancelled work is recovered or discarded;
- a diffusion model is ready only after its complete exact bundle is published.

The replacement must move ownership out of `ModelViewModel` without moving trust into an unverified OS download directory.

## Goals

1. A queued download survives navigation and ViewModel recreation on all platforms.
2. Android and iOS downloads continue using supported OS background-transfer mechanisms when the app is not foregrounded.
3. A transfer interrupted by process or network loss resumes from verified staged bytes when the server safely supports resumption.
4. Users can pause, resume, cancel, and retry downloads from CaraML.
5. Progress and terminal state are persistent and observable by any screen.
6. No model becomes loadable until its exact artifact or full bundle passes the existing verification and manifest publication rules.
7. Duplicate taps and scheduler retries are idempotent.
8. `Needs information` remains advisory; it never disables an otherwise exact, valid artifact download.

## Non-goals

- Synchronizing download queues across devices.
- Downloading from arbitrary user-provided URLs.
- Publishing partial diffusion bundles as runnable models.
- A new full-screen download manager in the first delivery. Existing Model Hub rows and platform notifications are sufficient initially.
- Keeping a Desktop download alive after the desktop process has exited. Desktop resumes persisted work on the next launch.

## Considered Approaches

### 1. Use Android `DownloadManager` and iOS background `URLSession` directly

This is attractive because the operating system owns the transfer. Android `DownloadManager` provides enqueue, automatic network start, status queries, and system retry behavior. It does not expose an application API for a user-controlled pause and resume; its `STATUS_PAUSED` represents system-controlled waiting. It also cannot preserve CaraML's streaming verifier, bundle transaction, and redirect controls during transfer. Verification would therefore require a second full read before publication.

This approach is acceptable for a generic public download, but it does not satisfy CaraML's pause/resume and artifact-security contract by itself.

### 2. Keep the current common Ktor flow and place it in an application coroutine

This is the smallest change and would survive navigation. It would still be terminated with the process, would not receive iOS background execution, and would not integrate with Android's user-visible long-running work rules. It does not satisfy the durability goal.

### 3. Persistent coordinator plus platform background drivers

This is the selected approach. Common code owns task identity, persistent state, verification, and publication. Platform code owns scheduling and transfer lifecycle:

- Android 14+ uses a user-initiated data-transfer `JobService` (UIDT).
- Android 9-13 uses a foreground `CoroutineWorker` scheduled by WorkManager.
- iOS uses one background `URLSession` with a stable identifier and delegate callbacks.
- Desktop uses an application-scoped coroutine worker backed by the same persistent queue.

Android and Desktop use the existing Ktor/Okio transfer engine extended with HTTP Range support. iOS uses native download tasks and resume data, then hands the temporary file to the common verifier. This gives each platform the lifecycle mechanism it supports while retaining one trust and publication boundary.

## Architecture

### Ownership boundaries

`huggingFaceManager` remains the owner of artifact request validation, secure staging, response validation, hashing, manifest commits, bundle publication, and bundle validation.

`composeApp` owns product-level durable orchestration because it already owns model metadata, Room, dependency injection, and UI state. It introduces:

- `DownloadCoordinator`: the only API used by ViewModels;
- `DownloadTaskStore`: persistent batch and artifact state;
- `PlatformDownloadScheduler`: an expect/actual scheduling boundary;
- `DownloadFinalizer`: verifies a completed staged artifact, commits its manifest, and publishes a completed batch;
- `DownloadReconciler`: repairs scheduler/database disagreement at application startup.

`ModelViewModel` becomes a renderer and command sender. It no longer performs network collection, publication, or completion persistence in `viewModelScope`.

### Persistent model

A dedicated KMP Room database named `downloads.db` will be used instead of adding transient state to the existing local-model database. It must use explicit migrations and must not use destructive migration fallback.

`download_batch` stores:

- deterministic batch ID;
- owner model ID and model type;
- state and failure code;
- whether the user confirmed download-for-later behavior;
- created and updated timestamps.

`download_artifact` stores:

- deterministic artifact task ID and parent batch ID;
- repository ID, immutable revision, remote relative path, and remote object ID;
- expected byte count and expected SHA-256 when available;
- logical role, destination-relative path, and bundle ID;
- non-sensitive display metadata required for the final local-model record;
- state, received bytes, ETag, Last-Modified, retry count, and platform task ID;
- a generated staging token, never an arbitrary or externally supplied absolute path.

Absolute destinations are resolved from `StoragePathProvider` every time work executes. Persisted input cannot escape the secure root.

The deterministic artifact ID is a SHA-256 of the canonical exact identity plus destination and bundle role. The batch ID is derived from the ordered artifact IDs and owner model ID. Enqueue uses insert-if-absent semantics, so repeated taps or scheduler redelivery return the existing task.

### State machine

Batch state is derived transactionally from artifact states. Artifact states are:

```text
QUEUED -> RUNNING -> VERIFYING -> COMPLETED
   |         |           |
   |         +-> PAUSED <-+
   |         +-> WAITING_FOR_NETWORK -> QUEUED
   |         +-> FAILED_RETRYABLE -> QUEUED
   |         +-> FAILED_TERMINAL
   +-------------------------------> CANCELLED
```

Only these commands are public:

- `enqueue(batch)`
- `pause(batchId)`
- `resume(batchId)`
- `cancel(batchId)`
- `retry(batchId)`
- `observe(batchId)` and `observeForModel(modelId)`

Commands update the database first and then reconcile platform work. A scheduler callback cannot directly mark a task complete; only `DownloadFinalizer` can do that after verification and manifest commit.

State transitions use database transactions and compare-and-set predicates. Only one worker lease may own an artifact. Scheduler duplication, process relaunch, and repeated completion callbacks are safe no-ops after the first valid transition.

### Transfer and resume protocol

All requests are created from a validated `DownloadArtifactIdentity`; platform drivers never accept an arbitrary URL. The base scheme and host are fixed to HTTPS Hugging Face endpoints, path segments are encoded, and only the immutable revision is used.

For Android and Desktop:

1. Resolve the secure staging target.
2. If no staged bytes exist, issue the existing full request.
3. If staged bytes exist with a persisted ETag or Last-Modified validator, issue `Range: bytes=<size>-` with `If-Range`.
4. Append only when the response is `206`, `Content-Range` begins at the exact staged length, total length matches expected bytes, and the validator remains compatible.
5. If the server returns `200`, an incompatible validator, or an invalid range, discard staged bytes and safely restart from zero.
6. Persist progress at a throttled cadence and at lifecycle boundaries; do not write Room for every network buffer.
7. Re-hash the complete staged file before publication. Hash state is not serialized across processes.

For iOS:

1. Create tasks in one `URLSessionConfiguration.background(withIdentifier:)` session.
2. Persist the native task identifier with the artifact record.
3. Pause using cancellation that produces resume data; store resume data in an app-private file referenced by a generated token, not in a database blob.
4. Resume with a new download task created from validated resume data.
5. On completion, move the temporary file immediately into CaraML's secure staging root before the delegate returns.
6. Reconcile session tasks with database records whenever the session is recreated.

iOS background sessions automatically follow redirects and do not call the redirection delegate. Therefore the initial URL is always generated from CaraML's fixed HTTPS Hugging Face base, and the final response URL, scheme, host, expected byte count, and artifact digest are validated before staged data can be published. A failed allowlist or private-address check is terminal and the staged file is removed.

### Verification and publication

Platform completion means only “all bytes reached secure staging.” It does not mean “model installed.”

`DownloadFinalizer` performs:

1. exact identity and destination revalidation from persisted structured fields;
2. secure-root containment and staged-file type checks;
3. exact byte-count validation;
4. SHA-256 calculation and comparison when the remote object identity provides it;
5. existing `ArtifactManifestEntry` creation and atomic manifest commit;
6. complete bundle validation and aggregate publication;
7. local model/component database insertion only after publication succeeds;
8. idempotent transition to `COMPLETED`.

For a multi-file diffusion batch, individual artifacts may be verified and committed to their repository manifests, but the owner model is not marked ready until every required artifact matches and the aggregate bundle manifest publishes successfully. Restart reconciliation can continue an interrupted bundle without redownloading already verified exact artifacts.

### Platform scheduling

#### Android

On API 34+, `AndroidDownloadScheduler` schedules a persisted UIDT `JobService` while the app is visible, declares `RUN_USER_INITIATED_JOBS` and `RECEIVE_BOOT_COMPLETED`, supplies estimated network bytes, and posts a progress notification. If Android stops the job, the worker checkpoints state before returning when callbacks are available; startup reconciliation also handles abrupt termination where no callback occurs.

On API 28-33, the scheduler enqueues unique foreground WorkManager work with a network constraint. The worker posts an ongoing notification, declares the `dataSync` foreground-service type and corresponding permission where required, and uses unique work keyed by batch ID. Pause/cancel first changes persistent state and then stops scheduled work. Resume schedules a new worker that uses the staged-range protocol.

The notification exposes Pause or Resume and Cancel actions through explicit immutable `PendingIntent`s. Android 13+ notification permission denial does not block a user-initiated download; the app still follows platform foreground-service requirements and shows in-app status.

#### iOS

The iOS app creates the stable background session during launch, including system relaunch. The Swift application delegate forwards `handleEventsForBackgroundURLSession` completion handling into the Kotlin bridge. The bridge invokes the completion handler only after all pending delegate events and database/finalization work are durably recorded.

The session uses `sessionSendsLaunchEvents = true`. Transfers are user-initiated, so `isDiscretionary` is false by default. Pause/resume is best-effort because Apple may decline to produce usable resume data; in that case the task remains safely restartable from zero.

#### Desktop

An application-scoped `DesktopDownloadService` starts after dependency injection, claims queued/retryable records, and runs a bounded number of transfers. Closing the application checkpoints each active artifact as paused or retryable. On the next launch the reconciler validates staged length and resumes with Range when safe.

The first version uses one concurrent artifact transfer on mobile and up to two on Desktop. Concurrency can be made configurable later without changing the storage contract.

## UI Behavior

Model Hub rows observe persistent download records instead of local ViewModel booleans. The row renders:

- download action when no task exists;
- queued state before execution begins;
- determinate progress while byte count is known;
- Pause while running;
- Resume while paused;
- “Waiting for network” for constrained retries;
- Retry for retryable failures;
- a concise terminal error for integrity, storage, or permission failures;
- installed state only after final publication.

Navigating away and returning reconstructs the same state from Room. The existing `Needs information` chip remains visible as compatibility information and does not alter these controls when the exact artifact is valid.

User-facing errors stay generic. Internal exception messages, URLs, filesystem paths, headers, tokens, and resume data are never logged or shown. Persisted failure codes are a closed enum such as `NETWORK`, `STORAGE`, `HTTP`, `INTEGRITY`, `SECURE_PATH`, and `PLATFORM`.

## Recovery Rules

At startup and whenever a platform callback arrives, `DownloadReconciler` compares persisted records, scheduler tasks, staged files, and manifests:

- a valid manifest wins and marks the exact task complete;
- `RUNNING` without a live platform task becomes `QUEUED` or `PAUSED` according to user intent;
- a live platform task without a valid record is cancelled and its untrusted output removed;
- staged bytes larger than expected, outside the secure root, or without matching identity are removed;
- a completed native task is finalized idempotently;
- a partial bundle retains verified exact members but remains not ready;
- cancellation removes platform work, resume data, uncommitted staging, and the active record while preserving previously published artifacts.

Automatic retries use bounded exponential backoff with jitter for network and transient 5xx failures. Integrity, identity, path-containment, unsupported response, and 4xx failures are terminal unless the user explicitly starts a fresh download after metadata refresh.

## Compatibility and Migration

The current `DownloadManager.download(): Flow<DownloadProgressDTO>` remains temporarily available behind an internal legacy adapter while call sites move to `DownloadCoordinator`. New UI code must not call it directly.

Existing complete manifests and local-model records require no migration. Existing `.part` files have no persisted validator or task identity; startup recovery removes them unless they can be matched exactly and safely restarted from zero. They are never treated as installed.

Rollout order:

1. Add persistent contracts, state-machine tests, coordinator, and finalizer while retaining the legacy path.
2. Add resumable Ktor transport and Desktop scheduler; migrate ViewModel observation and commands.
3. Add Android UIDT and WorkManager implementations plus notification actions.
4. Add iOS background-session bridge and app-delegate lifecycle handling.
5. Remove the legacy ViewModel-owned flow after all platform gates pass.

Each phase is compatibility-safe: if a platform scheduler cannot enqueue, the record becomes a retryable platform failure and the UI reports it. It must not silently fall back to a ViewModel-owned transfer.

## Test Strategy

Common tests:

- deterministic IDs and duplicate enqueue idempotency;
- every legal and illegal state transition;
- concurrent claim/lease behavior;
- exact identity serialization and reconstruction;
- batch completion only after all exact artifacts finalize;
- startup reconciliation scenarios;
- generic error mapping without sensitive data.

Transfer tests with an HTTP test server:

- valid `206` continuation with ETag and `Content-Range`;
- server ignores Range and safely restarts with `200`;
- validator changes between attempts;
- malformed or oversized range response;
- truncated response, timeout, cancellation, and retry;
- final byte count and digest mismatch;
- HTTPS/host/private-address rejection;
- Unicode paths and immutable revision encoding.

Android tests:

- API 34+ UIDT scheduling and reconciliation;
- API 28-33 unique WorkManager fallback;
- process stop and reboot recovery;
- notification pause/resume/cancel intents;
- denied notification permission behavior;
- network and storage constraints.

iOS tests:

- stable session recreation and task-to-record reconciliation;
- background completion-handler ordering;
- pause with and without usable resume data;
- temporary-file move before delegate return;
- system termination and relaunch delivery;
- final URL rejection and staged-file cleanup.

Desktop tests:

- close/relaunch checkpoint recovery;
- bounded concurrency;
- staged file reconciliation and Range resume.

Repository gates remain `./gradlew verifyProject`, `:composeApp:assembleDebug`, Desktop tests/build, and an Xcode iOS build. Device-level background tests are required before calling Android or iOS lifecycle behavior verified.

## Acceptance Criteria

- Starting a valid exact-artifact download immediately creates a durable observable record.
- Leaving Model Details does not stop the transfer.
- Android continues or safely reschedules after process loss and reboot within platform rules.
- iOS continues through suspension and reconnects after system relaunch.
- Desktop resumes safe staged work after app relaunch.
- Pause and resume preserve received bytes when the platform/server supplies valid continuation data; otherwise they safely restart without publishing stale bytes.
- Retry never produces duplicate final files, manifests, or local-model rows.
- A file with the wrong size, digest, identity, destination, final host, or bundle membership is never published.
- `Needs information` is displayed as information and never becomes a download blocker by itself.

## Platform References

- [Android `DownloadManager`](https://developer.android.com/reference/android/app/DownloadManager)
- [Android long-running WorkManager workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)
- [Android user-initiated data transfer jobs](https://developer.android.com/develop/background-work/background-tasks/uidt)
- [Apple: Downloading files from websites](https://developer.apple.com/documentation/foundation/downloading-files-from-websites)
- [Apple: Downloading files in the background](https://developer.apple.com/documentation/foundation/downloading-files-in-the-background)
