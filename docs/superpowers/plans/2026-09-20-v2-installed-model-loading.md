# V2 Installed Model Loading Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every installed CaraML model load through a durable, verified, freshly assessed V2 `LoadRequest`, including existing downloads after a one-time exact-metadata repair.

**Architecture:** Persist a bounded versioned evidence envelope with each durable download and publish it transactionally beside the Ready catalog row. At selection time, an installed-model resolver repairs missing evidence, revalidates local artifacts, captures current device/settings state, reassesses the model, and creates the only supported exact inference request.

**Tech Stack:** Kotlin 2.4.0, Kotlin Multiplatform, kotlinx.serialization, Okio SHA-256, Room 2.8.4 with bundled SQLite, coroutines/Flow, Koin, Compose ViewModel, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-09-20-v2-installed-model-loading-design.md`

## Global Constraints

- Do not retain or call `loadModel(LocalModelEntity)` in production inference code.
- Do not persist a selected run plan, `ModelAssessment`, or `PersonalizedRecommendation` as authoritative state.
- `Needs information` remains informational and must not disable an exact artifact download.
- Persisted evidence is untrusted: reject unknown JSON fields, unsupported schema versions, digest mismatch, control characters, invalid paths/revisions, oversized collections, and payloads larger than 262,144 bytes.
- Persisted evidence must never supply an absolute path or arbitrary URL; all load paths come from `StoragePathProvider` plus the verified artifact manifest.
- Every load revalidates the local artifact and recomputes assessment from the current device snapshot, settings, calibration, and recommendation profile.
- Existing model files must not be redownloaded during evidence repair.
- Use explicit Room migrations; preserve AppDatabase v3 rows and DownloadDatabase v1 rows.
- Propagate `CancellationException`; do not convert cancellation into a user error.
- Add no new dependency unless the existing Kotlin/Room/Okio stack cannot satisfy a requirement.
- Follow red-green-refactor for every behavior change and run `./gradlew verifyProject --no-daemon` before completion.

---

## File Structure

- `core/recommendation/storage/PersistedModelEvidence.kt`: versioned evidence model, strict codec, canonical digest, and descriptor conversion.
- `core/download/DownloadModels.kt`: evidence-bearing durable batch contract and evidence-sensitive batch identity.
- `core/download/storage/*`: DownloadDatabase v2 persistence and migration.
- `core/storage/evidence/*`: installed evidence entity, DAO, repository, and AppDatabase v4 migration.
- `core/storage/catalog/InstalledModelCatalogDao.kt`: one Room transaction for model, components, links, and evidence.
- `core/download/ModelDownloadFinalizer.kt`: validates batch evidence before transactional Ready publication.
- `features/modelhub/presentation/search/ModelViewModel.kt`: creates complete or `REQUIRES_ENRICHMENT` evidence without blocking downloads.
- `core/recommendation/InstalledModelEvidenceRepairer.kt`: exact, idempotent one-time evidence enrichment.
- `core/recommendation/InstalledModelLoadRequestResolver.kt`: current-state assessment and exact `LoadRequest` reconstruction.
- `features/chat/presentation/ChatViewModel.kt`: consumes only installed resolver results and exact loaders.
- `core/data/inference/*`: exact-request-only inference contract.

---

### Task 1: Strict persisted model evidence codec

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/storage/PersistedModelEvidence.kt`
- Create: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/storage/PersistedModelEvidenceTest.kt`
- Read: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/ModelDescriptor.kt`

**Interfaces:**
- Consumes: `ModelDescriptor`, `ModelFileIdentity`, `DiffusionComponentDescriptor`, `Evidence`, and existing `DescriptorLimits`.
- Produces:

```kotlin
enum class InstalledEvidenceState { COMPLETE, REQUIRES_ENRICHMENT }

data class EncodedModelEvidence(
    val state: InstalledEvidenceState,
    val schemaVersion: Int,
    val payload: String,
    val sha256: String,
)

data class DecodedModelEvidence(
    val state: InstalledEvidenceState,
    val artifactIdentities: List<ModelFileIdentity>,
    val descriptor: ModelDescriptor?,
)

class PersistedModelEvidenceCodec {
    fun encode(
        artifactIdentities: Collection<ModelFileIdentity>,
        descriptor: ModelDescriptor?,
    ): EncodedModelEvidence

    fun decode(encoded: EncodedModelEvidence): DecodedModelEvidence
}
```

- [ ] **Step 1: Write failing round-trip and strict-decoding tests**

```kotlin
@Test
fun completeLlmEvidenceRoundTrips() {
    val descriptor = llmDescriptor(path = "weights/model-q4_k_m.gguf")
    val encoded = codec.encode(descriptor.files, descriptor)

    val decoded = codec.decode(encoded)

    assertEquals(InstalledEvidenceState.COMPLETE, decoded.state)
    assertEquals(descriptor, decoded.descriptor)
    assertEquals(descriptor.files, decoded.artifactIdentities)
}

@Test
fun pendingEvidenceRoundTripsWithoutClaimingRunnability() {
    val identity = modelIdentity(path = "weights/model.gguf")
    val decoded = codec.decode(codec.encode(listOf(identity), descriptor = null))

    assertEquals(InstalledEvidenceState.REQUIRES_ENRICHMENT, decoded.state)
    assertNull(decoded.descriptor)
    assertEquals(listOf(identity), decoded.artifactIdentities)
}

@Test
fun digestMismatchIsRejected() {
    val valid = codec.encode(listOf(modelIdentity()), descriptor = null)
    assertFailsWith<IllegalArgumentException> {
        codec.decode(valid.copy(sha256 = "0".repeat(64)))
    }
}

@Test
fun unknownFieldAndOversizedPayloadAreRejected() {
    val valid = codec.encode(listOf(modelIdentity()), descriptor = null)
    assertFailsWith<IllegalArgumentException> {
        codec.decode(valid.copy(payload = valid.payload.dropLast(1) + ",\"unknown\":true}"))
    }
    assertFailsWith<IllegalArgumentException> {
        codec.decode(valid.copy(payload = "x".repeat(262_145)))
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `./gradlew :composeApp:jvmTest --tests '*PersistedModelEvidenceTest' --no-daemon`

Expected: compilation fails because `PersistedModelEvidenceCodec` and its transport types do not exist.

- [ ] **Step 3: Implement the bounded canonical codec**

Use private `@Serializable` DTOs with a discriminator for LLM/diffusion descriptors and quantization variants. Configure one codec-local JSON instance:

```kotlin
private const val EVIDENCE_SCHEMA_VERSION = 1
private const val MAX_EVIDENCE_PAYLOAD_BYTES = 262_144

private val evidenceJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    classDiscriminator = "descriptor_kind"
}

private fun digest(payload: String): String =
    Buffer().writeUtf8(payload).snapshot().sha256().hex()
```

`encode` must sort artifact identities by repository ID, revision, and path before serialization. `decode` must check UTF-8 byte size, schema version, 64-character lowercase SHA-256, canonical digest, unique identities, `ModelFileIdentity.hasValidExactIdentity()`, existing descriptor bounds, and exact equality between the descriptor's required identities and the envelope identities. A null descriptor is valid only with `REQUIRES_ENRICHMENT`; a non-null descriptor is valid only with `COMPLETE`.

- [ ] **Step 4: Run focused tests and the existing descriptor suite**

Run: `./gradlew :composeApp:jvmTest --tests '*PersistedModelEvidenceTest' --tests '*ModelDescriptorFactoryTest' --no-daemon`

Expected: PASS.

- [ ] **Step 5: Commit the codec**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/storage/PersistedModelEvidence.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/storage/PersistedModelEvidenceTest.kt
git commit -m "feat(models): add durable descriptor evidence codec"
```

---

### Task 2: Persist evidence in durable download batches

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadModels.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/storage/DownloadBatchEntity.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/storage/RoomDownloadTaskStore.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/storage/DownloadDatabase.kt`
- Modify: `composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/download/storage/DownloadDatabaseTest.kt`

**Interfaces:**
- Consumes: `EncodedModelEvidence` from Task 1.
- Produces: `DownloadBatchRequest.evidence: EncodedModelEvidence`, `DownloadBatchSnapshot.evidence: EncodedModelEvidence`, and `DOWNLOAD_MIGRATION_1_2`.

- [ ] **Step 1: Add failing persistence, identity, and migration tests**

Extend the existing request fixture with `evidence = pendingEvidence(identity)`, then add:

```kotlin
@Test
fun evidenceSurvivesDatabaseReopen() = runTest {
    val directory = Files.createTempDirectory("caraml-evidence-reopen")
    val path = directory.resolve("downloads.db").toString()
    val request = request()
    var database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
    val batchId = RoomDownloadTaskStore(database.downloadTaskDao()).create(request, 1L)
    database.close()

    database = getDownloadRoomDatabase(getDownloadDatabaseBuilder(path))
    try {
        assertEquals(request.evidence, RoomDownloadTaskStore(database.downloadTaskDao()).getBatch(batchId)!!.evidence)
    } finally {
        database.close()
    }
}

@Test
fun evidenceDigestParticipatesInBatchIdentity() {
    val request = request()
    val changed = request.copy(evidence = request.evidence.copy(sha256 = "f".repeat(64)))
    assertNotEquals(downloadBatchId(request), downloadBatchId(changed))
}
```

Add a migration test that creates the v1 `download_batch` and `download_artifact` tables with `BundledSQLiteDriver`, inserts one row, runs `DOWNLOAD_MIGRATION_1_2.migrate(connection)`, and asserts the old row remains with nullable evidence columns.

- [ ] **Step 2: Run the download database test and verify RED**

Run: `./gradlew :composeApp:jvmTest --tests '*DownloadDatabaseTest' --no-daemon`

Expected: compilation fails because download requests and snapshots have no evidence field and database v2 migration does not exist.

- [ ] **Step 3: Add evidence to the durable model and Room entity**

Add required evidence to new requests and nullable evidence to restored snapshots:

```kotlin
data class DownloadBatchRequest(
    val ownerModelId: String,
    val modelType: String,
    val artifacts: List<DownloadArtifactRequest>,
    val evidence: EncodedModelEvidence,
    val downloadForLaterConfirmed: Boolean,
    val displayName: String,
)

data class DownloadBatchSnapshot(
    val batchId: String,
    val ownerModelId: String,
    val modelType: String,
    val displayName: String,
    val state: DownloadBatchState,
    val userIntent: DownloadUserIntent,
    val artifacts: List<DownloadArtifactSnapshot>,
    val evidence: EncodedModelEvidence,
    val failureCode: DownloadFailureCode? = null,
)
```

Validate the evidence payload/digest by decoding at `DownloadBatchRequest` construction and include `evidence.sha256` in `downloadBatchId`.

- [ ] **Step 4: Implement DownloadDatabase v1 to v2 migration**

Add nullable `evidence_state`, `evidence_schema_version`, `evidence_payload`, and `evidence_sha256` columns to `DownloadBatchEntity`. Define `DOWNLOAD_MIGRATION_1_2` with four `ALTER TABLE` statements, bump the database to 2, and register it using `.addMigrations(DOWNLOAD_MIGRATION_1_2)` before `.build()`.

Persist all four fields in `RoomDownloadTaskStore.create`. When every field is present, reconstruct and strictly decode `EncodedModelEvidence`. When every field is null on a migrated v1 row, synthesize `REQUIRES_ENRICHMENT` evidence from that row's exact persisted artifacts so an in-flight pre-migration download can still finalize safely. Reject partially populated persisted evidence as corruption.

- [ ] **Step 5: Run focused download persistence tests**

Run: `./gradlew :composeApp:jvmTest --tests '*DownloadDatabaseTest' --tests '*DownloadModelsTest' --no-daemon`

Expected: PASS; the v1 fixture row remains present after migration.

- [ ] **Step 6: Commit durable download evidence**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/download/storage/DownloadDatabaseTest.kt
git commit -m "feat(downloads): persist model evidence with batches"
```

---

### Task 3: Publish Ready models and evidence atomically

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/storage/evidence/InstalledModelEvidenceEntity.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/storage/evidence/InstalledModelEvidenceDao.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/storage/evidence/InstalledModelEvidenceRepository.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/storage/catalog/InstalledModelCatalogDao.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/storage/AppDatabase.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/ModelDownloadFinalizer.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/di/AppModule.kt`
- Modify: `composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/download/ModelDownloadFinalizerTest.kt`
- Create: `composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/storage/AppDatabaseMigrationTest.kt`

**Interfaces:**
- Consumes: decoded evidence and `DownloadBatchSnapshot` from Tasks 1-2.
- Produces:

```kotlin
@Entity(tableName = "installed_model_evidence")
data class InstalledModelEvidenceEntity(
    @PrimaryKey @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "evidence_state") val evidenceState: String,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    @ColumnInfo(name = "payload") val payload: String,
    @ColumnInfo(name = "sha256") val sha256: String,
    @ColumnInfo(name = "published_at_epoch_ms") val publishedAtEpochMs: Long,
)

class InstalledModelEvidenceRepository {
    suspend fun get(modelId: String): EncodedModelEvidence?
    suspend fun put(modelId: String, evidence: EncodedModelEvidence, nowEpochMs: Long)
}
```

- [ ] **Step 1: Write failing finalizer and AppDatabase migration tests**

Add these finalizer behaviors:

```kotlin
@Test
fun malformedEvidenceNeverPublishesReadyCatalogEntry() = runTest {
    val calls = mutableListOf<String>()
    val malformed = evidenceForPath("model.gguf").copy(sha256 = "0".repeat(64))
    val finalizer = finalizer(batch = finalizerBatch(evidence = malformed), calls = calls)

    assertFailsWith<ArtifactVerificationException> { finalizer.finalize("batch") }

    assertEquals(listOf("manifest:publish", "manifest:validate"), calls)
}

@Test
fun evidenceArtifactMismatchNeverPublishesReadyCatalogEntry() = runTest {
    val batch = finalizerBatch(evidence = evidenceForPath("other.gguf"))
    assertFailsWith<ArtifactVerificationException> { finalizer(batch, mutableListOf()).finalize("batch") }
}
```

The AppDatabase migration test must create a v3 database containing one `local_model` row, execute `APP_MIGRATION_3_4`, then assert both the original row and empty `installed_model_evidence` table exist.

- [ ] **Step 2: Run tests and verify RED**

Run: `./gradlew :composeApp:jvmTest --tests '*ModelDownloadFinalizerTest' --tests '*AppDatabaseMigrationTest' --no-daemon`

Expected: compilation fails because evidence storage and migrations do not exist.

- [ ] **Step 3: Add AppDatabase v4 evidence storage**

Create DAO methods for `get`, `upsert`, and `delete`. Bump AppDatabase from 3 to 4, register `InstalledModelEvidenceEntity`, expose its DAO, and add `APP_MIGRATION_3_4`:

```sql
CREATE TABLE IF NOT EXISTS installed_model_evidence (
    model_id TEXT NOT NULL PRIMARY KEY,
    evidence_state TEXT NOT NULL,
    schema_version INTEGER NOT NULL,
    payload TEXT NOT NULL,
    sha256 TEXT NOT NULL,
    published_at_epoch_ms INTEGER NOT NULL
)
```

Register `.addMigrations(APP_MIGRATION_3_4)` before the existing destructive fallback so the supported v3 path is always non-destructive.

- [ ] **Step 4: Implement one transactional catalog DAO**

Create `InstalledCatalogRecord` containing the final `LocalModelEntity`, zero or more component records, and `InstalledModelEvidenceEntity`. `InstalledModelCatalogDao.replaceReady(record)` is an `@Transaction` default method that:

1. deletes prior links, local rows, and evidence for `modelId`;
2. inserts/reuses component rows by `(repo_id, file_path)`;
3. inserts component links;
4. inserts the Ready local-model row;
5. inserts evidence last.

Keep all SQL methods on this DAO so Room owns the full transaction on every platform.

- [ ] **Step 5: Require validated evidence in finalization**

Change `ModelCatalogPublisher` to:

```kotlin
fun interface ModelCatalogPublisher {
    suspend fun publish(batch: DownloadBatchSnapshot, evidence: EncodedModelEvidence)
}
```

After manifest validation, `ModelDownloadFinalizer` must decode evidence, compare its sorted exact identities to every batch artifact identity, and throw `ArtifactVerificationException` on missing, malformed, or mismatched evidence. `RepositoryModelCatalogPublisher` maps the batch to `InstalledCatalogRecord` and calls the transactional DAO; it no longer performs independent repository writes.

- [ ] **Step 6: Run finalizer and database tests**

Run: `./gradlew :composeApp:jvmTest --tests '*ModelDownloadFinalizerTest' --tests '*AppDatabaseMigrationTest' --tests '*DownloadDatabaseTest' --no-daemon`

Expected: PASS, including preservation of old rows and refusal to publish invalid evidence.

- [ ] **Step 7: Commit atomic catalog publication**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/storage composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/ModelDownloadFinalizer.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/di/AppModule.kt composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core
git commit -m "feat(models): publish ready catalog evidence atomically"
```

---

### Task 4: Attach evidence without blocking Needs information downloads

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadEvidenceFactory.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/ModelViewModel.kt`
- Modify: all `DownloadBatchRequest` fixtures under `composeApp/src/commonTest` and `composeApp/src/jvmTest`
- Modify: `composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/ModelViewModelRecommendationTest.kt`

**Interfaces:**
- Consumes: selected download artifacts, optional `RecommendedModelUiState.selectedDescriptor`, and Task 1 codec.
- Produces:

```kotlin
class DownloadEvidenceFactory(
    private val codec: PersistedModelEvidenceCodec,
) {
    fun create(
        artifacts: Collection<DownloadArtifactRequest>,
        descriptor: ModelDescriptor?,
    ): EncodedModelEvidence
}
```

- [ ] **Step 1: Write failing complete and Needs information enqueue tests**

```kotlin
@Test
fun assessedDownloadEnqueuesCompleteDescriptorEvidence() = runTest {
    val coordinator = RecordingDownloadCoordinator()
    val viewModel = modelViewModel(coordinator = coordinator, recommendation = assessedState())

    viewModel.startDownload(MODEL_ID, FILE_PATH, metadata())
    advanceUntilIdle()

    val evidence = requireNotNull(coordinator.lastRequest).evidence
    assertEquals(InstalledEvidenceState.COMPLETE, evidence.state)
}

@Test
fun needsInformationStillEnqueuesPendingEvidence() = runTest {
    val coordinator = RecordingDownloadCoordinator()
    val viewModel = modelViewModel(coordinator = coordinator, recommendation = needsInformationState())

    viewModel.startDownload(MODEL_ID, FILE_PATH, metadata())
    advanceUntilIdle()

    val evidence = requireNotNull(coordinator.lastRequest).evidence
    assertEquals(InstalledEvidenceState.REQUIRES_ENRICHMENT, evidence.state)
    assertNull(PersistedModelEvidenceCodec().decode(evidence).descriptor)
}
```

- [ ] **Step 2: Run the ModelViewModel test and verify RED**

Run: `./gradlew :composeApp:jvmTest --tests '*ModelViewModelRecommendationTest' --no-daemon`

Expected: failing assertions or compilation because enqueued batches have no generated evidence.

- [ ] **Step 3: Implement evidence creation for every durable enqueue**

`DownloadEvidenceFactory` converts the exact `DownloadArtifactIdentity` set to bounded `ModelFileIdentity` values and includes a descriptor only if its required identities exactly equal the request's artifacts. Otherwise it creates `REQUIRES_ENRICHMENT` evidence; it never throws solely because recommendation state is `Needs information`.

In both language and smart/diffusion enqueue paths, select the matching `RecommendedModelUiState.selectedDescriptor`, pass it with the complete artifact list to the factory, and set `DownloadBatchRequest.evidence`. Update test fixtures explicitly with complete or pending evidence rather than adding a permissive default.

- [ ] **Step 4: Run Model Hub and download tests**

Run: `./gradlew :composeApp:jvmTest --tests '*ModelViewModelRecommendationTest' --tests '*DownloadAdmissionPolicyTest' --tests '*DownloadDatabaseTest' --no-daemon`

Expected: PASS; both assessed and Needs information downloads enqueue.

- [ ] **Step 5: Commit Model Hub evidence integration**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/download/DownloadEvidenceFactory.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/ModelViewModel.kt composeApp/src/commonTest composeApp/src/jvmTest
git commit -m "feat(modelhub): retain evidence for every download"
```

---

### Task 5: Repair existing installed-model evidence exactly once

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/InstalledDescriptorMetadataSource.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/domain/HuggingFaceModelMetadataSource.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/InstalledModelEvidenceRepairer.kt`
- Create: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/InstalledModelEvidenceRepairerTest.kt`

**Interfaces:**
- Consumes: resolved local artifact identities, installed evidence repository, existing Hugging Face gateway/factory, and Task 1 codec.
- Produces:

```kotlin
sealed interface InstalledDescriptorLookup {
    data class Ready(val descriptor: ModelDescriptor) : InstalledDescriptorLookup
    data object RetryableUnavailable : InstalledDescriptorLookup
    data class Rejected(val reasons: List<AssessmentReason>) : InstalledDescriptorLookup
}

fun interface InstalledDescriptorMetadataSource {
    suspend fun findExact(
        repositoryId: String,
        mode: ModelHubBrowseMode,
        identities: List<ModelFileIdentity>,
    ): InstalledDescriptorLookup
}

sealed interface EvidenceRepairResult {
    data class Ready(val descriptor: ModelDescriptor) : EvidenceRepairResult
    data object NeedsNetwork : EvidenceRepairResult
    data class Rejected(val reasons: List<AssessmentReason>) : EvidenceRepairResult
}
```

- [ ] **Step 1: Write failing repair tests**

```kotlin
@Test
fun missingEvidenceIsFetchedPersistedAndReusedOffline() = runTest {
    var lookups = 0
    val source = InstalledDescriptorMetadataSource { _, _, _ ->
        lookups += 1
        InstalledDescriptorLookup.Ready(descriptor)
    }
    val repairer = repairer(source = source)

    assertIs<EvidenceRepairResult.Ready>(repairer.requireComplete(model, components, GenerationMode.Text))
    assertIs<EvidenceRepairResult.Ready>(repairer.requireComplete(model, components, GenerationMode.Text))

    assertEquals(1, lookups)
    assertEquals(InstalledEvidenceState.COMPLETE, evidenceRepository.get(model.modelId)!!.state)
}

@Test
fun offlineRepairDoesNotFallbackOrRewriteWeights() = runTest {
    val result = repairer(
        source = InstalledDescriptorMetadataSource { _, _, _ -> InstalledDescriptorLookup.RetryableUnavailable },
    ).requireComplete(model, components, GenerationMode.Text)

    assertEquals(EvidenceRepairResult.NeedsNetwork, result)
    assertEquals(0, fileWriter.calls)
}

@Test
fun remotelyReturnedDifferentVariantIsRejected() = runTest {
    val result = repairer(source = sourceReturning(otherDescriptor))
        .requireComplete(model, components, GenerationMode.Text)
    assertIs<EvidenceRepairResult.Rejected>(result)
}
```

- [ ] **Step 2: Run repair tests and verify RED**

Run: `./gradlew :composeApp:jvmTest --tests '*InstalledModelEvidenceRepairerTest' --no-daemon`

Expected: compilation fails because repair types and service do not exist.

- [ ] **Step 3: Add an exact installed-metadata lookup**

Refactor `HuggingFaceModelMetadataSource` so existing browse behavior is unchanged, while `findExact` preserves failure categories:

- a gateway network `Result.Error` returns `RetryableUnavailable`;
- malformed metadata, unsupported components, or no exact variant returns `Rejected(reasons)`;
- exactly one descriptor whose sorted identities equal the requested repository/revision/path/size/object IDs returns `Ready`;
- zero or multiple matches are rejected.

Do not match by display name or filename alone.

- [ ] **Step 4: Implement serialized idempotent repair**

Use one `Mutex` around the rare repair operation. Inside the lock, re-read evidence, decode and return an already complete exact descriptor, otherwise:

1. call `LocalArtifactIdentityResolver.resolve(model, components)`;
2. map generation mode to `ModelHubBrowseMode`;
3. call `InstalledDescriptorMetadataSource.findExact` with resolved identities;
4. encode the returned descriptor with the resolved identities;
5. upsert complete evidence and decode it once before returning.

Cancellation must escape the method. Repository write failure returns `Rejected(INVALID_METADATA)` without changing model files.

- [ ] **Step 5: Run repair and metadata-source suites**

Run: `./gradlew :composeApp:jvmTest --tests '*InstalledModelEvidenceRepairerTest' --tests '*HuggingFaceModelMetadataSourceTest' --no-daemon`

Expected: PASS, including exact variant rejection and a single remote lookup.

- [ ] **Step 6: Commit one-time repair**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/domain/HuggingFaceModelMetadataSource.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation
git commit -m "feat(models): repair installed descriptor evidence"
```

---

### Task 6: Reconstruct a fresh exact LoadRequest for every selection

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/InstalledModelWorkloadFactory.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/InstalledModelLoadRequestResolver.kt`
- Create: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/InstalledModelLoadRequestResolverTest.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/di/AppModule.kt`

**Interfaces:**
- Consumes: evidence repairer, component repository, artifact resolver, device snapshot provider, assessment repository, and settings repository.
- Produces:

```kotlin
sealed interface InstalledModelLoadResolution {
    data class Ready(val request: LoadRequest) : InstalledModelLoadResolution
    data object NeedsNetwork : InstalledModelLoadResolution
    data class NotAdmissible(val reason: AssessmentReason) : InstalledModelLoadResolution
    data class Rejected(val reason: ArtifactIdentityRejection) : InstalledModelLoadResolution
    data object Failed : InstalledModelLoadResolution
}

class InstalledModelLoadRequestResolver {
    suspend fun resolve(
        model: LocalModelEntity,
        expectedMode: GenerationMode,
    ): InstalledModelLoadResolution
}
```

- [ ] **Step 1: Write failing fresh-assessment and mismatch tests**

```kotlin
@Test
fun readyInstalledModelProducesExactRequestAfterRestart() = runTest {
    val result = resolver().resolve(model, GenerationMode.Text)
    val request = assertIs<InstalledModelLoadResolution.Ready>(result).request

    assertEquals(model.id, request.model.id)
    assertEquals(request.identity, request.artifact!!.identity)
    assertEquals(request.assessmentKey, request.assessedPlans!!.assessmentKey)
}

@Test
fun eachResolutionCapturesCurrentResourcesAndProfile() = runTest {
    resolver().resolve(model, GenerationMode.Text)
    snapshotProvider.nextSnapshot = constrainedSnapshot()
    profile.value = RecommendationProfile(RiskTolerance.CONSERVATIVE, OptimizationPriority.QUALITY_CONTEXT)
    resolver().resolve(model, GenerationMode.Text)

    assertEquals(2, snapshotProvider.captureCalls)
    assertEquals(listOf(RiskTolerance.BALANCED, RiskTolerance.CONSERVATIVE), personalizedProfiles)
}

@Test
fun wrongGenerationModeIsNotAdmissible() = runTest {
    val result = resolver().resolve(model, GenerationMode.Image)
    assertIs<InstalledModelLoadResolution.NotAdmissible>(result)
}

@Test
fun manifestMismatchStopsBeforeAssessment() = runTest {
    val result = resolver(artifactResolution = staleManifest()).resolve(model, GenerationMode.Text)
    assertIs<InstalledModelLoadResolution.Rejected>(result)
    assertEquals(0, assessmentRepository.calls)
}
```

- [ ] **Step 2: Run resolver tests and verify RED**

Run: `./gradlew :composeApp:jvmTest --tests '*InstalledModelLoadRequestResolverTest' --no-daemon`

Expected: compilation fails because the resolver does not exist.

- [ ] **Step 3: Implement deterministic installed workloads**

Move the current Model Hub defaults into `InstalledModelWorkloadFactory` so both flows use the same bounded values. Text uses at most the descriptor context limit with 4,096 requested/context tokens, 512 minimum, 256 prompt/reserve, 256 batch, and 64 micro-batch. Image uses 1,024 by 1,024; video uses 1,024 by 576 and 16 frames. Preserve the existing fallback flags and KV-cache allowed types.

- [ ] **Step 4: Implement ordered V2 resolution**

`resolve` must:

1. fetch linked components;
2. call `repairer.requireComplete`;
3. reject descriptor/generation-mode mismatch;
4. call `LocalArtifactIdentityResolver.resolve` before assessment;
5. capture `DeviceSnapshotProvider.capture()` and `settingsRepository.getSettings().first()`;
6. build the workload, call `ModelAssessmentRepository.assess`, then `personalize`;
7. require a selected plan matching Text/Image/Video;
8. call `LocalArtifactIdentityResolver.createLoadRequest` and map its result.

Catch non-cancellation exceptions only at the outer boundary and return `Failed`; never include exception text in the UI result.

- [ ] **Step 5: Register resolver dependencies and run tests**

Register codec, evidence repository, installed metadata source, repairer, workload factory, and resolver in `AppModule.kt` with singletons. Run:

`./gradlew :composeApp:jvmTest --tests '*InstalledModelLoadRequestResolverTest' --tests '*LocalArtifactIdentityResolverTest' --tests '*ModelAssessmentRepositoryTest' --no-daemon`

Expected: PASS.

- [ ] **Step 6: Commit installed V2 resolution**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/di/AppModule.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation
git commit -m "feat(models): resolve installed v2 load requests"
```

---

### Task 7: Remove legacy loading and route Chat exclusively through V2

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/inference/InferenceRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/inference/LlamaInferenceRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/inference/DiffusionInferenceRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/ChatViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/navigation/AppNavigation.kt`
- Delete: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/ModelLoadRouter.kt`
- Delete: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/RecommendedModelLoadRequestResolver.kt`
- Replace: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/chat/presentation/ModelLoadRouterTest.kt`
- Delete or rewrite: `composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/RecommendedModelLoadRequestTest.kt`
- Modify: inference fakes under `composeApp/src/commonTest` and `composeApp/src/jvmTest`

**Interfaces:**
- Consumes: `InstalledModelLoadRequestResolver` from Task 6.
- Produces the only inference loading contract:

```kotlin
interface InferenceRepository {
    suspend fun loadModel(request: LoadRequest): ModelLoadResult
    // Existing generation, context, cancellation, and unload methods remain unchanged.
}

internal suspend fun loadInstalledModel(
    model: LocalModelEntity,
    mode: GenerationMode,
    resolve: suspend (LocalModelEntity, GenerationMode) -> InstalledModelLoadResolution,
    loadText: suspend (LoadRequest) -> ModelLoadResult,
    loadDiffusion: suspend (LoadRequest) -> ModelLoadResult,
): ModelLoadResult
```

- [ ] **Step 1: Write a failing no-legacy routing test**

Replace router coverage with a focused `InstalledModelLoadingTest` around a small internal `loadInstalledModel` function used by `ChatViewModel`:

```kotlin
@Test
fun textSelectionResolvesThenCallsOnlyExactLoader() = runTest {
    val calls = mutableListOf<String>()
    val result = loadInstalledModel(
        model = model,
        mode = GenerationMode.Text,
        resolve = { _, _ -> InstalledModelLoadResolution.Ready(request) },
        loadText = { exact -> calls += "text:${exact.assessmentKey}"; ModelLoadResult.Success(4096) },
        loadDiffusion = { calls += "diffusion"; error("unexpected") },
    )

    assertIs<ModelLoadResult.Success>(result)
    assertEquals(listOf("text:${request.assessmentKey}"), calls)
}

@Test
fun missingEvidenceNetworkStateNeverInvokesInference() = runTest {
    var loadCalls = 0
    val result = loadInstalledModel(
        model = model,
        mode = GenerationMode.Text,
        resolve = { _, _ -> InstalledModelLoadResolution.NeedsNetwork },
        loadText = { loadCalls += 1; ModelLoadResult.Success(4096) },
        loadDiffusion = { loadCalls += 1; ModelLoadResult.Success(0) },
    )
    assertEquals(0, loadCalls)
    assertEquals(
        ModelLoadResult.Error("Connect once to verify this installed model's metadata, then try again."),
        result,
    )
}
```

- [ ] **Step 2: Run the loading test and verify RED**

Run: `./gradlew :composeApp:jvmTest --tests '*InstalledModelLoadingTest' --no-daemon`

Expected: compilation fails because Chat still routes with rollout mode, nullable transient request, and entity-only callbacks.

- [ ] **Step 3: Remove entity-only inference APIs**

Delete `loadModel(LocalModelEntity)` from `InferenceRepository` and both concrete repositories. Remove `RecommendationRolloutModeSource` from their constructors and delete the legacy load implementations rather than retaining private fallback code. Keep exact admission, artifact revalidation, recovery, CPU/GPU fallback inside the existing `loadModel(LoadRequest)` implementations.

Update test fakes to accept `LoadRequest`. A fake that does not care about loading must return an explicit test result from the exact method; do not reconstruct a request from the entity in test code.

- [ ] **Step 4: Route Chat through installed resolution**

Inject `InstalledModelLoadRequestResolver` into `ChatViewModel`; remove `recommendationRolloutModeSource` and `selectedLoadRequest`. Collapse selection to `selectModel(model: LocalModelEntity)`. During loading:

- preserve previous-job joining and native-session safety;
- resolve the current model/mode first;
- release the opposite runner before invoking the exact repository;
- map `NeedsNetwork`, `NotAdmissible`, `Rejected`, and `Failed` to fixed safe messages;
- pass `Ready.request` only to the exact loader;
- preserve `AdmissionRequired` handling.

Delete `ModelLoadRouter` after no production references remain.

- [ ] **Step 5: Remove transient navigation handoff**

In `AppNavigation`, remove `RecommendedModelLoadRequestResolver`, selection coroutine, and rollout branch. Both Search and Library call `chatViewModel.selectModel(model)` and pop the route. Delete the obsolete resolver and rewrite/delete its tests.

- [ ] **Step 6: Prove legacy loading is absent and run focused suites**

Run:

```bash
rg -n "loadModel\(model: LocalModelEntity|legacyLoad|selectedLoadRequest|ModelLoadRouter" composeApp/src/commonMain
./gradlew :composeApp:jvmTest --tests '*InstalledModelLoadingTest' --tests '*InstalledModelLoadRequestResolverTest' --tests '*LlamaInferenceRepositoryTest' --tests '*DiffusionInferenceRepositoryTest' --no-daemon
```

Expected: `rg` returns no matches and all tests PASS.

- [ ] **Step 7: Commit the V2-only inference contract**

```bash
git add composeApp/src
git commit -m "refactor(inference): require v2 requests for installed models"
```

---

### Task 8: Documentation, full verification, and connected-device acceptance

**Files:**
- Modify: `README.md`
- Modify: `composeApp/README.md`
- Modify: `huggingFaceManager/README.md` only if Task 4 changes that module's public API
- Modify: focused tests if a platform compile exposes a contract mismatch

**Interfaces:**
- Consumes: completed Tasks 1-7.
- Produces: verified project gate and Pixel 9 evidence that the existing MiniCPM installation loads through the exact path.

- [ ] **Step 1: Update Recent Changes documentation**

Add concise bullets stating that installed models now retain exact evidence, old downloads receive one-time metadata repair, and inference accepts only freshly assessed exact requests. Do not claim iOS background execution or unrelated recommendation rollout removal.

- [ ] **Step 2: Run hygiene and focused tests**

Run:

```bash
git diff --check
./gradlew :composeApp:jvmTest --tests '*PersistedModelEvidenceTest' --tests '*DownloadDatabaseTest' --tests '*ModelDownloadFinalizerTest' --tests '*InstalledModelEvidenceRepairerTest' --tests '*InstalledModelLoadRequestResolverTest' --tests '*InstalledModelLoadingTest' --no-daemon
```

Expected: no whitespace errors and all focused tests PASS.

- [ ] **Step 3: Run the repository gate**

Run: `./gradlew verifyProject --no-daemon`

Expected: PASS. Report dependency/cache/permission failures separately from product-test failures and rerun with the required access when appropriate.

- [ ] **Step 4: Build and install while preserving app data**

Run:

```bash
./gradlew :androidApp:installDebug --no-daemon
adb -s 48221FDAQ003AT shell am force-stop com.debanshu777.caraml
adb -s 48221FDAQ003AT shell monkey -p com.debanshu777.caraml 1
```

Expected: install succeeds without `pm clear`; the existing 1.4 GB MiniCPM file remains in app storage.

- [ ] **Step 5: Verify the existing MiniCPM model reaches native loading**

Use the device UI to select `openbmb/MiniCPM5-2B-GGUF / MiniCPM5-2B-Q4_K_M.gguf`, then inspect logs:

```bash
adb -s 48221FDAQ003AT logcat -c
adb -s 48221FDAQ003AT logcat -v time | rg "InstalledModel|LlamaInferenceRepository|LlamaRunner|Unable to load|reassessed"
```

Expected: one evidence repair for the pre-migration row, exact repository load, native runner load, and no `needs to be reassessed` error. Submit a short prompt and verify a generated response.

- [ ] **Step 6: Verify post-repair offline restart**

Disable networking from the device controls, force-stop and relaunch the app, select the same MiniCPM model, and submit another short prompt.

Expected: no metadata fetch is required; persisted complete evidence is decoded, the model is freshly assessed, and native loading succeeds.

- [ ] **Step 7: Review final diff and commit documentation/fixes**

Run:

```bash
git status --short
git diff --stat HEAD~7..HEAD
git diff --check
```

Verify only V2 installed loading, its migrations/tests, and relevant README entries changed. Then commit remaining documentation or device-discovered corrections:

```bash
git add README.md composeApp/README.md huggingFaceManager/README.md composeApp/src
git commit -m "docs: document v2 installed model loading"
```

If `huggingFaceManager/README.md` was not changed, omit it from `git add`.
