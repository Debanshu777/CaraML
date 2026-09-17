# Durable Model Downloads Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace ViewModel-owned model transfers with a durable, resumable queue that keeps downloading in the background, exposes pause/resume/cancel/retry, reports Android notification progress, and publishes only fully verified artifacts.

**Architecture:** A common `DownloadCoordinator` persists exact batch/artifact records in a dedicated KMP Room database before scheduling work. Android uses UIDT jobs on API 34+ and foreground WorkManager on API 28-33; iOS uses one background `URLSession`; Desktop uses an application-scoped worker that resumes on relaunch. All platforms terminate at the existing `ArtifactManifestStore` and `ArtifactBundleManifestStore` verification boundary before local-model records become visible.

**Tech Stack:** Kotlin 2.4.0, Compose Multiplatform 1.11.1, Room 2.8.4, Ktor 3.5.0, Okio 3.9.1, kotlinx-coroutines 1.11.0, WorkManager 2.11.2, Android JobScheduler UIDT, iOS Foundation background `URLSession`.

**Spec:** `docs/superpowers/specs/2026-09-17-durable-model-downloads-design.md`

## Global Constraints

- Android minSdk remains 28, compileSdk/targetSdk remain 36, and JDK remains 21+.
- Android 14+ uses a persisted user-initiated data-transfer `JobService`; Android 9-13 uses foreground WorkManager 2.11.2.
- iOS uses one background session identifier: `com.debanshu777.caraml.model-downloads`.
- Mobile concurrency is one active artifact per batch; Desktop permits two active batches.
- Queue state must be persisted before platform scheduling; scheduler failure becomes `FAILED_RETRYABLE`, never a ViewModel-owned fallback.
- `Needs information` remains advisory when `DownloadArtifactIdentity` exactly matches the current detail artifact.
- No arbitrary URL or absolute destination may enter the queue. Persist only validated structured identity plus a generated staging/resume token.
- Preserve secure-root containment, special-file rejection, exact byte count, SHA-256 verification, manifest journaling, and atomic publication.
- A diffusion model becomes ready only after every required exact artifact and the aggregate bundle manifest validate.
- User-visible errors remain generic; never persist or log request URLs, headers, tokens, filesystem paths, resume bytes, or exception text.
- Existing dirty changes are user-owned. Stage only files named by a completed task; do not overwrite the current Model Details or native root fixes.
- Commit steps are execution checkpoints. If the user has not authorized commits in the dirty worktree, stop after the preceding verification step and report the exact staged/unstaged boundary.

## File Structure

### Common orchestration (`composeApp`)

- Create `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadModels.kt` — closed states, requests, snapshots, errors, and deterministic IDs.
- Create `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadTaskStore.kt` — persistence interface and atomic transition contract.
- Create `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/PlatformDownloadScheduler.kt` — platform scheduling interface.
- Create `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadCoordinator.kt` — enqueue and user-command boundary.
- Create `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadBatchRunner.kt` — sequential artifact runner used by Android and Desktop.
- Create `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/ModelDownloadFinalizer.kt` — aggregate manifest publication and local-model/component insertion.
- Create `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadReconciler.kt` — startup/scheduler/staging reconciliation.
- Create `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadRuntime.kt` — idempotent app-scope startup.
- Create `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadNotificationPermissionController.kt` — non-blocking permission request boundary.

### Durable queue storage (`composeApp`)

- Create `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/storage/DownloadBatchEntity.kt`.
- Create `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/storage/DownloadArtifactEntity.kt`.
- Create `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/storage/DownloadTaskDao.kt`.
- Create `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/storage/DownloadDatabase.kt`.
- Create `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/storage/RoomDownloadTaskStore.kt`.
- Create `DownloadDatabase.android.kt`, `DownloadDatabase.ios.kt`, and `DownloadDatabase.jvm.kt` in each platform's `com/debanshu777/caraml/core/download/storage/` package.

### Transfer and verification (`huggingFaceManager`, `nativeEngine`)

- Create `huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadResumeMetadata.kt`.
- Create `huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/ContentRange.kt`.
- Modify the common `DownloadEngine.kt`, `DownloadManager.kt`, `ArtifactManifestStore.kt`, and `SecureArtifactRoot.kt` contracts plus their Android, iOS, and JVM implementations named in Task 3.
- Modify `nativeEngine/src/artifactFs/cpp/artifact_fs_jni.cpp` and `artifact_fs_jni_windows.cpp` to add secure append-only opening without weakening root pinning.
- Create `huggingFaceManager/src/iosMain/kotlin/com/debanshu777/huggingfacemanager/download/IosCompletedDownloadImporter.kt`.

### Platform runtimes

- Create Desktop files under `composeApp/src/jvmMain/kotlin/com/debanshu777/caraml/core/download/`.
- Create Android scheduler, worker, UIDT service, notification factory, actions, and permission controller under `composeApp/src/androidMain/kotlin/com/debanshu777/caraml/core/download/`.
- Create `composeApp/src/androidMain/AndroidManifest.xml` for library-owned service/receiver declarations.
- Create iOS scheduler, delegate, resume-data store, notification presenter, and bridge under `composeApp/src/iosMain/kotlin/com/debanshu777/caraml/core/download/`.
- Modify `androidApp` and `iosApp` entry points to initialize and reconnect the durable runtime.

### Presentation and documentation

- Modify `ModelViewModel.kt`, `DetailsScreen.kt`, `ModelDetailContent.kt`, `GgufFileListItem.kt`, and `InstallBundleCard.kt` to render persistent state and dispatch commands.
- Update root, `composeApp`, and `huggingFaceManager` README Recent Changes sections after all behavior is verified.

---

### Task 1: Define durable download contracts and deterministic identity

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadModels.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/download/DownloadModelsTest.kt`
- Modify: `composeApp/build.gradle.kts`

**Interfaces:**
- Consumes: `DownloadMetadataDTO`, `DownloadArtifactIdentity`, and model type strings.
- Produces: `DownloadBatchRequest`, `DownloadArtifactRequest`, `DownloadBatchSnapshot`, `DownloadArtifactSnapshot`, `DownloadBatchState`, `DownloadArtifactState`, `DownloadFailureCode`, `DownloadUserIntent`, `downloadArtifactTaskId()`, and `downloadBatchId()`.

- [ ] **Step 1: Add an explicit Okio dependency to common code**

Add `implementation(libs.okio)` to `composeApp` `commonMain.dependencies`; deterministic IDs use `Buffer.sha256()` and must not depend on a transitive dependency.

- [ ] **Step 2: Write failing identity and state tests**

Cover canonical ordering, duplicate artifact rejection, exact-ID sensitivity, legal transitions, illegal terminal transitions, and bounded display metadata:

```kotlin
@Test
fun batchIdIsOrderIndependentButRoleAndDestinationSensitive() {
    val a = artifactRequest("model", "weights/model.gguf")
    val b = artifactRequest("clip-l", "text_encoder/model.safetensors")
    assertEquals(downloadBatchId("owner/model", listOf(a, b)), downloadBatchId("owner/model", listOf(b, a)))
    assertNotEquals(
        downloadBatchId("owner/model", listOf(a)),
        downloadBatchId("owner/model", listOf(a.copy(logicalRole = "draft-model"))),
    )
}

@Test
fun completedArtifactCannotReturnToRunning() {
    assertFalse(DownloadArtifactState.COMPLETED.canTransitionTo(DownloadArtifactState.RUNNING))
}
```

- [ ] **Step 3: Run the focused test and verify it fails**

Run: `./gradlew :composeApp:jvmTest --tests '*DownloadModelsTest'`

Expected: FAIL because the durable download contracts do not exist.

- [ ] **Step 4: Implement the closed contracts**

Use these exact enum values and request shapes:

```kotlin
enum class DownloadBatchState { QUEUED, RUNNING, PAUSED, WAITING_FOR_NETWORK, VERIFYING, COMPLETED, FAILED_RETRYABLE, FAILED_TERMINAL, CANCELLED }
enum class DownloadArtifactState { QUEUED, RUNNING, PAUSED, WAITING_FOR_NETWORK, VERIFYING, COMPLETED, FAILED_RETRYABLE, FAILED_TERMINAL, CANCELLED }
enum class DownloadFailureCode { NETWORK, STORAGE, HTTP, INTEGRITY, SECURE_PATH, PLATFORM }
enum class DownloadUserIntent { RUN, PAUSE, CANCEL }

data class DownloadArtifactRequest(
    val metadata: DownloadMetadataDTO,
    val primary: Boolean,
)

data class DownloadBatchRequest(
    val ownerModelId: String,
    val modelType: String,
    val artifacts: List<DownloadArtifactRequest>,
    val downloadForLaterConfirmed: Boolean,
)
```

Validate one to 64 unique artifacts, one or more primaries, a single bundle ID, exact `sizeBytes`, role/destination bounds already enforced by `DownloadMetadataDTO`, and display strings capped at 512 characters without control characters. Hash length-prefixed UTF-8 values; never join with an ambiguous delimiter. `downloadForLaterConfirmed` is informational queue metadata only and must not reject an exact artifact or change scheduling; add a test that both values produce an admissible request.

- [ ] **Step 5: Run the contract tests**

Run: `./gradlew :composeApp:jvmTest --tests '*DownloadModelsTest'`

Expected: PASS.

- [ ] **Step 6: Commit the contract milestone**

```bash
git add composeApp/build.gradle.kts composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadModels.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/download/DownloadModelsTest.kt
git commit -m "feat(downloads): define durable queue contracts"
```

### Task 2: Add the dedicated non-destructive download database

**Files:**
- Modify: `huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/StoragePathProvider.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadTaskStore.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/storage/DownloadBatchEntity.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/storage/DownloadArtifactEntity.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/storage/DownloadTaskDao.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/storage/DownloadDatabase.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/storage/RoomDownloadTaskStore.kt`
- Create: `composeApp/src/androidMain/kotlin/com/debanshu777/caraml/core/download/storage/DownloadDatabase.android.kt`
- Create: `composeApp/src/iosMain/kotlin/com/debanshu777/caraml/core/download/storage/DownloadDatabase.ios.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/debanshu777/caraml/core/download/storage/DownloadDatabase.jvm.kt`
- Modify: `composeApp/build.gradle.kts`
- Test: `composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/download/storage/DownloadDatabaseTest.kt`

**Interfaces:**
- Consumes: Task 1 contracts.
- Produces: `DownloadTaskStore` with atomic insert, observation, claim, progress, transition, lease recovery, and deletion methods.

- [ ] **Step 1: Add a deterministic database path**

Add this default to `StoragePathProvider`:

```kotlin
fun getDownloadDatabasePath(): String = siblingDatabasePath("downloads.db")
```

Extract the existing recommendation path construction into a private path helper so both database filenames remain fixed and no caller supplies a filename.

- [ ] **Step 2: Write failing persistence tests**

The tests must prove:

```kotlin
@Test
fun enqueueIsIdempotentAndPersistsExactIdentity() = runTest { /* insert twice; assert one batch and exact artifact */ }

@Test
fun onlyOneLeaseCanClaimTheSameArtifact() = runTest { /* race two claim calls; assert one winner */ }

@Test
fun reopeningDatabasePreservesPausedCheckpointAndUserIntent() = runTest { /* close/reopen; assert bytes, ETag, PAUSE */ }

@Test
fun downloadDatabaseIsIndependentFromPrimaryModelDatabase() = runTest { /* clear queue; assert LocalModelEntity remains */ }
```

- [ ] **Step 3: Run the focused database test and verify it fails**

Run: `./gradlew :composeApp:jvmTest --tests '*DownloadDatabaseTest'`

Expected: FAIL because `DownloadDatabase` and its DAO do not exist.

- [ ] **Step 4: Implement normalized Room entities**

Use `download_batch` and `download_artifact`, with a foreign key from artifact to batch and an index on `(owner_model_id, updated_at_epoch_ms)`. Persist enum names as strings and reconstruct domain types in `RoomDownloadTaskStore`; reject unknown enum names instead of coercing them.

Required artifact columns are repository ID, immutable revision, relative path, remote object ID, expected bytes, logical role, destination-relative path, bundle ID, primary flag, state, user-independent received bytes, ETag, Last-Modified, retry count, platform task ID, lease owner, lease expiry, generated staging token, and bounded display metadata. Do not persist an absolute path or URL.

- [ ] **Step 5: Implement atomic DAO operations**

Use guarded updates such as:

```kotlin
@Query("""
    UPDATE download_artifact
    SET state = 'RUNNING', lease_owner = :owner, lease_expires_at_epoch_ms = :expiresAt
    WHERE artifact_id = :artifactId
      AND state IN ('QUEUED', 'FAILED_RETRYABLE', 'WAITING_FOR_NETWORK')
      AND (lease_owner IS NULL OR lease_expires_at_epoch_ms < :now)
""")
suspend fun claim(artifactId: String, owner: String, now: Long, expiresAt: Long): Int
```

DAO transaction methods must insert a batch and all artifacts together, derive batch state from artifact states, and reject progress updates whose byte count decreases or exceeds `expected_bytes`.

- [ ] **Step 6: Build the dedicated Room database without destructive fallback**

Define database version 1 with explicit schema. `getDownloadRoomDatabase()` must set `BundledSQLiteDriver()` and must not call `fallbackToDestructiveMigration`. Create platform builders matching existing database builder conventions.

- [ ] **Step 7: Run persistence and repository gates**

Run: `./gradlew :composeApp:jvmTest --tests '*DownloadDatabaseTest'`

Run: `./gradlew :composeApp:jvmTest --tests '*RecommendationDatabaseTest'`

Expected: PASS; queue deletion/reopen does not alter `caraml.db` or `recommendation_cache.db`.

- [ ] **Step 8: Commit the persistence milestone**

```bash
git add huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/StoragePathProvider.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadTaskStore.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/storage/DownloadBatchEntity.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/storage/DownloadArtifactEntity.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/storage/DownloadTaskDao.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/storage/DownloadDatabase.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/storage/RoomDownloadTaskStore.kt composeApp/src/androidMain/kotlin/com/debanshu777/caraml/core/download/storage/DownloadDatabase.android.kt composeApp/src/iosMain/kotlin/com/debanshu777/caraml/core/download/storage/DownloadDatabase.ios.kt composeApp/src/jvmMain/kotlin/com/debanshu777/caraml/core/download/storage/DownloadDatabase.jvm.kt composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/download/storage/DownloadDatabaseTest.kt composeApp/build.gradle.kts
git commit -m "feat(downloads): persist durable download queue"
```

### Task 3: Make secure staged transfers resumable

**Files:**
- Create: `huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadResumeMetadata.kt`
- Create: `huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/ContentRange.kt`
- Modify: `huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadProgressDTO.kt`
- Modify: `huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadManager.kt`
- Modify: `huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadEngine.kt`
- Modify: `huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/ArtifactManifestStore.kt`
- Modify: `huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/SecureArtifactRoot.kt`
- Modify: `huggingFaceManager/src/androidMain/kotlin/com/debanshu777/huggingfacemanager/download/SecureArtifactRoot.android.kt`
- Modify: `huggingFaceManager/src/iosMain/kotlin/com/debanshu777/huggingfacemanager/download/SecureArtifactRoot.ios.kt`
- Modify: `huggingFaceManager/src/jvmMain/kotlin/com/debanshu777/huggingfacemanager/download/SecureArtifactRoot.jvm.kt`
- Modify: `huggingFaceManager/src/androidMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadManager.android.kt`
- Modify: `huggingFaceManager/src/iosMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadManager.ios.kt`
- Modify: `huggingFaceManager/src/jvmMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadManager.jvm.kt`
- Modify: `nativeEngine/src/artifactFs/cpp/artifact_fs_jni.cpp`
- Modify: `nativeEngine/src/artifactFs/cpp/artifact_fs_jni_windows.cpp`
- Modify: `nativeEngine/src/artifactFs/cpp/CMakeLists.txt`
- Modify: `nativeEngine/build.gradle.kts`
- Test: `huggingFaceManager/src/commonTest/kotlin/com/debanshu777/huggingfacemanager/download/ContentRangeTest.kt`
- Test: `huggingFaceManager/src/jvmTest/kotlin/com/debanshu777/huggingfacemanager/download/DownloadManagerJvmTest.kt`
- Test: `nativeEngine/src/artifactFs/cpp/artifact_fs_append_test.cpp`

**Interfaces:**
- Consumes: existing validated metadata and secure manifest transaction.
- Produces: backward-compatible `DownloadManager.download(..., resumeMetadata)` and checkpoint-bearing `DownloadProgressDTO`.

- [ ] **Step 1: Add failing Range and append tests**

Test exact `Content-Range` parsing, malformed/overflow input rejection, valid `206` append, `200` safe restart, changed ETag restart, invalid range failure, cancellation preserving a synchronized partial, and final full-file digest verification.

The native test must open a root-pinned file in append mode, write a second segment, verify concatenated bytes, and reject append through a symlink or replaced root.

- [ ] **Step 2: Run the focused tests and verify they fail**

Run: `./gradlew :huggingFaceManager:jvmTest --tests '*ContentRangeTest' --tests '*DownloadManagerJvmTest'`

Run: `./gradlew :nativeEngine:testArtifactFsAppendDesktop`

Expected: FAIL because resume metadata, Range validation, and secure append mode are absent.

- [ ] **Step 3: Add the resume contract without breaking existing callers**

```kotlin
data class DownloadResumeMetadata(
    val bytesReceived: Long,
    val entityTag: String?,
    val lastModified: String?,
)

data class DownloadProgressDTO(
    val bytesReceived: Long,
    val contentLength: Long?,
    val percentage: Float,
    val localPath: String? = null,
    val contentSha256: String? = null,
    val entityTag: String? = null,
    val lastModified: String? = null,
)
```

Add `resumeMetadata: DownloadResumeMetadata? = null` to the expect/actual `download()` signature. Existing call sites remain source-compatible.

- [ ] **Step 4: Add secure append-only storage support**

Add `appendSink(relativePath: String, expectedOffset: Long): Sink` to `SecureArtifactRoot`. Each implementation must open an existing regular no-follow file, confirm its size equals `expectedOffset`, seek/append atomically, and revalidate the pinned root after close.

Add native open mode `3` (`kAppendExisting`) using `O_WRONLY | O_APPEND` on POSIX and `FILE_APPEND_DATA` plus `FILE_OPEN` on Windows. Do not reuse truncate mode. Preserve the current Android direct-root fix in the already modified JNI file.

- [ ] **Step 5: Implement safe HTTP continuation**

`downloadArtifact()` must:

1. compare persisted bytes with the actual secure `.part` size;
2. send `Range` and `If-Range` only when bytes are positive and an ETag or Last-Modified exists;
3. accept append only for `206` with exact start offset and expected total;
4. treat `200` as a full restart by deleting/truncating staging before consuming the body;
5. reject all other status/range combinations;
6. checkpoint ETag/Last-Modified in progress events;
7. synchronize staging before exposing checkpoint progress at cancellation boundaries;
8. re-hash the complete staged file before `ArtifactManifestStore.commit()`.

- [ ] **Step 6: Run resumability and existing integrity tests**

Run: `./gradlew :huggingFaceManager:jvmTest --tests '*DownloadManagerJvmTest' --tests '*ArtifactManifestStoreTest' --tests '*ContentRangeTest'`

Run: `./gradlew :nativeEngine:testArtifactFsAppendDesktop :nativeEngine:testArtifactFsAndroidRootDesktop`

Expected: PASS, including existing truncation, Unicode, identity, and concurrent-manifest cases.

- [ ] **Step 7: Commit the resumable transfer milestone**

```bash
git add huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadResumeMetadata.kt huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/ContentRange.kt huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadProgressDTO.kt huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadManager.kt huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadEngine.kt huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/ArtifactManifestStore.kt huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/SecureArtifactRoot.kt huggingFaceManager/src/androidMain/kotlin/com/debanshu777/huggingfacemanager/download/SecureArtifactRoot.android.kt huggingFaceManager/src/androidMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadManager.android.kt huggingFaceManager/src/iosMain/kotlin/com/debanshu777/huggingfacemanager/download/SecureArtifactRoot.ios.kt huggingFaceManager/src/iosMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadManager.ios.kt huggingFaceManager/src/jvmMain/kotlin/com/debanshu777/huggingfacemanager/download/SecureArtifactRoot.jvm.kt huggingFaceManager/src/jvmMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadManager.jvm.kt huggingFaceManager/src/commonTest/kotlin/com/debanshu777/huggingfacemanager/download/ContentRangeTest.kt huggingFaceManager/src/jvmTest/kotlin/com/debanshu777/huggingfacemanager/download/DownloadManagerJvmTest.kt nativeEngine/src/artifactFs/cpp/artifact_fs_jni.cpp nativeEngine/src/artifactFs/cpp/artifact_fs_jni_windows.cpp nativeEngine/src/artifactFs/cpp/CMakeLists.txt nativeEngine/src/artifactFs/cpp/artifact_fs_append_test.cpp nativeEngine/build.gradle.kts
git commit -m "feat(downloads): resume verified staged transfers"
```

### Task 4: Implement the persistent coordinator and command semantics

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/PlatformDownloadScheduler.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadCoordinator.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadNotificationPermissionController.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/download/DownloadCoordinatorTest.kt`

**Interfaces:**
- Consumes: `DownloadTaskStore` and Task 1 request/snapshot types.
- Produces: the only UI-facing enqueue/pause/resume/cancel/retry/observe API and the platform scheduler contract.

- [ ] **Step 1: Write failing coordinator tests with fake store and scheduler**

Prove persistence-before-schedule ordering, duplicate enqueue idempotency, scheduler failure mapping, pause/resume intent ordering, terminal retry rejection, cancel cleanup request, and non-blocking notification permission requests.

```kotlin
@Test
fun enqueuePersistsBeforeScheduling() = runTest {
    coordinator.enqueue(request)
    assertEquals(listOf("store:create", "permission:request", "scheduler:enqueue"), calls)
}

@Test
fun schedulerFailureLeavesRetryablePersistentRecord() = runTest {
    scheduler.enqueueFailure = IllegalStateException()
    val id = coordinator.enqueue(request)
    assertEquals(DownloadBatchState.FAILED_RETRYABLE, store.requireBatch(id).state)
    assertEquals(DownloadFailureCode.PLATFORM, store.requireBatch(id).failureCode)
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `./gradlew :composeApp:jvmTest --tests '*DownloadCoordinatorTest'`

Expected: FAIL because the coordinator does not exist.

- [ ] **Step 3: Define the platform boundary**

```kotlin
interface PlatformDownloadScheduler {
    suspend fun enqueue(batchId: String)
    suspend fun pause(batchId: String)
    suspend fun cancel(batchId: String)
    suspend fun reconcile(liveBatchIds: Set<String>)
}

interface DownloadNotificationPermissionController {
    fun requestIfNeeded()
}
```

- [ ] **Step 4: Implement coordinator commands**

```kotlin
class DownloadCoordinator(
    private val store: DownloadTaskStore,
    private val scheduler: PlatformDownloadScheduler,
    private val notifications: DownloadNotificationPermissionController,
) {
    fun observeForModel(modelId: String): Flow<List<DownloadBatchSnapshot>> = store.observeForModel(modelId)
    suspend fun enqueue(request: DownloadBatchRequest): String
    suspend fun pause(batchId: String)
    suspend fun resume(batchId: String)
    suspend fun cancel(batchId: String)
    suspend fun retry(batchId: String)
}
```

Each command changes `DownloadUserIntent` transactionally before platform calls. `cancel` is terminal for the record; a later user tap creates or reactivates only through an explicit fresh enqueue after exact metadata reconstruction.

- [ ] **Step 5: Run coordinator tests**

Run: `./gradlew :composeApp:jvmTest --tests '*DownloadCoordinatorTest'`

Expected: PASS.

- [ ] **Step 6: Commit the coordinator milestone**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/PlatformDownloadScheduler.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadCoordinator.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadNotificationPermissionController.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/download/DownloadCoordinatorTest.kt
git commit -m "feat(downloads): add persistent download coordinator"
```

### Task 5: Move artifact execution and model publication out of the ViewModel

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadBatchRunner.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/ModelDownloadFinalizer.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/download/DownloadBatchRunnerTest.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/download/ModelDownloadFinalizerTest.kt`

**Interfaces:**
- Consumes: `DownloadTaskStore`, `DownloadManager`, `LocalModelRepository`, `ComponentRepository`, and `StoragePathProvider`.
- Produces: `DownloadBatchRunner.run(batchId, progressSink)` and idempotent batch publication.

- [ ] **Step 1: Write failing runner tests**

Cover sequential artifact execution, persisted resume metadata forwarding, monotonic throttled progress, cancellation checkpointing, retryable versus terminal exception mapping, and lease release.

```kotlin
@Test
fun runnerUsesPersistedCheckpointAndMarksArtifactVerifyingBeforeCompletion() = runTest {
    store.seed(runningArtifact(bytesReceived = 4L, entityTag = "etag-1"))
    runner.run(BATCH_ID) { }
    assertEquals(DownloadResumeMetadata(4L, "etag-1", null), manager.lastResumeMetadata)
    assertEquals(listOf(RUNNING, VERIFYING, COMPLETED), store.artifactTransitions)
}
```

- [ ] **Step 2: Write failing finalizer tests**

Prove that a single GGUF inserts one `LocalModelEntity`, a diffusion batch inserts/links every external component, aggregate publication happens before database readiness, repeated finalization is idempotent, and failed bundle validation leaves the batch terminal without a runnable model record.

- [ ] **Step 3: Run focused tests and verify failure**

Run: `./gradlew :composeApp:jvmTest --tests '*DownloadBatchRunnerTest' --tests '*ModelDownloadFinalizerTest'`

Expected: FAIL because execution and publication still live in `ModelViewModel`.

- [ ] **Step 4: Implement the batch runner**

```kotlin
sealed interface DownloadRunResult {
    data object Completed : DownloadRunResult
    data object Paused : DownloadRunResult
    data object Cancelled : DownloadRunResult
    data class Retry(val code: DownloadFailureCode) : DownloadRunResult
    data class Failed(val code: DownloadFailureCode) : DownloadRunResult
}

class DownloadBatchRunner(
    private val store: DownloadTaskStore,
    private val downloadManager: DownloadManager,
    private val finalizer: ModelDownloadFinalizer,
    private val clock: () -> Long,
) {
    suspend fun run(batchId: String, progressSink: suspend (DownloadBatchSnapshot) -> Unit): DownloadRunResult
}
```

Use a random bounded lease owner per invocation. Persist progress at most every 500 ms or each additional MiB, plus every state boundary. Map `InsufficientStorageException`, `ArtifactVerificationException`, and `ArtifactFileAccessException` to terminal storage/integrity/secure-path failures; map timeouts, connectivity, HTTP 5xx, and scheduler stops to retryable failures; rethrow `CancellationException` after storing the current intent-derived state.

- [ ] **Step 5: Implement idempotent model finalization**

`ModelDownloadFinalizer.finalize(batchId)` must reconstruct every `DownloadMetadataDTO` from structured columns, call `publishBundle()` and `validateBundle()`, compute local paths from `StoragePathProvider`, insert component links, and finally insert the language or diffusion `LocalModelEntity`. It must never trust a stored absolute path.

Use one finalization lease. If a validated aggregate manifest and matching local-model record already exist, return success without duplicate rows. If only exact artifact manifests exist, rerun aggregate publication and database insertion.

- [ ] **Step 6: Run runner/finalizer and existing bundle tests**

Run: `./gradlew :composeApp:jvmTest --tests '*DownloadBatchRunnerTest' --tests '*ModelDownloadFinalizerTest' --tests '*LocalArtifactIdentityResolverTest'`

Run: `./gradlew :huggingFaceManager:jvmTest --tests '*ArtifactBundleManifestStoreTest'`

Expected: PASS.

- [ ] **Step 7: Commit the execution milestone**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadBatchRunner.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/ModelDownloadFinalizer.kt composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/download/DownloadBatchRunnerTest.kt composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/download/ModelDownloadFinalizerTest.kt
git commit -m "feat(downloads): finalize durable model batches"
```

### Task 6: Add startup reconciliation and the Desktop runtime

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadReconciler.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadRuntime.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/debanshu777/caraml/core/download/DesktopDownloadScheduler.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/debanshu777/caraml/core/download/DesktopDownloadNotificationPermissionController.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/debanshu777/caraml/main.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/di/AppModule.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/debanshu777/caraml/core/di/AppModule.jvm.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/download/DownloadReconcilerTest.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/download/DesktopDownloadSchedulerTest.kt`

**Interfaces:**
- Consumes: Tasks 2, 4, and 5.
- Produces: idempotent `DownloadRuntime.start()` and the first working durable platform.

- [ ] **Step 1: Write failing reconciliation tests**

Cover manifest-wins recovery, orphan `RUNNING` to `QUEUED`, user-paused preservation, user-cancelled cleanup, expired leases, completed scheduler work awaiting finalization, and a live scheduler task with no database record.

- [ ] **Step 2: Write failing Desktop scheduler tests**

Use a fake runner and test scope to prove unique batch jobs, a maximum of two concurrent batches, pause/cancel propagation, close-time checkpointing, and queued work restarting after runtime relaunch.

- [ ] **Step 3: Run focused tests and verify failure**

Run: `./gradlew :composeApp:jvmTest --tests '*DownloadReconcilerTest' --tests '*DesktopDownloadSchedulerTest'`

Expected: FAIL because runtime/reconciliation do not exist.

- [ ] **Step 4: Implement the reconciler**

```kotlin
class DownloadReconciler(
    private val store: DownloadTaskStore,
    private val scheduler: PlatformDownloadScheduler,
    private val downloadManager: DownloadManager,
    private val finalizer: ModelDownloadFinalizer,
) {
    suspend fun reconcile()
}
```

Reconcile manifests before scheduler state. Never delete a published exact artifact. Remove unmatched staging only through a validated `DownloadManager.discardStaged()` API; do not expose raw paths to the reconciler.

- [ ] **Step 5: Implement Desktop scheduling and runtime startup**

Use `CoroutineScope(SupervisorJob() + Dispatchers.IO)` and a two-permit semaphore. `enqueue()` launches only after reading current user intent from Room. `pause()` and `cancel()` cancel the in-memory job after the coordinator persists intent. `DownloadRuntime.start()` uses an atomic once guard, runs reconciliation, then schedules queued batches.

`main()` must retain the runtime and call `runtime.close()` from `onExit` before `exitApplication()` so active work is checkpointed.

- [ ] **Step 6: Register common and JVM dependencies**

Register `DownloadDatabase`, DAO, `RoomDownloadTaskStore`, finalizer, runner, reconciler, coordinator, runtime, `DesktopDownloadScheduler`, and the no-op Desktop notification permission controller in Koin. Avoid eager network work during module construction; only `DownloadRuntime.start()` may reconcile/schedule.

- [ ] **Step 7: Run Desktop durability tests and JVM suite**

Run: `./gradlew :composeApp:jvmTest --tests '*DownloadReconcilerTest' --tests '*DesktopDownloadSchedulerTest'`

Run: `./gradlew :composeApp:jvmTest`

Expected: PASS.

- [ ] **Step 8: Commit the Desktop milestone**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadReconciler.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadRuntime.kt composeApp/src/jvmMain/kotlin/com/debanshu777/caraml/core/download/DesktopDownloadScheduler.kt composeApp/src/jvmMain/kotlin/com/debanshu777/caraml/core/download/DesktopDownloadNotificationPermissionController.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/di/AppModule.kt composeApp/src/jvmMain/kotlin/com/debanshu777/caraml/core/di/AppModule.jvm.kt composeApp/src/jvmMain/kotlin/com/debanshu777/caraml/main.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/download/DownloadReconcilerTest.kt composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/download/DesktopDownloadSchedulerTest.kt
git commit -m "feat(downloads): resume durable downloads on desktop"
```

### Task 7: Migrate Model Details and diffusion installs to persistent state

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/ModelViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/DetailsScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/components/ModelDetailContent.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/components/GgufFileListItem.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/components/InstallBundleCard.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/di/AppModule.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/ModelViewModelRecommendationTest.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/ModelDetailsAuroraUiTest.kt`
- Create: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/DownloadControlsUiTest.kt`

**Interfaces:**
- Consumes: `DownloadCoordinator.observeForModel()` and command methods.
- Produces: durable row/bundle UI state plus pause/resume/cancel/retry actions.

- [ ] **Step 1: Write failing ViewModel restoration tests**

Test that an existing running record appears after a fresh ViewModel is created, leaving Details does not invoke scheduler cancellation, exact `Needs information` artifacts enqueue, malformed identity remains blocked, and a diffusion request persists every primary/component in one batch.

- [ ] **Step 2: Write failing UI semantics tests**

Assert these state descriptions and actions:

```text
Queued
Downloading 42 percent
Paused
Waiting for network
Verifying download
Download failed; retry available
Downloaded
```

Assert Pause, Resume, Cancel, and Retry content descriptions appear only in legal states. Preserve the existing 48 dp action target and advisory `Needs information` chip.

- [ ] **Step 3: Run focused tests and verify failure**

Run: `./gradlew :composeApp:jvmTest --tests '*ModelViewModelRecommendationTest' --tests '*ModelDetailsAuroraUiTest' --tests '*DownloadControlsUiTest'`

Expected: FAIL because UI state is still local to `ModelViewModel`.

- [ ] **Step 4: Replace transient download fields with coordinator observation**

Inject `DownloadCoordinator` into `ModelViewModel`. Replace `_isDownloading`, `_activeDownloadArtifact`, `_installProgress`, and direct `trackedDownload()` collection with a `StateFlow<List<DownloadBatchSnapshot>>` scoped to the loaded model ID. Keep admission/metadata construction in the ViewModel, but call `coordinator.enqueue()` immediately after admission.

Build a single-artifact `DownloadBatchRequest` for language models. Build one diffusion request containing the selected primary artifacts plus all required component metadata; preserve deterministic roles, destinations, and bundle ID from `buildDeterministicDiffusionBundleMetadata()`.

- [ ] **Step 5: Introduce presentation-only download state**

```kotlin
data class DownloadControlUiState(
    val status: DownloadArtifactState?,
    val bytesReceived: Long,
    val bytesTotal: Long,
    val progress: Float?,
    val canPause: Boolean,
    val canResume: Boolean,
    val canCancel: Boolean,
    val canRetry: Boolean,
)
```

Map persistent state to controls without inspecting coroutine jobs. `ModelDetailContent` passes row-specific state and callbacks; it must not globally lock unrelated rows merely because another model is downloading.

- [ ] **Step 6: Render durable controls**

Use Download for absent tasks, Pause for `RUNNING`, Resume for `PAUSED`, Cancel for queued/running/paused/waiting states, Retry for `FAILED_RETRYABLE`, a spinner or determinate bar for `VERIFYING`, and Check only after a validated manifest and completed record. The diffusion footer uses the batch aggregate state and bytes.

- [ ] **Step 7: Run ViewModel/UI tests and full Compose JVM tests**

Run: `./gradlew :composeApp:jvmTest --tests '*ModelViewModelRecommendationTest' --tests '*ModelDetailsAuroraUiTest' --tests '*DownloadControlsUiTest'`

Run: `./gradlew :composeApp:jvmTest`

Expected: PASS; no test relies on ViewModel-owned transfer lifetime.

- [ ] **Step 8: Commit the UI migration milestone**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/ModelViewModel.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/DetailsScreen.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/components/ModelDetailContent.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/components/GgufFileListItem.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/components/InstallBundleCard.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/di/AppModule.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/DownloadControlsUiTest.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/ModelDetailsAuroraUiTest.kt composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/ModelViewModelRecommendationTest.kt
git commit -m "feat(modelhub): render persistent download controls"
```

### Task 8: Implement Android background execution and progress notifications

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `composeApp/build.gradle.kts`
- Create: `composeApp/src/androidMain/AndroidManifest.xml`
- Create: `composeApp/src/androidMain/kotlin/com/debanshu777/caraml/core/download/AndroidDownloadScheduler.kt`
- Create: `composeApp/src/androidMain/kotlin/com/debanshu777/caraml/core/download/AndroidDownloadWorker.kt`
- Create: `composeApp/src/androidMain/kotlin/com/debanshu777/caraml/core/download/AndroidUidtDownloadService.kt`
- Create: `composeApp/src/androidMain/kotlin/com/debanshu777/caraml/core/download/AndroidDownloadNotificationFactory.kt`
- Create: `composeApp/src/androidMain/kotlin/com/debanshu777/caraml/core/download/AndroidDownloadActionReceiver.kt`
- Create: `composeApp/src/androidMain/kotlin/com/debanshu777/caraml/core/download/AndroidDownloadNotificationPermissionController.kt`
- Create: `composeApp/src/androidHostTest/kotlin/com/debanshu777/caraml/core/download/AndroidSchedulingPolicyTest.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/debanshu777/caraml/core/di/AppModule.android.kt`
- Modify: `androidApp/src/main/AndroidManifest.xml`
- Modify: `androidApp/src/main/kotlin/com/debanshu777/caraml/CaraMLApplication.kt`
- Modify: `androidApp/src/main/kotlin/com/debanshu777/caraml/MainActivity.kt`
- Create: `androidApp/src/main/res/drawable/ic_download_notification.xml`
- Modify: `androidApp/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `DownloadBatchRunner`, `DownloadCoordinator`, `DownloadRuntime`, and persistent batch IDs.
- Produces: API-gated UIDT/WorkManager scheduling plus ongoing notifications with progress and safe actions.

- [ ] **Step 1: Pin stable WorkManager dependencies**

Add `work = "2.11.2"`, `androidx-work-runtime-ktx`, and `androidx-work-testing` aliases. Add runtime and `androidx-core-ktx` to `androidMain`; add testing dependency to `androidHostTest`. WorkManager 2.11.2 supports this project's minSdk 28.

- [ ] **Step 2: Write failing scheduling-policy tests**

Extract a pure policy and assert API 34+ selects UIDT, API 28-33 selects WorkManager, job IDs are deterministic with bounded collision probing, unique work names contain only the validated 64-hex batch ID, and unknown notification action IDs are rejected.

- [ ] **Step 3: Run the Android host test and verify failure**

Run: `./gradlew :composeApp:testAndroidHostTest --tests '*AndroidSchedulingPolicyTest'`

Expected: FAIL because the Android scheduler does not exist. The repository exposes the host-test task as `testAndroidHostTest`.

- [ ] **Step 4: Implement API 34+ UIDT scheduling**

Create persisted `JobInfo` with `setUserInitiated(true)`, `setRequiredNetworkType(NETWORK_TYPE_ANY)`, `setRequiresStorageNotLow(true)`, `setEstimatedNetworkBytes(0L, remainingBytes)`, `setPersisted(true)`, and a `PersistableBundle` containing only the validated batch ID. `AndroidUidtDownloadService` runs the batch in a supervisor scope, calls `setNotification()` before transfer, updates notification progress at the same throttled cadence, and always calls `jobFinished()`.

`onStopJob()` must cancel the runner scope, persist a retryable checkpoint unless user intent is pause/cancel, and return whether the system should reschedule.

- [ ] **Step 5: Implement API 28-33 WorkManager fallback**

Enqueue unique work named `caraml-download-<batchId>` with `ExistingWorkPolicy.KEEP`, connected-network and storage-not-low constraints, and exponential backoff starting at 10 seconds. `AndroidDownloadWorker` calls `setForeground()` before running and maps `DownloadRunResult` to `Result.success()`, `Result.retry()`, or `Result.failure()`.

- [ ] **Step 6: Implement the ongoing notification and actions**

Create channel `model_downloads`. Notification content shows model name, percentage or indeterminate progress, received/total bytes, and one of Pause/Resume plus Cancel. Use explicit immutable `PendingIntent`s and validate batch IDs before dispatch.

Pause/Cancel use `AndroidDownloadActionReceiver`. Resume opens `MainActivity` with action `com.debanshu777.caraml.RESUME_DOWNLOAD`; `MainActivity` handles it after becoming visible, then calls `coordinator.resume(batchId)`, satisfying UIDT visibility rules.

- [ ] **Step 7: Declare required permissions and components**

Declare `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `RUN_USER_INITIATED_JOBS`, `FOREGROUND_SERVICE`, and `FOREGROUND_SERVICE_DATA_SYNC`. Declare the UIDT service with `BIND_JOB_SERVICE` and `exported=false`, the action receiver with `exported=false`, and merge WorkManager's `SystemForegroundService` with `foregroundServiceType="dataSync"`.

- [ ] **Step 8: Attach notification permission and runtime startup**

`MainActivity` registers an Activity Result permission launcher and attaches it to `AndroidDownloadNotificationPermissionController`; denial never blocks enqueue. `CaraMLApplication` starts `DownloadRuntime` after Koin initialization. Notifications remain visible in Android Task Manager even when drawer permission is denied; in-app state remains authoritative.

- [ ] **Step 9: Run Android tests and build**

Run: `./gradlew :composeApp:testAndroidHostTest --tests '*AndroidSchedulingPolicyTest'`

Run: `./gradlew :androidApp:assembleDebug`

Expected: PASS.

- [ ] **Step 10: Run device lifecycle verification**

On API 34+ and one API 28-33 device/emulator:

1. start a file larger than 1 GB;
2. background the app and verify notification progress changes;
3. pause from notification and verify bytes stop increasing;
4. resume and verify the next request uses Range when supported;
5. kill the app process and verify persisted reconciliation;
6. run `adb shell cmd jobscheduler timeout com.debanshu777.caraml <jobId>` on API 34+ and verify retry;
7. reboot and verify persisted queued/running work reconciles;
8. deny notification permission and verify Task Manager plus in-app status without a crash.

Record device/API, artifact ID, and observed terminal state; do not log the signed URL.

- [ ] **Step 11: Commit the Android milestone**

```bash
git add gradle/libs.versions.toml composeApp/build.gradle.kts composeApp/src/androidMain/AndroidManifest.xml composeApp/src/androidMain/kotlin/com/debanshu777/caraml/core/download/AndroidDownloadScheduler.kt composeApp/src/androidMain/kotlin/com/debanshu777/caraml/core/download/AndroidDownloadWorker.kt composeApp/src/androidMain/kotlin/com/debanshu777/caraml/core/download/AndroidUidtDownloadService.kt composeApp/src/androidMain/kotlin/com/debanshu777/caraml/core/download/AndroidDownloadNotificationFactory.kt composeApp/src/androidMain/kotlin/com/debanshu777/caraml/core/download/AndroidDownloadActionReceiver.kt composeApp/src/androidMain/kotlin/com/debanshu777/caraml/core/download/AndroidDownloadNotificationPermissionController.kt composeApp/src/androidHostTest/kotlin/com/debanshu777/caraml/core/download/AndroidSchedulingPolicyTest.kt composeApp/src/androidMain/kotlin/com/debanshu777/caraml/core/di/AppModule.android.kt androidApp/src/main/AndroidManifest.xml androidApp/src/main/kotlin/com/debanshu777/caraml/CaraMLApplication.kt androidApp/src/main/kotlin/com/debanshu777/caraml/MainActivity.kt androidApp/src/main/res/drawable/ic_download_notification.xml androidApp/src/main/res/values/strings.xml
git commit -m "feat(android): run model downloads in background"
```

### Task 9: Implement iOS background downloads, resume data, and completion notifications

**Files:**
- Create: `huggingFaceManager/src/iosMain/kotlin/com/debanshu777/huggingfacemanager/download/IosCompletedDownloadImporter.kt`
- Create: `huggingFaceManager/src/iosTest/kotlin/com/debanshu777/huggingfacemanager/download/IosCompletedDownloadImporterTest.kt`
- Create: `composeApp/src/iosMain/kotlin/com/debanshu777/caraml/core/download/IosBackgroundDownloadScheduler.kt`
- Create: `composeApp/src/iosMain/kotlin/com/debanshu777/caraml/core/download/IosBackgroundSessionDelegate.kt`
- Create: `composeApp/src/iosMain/kotlin/com/debanshu777/caraml/core/download/IosResumeDataStore.kt`
- Create: `composeApp/src/iosMain/kotlin/com/debanshu777/caraml/core/download/IosDownloadNotificationPresenter.kt`
- Create: `composeApp/src/iosMain/kotlin/com/debanshu777/caraml/core/download/IosDownloadNotificationPermissionController.kt`
- Create: `composeApp/src/iosMain/kotlin/com/debanshu777/caraml/core/download/IosBackgroundDownloadBridge.kt`
- Modify: `composeApp/src/iosMain/kotlin/com/debanshu777/caraml/core/di/AppModule.ios.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/di/KoinInit.kt`
- Modify: `composeApp/src/iosMain/kotlin/com/debanshu777/caraml/MainViewController.kt`
- Modify: `iosApp/iosApp/iOSApp.swift`

**Interfaces:**
- Consumes: persisted `DownloadTaskRecord`, deterministic artifact IDs, the shared secure staging contract, and `DownloadCoordinator` commands.
- Produces: one stable background `NSURLSession`, task-to-artifact restoration, bounded resume-data storage, secure completed-file import, and local completion/failure notifications.

- [ ] **Step 1: Write failing importer and restoration tests**

Cover these cases in `IosCompletedDownloadImporterTest`: a completed regular file with matching size/hash is imported and committed; a directory, symbolic link, short file, oversized file, or hash mismatch is rejected; importing the same completed file twice is idempotent; a missing persistent record is quarantined rather than attached to a different artifact.

Add common scheduler-restoration tests proving that task descriptions accept only 64-character lowercase hex artifact IDs, unknown native tasks are cancelled, duplicate native tasks retain one deterministic owner, and stored resume data is associated with the same artifact ID only.

- [ ] **Step 2: Run the focused iOS tests and verify failure**

Run: `./gradlew :huggingFaceManager:iosSimulatorArm64Test --tests '*IosCompletedDownloadImporterTest'`

Expected: FAIL because the iOS completed-file importer and restoration policy do not exist.

- [ ] **Step 3: Implement secure completed-file import**

`IosCompletedDownloadImporter` receives the system temporary-file URL plus the persisted artifact record. It must reject non-file URLs, symbolic links, non-regular files, unexpected byte counts, and files outside the callback-provided system download location. Copy through the secure storage root into the artifact's existing `.part` staging path, compute SHA-256 while copying, verify the expected digest when supplied, fsync/close, and publish via the existing manifest store. Never trust a filename, model ID, or destination path from `taskDescription`.

If the manifest is already valid, return success without replacing it. Delete the imported staging file after terminal integrity failure; retain it only for a retryable I/O interruption.

- [ ] **Step 4: Create one stable background URL session**

Use the identifier `com.debanshu777.caraml.model-downloads`. Configure `NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier`, set `sessionSendsLaunchEvents = true`, `discretionary = false`, `allowsCellularAccess = true`, and `HTTPMaximumConnectionsPerHost = 1`. Keep one strongly referenced delegate and session for the process lifetime.

Create tasks only from URLs produced by the existing validated Hugging Face artifact identity. Require HTTPS and validate the final response host against the configured Hugging Face download-host allowlist. Resolve and reject loopback, link-local, private, multicast, and metadata-service address ranges before importing any bytes. Persist the native task identifier plus the artifact ID before calling `resume()`.

- [ ] **Step 5: Persist task identity and bounded resume data**

Set `taskDescription` to the validated artifact ID only. On pause, call `cancelByProducingResumeData`, persist at most 1 MiB of opaque resume data in app-private storage keyed by artifact ID, then mark the record `PAUSED`. If data exceeds the bound or the OS rejects it, discard it and restart that iOS artifact from zero on the next explicit Resume; never splice an unvalidated native temporary file into existing staging.

On resume, use `downloadTaskWithResumeData` when valid resume data exists; otherwise delete uncommitted staging for that artifact and create a fresh background task from the validated identity. On startup, call `getAllTasksWithCompletionHandler`, reconcile every task with the persistent database, cancel unknown or duplicate native tasks, and adopt the surviving task before scheduling anything new.

- [ ] **Step 6: Bridge background relaunch completion correctly**

Make the common `core/di/KoinInit.kt` entry point process-wide and idempotent so a background session event can initialize dependencies before `MainViewController` is created. Preserve the caller's Koin configuration on the first initialization and return the existing Koin instance on later calls. In `iOSApp.swift`, add an `UIApplicationDelegateAdaptor` whose `application(_:handleEventsForBackgroundURLSession:completionHandler:)` forwards the identifier and completion handler to `IosBackgroundDownloadBridge`.

The bridge stores exactly one completion handler per recognized session identifier. `IosBackgroundSessionDelegate` imports every completed file and persists terminal state first; it invokes the stored completion handler only from `URLSessionDidFinishEventsForBackgroundURLSession`. Unknown session identifiers invoke their completion handlers immediately and are not adopted.

- [ ] **Step 7: Map native callbacks to persistent progress and retry state**

Map `didWriteData` to throttled received/total byte updates. Map HTTP status and `NSError` categories to the shared failure codes: cancellation caused by Pause is not failure; offline/timeouts are retryable; authentication, forbidden, missing artifact, and integrity mismatch are terminal until the user explicitly retries after metadata refresh.

Do not persist signed URLs, response headers, resume-data contents, or raw error bodies in logs or Room. Store only normalized failure codes and bounded user-facing messages.

- [ ] **Step 8: Add iOS local completion notifications**

Request notification authorization only from a visible user action and never block download enqueue if denied. Post a local notification for completed and failed batches when the app is not active; in-app state remains the source of truth while foregrounded. iOS live progress remains in the app because background `NSURLSession` does not provide an Android-style continuously updated system download notification.

- [ ] **Step 9: Run iOS tests and framework build**

Run: `./gradlew :huggingFaceManager:iosSimulatorArm64Test --tests '*IosCompletedDownloadImporterTest'`

Run: `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64`

Run: `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -configuration Debug CODE_SIGNING_ALLOWED=NO build`

Expected: PASS.

- [ ] **Step 10: Run iOS lifecycle verification**

On a physical iPhone:

1. start a file larger than 1 GB and background the app;
2. lock the device and verify the transfer continues when iOS grants networking time;
3. let iOS terminate the suspended process, then verify system relaunch restores the session and delivers completion;
4. pause, relaunch, resume, and confirm bytes do not restart from zero when resume data remains valid;
5. invalidate resume data and confirm safe fallback to a fresh task;
6. complete while suspended and verify one completion notification plus a committed manifest;
7. deny notification permission and verify completion is still visible in-app;
8. interrupt connectivity and confirm persistent retry state without duplicate native tasks;
9. force-quit the app as the user and verify no claim is made that iOS continues the transfer; on manual relaunch, reconcile the OS-cancelled task safely.

Record iOS version, device, artifact ID, and terminal state. Do not record request URLs or response headers.

- [ ] **Step 11: Commit the iOS milestone**

```bash
git add huggingFaceManager/src/iosMain/kotlin/com/debanshu777/huggingfacemanager/download/IosCompletedDownloadImporter.kt huggingFaceManager/src/iosTest/kotlin/com/debanshu777/huggingfacemanager/download/IosCompletedDownloadImporterTest.kt composeApp/src/iosMain/kotlin/com/debanshu777/caraml/core/download/IosBackgroundDownloadScheduler.kt composeApp/src/iosMain/kotlin/com/debanshu777/caraml/core/download/IosBackgroundSessionDelegate.kt composeApp/src/iosMain/kotlin/com/debanshu777/caraml/core/download/IosResumeDataStore.kt composeApp/src/iosMain/kotlin/com/debanshu777/caraml/core/download/IosDownloadNotificationPresenter.kt composeApp/src/iosMain/kotlin/com/debanshu777/caraml/core/download/IosDownloadNotificationPermissionController.kt composeApp/src/iosMain/kotlin/com/debanshu777/caraml/core/download/IosBackgroundDownloadBridge.kt composeApp/src/iosMain/kotlin/com/debanshu777/caraml/core/di/AppModule.ios.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/di/KoinInit.kt composeApp/src/iosMain/kotlin/com/debanshu777/caraml/MainViewController.kt iosApp/iosApp/iOSApp.swift
git commit -m "feat(ios): restore model downloads in background"
```

### Task 10: Prove recovery, concurrency, and security boundaries

**Files:**
- Create: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/download/DownloadRecoveryIntegrationTest.kt`
- Create: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/download/DownloadSecurityTest.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadReconciler.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadCoordinator.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadBatchRunner.kt`
- Modify: `huggingFaceManager/src/jvmTest/kotlin/com/debanshu777/huggingfacemanager/download/DownloadManagerJvmTest.kt`

**Interfaces:**
- Consumes: all shared state-machine, staging, manifest, scheduler, and finalizer contracts.
- Produces: executable evidence that retries are idempotent, one owner writes each artifact, restart recovery is deterministic, and sensitive transport data is not persisted.

- [ ] **Step 1: Add failure-injection integration tests**

Build fakes that can stop after an exact byte boundary, fail during checkpoint persistence, return `200` to a range request, fail during hash verification, fail between manifest publication and Room insertion, and simulate process restart with the same database and storage roots.

Assert each retry either resumes from the validated offset or safely restarts, never appends a full response to a partial file, never publishes an invalid manifest, and converges to one completed model record.

- [ ] **Step 2: Add concurrency and lease tests**

Launch two runners for one artifact ID and assert only the lease owner opens the staging sink. Expire the lease and prove another runner can adopt it. Assert two different artifact IDs may run concurrently up to the platform limit, while a third remains queued. Verify Cancel followed by a stale native callback cannot resurrect the record.

- [ ] **Step 3: Add retry-budget tests**

Use exponential backoff from 10 seconds capped at 15 minutes with jitter and a maximum of five automatic attempts. Offline, timeout, and transient 5xx failures consume the retry budget. Invalid identity, non-HTTPS redirect, 401/403/404, storage permission, insufficient disk, and integrity mismatch become terminal records that require explicit user Retry after the underlying condition changes.

- [ ] **Step 4: Add persistence and log-redaction tests**

Inspect all persisted task fields and captured structured logs. Assert they contain artifact IDs, normalized host category, byte counts, timestamps, and failure codes only. Assert they never contain bearer tokens, cookies, authorization headers, signed query parameters, complete request URLs, raw response bodies, or iOS resume-data bytes.

- [ ] **Step 5: Run the recovery suite repeatedly**

Run: `./gradlew :composeApp:jvmTest --tests '*DownloadRecoveryIntegrationTest' --tests '*DownloadSecurityTest'`

Run: `./gradlew :huggingFaceManager:jvmTest --tests '*DownloadManagerJvmTest'`

Run the two commands three consecutive times.

Expected: all six invocations PASS with no timing-dependent failures.

- [ ] **Step 6: Commit the recovery proof**

```bash
git add composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/download/DownloadRecoveryIntegrationTest.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/download/DownloadSecurityTest.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadReconciler.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadCoordinator.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadBatchRunner.kt huggingFaceManager/src/jvmTest/kotlin/com/debanshu777/huggingfacemanager/download/DownloadManagerJvmTest.kt
git commit -m "test(downloads): prove restart and retry safety"
```

### Task 11: Remove legacy lifecycle ownership, document behavior, and run the release gate

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/ModelViewModel.kt`
- Modify: `huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadManager.kt`
- Modify: `README.md`
- Modify: `composeApp/README.md`
- Modify: `huggingFaceManager/README.md`
- Modify: `docs/superpowers/specs/2026-09-17-durable-model-downloads-design.md` only if implementation evidence requires a factual correction

**Interfaces:**
- Consumes: the completed persistent coordinator and all platform schedulers.
- Produces: no ViewModel-owned transfer lifetime, accurate module documentation, and a recorded cross-platform acceptance result.

- [ ] **Step 1: Remove obsolete presentation-owned paths**

Delete the old `viewModelScope` transfer collection, transient global `isDownloading` state, active-artifact bookkeeping, and any callback path that treats leaving Model Details as cancellation. Keep `DownloadManager` only as the low-level validated transfer primitive used by runners; remove any public convenience API that bypasses persistent task creation.

Search: `rg -n "trackedDownload|activeDownloadArtifact|isDownloading|viewModelScope.*download|DownloadManager.*download" composeApp huggingFaceManager`

Expected: remaining matches are either UI projections of persistent records, low-level runner calls, or tests that assert the legacy path is absent.

- [ ] **Step 2: Update the relevant README Recent Changes sections**

Update only root `README.md`, `composeApp/README.md`, and `huggingFaceManager/README.md`. State precisely that Android uses UIDT on API 34+ with WorkManager fallback, iOS uses a background `NSURLSession`, Desktop uses a process-resilient in-app queue, manifests remain authoritative, and `Needs information` is advisory rather than a download block.

Do not claim that Desktop survives application exit or that iOS guarantees uninterrupted execution; state that each platform resumes/reconciles according to its operating-system lifecycle.

- [ ] **Step 3: Run static hygiene and focused suites**

Run: `git diff --check`

Run: `./gradlew :huggingFaceManager:jvmTest`

Run: `./gradlew :composeApp:jvmTest`

Expected: PASS.

- [ ] **Step 4: Run repository and Android gates**

Run: `./gradlew verifyProject`

Run: `./gradlew :androidApp:assembleDebug`

Expected: PASS. If a failure is unrelated to changed download code, record the exact task and failure separately; do not relabel it as download validation.

- [ ] **Step 5: Run Apple compilation gates on macOS**

Run: `./gradlew :huggingFaceManager:iosSimulatorArm64Test`

Run: `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64`

Run: `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -configuration Debug CODE_SIGNING_ALLOWED=NO build`

Expected: PASS.

- [ ] **Step 6: Run the final acceptance matrix**

For one small artifact and one artifact larger than 1 GB, capture these outcomes per supported platform:

| Scenario | Android API 34+ | Android API 28-33 | iOS device | Desktop JVM |
|---|---|---|---|---|
| Background/suspend | UIDT continues with notification/Task Manager visibility | WorkManager foreground execution continues | background session owns transfer; completion notification if allowed | transfer continues while process remains alive |
| Pause/resume | action persists pause and resumes via visible activity | notification action resumes unique work | resume data or safe fresh task | queue resumes from validated `.part` offset |
| Process death/relaunch | scheduler plus reconciliation restores one owner | WorkManager plus reconciliation restores one owner | native task adoption restores one owner | stale running lease becomes queued on next launch |
| Network loss | bounded automatic retry | bounded automatic retry | persistent retry state | persistent retry state |
| Integrity mismatch | no manifest; terminal failure | no manifest; terminal failure | no manifest; terminal failure | no manifest; terminal failure |
| `Needs information` | download remains enabled | download remains enabled | download remains enabled | download remains enabled |

For every row, record platform version, artifact ID, final persistent state, manifest presence, and whether local-model publication occurred. Never include signed URLs or headers in the record.

- [ ] **Step 7: Review the final diff for scope and security**

Run: `git status --short`

Run: `git diff --stat`

Run: `git diff --check`

Confirm unrelated pre-existing changes are still present and unmodified, no credentials or signed URLs entered the diff, every manifest write follows verified staging, notification components are non-exported, and README claims match the observed acceptance matrix.

- [ ] **Step 8: Commit the final cleanup and documentation**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/ModelViewModel.kt huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadManager.kt README.md composeApp/README.md huggingFaceManager/README.md
git commit -m "docs(downloads): document durable platform behavior"
```
