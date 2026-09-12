# Device-Aware Model Recommendation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace CaraML's heuristic model rating with a variant-specific, confidence-aware recommendation system that selects a concrete run plan for the current device and the user's risk and optimization preferences.

**Architecture:** Pure common Kotlin builds validated model descriptors, generates bounded run-plan candidates, estimates resource/performance intervals, and applies a constraint-first policy. Platform collectors provide fresh resource snapshots, runner modules provide post-download native preflight, and local Room-backed observations calibrate predictions without transmitting content or device identifiers.

**Tech Stack:** Kotlin Multiplatform 2.4.0, Compose Multiplatform 1.11.1, kotlinx.coroutines 1.11.0, DataStore 1.2.1, Room 2.8.4, Koin 4.2.2, Ktor 3.5.0, kotlin.test, JNI/C++, Kotlin/Native cinterop, llama.cpp, stable-diffusion.cpp.

**Spec:** `docs/superpowers/specs/2026-09-05-device-aware-model-recommendation-design.md`

## Global Constraints

- Preserve the current uncommitted P0/P1 work; do not revert, overwrite, or accidentally include unrelated hunks in a task commit.
- At execution start, do not create a clean worktree from `HEAD` if that would omit required uncommitted P0/P1 dependencies. Resolve the baseline with the user first.
- Commit after each task only when execution authorization includes commits. Before each task commit, mark every newly created path in that task's **Files** list with `git add --intent-to-add`, use the task's interactive staging command, reject unrelated P0/P1 hunks, then inspect `git diff --cached --name-only`, `git diff --cached`, and `git diff --cached --check`. Commit only the reviewed staged patch.
- Keep objective `ModelAssessment` independent of `RiskTolerance` and `OptimizationPriority`; only `RecommendationPolicy` applies those preferences.
- Hard incompatibility, invalid metadata, arithmetic overflow, storage no-fit, and memory no-fit can never be improved by performance or quality.
- Initial runtime-memory reserves are Conservative 25%, Balanced 15%, and Experimental 5%, applied after platform reserve.
- Initial platform minimum reserve is 384 MiB on Android/iOS and 512 MiB on desktop.
- Filesystem reserve is `min(10 GiB, max(512 MiB, 5% of current free space))` and is never reduced by Experimental mode.
- Parser limits are 1 PiB per file, 2 PiB per bundle, 4,096 components, 1 quadrillion parameters, 16,777,216 context tokens, and 65,536 pixels per dimension.
- Native result ABIs expose at most 16 memory/backend pools, 64 diffusion component records, and 64 bytes per sanitized backend/architecture label; excess becomes a structured unavailable result.
- Initial LLM decode targets are 8, 4, and 2 tokens/second for Speed, Balanced, and Quality respectively.
- Initial diffusion reference targets are 30, 90, and 180 seconds for 512x512 at 20 steps.
- Personalized retrieval starts with 48 candidates, adds 24 per load, retains at most 96 enriched candidates, and performs at most four enrichment calls concurrently.
- Repository cards assess every validated runnable variant only when there are at most 64; larger/ambiguous sets stay Needs information with “Select a variant” rather than sampling an assumed quantization or component graph.
- A displayed `ResourceSnapshot` becomes stale after 30 seconds. Download and load admission always capture a new snapshot; native preflight may be reused for at most 30 seconds and only for an exact verified file/config/backend/engine key.
- Calibration requires five comparable samples, uses a 30-day exponential decay, retains at most 500 rows for 90 days, and never lowers a high-memory correction below 1.0.
- Rethrow `CancellationException` unchanged; map ordinary native failures to typed results.
- Never log prompts, generated output, credentials, raw private paths, native stack traces, or a hardware serial number.
- No cloud telemetry, remote feature flags, automatic downloads, or new model-quality claims.
- Every external number is validated before arithmetic; overflow becomes invalid evidence (CWE-20/CWE-190/CWE-400).
- No network, filesystem parsing, native work, or calibration runs on the UI thread.

---

## Security Findings Addressed

- `ClientWrapper.bodyAsText()` currently permits an unbounded decoded body (CWE-400); Task 2 adds a decoded-byte ceiling before JSON parsing.
- Model IDs, revisions, and component paths cross network/filesystem boundaries (CWE-20/CWE-22/CWE-918); Tasks 2 and 10 retain path containment, require immutable validated revisions, and use only an allowlisted hub origin.
- Metadata-driven parameter/memory arithmetic can overflow (CWE-190); Tasks 1–6 reject invalid operands and propagate structured invalid evidence.
- Native parse/allocation failures must not cross JNI/C ABI boundaries or expose private paths (CWE-248/CWE-532); Tasks 11–13 map them to bounded typed results and sanitized reason codes.

## File Structure

Create the recommendation domain under `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/`:

- `RecommendationProfile.kt`: preference enums and versioned policy constants.
- `AssessmentModels.kt`: evidence, confidence, intervals, compatibility, plan assessments, categories, reasons, and personalized output.
- `ModelDescriptor.kt`: normalized LLM/diffusion descriptors and validated file/component identities.
- `ModelDescriptorFactory.kt`: normalization and validation of untrusted remote/local metadata.
- `QuantizationParser.kt`: filename/config quantization evidence without assigning a category.
- `CompatibilityChecker.kt`: hard format, architecture, quantization, component, and backend gates.
- `EngineCapabilitySource.kt`: testable engine feature support, later backed by native registry probes.
- `RunnerEngineCapabilitySource.kt`: production adapter to llama/diffusion compiled feature probes.
- `CheckedResourceMath.kt`: checked/saturating resource arithmetic.
- `WorkloadConfig.kt`: requested LLM/image/video workloads.
- `WorkloadConfigFactory.kt`: converts validated app settings and UI intent into bounded workloads.
- `RunPlan.kt`: executable LLM/diffusion plans and disclosed compromises.
- `RunPlanGenerator.kt`: finite, profile-neutral candidate generation.
- `LlmFootprintEstimator.kt`: LLM weights/KV/graph interval estimation.
- `DiffusionFootprintEstimator.kt`: diffusion components/activation interval estimation.
- `PerformanceEstimator.kt`: roofline-style prediction and calibration application.
- `SuitabilityEngine.kt`: assesses compatibility and every candidate without applying a user profile.
- `RunPlanOptimizer.kt`: policy-layer candidate selection and fallback capping.
- `RecommendationPolicy.kt`: profile reserve, fit-band mapping, plan selection, category, reasons, and sorting.
- `ModelAssessmentRepository.kt`: assessment/preflight cache coordination and deduplication.
- `NativeRunPlanAdapter.kt`: lossless mapping from admitted plans to runner configs.
- `LegacySuitabilityAdapter.kt`: temporary v1/v2 UI bridge.

Create feature orchestration under `features/modelhub/domain/` and keep network access out of the core estimator:

- `ModelMetadataSource.kt`: testable remote metadata boundary.
- `HuggingFaceModelMetadataSource.kt`: production adapter.
- `RecommendationQuerySession.kt`: per-query cancellation, window, snapshot, and incremental UI state.
- `ModelRecommendationService.kt`: bounded enrichment, assessment, and local ranking.
- `DownloadStorageEstimator.kt`: checked temporary/final storage requirements per volume.
- `DownloadAdmissionPolicy.kt`: storage and “download for later” decisions.

Persist download identity outside the existing model database:

- `DownloadArtifactIdentity.kt`: immutable hub revision, normalized repository-relative file identity, remote object ID, actual byte count, and streaming SHA-256.
- `ArtifactManifestStore.kt`: bounded, atomically replaced `.caraml-artifact-v1.json` sidecars for single files and component bundles.
- `LocalArtifactIdentityResolver.kt`: validates sidecars and performs a one-time streaming content-hash backfill for legacy downloads whose `LocalModelEntity` has no revision or digest.

Create device collection boundaries in `core/platform/DeviceSnapshot.kt`, `BackendCapabilitySource.kt`, and `RunnerBackendCapabilitySource.kt`.

Create disposable calibration storage under `core/recommendation/storage/` with `RecommendationObservationEntity`, `RecommendationObservationDao`, `RecommendationDatabase`, and platform builders; do not add entities to `AppDatabase`.

Extend runner modules with preflight/backend result models and bounded native bridges. Extend current Compose rating components in place during migration, then delete the legacy calculator only at the final gate.

### Task 1: Add recommendation contracts and overflow-safe resource arithmetic

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/RecommendationProfile.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/AssessmentModels.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/CheckedResourceMath.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/CheckedResourceMathTest.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/AssessmentModelsTest.kt`

**Interfaces:**
- Produces: `RiskTolerance`, `OptimizationPriority`, `RecommendationProfile`, and `RecommendationCategory`.
- Produces: `EstimateRange`, `Confidence`, `AssessmentConfidence`, `Evidence`, `Compatibility`, `FitBand`, `AssessmentReason`, `PlanAssessment`, `AssessedPlans`, `ModelAssessment`, and `PersonalizedRecommendation`. Only `PersonalizedRecommendation` may contain a category, selected plan, or user profile.
- Produces: `checkedAdd`, `checkedSubtractNonNegative`, `checkedMultiply`, `checkedPercentage`, and `EstimateRange.create` that return invalid evidence rather than wrapping.

- [ ] **Step 1: Write failing arithmetic and interval tests**

```kotlin
@Test
fun checkedArithmeticRejectsOverflowAndInvalidRanges() {
    assertIs<CheckedLong.Invalid>(checkedAdd(Long.MAX_VALUE, 1L))
    assertIs<CheckedLong.Invalid>(checkedSubtractNonNegative(1L, 2L))
    assertIs<CheckedLong.Invalid>(checkedMultiply(Long.MAX_VALUE, 2L))
    assertIs<CheckedEstimateRange.Invalid>(EstimateRange.create(10L, 9L, 11L))
    assertEquals(CheckedLong.Value(15L), checkedAdd(7L, 8L))
    assertEquals(CheckedLong.Value(850L), checkedPercentage(1_000L, 85))
}
```

- [ ] **Step 2: Run the focused tests and confirm the contracts are unresolved**

Run: `./gradlew :composeApp:jvmTest --tests '*CheckedResourceMathTest*' --tests '*AssessmentModelsTest*'`

Expected: FAIL because `CheckedLong`, `EstimateRange`, and recommendation enums do not exist.

- [ ] **Step 3: Implement immutable contracts and checked math**

```kotlin
sealed interface CheckedLong {
    data class Value(val value: Long) : CheckedLong
    data class Invalid(val reason: AssessmentReason) : CheckedLong
}

internal fun checkedAdd(left: Long, right: Long): CheckedLong = when {
    left < 0L || right < 0L -> CheckedLong.Invalid(AssessmentReason.INVALID_METADATA)
    left > Long.MAX_VALUE - right -> CheckedLong.Invalid(AssessmentReason.ARITHMETIC_OVERFLOW)
    else -> CheckedLong.Value(left + right)
}

internal fun checkedSubtractNonNegative(left: Long, right: Long): CheckedLong = when {
    left < 0L || right < 0L || right > left -> CheckedLong.Invalid(AssessmentReason.INVALID_METADATA)
    else -> CheckedLong.Value(left - right)
}

internal fun checkedMultiply(left: Long, right: Long): CheckedLong = when {
    left < 0L || right < 0L -> CheckedLong.Invalid(AssessmentReason.INVALID_METADATA)
    left != 0L && right > Long.MAX_VALUE / left -> CheckedLong.Invalid(AssessmentReason.ARITHMETIC_OVERFLOW)
    else -> CheckedLong.Value(left * right)
}

internal fun checkedPercentage(value: Long, percent: Int): CheckedLong = when {
    value < 0L || percent !in 0..100 -> CheckedLong.Invalid(AssessmentReason.INVALID_METADATA)
    else -> checkedAdd((value / 100L) * percent, ((value % 100L) * percent) / 100L)
}

data class EstimateRange private constructor(
    val lowBytes: Long,
    val likelyBytes: Long,
    val highBytes: Long,
) {
    companion object {
        fun create(lowBytes: Long, likelyBytes: Long, highBytes: Long): CheckedEstimateRange =
            if (lowBytes < 0L || lowBytes > likelyBytes || likelyBytes > highBytes) {
                CheckedEstimateRange.Invalid(AssessmentReason.INVALID_ESTIMATE_RANGE)
            } else {
                CheckedEstimateRange.Value(EstimateRange(lowBytes, likelyBytes, highBytes))
            }
    }
}

sealed interface CheckedEstimateRange {
    data class Value(val range: EstimateRange) : CheckedEstimateRange
    data class Invalid(val reason: AssessmentReason) : CheckedEstimateRange
}
```

Define `RecommendationCategory` with `RECOMMENDED`, `USABLE`, `RISKY`, `NOT_SUITABLE`, `INCOMPATIBLE`, and `NEEDS_INFORMATION`. Define `Confidence` as ordered `LOW`, `MEDIUM`, `HIGH`, but store confidence separately for compatibility, memory, storage, and performance.

- [ ] **Step 4: Add invariant tests for category-independent assessment data**

Construct one `ModelAssessment` fixture without any profile/category arguments and one `PersonalizedRecommendation` fixture that references its assessment key. Assert the former retains only compatibility, plan assessments, base budgets, confidence, and evidence while the latter owns category/selected plan/reasons. This is an API-shape regression test and requires no JVM reflection dependency.

- [ ] **Step 5: Run the focused tests**

Run: `./gradlew :composeApp:jvmTest --tests '*CheckedResourceMathTest*' --tests '*AssessmentModelsTest*'`

Expected: PASS.

- [ ] **Step 6: Commit the domain kernel**

```bash
git add -p -- composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation
git commit -m "feat(recommendation): add assessment contracts"
```

### Task 2: Normalize and validate remote model metadata

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/ModelDescriptor.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/ModelDescriptorFactory.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/QuantizationParser.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/CompatibilityChecker.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/EngineCapabilitySource.kt`
- Create: `huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/model/TransformerConfigResponse.kt`
- Create: `huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/usecase/GetModelConfigUseCase.kt`
- Modify: `huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/api/ClientWrapper.kt`
- Modify: `huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/api/RemoteHuggingFaceApiService.kt`
- Modify: `huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/repository/HuggingFaceRepository.kt`
- Modify: `huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/HuggingFaceApi.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/ModelDescriptorFactoryTest.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/CompatibilityCheckerTest.kt`
- Test: `huggingFaceManager/src/commonTest/kotlin/com/debanshu777/huggingfacemanager/api/BoundedResponseTest.kt`

**Interfaces:**
- Produces: sealed `ModelDescriptor` with `LlmModelDescriptor` and `DiffusionModelDescriptor`.
- Produces: `QuantizationParser.parseFilename(filename): QuantizationEvidence`, replacing the legacy calculator's filename parser without assigning a rating.
- Produces: `DescriptorBuildResult.Ready`, `.NeedsVariant`, and `.Invalid`.
- Produces: `ModelDescriptorFactory.buildLlm(detail, file, transformerConfig)` and `buildDiffusion(detail, files, setup, mode)`.
- Produces: `CompatibilityChecker.check(descriptor, hardwareProfile): Compatibility` with hard gates before estimation, backed by `EngineCapabilitySource.supportFor(descriptor): SupportEvidence`.
- Produces: `HuggingFaceApi.getModelConfig(modelId, revision)` with a 1 MiB decoded response limit.
- Changes `HuggingFaceApi.getModelFileTree(modelId, revision)` to require the same immutable revision used by the descriptor.
- Changes file-tree retrieval to consume bounded pagination (maximum 64 pages and 4,096 entries) without following an arbitrary `Link` URL.

- [ ] **Step 1: Add failing descriptor validation tests**

```kotlin
@Test
fun rejectsOverflowAndDoesNotAssumeAQuantization() {
    val invalid = factory.buildLlm(detail(totalParameters = Long.MAX_VALUE), file(size = -1L), null)
    assertIs<DescriptorBuildResult.Invalid>(invalid)

    val missingVariant = factory.buildProvisional(listModel(numParameters = 7_000_000_000L))
    assertIs<DescriptorBuildResult.NeedsVariant>(missingVariant)
    assertNull(missingVariant.assumedQuantization)
}
```

Cover the exact parser limits, unknown JSON fields, overlong strings/collections, empty/duplicate component paths, more than 4,096 components, missing required diffusion roles, mixed quantization evidence, case-insensitive quantization filenames without substring false positives, and transformer shapes where `headDim` is derived from `hiddenSize / attentionHeads` only when divisible. Add compatibility rules for GGUF version, required diffusion components, and backend evidence. Architecture/quantization/engine-feature support comes from `EngineCapabilitySource`; until Tasks 11–12 wire the native engine probes, critical support is Unknown rather than a stale favorable table.

- [ ] **Step 2: Run the descriptor test and verify failure**

Run: `./gradlew :composeApp:jvmTest --tests '*ModelDescriptorFactoryTest*' --tests '*CompatibilityCheckerTest*'`

Expected: FAIL because descriptor types and factory do not exist.

- [ ] **Step 3: Implement bounded HTTP response reading before adding config retrieval**

```kotlin
suspend inline fun <reified T> networkGetUsecase(
    endpoint: String,
    queries: Map<String, String>? = null,
    maxResponseBytes: Long = 8L * 1024L * 1024L,
): Result<T, DataError.Network>
```

Reject a declared `Content-Length` above the limit. For chunked responses, read `maxResponseBytes + 1` through Ktor's `ByteReadChannel`; return `PayloadTooLarge` before JSON decoding when the extra byte is present. Use recommendation-specific strict DTO/projection decoding with a versioned allowlist of accepted keys, bounded collection/string lengths, and exact numeric types; an unexpected field or type becomes structured invalid/Needs information evidence rather than reaching arithmetic. Preserve cancellation and existing HTTP mappings.

- [ ] **Step 4: Add a bounded-response regression test**

```kotlin
@Test
fun chunkedBodyOverLimitIsRejectedBeforeDecode() = runTest {
    val result = clientWrapper.networkGetUsecase<ModelDetailResponse>(
        endpoint = server.url("/oversized"),
        maxResponseBytes = 32L,
    )
    assertEquals(Result.Error(DataError.Network.PayloadTooLarge), result)
}
```

Add pagination tests where a valid same-origin next cursor yields a second page, an off-origin/malformed `Link` is rejected, duplicate paths across pages are invalid, cancellation stops immediately, and the 64-page/4,096-entry ceiling terminates retrieval.

- [ ] **Step 5: Add fixed-origin transformer-config retrieval**

```kotlin
@Serializable
data class TransformerConfigResponse(
    @SerialName("num_hidden_layers") val numHiddenLayers: Int? = null,
    @SerialName("num_key_value_heads") val numKeyValueHeads: Int? = null,
    @SerialName("num_attention_heads") val numAttentionHeads: Int? = null,
    @SerialName("hidden_size") val hiddenSize: Int? = null,
    @SerialName("head_dim") val headDim: Int? = null,
    @SerialName("max_position_embeddings") val maxPositionEmbeddings: Int? = null,
)
```

Require an immutable revision matching `^[0-9a-fA-F]{40,64}$`, and construct `/{owner}/{repo}/resolve/{revision}/config.json` with `URLBuilder.appendPathSegments`. Validate the configured hub origin once: production permits HTTPS `huggingface.co` and explicit app-owned allowlisted mirrors only; a package-internal test constructor may inject a loopback origin. Never accept a host or URL from model metadata or user input. Apply the 1 MiB decoded-response limit.

- [ ] **Step 6: Implement descriptor normalization**

```kotlin
sealed interface DescriptorBuildResult {
    data class Ready(val descriptor: ModelDescriptor) : DescriptorBuildResult
    data class NeedsVariant(val repositoryId: String, val reasons: List<AssessmentReason>) : DescriptorBuildResult
    data class Invalid(val reasons: List<AssessmentReason>) : DescriptorBuildResult
}
```

Use `ModelFileTreeResponse.lfs.size ?: size`, revision SHA, LFS OID/Xet hash, exact component roles, and per-field `Evidence`. Do not turn an absent value into zero. Run `CompatibilityChecker` before any footprint estimator and preserve unsupported versus unknown as distinct results.

For file-tree pagination, validate a returned `Link` against the already trusted origin and exact `/api/models/{owner}/{repo}/tree/{revision}` path, extract only the opaque cursor query value, and rebuild the next request with `URLBuilder`; never request the server-provided URL verbatim. Stop before decoding more than 4,096 entries or 64 pages.

- [ ] **Step 7: Run module tests**

Run: `./gradlew :huggingFaceManager:jvmTest :composeApp:jvmTest --tests '*ModelDescriptorFactoryTest*' --tests '*CompatibilityCheckerTest*' --tests '*BoundedResponseTest*'`

Expected: PASS.

- [ ] **Step 8: Commit metadata normalization**

```bash
git add -p -- huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/api/ClientWrapper.kt huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/api/RemoteHuggingFaceApiService.kt huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/repository/HuggingFaceRepository.kt huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/HuggingFaceApi.kt huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/model/TransformerConfigResponse.kt huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/usecase/GetModelConfigUseCase.kt huggingFaceManager/src/commonTest/kotlin/com/debanshu777/huggingfacemanager/api/BoundedResponseTest.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/ModelDescriptor.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/ModelDescriptorFactory.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/QuantizationParser.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/CompatibilityChecker.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/EngineCapabilitySource.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/ModelDescriptorFactoryTest.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/CompatibilityCheckerTest.kt
git commit -m "feat(recommendation): validate model metadata"
```

### Task 3: Replace coarse device hints with fresh capability snapshots

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/platform/DeviceSnapshot.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/platform/BackendCapabilitySource.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/DeviceSnapshotProvider.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/platform/DeviceCapabilities.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/debanshu777/caraml/core/platform/DeviceCapabilities.android.kt`
- Modify: `composeApp/src/iosMain/kotlin/com/debanshu777/caraml/core/platform/DeviceCapabilities.ios.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/debanshu777/caraml/core/platform/DeviceCapabilities.jvm.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/platform/MemoryBudgetPolicy.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/platform/DeviceSnapshotPolicyTest.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/DeviceSnapshotProviderTest.kt`

**Interfaces:**
- Preserves: `DeviceCapabilities.getDeviceHints()` for legacy callers until Task 15.
- Produces: `getHardwareProfile(): HardwareProfile` and `getResourceSnapshot(): ResourceSnapshot`.
- Produces: `BackendCapabilitySource.capabilities(): List<BackendCapability>`, initially CPU-verified and GPU-unknown until runner registry probes are wired in Tasks 11–12.
- Produces: `suspend DeviceSnapshotProvider.capture(): DeviceSnapshot`, combining platform resources with `StoragePathProvider` storage on an injected non-UI dispatcher.
- Produces: pure `computeBaseBudget` and `computeStorageReserve` policies.

- [ ] **Step 1: Write failing budget and snapshot tests**

```kotlin
@Test
fun baseBudgetUsesLargestReserveAndStorageIsProfileIndependent() {
    assertEquals(
        600L,
        computeBaseBudget(allocatable = 1_000L, osThreshold = 200L, noiseP95 = 400L, minimum = 300L),
    )
    assertEquals(512L * MIB, computeStorageReserve(4L * GIB))
    assertEquals(10L * GIB, computeStorageReserve(1L * TIB))
}
```

Also test unified versus discrete pool construction, stale timestamps, missing readings, low-memory evidence, and one/two-core devices so performance-core fallback never calls `coerceIn` with an empty range. Validate `1 <= performanceCoreCount <= logicalCoreCount <= 1,024` or preserve the reading as invalid evidence.

- [ ] **Step 2: Run the tests and verify failure**

Run: `./gradlew :composeApp:jvmTest --tests '*DeviceSnapshotPolicyTest*' --tests '*DeviceSnapshotProviderTest*'`

Expected: FAIL because snapshot APIs do not exist.

- [ ] **Step 3: Add common snapshot types and provider**

```kotlin
expect class DeviceCapabilities() {
    fun getDeviceHints(): DeviceHints
    fun getHardwareProfile(): HardwareProfile
    fun getResourceSnapshot(): ResourceSnapshot
}

class DeviceSnapshotProvider(
    private val capabilities: DeviceCapabilities,
    private val backendCapabilitySource: BackendCapabilitySource,
    private val storage: StoragePathProvider,
    private val probeDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
) {
    suspend fun capture(): DeviceSnapshot = withContext(probeDispatcher) { captureBlocking() }
}
```

The provider inserts free storage, computes base host/GPU/shared budgets, stamps evidence and freshness, and never treats an unavailable measurement as zero capacity or unlimited capacity. `ResourceSnapshot.lowMemory` is nullable: null means the platform has no trustworthy current signal, not false. For unified memory, shared budget is the minimum of the independently known OS headroom and backend working-set headroom after matching reserves—never their sum. For discrete memory, host and GPU budgets remain separate and both must pass. `ResourceSnapshot` also carries `PowerPolicyState.NORMAL/POWER_SAVER/UNKNOWN` so optional calibration can decline safely without affecting the permanent model assessment.

- [ ] **Step 4: Implement Android collection**

```kotlin
actual fun getResourceSnapshot(): ResourceSnapshot {
    val context = KoinPlatform.getKoin().get<Context>()
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val info = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
    val processBytes = Debug.getPss().toLong() * 1024L
    return ResourceSnapshot(
        additionalAllocatableHostBytes = info.availMem.takeIf { it >= 0L },
        additionalAllocatableGpuBytes = readRegisteredBackendBudgetOrNull(),
        currentProcessBytes = processBytes,
        freeStorageBytes = null,
        osPressureReserveHostBytes = info.threshold.takeIf { it >= 0L },
        lowMemory = info.lowMemory,
        thermalState = readAndroidThermalState(),
        powerPolicyState = if (powerManager.isPowerSaveMode) PowerPolicyState.POWER_SAVER else PowerPolicyState.NORMAL,
        capturedAtEpochMs = currentEpochMillis(),
        evidence = listOf(Evidence.osApi("ActivityManager.MemoryInfo")),
    )
}
```

Register CPU as available. Treat package-manager Vulkan support as hardware evidence only: without a successful runner-registry probe, record Vulkan as `UNKNOWN`, never available or definitively unavailable. Tasks 11–12 replace that uncertainty with the actual compiled/registered backend list. Use `PowerManager.currentThermalStatus` when API 29+ and `UNKNOWN` otherwise.

Keep `availMem` and `threshold` separate: `additionalAllocatableHostBytes` is current availability and `osPressureReserveHostBytes` is an input to `platformReserve`. Subtract the selected reserve exactly once in `computeBaseBudget`. Negative or overflowing OS values attach `INVALID_OS_MEMORY_READING` evidence and remain null; do not silently use zero.

- [ ] **Step 5: Implement iOS collection**

```kotlin
actual fun getResourceSnapshot(): ResourceSnapshot = ResourceSnapshot(
    additionalAllocatableHostBytes = os_proc_available_memory().toLong().takeIf { it > 0L },
    additionalAllocatableGpuBytes = readMetalHeadroomOrNull(),
    currentProcessBytes = readResidentSizeOrNull(),
    freeStorageBytes = null,
    osPressureReserveHostBytes = null,
    lowMemory = null,
    thermalState = NSProcessInfo.processInfo.thermalState.toDomainState(),
    powerPolicyState = if (NSProcessInfo.processInfo.lowPowerModeEnabled) PowerPolicyState.POWER_SAVER else PowerPolicyState.NORMAL,
    capturedAtEpochMs = currentEpochMillis(),
    evidence = listOf(Evidence.osApi("os_proc_available_memory")),
)
```

Query Metal `recommendedMaxWorkingSetSize - currentAllocatedSize` when a device exists. Mark simulator GPU unavailable. Do not cache `os_proc_available_memory()`.

Check every `ULong`/`NSUInteger` to `Long` conversion before conversion and subtract Metal values only after proving `recommended >= allocated`; otherwise record unavailable evidence instead of wrapping.

- [ ] **Step 6: Implement JVM collection**

Use `OperatingSystemMXBean` when supported, `/proc/meminfo` on Linux, and `vm_stat` on macOS. Execute only fixed command/argument lists, cap captured output at 64 KiB, add a two-second timeout, close streams, and destroy a spawned command process on timeout. Do not call blocking `readText()` before the timeout can fire. Report command/API failure as low-confidence evidence and retain the existing conservative total-memory fallback.

```kotlin
actual fun getResourceSnapshot(): ResourceSnapshot = ResourceSnapshot(
    additionalAllocatableHostBytes = getAvailablePhysicalMemoryBytes(),
    additionalAllocatableGpuBytes = registeredBackendBudgetOrNull(),
    currentProcessBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
    freeStorageBytes = null,
    osPressureReserveHostBytes = null,
    lowMemory = null,
    thermalState = ThermalState.UNKNOWN,
    powerPolicyState = PowerPolicyState.UNKNOWN,
    capturedAtEpochMs = currentEpochMillis(),
    evidence = collectedEvidence,
)
```

- [ ] **Step 7: Run common and platform compilation**

Run: `./gradlew :composeApp:jvmTest --tests '*DeviceSnapshot*' :composeApp:compileKotlinIosSimulatorArm64 :composeApp:assembleDebug`

Expected: PASS.

- [ ] **Step 8: Commit capability snapshots**

```bash
git add -p -- composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/platform composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/DeviceSnapshotProvider.kt composeApp/src/androidMain/kotlin/com/debanshu777/caraml/core/platform composeApp/src/iosMain/kotlin/com/debanshu777/caraml/core/platform composeApp/src/jvmMain/kotlin/com/debanshu777/caraml/core/platform composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/platform/DeviceSnapshotPolicyTest.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/DeviceSnapshotProviderTest.kt
git commit -m "feat(recommendation): capture dynamic device resources"
```

### Task 4: Generate and estimate LLM run plans

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/WorkloadConfig.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/WorkloadConfigFactory.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/RunPlan.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/RunPlanGenerator.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/LlmFootprintEstimator.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/LlmRunPlanGeneratorTest.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/LlmFootprintEstimatorTest.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/WorkloadConfigFactoryTest.kt`

**Interfaces:**
- Produces: sealed `WorkloadConfig` and `RunPlan`, including `LlmWorkloadConfig` and `LlmRunPlan`, plus profile-neutral `PlanningSettings` for engine limits and user-permitted fallback axes.
- Produces: `WorkloadConfigFactory` that validates/clamps app settings against model and engine product limits while recording every clamp as evidence.
- Produces: `RunPlanGenerator.llmCandidates(descriptor, workload, settings): List<LlmRunPlan>` with a hard maximum of 24 candidates.
- Produces: `LlmFootprintEstimator.estimate(descriptor, plan, calibration): PlanAssessment`.

- [ ] **Step 1: Write failing candidate-bound and KV-cache tests**

```kotlin
@Test
fun llmCandidatesAreFiniteAndStartWithTheRequestedWorkload() {
    val plans = generator.llmCandidates(llmDescriptor(maxContext = 131_072), workload(context = 32_768), autoSettings())
    assertTrue(plans.size <= 24)
    assertEquals(32_768, plans.first().contextTokens)
    assertEquals(plans.distinct(), plans)
}

@Test
fun kvCacheUsesArchitectureShapeAndKvTypes() {
    val estimate = estimator.estimate(llmDescriptor(layers = 32, kvHeads = 8, headDim = 128), q8Plan(context = 4096), noCalibration())
    assertTrue(estimate.memory.host.likelyBytes > estimate.weightsBytes)
    assertEquals(Confidence.MEDIUM, estimate.confidence.memory)
}
```

- [ ] **Step 2: Run focused tests and confirm failure**

Run: `./gradlew :composeApp:jvmTest --tests '*LlmRunPlanGeneratorTest*' --tests '*LlmFootprintEstimatorTest*'`

Expected: FAIL because run-plan and estimator classes do not exist.

- [ ] **Step 3: Implement finite LLM candidate generation**

```kotlin
private val CONTEXT_BUCKETS = intArrayOf(16_384, 8_192, 4_096, 2_048, 1_024, 512)
private val BATCH_BUCKETS = intArrayOf(512, 256, 128)

fun llmCandidates(
    descriptor: LlmModelDescriptor,
    workload: LlmWorkloadConfig,
    settings: PlanningSettings,
): List<LlmRunPlan> = buildList {
    // requested plan first; then lower contexts, permitted KV types, and safe batches
}.distinct().take(MAX_LLM_PLAN_CANDIDATES)
```

Insert the exact validated requested context first even when it is not a predefined bucket; buckets are only fallback candidates below it. Respect explicit KV settings. Generate alternate KV types only for Auto. Mark every changed field in `RunPlan.compromises`; do not silently mutate the requested workload.

`WorkloadConfig` carries both requested and minimum acceptable bounds plus permitted fallback axes. `WorkloadConfigFactory` rejects nonpositive or over-limit context, sequence count, batch, prompt reserve, generation reserve, dimensions, steps, and frames before candidate generation. LLM default minimum context is 512. Diffusion uses the native architecture/engine minimum rounded to required multiples; frame-count reduction is not permitted unless the UI explicitly requested it. Clamping to a model/engine maximum is permitted only when surfaced as `WORKLOAD_CLAMPED`; it never changes a user-selected diffusion resolution in the requested plan.

- [ ] **Step 4: Implement checked LLM interval estimation**

Use exact file bytes for weights. Compute KV bytes as:

```text
layers * kvHeads * headDim * context * sequenceCount * (bytesPerK + bytesPerV)
```

For the chat workload `sequenceCount` is one unless parallel sequences are explicitly requested; `n_batch`/`n_ubatch` affect graph and compute buffers, not persistent KV multiplicity. Represent GGML block formats as rational bytes per element (`F16=2/1`, `Q8_0=34/32`, `Q4_0=18/32`) and use checked multiplication before division. Add recurrent-state intervals for hybrid descriptors. When shape is absent, emit `MISSING_MODEL_SHAPE` and widen only KV/graph bounds; do not reuse a hard-coded architecture shape as exact evidence.

Assign CPU-only weights to host and unified-backend weights to the shared pool. For a discrete backend before native preflight, estimate layer offload from explicit layer count and non-layer tensor overhead, with independent host/GPU intervals; never divide total file bytes by total RAM+VRAM. If layer distribution is unknown, return low-confidence widened pool bounds or Needs information when no safe upper pool bound exists. A successful Task 11 native per-pool report supersedes this analytical split for the exact file/config.

- [ ] **Step 5: Add monotonic property tests**

```kotlin
@Test
fun largerContextNeverReducesEstimatedMemory() {
    for (context in listOf(512, 1024, 2048, 4096, 8192)) {
        val current = estimator.estimate(descriptor, plan(context), noCalibration()).memory.host
        assertTrue(current.lowBytes >= previous.lowBytes)
        previous = current
    }
}
```

- [ ] **Step 6: Run focused tests**

Run: `./gradlew :composeApp:jvmTest --tests '*LlmRunPlanGeneratorTest*' --tests '*LlmFootprintEstimatorTest*'`

Expected: PASS.

- [ ] **Step 7: Commit LLM planning**

```bash
git add -p -- composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation
git commit -m "feat(recommendation): estimate LLM run plans"
```

### Task 5: Generate and estimate diffusion image/video plans

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/DiffusionFootprintEstimator.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/RunPlanGenerator.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/WorkloadConfig.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/RunPlan.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/DiffusionRunPlanGeneratorTest.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/DiffusionFootprintEstimatorTest.kt`

**Interfaces:**
- Produces: `DiffusionWorkloadConfig`, `DiffusionRunPlan`, and `DiffusionMode.IMAGE/VIDEO`.
- Produces: `RunPlanGenerator.diffusionCandidates(descriptor, workload, settings): List<DiffusionRunPlan>` with a hard maximum of 12.
- Produces: `DiffusionFootprintEstimator.estimate(descriptor, plan, calibration): PlanAssessment`.

- [ ] **Step 1: Write failing component, resolution, and fallback tests**

```kotlin
@Test
fun lowerResolutionIsDisclosedAndNeverThePreferredCandidate() {
    val plans = generator.diffusionCandidates(fluxDescriptor(), imageWorkload(1024, 1024), autoSettings())
    assertEquals(1024, plans.first().width)
    assertTrue(plans.filter { it.width < 1024 }.all { PlanCompromise.LOWER_RESOLUTION in it.compromises })
    assertTrue(plans.size <= 12)
}

@Test
fun videoFramesIncreaseOutputAndActivationHighBound() {
    val short = estimator.estimate(wanDescriptor(), videoPlan(frames = 8), noCalibration())
    val long = estimator.estimate(wanDescriptor(), videoPlan(frames = 32), noCalibration())
    assertTrue(long.memory.shared.highBytes >= short.memory.shared.highBytes)
}
```

- [ ] **Step 2: Run focused tests and verify failure**

Run: `./gradlew :composeApp:jvmTest --tests '*DiffusionRunPlanGeneratorTest*' --tests '*DiffusionFootprintEstimatorTest*'`

Expected: FAIL because diffusion plan types and estimator do not exist.

- [ ] **Step 3: Implement finite diffusion candidates**

```kotlin
fun diffusionCandidates(
    descriptor: DiffusionModelDescriptor,
    workload: DiffusionWorkloadConfig,
    settings: PlanningSettings,
): List<DiffusionRunPlan> = listOfNotNull(
    preferredPlan,
    preferredPlan.copy(vaeTiling = true, compromises = setOf(PlanCompromise.VAE_TILING)),
    streamingPlan.takeIf { descriptor.supportsStreaming },
    lowerResolutionProposal,
).distinct().take(MAX_DIFFUSION_PLAN_CANDIDATES)
```

Generate normal, VAE-tiled, auto-fit/max-VRAM, and layer-streaming plans only when supported. A resolution fallback preserves aspect ratio, rounds to the engine-required multiple, never falls below `WorkloadConfig` minimums, never reduces video frame count without explicit permission, and remains a proposal requiring user acceptance.

- [ ] **Step 4: Implement diffusion intervals**

Sum validated component weights by role. Estimate activation ranges from a versioned architecture table keyed by `SdArchitecture`, pixels, batch, and frames. Keep weights and activations separate in `PlanAssessment`. Unknown architecture with no native/component evidence returns Needs information rather than the old 3.5 GB favorable fallback.

```kotlin
val pixelUnits = when (val value = checkedMultiply(plan.width.toLong(), plan.height.toLong())) {
    is CheckedLong.Value -> value.value
    is CheckedLong.Invalid -> return PlanAssessment.invalid(value.reason)
}
val frameUnits = when (val value = checkedMultiply(pixelUnits, plan.frameCount.toLong())) {
    is CheckedLong.Value -> value.value
    is CheckedLong.Invalid -> return PlanAssessment.invalid(value.reason)
}
val activation = coefficients.forArchitecture(descriptor.architecture)
    ?.estimate(frameUnits, plan)
    ?: return PlanAssessment.needsInformation(AssessmentReason.UNKNOWN_ARCHITECTURE)
```

Allocate component/activation ranges to host, shared, or discrete GPU pools according to the actual plan (`offloadToCpu`, keep-CLIP/VAE-on-CPU, max-VRAM, and streaming). Before native evidence, streaming reduces only the versioned layer-resident portion—not text encoder/VAE/backend overhead. If a component role or allocation destination is ambiguous, widen that pool or return Needs information; never combine host and discrete VRAM into one budget. Task 12 native backend specs supersede the analytical split for the exact bundle/config.

- [ ] **Step 5: Run focused tests**

Run: `./gradlew :composeApp:jvmTest --tests '*DiffusionRunPlanGeneratorTest*' --tests '*DiffusionFootprintEstimatorTest*'`

Expected: PASS.

- [ ] **Step 6: Commit diffusion planning**

```bash
git add -p -- composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation
git commit -m "feat(recommendation): estimate diffusion run plans"
```

### Task 6: Implement performance prediction and constraint-first policy

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/PerformanceEstimator.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/SuitabilityEngine.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/RunPlanOptimizer.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/RecommendationPolicy.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/RecommendationProfile.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/PerformanceEstimatorTest.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/SuitabilityEngineTest.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/RunPlanOptimizerTest.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/RecommendationPolicyTest.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/RecommendationPolicyPropertyTest.kt`

**Interfaces:**
- Produces: `CalibrationSource.backendProfileFor(backend): BackendPerformanceProfile?`, `correctionFor(CalibrationKey): CalibrationCorrection?`, and monotonic `revision(): Long` for cache invalidation, plus `NoCalibrationSource` returning unknown/no correction until Task 14.
- Produces: `PerformanceEstimator.estimate(descriptor, plan, hardware, calibration): PerformanceEstimate`.
- Produces: `SuitabilityEngine.assessPlans(descriptor, hardwareProfile, workload): AssessedPlans`, which checks compatibility first and estimates every bounded candidate without user preferences, plus `assemble(assessedPlans, resourceSnapshot): ModelAssessment` for current base budgets/evidence.
- Produces: `RunPlanOptimizer.select(assessment, snapshot, profile): SelectedPlan`, which applies policy and preserves candidate order as the final plan tie-breaker.
- Produces: `RecommendationPolicy.recommend(assessment, snapshot, profile): PersonalizedRecommendation`.
- Produces: `RecommendationSortKey` implementing category, confidence, utility, headroom, stable-ID order.

- [ ] **Step 1: Write the complete policy mapping as failing table tests**

```kotlin
@Test
fun mapsFitBandByRiskProfile() {
    assertCategory(FitBand.COMFORTABLE, CONSERVATIVE, RECOMMENDED)
    assertCategory(FitBand.COMFORTABLE, BALANCED, RECOMMENDED)
    assertCategory(FitBand.COMFORTABLE, EXPERIMENTAL, RECOMMENDED)
    assertCategory(FitBand.LIKELY, CONSERVATIVE, RISKY)
    assertCategory(FitBand.LIKELY, BALANCED, USABLE)
    assertCategory(FitBand.LIKELY, EXPERIMENTAL, RECOMMENDED)
    assertCategory(FitBand.BORDERLINE, CONSERVATIVE, NOT_SUITABLE)
    assertCategory(FitBand.BORDERLINE, BALANCED, RISKY)
    assertCategory(FitBand.BORDERLINE, EXPERIMENTAL, RISKY)
    assertCategory(FitBand.NO_FIT, CONSERVATIVE, NOT_SUITABLE)
    assertCategory(FitBand.NO_FIT, BALANCED, NOT_SUITABLE)
    assertCategory(FitBand.NO_FIT, EXPERIMENTAL, NOT_SUITABLE)
}
```

Add tests proving Incompatible and Needs information precede fitting, fallback plans cap at Usable, Conservative+Medium safety evidence caps at Usable, every Low safety-confidence result caps at Risky, low performance confidence yields `SPEED_NOT_VERIFIED`, storage reserve is profile-independent, and performance/quality cannot upgrade a worse safety category.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `./gradlew :composeApp:jvmTest --tests '*PerformanceEstimatorTest*' --tests '*SuitabilityEngineTest*' --tests '*RunPlanOptimizerTest*' --tests '*RecommendationPolicyTest*' --tests '*RecommendationPolicyPropertyTest*'`

Expected: FAIL because estimator and policy do not exist.

- [ ] **Step 3: Implement versioned policy constants and fit calculation**

```kotlin
internal object RecommendationPolicyV1 {
    val memoryReservePercent = mapOf(CONSERVATIVE to 25, BALANCED to 15, EXPERIMENTAL to 5)
    val llmDecodeTarget = mapOf(SPEED_EFFICIENCY to 8.0, BALANCED to 4.0, QUALITY_CONTEXT to 2.0)
    val llmDecodeHardMinimum = mapOf(SPEED_EFFICIENCY to 4.0, BALANCED to 2.0, QUALITY_CONTEXT to 1.0)
    val diffusionSecondsTarget = mapOf(SPEED_EFFICIENCY to 30.0, BALANCED to 90.0, QUALITY_CONTEXT to 180.0)
    val diffusionSecondsHardMaximum = mapOf(SPEED_EFFICIENCY to 60.0, BALANCED to 180.0, QUALITY_CONTEXT to 360.0)
    const val RESOURCE_SNAPSHOT_MAX_AGE_MS = 30_000L
    const val NATIVE_PREFLIGHT_MAX_AGE_MS = 30_000L
    val categoryRank = mapOf(
        RECOMMENDED to 0,
        USABLE to 1,
        RISKY to 2,
        NEEDS_INFORMATION to 3,
        NOT_SUITABLE to 4,
        INCOMPATIBLE to 5,
    )
}

internal fun fitBand(range: EstimateRange, policyBudget: Long): FitBand = when {
    range.highBytes <= policyBudget -> FitBand.COMFORTABLE
    range.likelyBytes <= policyBudget -> FitBand.LIKELY
    range.lowBytes <= policyBudget -> FitBand.BORDERLINE
    else -> FitBand.NO_FIT
}
```

Derive `policyBudget` with Task 1's integer `checkedPercentage(baseBudget, 100 - reservePercent)` and floor fractional bytes; do not multiply a byte count by `Double`.

- [ ] **Step 4: Implement roofline prediction with honest unknowns**

```kotlin
val backendProfile = calibration.backendProfileFor(plan.backend)
    ?: return PerformanceEstimate.Unknown(AssessmentReason.SPEED_NOT_VERIFIED)
val seconds = maxOf(
    bytesMoved / backendProfile.sustainedBytesPerSecond,
    operations / backendProfile.sustainedOperationsPerSecond,
) * correction.likely
```

Return `PerformanceEstimate.Unknown` when bandwidth/compute inputs are absent. Apply weighted-median/P90 correction only through `CalibrationSource`; do not infer speed from core count alone.
Reject non-finite/nonpositive bandwidth, operation, duration, and correction inputs before division; cap derived durations/rates at versioned display/serialization bounds and return invalid evidence on overflow.

`PerformanceEstimate.Llm` contains prompt tokens/second, decode tokens/second, time-to-first-token, and load-time ranges. `PerformanceEstimate.DiffusionImage` contains seconds/step and total-time ranges for the exact dimensions/steps; `DiffusionVideo` also contains seconds/frame and total time. Each range carries provenance/confidence independently.

For diffusion category policy, compare a checked normalization to the 512×512×20-step reference target while displaying the predicted total for the user's actual workload. Keep video speed non-blocking until a comparable video calibration bucket exists; memory and compatibility gates still apply. Missing bandwidth, compute, energy, or benchmark-quality inputs are omitted with explicit evidence rather than filled with core-count heuristics.

- [ ] **Step 5: Implement policy selection and utility**

Select among already-assessed candidates. Compute the weighted geometric mean using the exact vectors from the spec—Speed `(performance=.60, energy=.25, quality=.05, context=.00, storage=.10)`, Balanced `(.30,.10,.30,.20,.10)`, and Quality `(.10,.05,.55,.25,.05)`—with each available metric clamped to `[0.05, 1.0]` and missing dimensions omitted before weight renormalization. Produce ordered `AssessmentReason` values and a visible tight-fit warning for Experimental+Likely. A fallback plan is capped at Usable. A missed performance target downgrades one category; performance below the minimum can produce Not suitable only with at least Medium performance confidence, otherwise return Risky with `PERFORMANCE_UNCERTAIN`.

Version 1 defines the hard experience floor as half the LLM target throughput and twice the diffusion target duration, as shown in `llmDecodeHardMinimum`/`diffusionSecondsHardMaximum`. A target miss downgrades along Recommended → Usable → Risky → Not suitable; crossing the hard floor yields Not suitable only with at least Medium confidence. Keep these product thresholds versioned and fixture-tested, not scattered through UI code.

Build the confidence portion of `RecommendationSortKey` as `(minimumOf(compatibility, memory, storage), performance)` in descending order; this prevents high speed confidence from masking weak safety evidence while still preferring verified performance among equally safe models. Headroom compares the worst normalized required memory pool so unified and discrete budgets are never added together.

Apply evidence caps after the fit-band table and before performance utility: High safety confidence has no extra cap; Medium safety confidence caps Conservative at Usable but does not cap Balanced/Experimental; Low safety confidence caps every profile at Risky. `Compatibility.Unknown` or a missing safe memory/storage upper bound is not Low—it is Needs information. These caps can only worsen a provisional category.

```kotlin
fun recommend(
    assessment: ModelAssessment,
    snapshot: DeviceSnapshot,
    profile: RecommendationProfile,
): PersonalizedRecommendation {
    val compatible = assessment.compatibility
    if (compatible is Compatibility.Incompatible) return incompatible(compatible)
    if (compatible is Compatibility.Unknown) return needsInformation(compatible)
    val selected = runPlanOptimizer.select(assessment, snapshot, profile)
    return classify(selected, profile)
}
```

- [ ] **Step 6: Add generated monotonic invariants**

Use deterministic loops over 1,000 seeded combinations rather than a new property-test dependency. Assert that increasing estimated memory cannot improve category, increasing budget cannot worsen it, lowering compatibility/memory/storage confidence cannot improve it, and Experimental is never stricter than Conservative for identical facts. Lower performance confidence may turn a confidently too-slow Not suitable result into Risky/`PERFORMANCE_UNCERTAIN`, but it can never produce Usable or Recommended.

- [ ] **Step 7: Run focused tests**

Run: `./gradlew :composeApp:jvmTest --tests '*PerformanceEstimatorTest*' --tests '*SuitabilityEngineTest*' --tests '*RunPlanOptimizerTest*' --tests '*RecommendationPolicyTest*' --tests '*RecommendationPolicyPropertyTest*'`

Expected: PASS.

- [ ] **Step 8: Commit policy and performance prediction**

```bash
git add -p -- composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation
git commit -m "feat(recommendation): classify device fit by policy"
```

### Task 7: Add assessment repository, cache keys, and shadow comparison

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/ModelAssessmentRepository.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/LegacySuitabilityAdapter.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/RecommendationRolloutMode.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/di/AppModule.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/ModelAssessmentRepositoryTest.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/LegacySuitabilityAdapterTest.kt`

**Interfaces:**
- Produces: `suspend fun assess(descriptor, snapshot, workload): ModelAssessment`.
- Produces: `fun personalize(assessment, snapshot, profile): PersonalizedRecommendation`.
- Produces: `fun invalidate(fileIdentity: ModelFileIdentity)`.
- Produces: rollout modes `LEGACY`, `SHADOW`, and `V2`; debug starts in `SHADOW`, while release remains `LEGACY` until Task 15 gates pass.

- [ ] **Step 1: Write failing cache and cancellation tests**

```kotlin
@Test
fun concurrentEqualAssessmentsShareOneCalculation() = runTest {
    val results = awaitAll(
        async { repository.assess(descriptor, snapshot, workload) },
        async { repository.assess(descriptor, snapshot, workload) },
    )
    assertEquals(1, estimator.invocationCount)
    assertEquals(results[0], results[1])
}

@Test
fun profileChangeDoesNotRecalculateObjectiveAssessment() = runTest {
    val assessment = repository.assess(descriptor, snapshot, workload)
    repository.personalize(assessment, snapshot, conservativeProfile())
    repository.personalize(assessment, snapshot, experimentalProfile())
    assertEquals(1, estimator.invocationCount)
}
```

- [ ] **Step 2: Run focused tests and verify failure**

Run: `./gradlew :composeApp:jvmTest --tests '*ModelAssessmentRepositoryTest*' --tests '*LegacySuitabilityAdapterTest*'`

Expected: FAIL because repository and adapter do not exist.

- [ ] **Step 3: Implement bounded in-memory caches and single-flight work**

```kotlin
data class AssessmentCacheKey(
    val identity: ModelFileIdentity,
    val workload: WorkloadConfig,
    val hardwareFingerprint: HardwareFingerprint,
    val engineVersion: String,
    val estimatorVersion: Int,
    val calibrationRevision: Long,
)

class ModelAssessmentRepository(
    private val suitabilityEngine: SuitabilityEngine,
    private val recommendationPolicy: RecommendationPolicy,
) {
    private val inFlight = mutableMapOf<AssessmentCacheKey, RefCountedAssessment>()
    private val estimateCache = LinkedHashMap<AssessmentCacheKey, AssessedPlans>()
}
```

Guard maps with `Mutex`, reference-count consumers, cancel deferred work when the final consumer leaves, remove cancelled/failed deferred values, and never cache a dynamic category or `ResourceSnapshot`. Implement common-Kotlin LRU behavior by removing/reinserting hits in the standard `LinkedHashMap` and evicting the eldest insertion whenever size exceeds 128; do not depend on Android's `LruCache`. Reuse immutable plan estimates by hardware fingerprint, then assemble a fresh profile-neutral `ModelAssessment` with the caller's current snapshot/base budgets. Increment `calibrationRevision` after every accepted observation/prune/reset so corrected estimates cannot remain stale. Preflight cache keys additionally include verified file identity, all run-plan fields, backend, engine version, and estimator version.

- [ ] **Step 4: Implement shadow output without user content**

`LegacySuitabilityAdapter` maps v2 categories to current v1 visual tiers only while old components remain. In debug builds, `SHADOW` computes both algorithms and records only model identity digest, old/new enum names, reason codes, and estimator version through `AppLogger`; it never logs repository paths or metadata payloads. Release uses `LEGACY` until Task 15 explicitly promotes v2.

```kotlin
AppLogger.d(TAG) {
    "shadow id=${identity.stableDigest} old=${legacy.name} new=${v2.category.name} " +
        "reasons=${v2.reasons.joinToString(",") { it.name }} estimator=$estimatorVersion"
}
```

- [ ] **Step 5: Wire Koin and run tests**

```kotlin
single<EngineCapabilitySource> { UnknownEngineCapabilitySource }
single<CalibrationSource> { NoCalibrationSource }
single { ModelAssessmentRepository(get(), get()) }
single { RecommendationPolicy(get()) }
```

The conservative source is temporary production wiring: it reports format/component facts but returns Unknown for native architecture/quantization support. Task 11 replaces this single binding with `RunnerEngineCapabilitySource` rather than registering a duplicate Koin definition.

Run: `./gradlew :composeApp:jvmTest --tests '*ModelAssessmentRepositoryTest*' --tests '*LegacySuitabilityAdapterTest*'`

Expected: PASS.

- [ ] **Step 6: Commit the repository boundary**

```bash
git add -p -- composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/di/AppModule.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation
git commit -m "feat(recommendation): coordinate cached assessments"
```

### Task 8: Persist preferences and add first-open profile onboarding

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/settings/AppSettings.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/settings/SettingsRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/settings/DefaultSettingsRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/settings/presentation/SettingsViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/settings/presentation/SettingsScreen.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/settings/presentation/RecommendationProfileSection.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/RecommendationProfileDialog.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/SearchScreen.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/data/settings/DefaultSettingsRepositoryTest.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/RecommendationProfileStateTest.kt`

**Interfaces:**
- Adds to `AppSettings`: `riskTolerance`, `optimizationPriority`, and `modelProfileOnboardingComplete`.
- Produces atomic repository methods `updateRecommendationProfile(profile)` and `completeModelProfileOnboarding(profile)`.
- Produces ViewModel methods with the same intent names.

- [ ] **Step 1: Add failing settings persistence tests**

```kotlin
@Test
fun unknownPreferenceNamesFallBackToBalanced() = runTest {
    store.edit { it[stringPreferencesKey("model_risk_tolerance")] = "REMOVED_VALUE" }
    val settings = repository.getSettings().first()
    assertEquals(RiskTolerance.BALANCED, settings.riskTolerance)
    assertEquals(OptimizationPriority.BALANCED, settings.optimizationPriority)
}
```

Also verify onboarding defaults false, dismiss persists Balanced and completion atomically, and updating the profile does not overwrite temperature/KV/GPU settings.

- [ ] **Step 2: Run tests and verify failure**

Run: `./gradlew :composeApp:jvmTest --tests '*DefaultSettingsRepositoryTest*' --tests '*RecommendationProfileStateTest*'`

Expected: FAIL because recommendation preferences are absent.

- [ ] **Step 3: Add DataStore keys and atomic intent methods**

```kotlin
suspend fun completeModelProfileOnboarding(profile: RecommendationProfile) {
    dataStore.edit { prefs ->
        prefs[riskKey] = profile.riskTolerance.name
        prefs[priorityKey] = profile.optimizationPriority.name
        prefs[onboardingCompleteKey] = true
    }
}
```

Do not implement these intents by reading and rewriting the entire `AppSettings`; update only the three relevant keys to avoid lost updates.

- [ ] **Step 4: Implement reusable profile controls and first-open dialog**

```kotlin
@Composable
fun RecommendationProfileDialog(
    initial: RecommendationProfile,
    onContinue: (RecommendationProfile) -> Unit,
    onDismissWithBalanced: () -> Unit,
)
```

Show three risk choices and three optimization choices with Balanced selected. Dismiss calls `onDismissWithBalanced` and completes onboarding. Add the same controls to Settings and a compact profile action in the Models tab. Provide content descriptions that state the chosen risk and priority.

Keep first-open presentation behind the v2-capable rollout branch during Tasks 8–14 so release users do not choose a profile while the legacy algorithm is still authoritative. Task 15 enables the branch and onboarding together after the release gates pass.

- [ ] **Step 5: Add state tests for one-time presentation**

```kotlin
@Test
fun dialogShowsOnlyUntilOnboardingIsCompleted() {
    assertTrue(profileUiState(settings(onboardingComplete = false)).showDialog)
    assertFalse(profileUiState(settings(onboardingComplete = true)).showDialog)
}
```

- [ ] **Step 6: Run tests and compile Compose**

Run: `./gradlew :composeApp:jvmTest --tests '*DefaultSettingsRepositoryTest*' --tests '*RecommendationProfileStateTest*' :composeApp:compileKotlinJvm`

Expected: PASS.

- [ ] **Step 7: Commit profile persistence and UI**

```bash
git add -p -- composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/settings/AppSettings.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/settings/SettingsRepository.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/settings/DefaultSettingsRepository.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/settings/presentation/SettingsViewModel.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/settings/presentation/SettingsScreen.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/settings/presentation/RecommendationProfileSection.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/RecommendationProfileDialog.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/SearchScreen.kt composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/data/settings/DefaultSettingsRepositoryTest.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/RecommendationProfileStateTest.kt
git commit -m "feat(modelhub): add recommendation profiles"
```

### Task 9: Add bounded metadata enrichment and personalized ranking

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/domain/ModelMetadataSource.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/domain/HuggingFaceModelMetadataSource.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/domain/RecommendationQuerySession.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/domain/ModelRecommendationService.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/di/AppModule.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/ModelViewModel.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/domain/ModelRecommendationServiceTest.kt`

**Interfaces:**
- Produces: testable `ModelMetadataSource.describeVariants(repositoryId, mode): RepositoryVariantSet`, containing exact descriptors or a conservative Select-variant result.
- Produces: query-scoped `RecommendationQuerySession.state: StateFlow<List<RecommendedModelUiState>>` plus `ModelRecommendationService.evaluateInitial(session, profile)`, `evaluateMore(session, profile)`, and `rerank(session, profile)`.
- Produces: `RecommendedModelUiState` with descriptor state, objective assessment, personalized result, and stable source index.

- [ ] **Step 1: Write failing window, concurrency, and stable-order tests**

```kotlin
@Test
fun evaluatesBoundedWindowsWithStableTies() = runTest {
    val session = service.startQuery("query-1", models(100, identicalAssessments = true), workload)
    assertEquals(48, session.state.value.count { it.descriptorState == DescriptorState.CHECKING })
    service.evaluateInitial(session, balancedProfile())
    val first = session.state.value
    assertEquals(48, metadataSource.requestCount)
    assertTrue(metadataSource.maxConcurrent <= 4)
    assertEquals(first.sortedBy { it.stableModelId }, first.sortedWith(stableTieComparator))

    service.evaluateMore(session, balancedProfile())
    assertEquals(72, metadataSource.requestCount)
}
```

Add cancellation tests proving a superseded query stops enrichment and a profile-only change performs zero metadata requests.

Add variant tests proving a repository with Q4/Q5/Q8 descriptors names and selects the highest-policy candidate, an incompatible smaller variant cannot win, and 65 runnable variants or an ambiguous diffusion component graph remain Needs information/Select a variant rather than being sampled.

- [ ] **Step 2: Run focused test and verify failure**

Run: `./gradlew :composeApp:jvmTest --tests '*ModelRecommendationServiceTest*'`

Expected: FAIL because metadata source and service do not exist.

- [ ] **Step 3: Implement the production metadata adapter**

Fetch detail, filtered file tree, and optional transformer config through `HuggingFaceApi`. Cache immutable revision+file descriptors in `ModelAssessmentRepository`; return Needs information on ordinary network/metadata failure. For every cross-repository diffusion setup component, fetch that component repository's detail and exact tree entry as part of the same bounded four-call concurrency budget, and attach its own validated revision/object identity. A missing component commit or exact file entry makes the bundle Needs information and prevents an unpinned download. Rethrow cancellation.

```kotlin
interface ModelMetadataSource {
    suspend fun describeVariants(repositoryId: String, mode: ModelHubBrowseMode): RepositoryVariantSet
}
```

Group sharded GGUF files into one immutable variant identity and reject incomplete/duplicate shard sets. Assess all runnable descriptors only when the set contains at most 64 variants. Do not synthesize diffusion component combinations: accept an explicit supported setup/manifest or return Select a variant. The repository card may display the best personalized assessed variant only when the full runnable set was evaluated, and must name that filename/bundle.

- [ ] **Step 4: Implement bounded local ranking**

```kotlin
private val enrichmentGate = Semaphore(4)

suspend fun evaluateInitial(session: RecommendationQuerySession, profile: RecommendationProfile) {
    enrichAndEmit(session, targetCount = minOf(48, session.models.size), profile)
}

suspend fun evaluateMore(session: RecommendationQuerySession, profile: RecommendationProfile) {
    enrichAndEmit(session, targetCount = minOf(session.evaluatedCount + 24, 96, session.models.size), profile)
}

suspend fun rerank(session: RecommendationQuerySession, profile: RecommendationProfile) =
    rank(session.objectiveAssessmentsWithFreshSnapshot(), profile)
```

The session publishes the initial window as Checking before starting enrichment, so first paint never awaits network or estimators. It owns immutable query ID, model/source-index list, and workload plus mutex-protected snapshot/evaluated-count/objective-result state. Do not keep one mutable `evaluatedCount` on the singleton service. Emit each completed assessment incrementally; with Personalized ordering, pending cards follow assessed cards in original order, while server ordering never reorders and only updates chips. A profile change reuses descriptors and plan estimates; if the 30-second resource snapshot is stale, refresh only local device/storage facts off the UI thread and rebuild the cheap profile-neutral assessment—perform zero metadata requests, native calls, or calibration. Use `supervisorScope` so one malformed repository becomes Needs information without cancelling peers, but rethrow `CancellationException`. Preserve the stable repository/file identifier—not mutable arrival order—as the final ranking tie-breaker, with source index used only to render pending cards predictably.

- [ ] **Step 5: Replace only ModelViewModel orchestration**

Inject `ModelRecommendationService` and `SettingsRepository`. Add `recommendedModels: StateFlow<List<RecommendedModelUiState>>` and a local ordering selection; do not put estimator math back into the ViewModel. Standard server sorts still call `loadModels()` unchanged.

```kotlin
private var recommendationJob: Job? = null

private fun startRecommendations(models: List<ListModelsResponse.Model>, workload: WorkloadConfig) {
    recommendationJob?.cancel()
    val session = recommendationService.startQuery(queryKey(), models, workload)
    recommendationJob = viewModelScope.launch {
        coroutineScope {
            launch { session.state.collectLatest(_recommendedModels::emit) }
            launch { recommendationService.evaluateInitial(session, settings.value.recommendationProfile) }
        }
    }
}
```

- [ ] **Step 6: Run focused and ViewModel tests**

Run: `./gradlew :composeApp:jvmTest --tests '*ModelRecommendationServiceTest*' --tests '*ModelViewModel*'`

Expected: PASS.

- [ ] **Step 7: Commit enrichment and ranking**

```bash
git add -p -- composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/domain composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/ModelViewModel.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/di/AppModule.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/domain/ModelRecommendationServiceTest.kt
git commit -m "feat(modelhub): rank models for this device"
```

### Task 10: Replace rating UI and enforce download admission

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/domain/DownloadAdmissionPolicy.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/domain/DownloadStorageEstimator.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/rating/ui/RecommendationDetailsSheet.kt`
- Create: `huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadArtifactIdentity.kt`
- Create: `huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/ArtifactManifestStore.kt`
- Modify: `huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadMetadataDTO.kt`
- Modify: `huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadProgressDTO.kt`
- Modify: `huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadManager.kt`
- Modify: `huggingFaceManager/src/androidMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadManager.android.kt`
- Modify: `huggingFaceManager/src/iosMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadManager.ios.kt`
- Modify: `huggingFaceManager/src/jvmMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadManager.jvm.kt`
- Modify: `huggingFaceManager/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/rating/ui/SuitabilityInfoSheet.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/rating/ui/SuitabilityChip.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/rating/ui/SuitabilityDot.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/rating/ui/SuitabilityColors.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/SortFilterChips.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/ModelListItem.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/components/GgufFileListItem.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/components/VariantPickerRow.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/components/InstallBundleCard.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/components/ModelDetailContent.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/DetailsScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/SearchScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/ModelViewModel.kt`
- Modify: `composeApp/build.gradle.kts`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/domain/DownloadAdmissionPolicyTest.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/domain/DownloadStorageEstimatorTest.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/RecommendationPresentationTest.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/rating/ui/RecommendationComponentsUiTest.kt`
- Test: `huggingFaceManager/src/commonTest/kotlin/com/debanshu777/huggingfacemanager/download/ArtifactManifestStoreTest.kt`
- Test: `huggingFaceManager/src/jvmTest/kotlin/com/debanshu777/huggingfacemanager/download/DownloadManagerJvmTest.kt`

**Interfaces:**
- Produces: `DownloadAdmission.Allowed`, `.ConfirmationRequired`, and `.Blocked`.
- Produces: `DownloadStorageEstimator.estimate(descriptor, localInventory, layout): StorageRequirement`, grouped by filesystem/volume.
- Changes UI inputs from `SuitabilityRating` to `PersonalizedRecommendation` while preserving a temporary adapter overload.
- Adds local ordering `Recommended for me` without serializing it as a Hugging Face `ModelSort`.
- Changes every download target from mutable `main` to the descriptor's validated immutable revision and emits an atomic identity sidecar without changing `AppDatabase`.

- [ ] **Step 1: Write failing download admission tests**

```kotlin
@Test
fun storageNoFitAlwaysBlocksButRuntimeNoFitCanDownloadForLater() {
    assertIs<DownloadAdmission.Blocked>(policy.decide(storageNoFit(), allowForLater = true))
    assertIs<DownloadAdmission.ConfirmationRequired>(policy.decide(runtimeNoFit(), allowForLater = true))
    assertIs<DownloadAdmission.Allowed>(policy.decide(recommended(), allowForLater = false))
}
```

Add storage tests for a new single file, replacement while the old final file exists, partially present component bundles, `.part` files colocated with final files, and a synthetic split-filesystem layout. Validate all sizes with checked arithmetic; a missing or overflowing size returns Needs information/Blocked rather than zero.

Add download identity tests that reject a blank/mutable/malformed revision, mismatched model/path arguments, invalid object IDs, an over-256-KiB manifest, more than 64 entries, duplicate logical roles/paths, and a digest or byte-count mismatch. Prove a network interruption before commit publishes neither a final file nor a manifest entry, a successful replacement preserves the old generation until the new file and manifest are durable, and a component-bundle digest is independent of local absolute paths and input ordering. Simulate restart after every commit-journal phase and prove recovery deterministically completes a valid new generation or restores the previous one.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `./gradlew :composeApp:jvmTest --tests '*DownloadAdmissionPolicyTest*' --tests '*DownloadStorageEstimatorTest*' --tests '*RecommendationPresentationTest*' --tests '*RecommendationComponentsUiTest*' :huggingFaceManager:jvmTest --tests '*ArtifactManifestStoreTest*' --tests '*DownloadManagerJvmTest*'`

Expected: FAIL because admission, artifact identity, and presentation mapping do not exist.

- [ ] **Step 3: Implement fixed-order admission**

```kotlin
fun decide(result: PersonalizedRecommendation, allowForLater: Boolean): DownloadAdmission {
    if (result.storageFit == FitBand.NO_FIT) {
        return DownloadAdmission.Blocked(AssessmentReason.INSUFFICIENT_STORAGE)
    }
    return when (result.category) {
        INCOMPATIBLE -> DownloadAdmission.Blocked(AssessmentReason.INCOMPATIBLE_MODEL)
        NOT_SUITABLE -> if (allowForLater) {
            DownloadAdmission.ConfirmationRequired(AssessmentReason.DOWNLOAD_FOR_LATER)
        } else {
            DownloadAdmission.Blocked(AssessmentReason.NO_RUN_PLAN)
        }
        else -> DownloadAdmission.Allowed
    }
}
```

Refresh storage and regenerate the personalized result immediately before calling the existing `DownloadManager`.

Treat this as an admission snapshot, not a reservation: retain the download manager's in-stream storage/error checks and map a later `InsufficientStorageException` to the same stable user result. Use a ref-counted keyed mutex to serialize downloads that update the same validated model-root manifest (not merely the same filename), remove unused locks, and reuse `.part`/atomic-rename behavior so retries remain idempotent without an unbounded lock map.

`DownloadStorageEstimator` counts only missing final components, accounts for the largest concurrent temporary/replacement file, and compares requirements per volume after the profile-independent reserve. Current Android/iOS/JVM downloaders place `.part` beside the destination, so temp and final normally share one volume; retain the grouped representation so a future separate cache volume cannot be added to model storage as fictitious free space.

Extend `DownloadMetadataDTO` with a required `DownloadArtifactIdentity` copied from the assessed descriptor. It contains the validated repository ID, 40–64 hex immutable revision, normalized repository-relative path, bounded remote LFS/Xet object ID when present, and expected byte count. Refuse a call when the legacy `modelId`/`path` arguments disagree with that identity. Construct `resolve/{immutableRevision}/{relativePath}` through `URLBuilder`; never download from `main` or a metadata-provided URL.

Add Okio as an explicit catalog dependency of `huggingFaceManager`. In each downloader, stream the response through `HashingSink.sha256` while writing the existing sibling `.part`, enforce the descriptor/response byte ceiling with checked addition, flush/sync, compare a SHA-256-shaped LFS OID when the hub supplies one, and only then enter the commit transaction. Return the actual digest in the terminal `DownloadProgressDTO`.

`ArtifactManifestStore` owns a bounded fixed-name transaction journal with `PREPARED`, `OLD_PRESERVED`, and `NEW_PUBLISHED` phases. It fsyncs the new file and strict version-1 manifest, preserves an existing final/manifest as fixed `.previous` siblings, atomically renames the new pair into place, then deletes the previous generation and journal. On startup or retry, it resolves the journal before accepting another download: finish when the new generation validates, otherwise restore the previous generation. Never derive a cleanup path from journal content; reconstruct only the fixed suffixes from the already validated target. Publish terminal completion and insert the Room row only after journal removal. The manifest/journal contain no absolute path, URL, credentials, or user/device data.

For a diffusion bundle, record every component's own repository, immutable revision, logical role, relative path, object ID, byte count, and content digest, then derive the bundle identity by SHA-256 over a canonical length-prefixed serialization sorted by role/repository/path. Cap manifests at 64 entries and 256 KiB decoded. Treat a malformed/stale sidecar as unavailable evidence, never as permission to load. Keep the existing `LocalModelEntity`, `DownloadedComponentEntity`, `ModelComponentLinkEntity`, and `AppDatabase` schemas unchanged.

- [ ] **Step 4: Implement category UI and explanation sheet**

Render category, confidence, selected variant, assumed workload, memory/storage interval, expected speed, selected/fallback plan, and ordered reasons. `Checking` is a loading state, not a persisted category. Accessibility semantics must announce category, confidence, and primary reason without relying on color.

```kotlin
@Composable
fun SuitabilityChip(
    recommendation: PersonalizedRecommendation,
    onInfoClick: (() -> Unit)? = null,
)
```

Add `implementation(compose.uiTest)` to `commonTest`. Use `runComposeUiTest` to verify the onboarding dialog's selected values, progressive Checking-to-category rendering, Experimental tight-fit warning, details-sheet text, and chip semantics for category/confidence/primary reason. Do not assert colors as the only signal.

- [ ] **Step 5: Add Recommended for me ordering UI**

Create a local `ModelOrdering` sealed type with `Personalized` and `Server(ModelSort)`. Only `Server` values are sent to `ListModelsParams`. Profile changes rerank cached `RecommendedModelUiState` values without refetching.

```kotlin
sealed interface ModelOrdering {
    data object Personalized : ModelOrdering
    data class Server(val value: ModelSort) : ModelOrdering
}

private fun ModelOrdering.serverSortOrNull(): ModelSort? =
    (this as? ModelOrdering.Server)?.value
```

- [ ] **Step 6: Run tests and Compose compilation**

Run: `./gradlew :huggingFaceManager:jvmTest --tests '*ArtifactManifestStoreTest*' --tests '*DownloadManagerJvmTest*' :composeApp:jvmTest --tests '*DownloadAdmissionPolicyTest*' --tests '*DownloadStorageEstimatorTest*' --tests '*RecommendationPresentationTest*' --tests '*RecommendationComponentsUiTest*' :composeApp:compileKotlinJvm`

Expected: PASS.

- [ ] **Step 7: Commit personalized presentation and download gate**

```bash
git add -p -- gradle/libs.versions.toml huggingFaceManager/build.gradle.kts huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadArtifactIdentity.kt huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/ArtifactManifestStore.kt huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadMetadataDTO.kt huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadProgressDTO.kt huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadManager.kt huggingFaceManager/src/androidMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadManager.android.kt huggingFaceManager/src/iosMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadManager.ios.kt huggingFaceManager/src/jvmMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadManager.jvm.kt huggingFaceManager/src/commonTest/kotlin/com/debanshu777/huggingfacemanager/download/ArtifactManifestStoreTest.kt huggingFaceManager/src/jvmTest/kotlin/com/debanshu777/huggingfacemanager/download/DownloadManagerJvmTest.kt composeApp/build.gradle.kts composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/rating/ui composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/domain/DownloadAdmissionPolicyTest.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/modelhub/domain/DownloadStorageEstimatorTest.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/RecommendationPresentationTest.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/rating/ui/RecommendationComponentsUiTest.kt
git commit -m "feat(modelhub): present and enforce model recommendations"
```

### Task 11: Expose side-effect-free llama.cpp preflight

**Files:**
- Create: `runner/src/commonMain/kotlin/com/debanshu777/runner/LlamaPreflightResult.kt`
- Create: `runner/src/commonMain/kotlin/com/debanshu777/runner/NativeBackendCapability.kt`
- Create: `runner/src/commonMain/kotlin/com/debanshu777/runner/NativeModelFeatureSupport.kt`
- Modify: `runner/src/commonMain/kotlin/com/debanshu777/runner/LlamaRunner.kt`
- Modify: `runner/src/androidMain/kotlin/com/debanshu777/runner/LlamaRunner.android.kt`
- Modify: `runner/src/jvmMain/kotlin/com/debanshu777/runner/LlamaRunner.jvm.kt`
- Modify: `runner/src/iosMain/kotlin/com/debanshu777/runner/LlamaRunner.ios.kt`
- Modify: `runner/src/commonCpp/llama_runner_core.h`
- Modify: `runner/src/commonCpp/llama_runner_core.cpp`
- Modify: `runner/src/commonCpp/llama_runner_jni.cpp`
- Create: `runner/src/iosMain/cpp/llama_runner.h`
- Modify: `runner/src/iosMain/cpp/llama_runner.cpp`
- Modify: `runner/src/iosMain/cpp/llama_runner.def`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/platform/RunnerBackendCapabilitySource.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/RunnerEngineCapabilitySource.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/di/AppModule.kt`
- Test: `runner/src/commonTest/kotlin/com/debanshu777/runner/LlamaPreflightResultTest.kt`

**Interfaces:**
- Produces: `fun LlamaRunner.preflightModel(modelPath, config): LlamaPreflightResult` on every platform.
- Produces: `fun LlamaRunner.backendCapabilities(): List<NativeBackendCapability>` from `ggml_backend_dev_count`/device properties, not OS library hints.
- Produces: `fun LlamaRunner.probeModelFeatures(architecture, quantization): NativeModelFeatureSupport` tied to the pinned native engine.
- Produces: native `llama_runner_core_preflight` returning fitted context, GPU layers, and per-pool model/context/compute/free/total bytes.
- Refactors: one `resolve_fit_plan` helper shared by preflight and actual load.

- [ ] **Step 1: Write failing result-decoding tests**

```kotlin
@Test
fun malformedNativePayloadBecomesUnavailable() {
    assertIs<LlamaPreflightResult.Unavailable>(decodeLlamaPreflight(longArrayOf(1L)))
}

@Test
fun successPreservesPerPoolBreakdown() {
    val result = decodeLlamaPreflight(successPayload(hostModel = 1_000L, gpuModel = 2_000L))
    assertEquals(2, assertIs<LlamaPreflightResult.Fit>(result).report.memoryPools.size)
}
```

Also test that overlong/non-ASCII control labels are rejected before native entry, a known upstream architecture returns Supported with the current engine version, and an unknown architecture remains Unsupported/Unknown without throwing.

- [ ] **Step 2: Run runner test and verify failure**

Run: `./gradlew :runner:jvmTest --tests '*LlamaPreflightResultTest*'`

Expected: FAIL because preflight result decoding does not exist.

- [ ] **Step 3: Add fixed-layout native preflight ABI**

```cpp
enum LlamaPreflightStatus { LLAMA_PREFLIGHT_FIT = 0, LLAMA_PREFLIGHT_NO_FIT = 1, LLAMA_PREFLIGHT_INVALID = 2, LLAMA_PREFLIGHT_UNAVAILABLE = 3 };

struct LlamaPreflightResultNative {
    int status;
    int n_ctx;
    int n_gpu_layers;
    int pool_count;
    LlamaPreflightMemoryPool pools[LLAMA_PREFLIGHT_MAX_POOLS];
};
```

Use `common_fit_params`, then `common_get_device_memory_data` (which internally performs a `no_alloc` model/context load) to populate host and device pools. Bound pool count and every `size_t -> int64_t` conversion. Return invalid/unavailable status on overflow or parser error. Treat `LLAMA_PREFLIGHT_FIT` as a projected native allocation layout, not final admission: upstream fitting assumes host memory is unlimited, so `RecommendationPolicy` must still compare every reported host/device pool against the fresh OS snapshot.

- [ ] **Step 4: Refactor fitting so load and preflight cannot drift**

```cpp
FitPlan resolve_fit_plan(const char * model_path, const LlamaRunnerConfig & config);
```

The helper owns tensor-split and buffer-override vectors for the duration of fitting. Preflight releases all transient model/context metadata and does not assign `g_model`, `g_ctx`, or generation state. Serialize it with the runner's existing native session ownership because `common_fit_params` changes global logger state, and use an RAII guard to restore CaraML's logger on success and every exception path. Suppress or sanitize upstream messages that include `model_path`; Kotlin receives only typed status/reason codes and bounded numbers.

- [ ] **Step 5: Bridge JNI and iOS**

JNI returns a bounded `jlongArray`; check allocation and pending exceptions before writes. Move the duplicated iOS declarations into the new `llama_runner.h`, make the `.def` consume that header, and return the fixed C struct by value. Kotlin decoding validates status, field count, nonnegative bytes, and ordered pool records. Expose a second bounded registry payload containing only backend type, stable name/category, and optional total/free bytes; never return an unbounded native device string.

For feature support, reject architecture labels over 64 bytes and quantization labels over 32 bytes before JNI/cinterop. In C++, map architecture with the pinned `llm_arch_from_string` plus model-factory support and quantization with the engine's GGML type support; return separate Supported/Unsupported/Unknown fields and engine version. Do not duplicate the upstream architecture list in Kotlin.

```kotlin
expect class LlamaRunner() {
    fun preflightModel(modelPath: String, config: NativeRunnerConfig): LlamaPreflightResult
    fun backendCapabilities(): List<NativeBackendCapability>
    fun probeModelFeatures(architecture: String, quantization: String?): NativeModelFeatureSupport
}
```

Map backend results through `RunnerBackendCapabilitySource` and feature results through `RunnerEngineCapabilitySource`; wire both in Koin. Registry failure preserves CPU and marks GPU evidence Unknown; feature-probe failure remains Unknown. Neither path falls back to an OS hint or stale Kotlin architecture list as Available.

- [ ] **Step 6: Run Kotlin tests and native compile gates**

Run: `./gradlew :runner:jvmTest :nativeEngine:compileLlamaRunnerDesktop :composeApp:assembleDebug :composeApp:compileKotlinIosSimulatorArm64`

Expected: PASS with no ABI mismatch.

- [ ] **Step 7: Commit llama preflight**

```bash
git add -p -- runner/src/commonMain/kotlin/com/debanshu777/runner/LlamaPreflightResult.kt runner/src/commonMain/kotlin/com/debanshu777/runner/NativeBackendCapability.kt runner/src/commonMain/kotlin/com/debanshu777/runner/NativeModelFeatureSupport.kt runner/src/commonMain/kotlin/com/debanshu777/runner/LlamaRunner.kt runner/src/androidMain/kotlin/com/debanshu777/runner/LlamaRunner.android.kt runner/src/jvmMain/kotlin/com/debanshu777/runner/LlamaRunner.jvm.kt runner/src/iosMain/kotlin/com/debanshu777/runner/LlamaRunner.ios.kt runner/src/commonCpp/llama_runner_core.h runner/src/commonCpp/llama_runner_core.cpp runner/src/commonCpp/llama_runner_jni.cpp runner/src/iosMain/cpp/llama_runner.h runner/src/iosMain/cpp/llama_runner.cpp runner/src/iosMain/cpp/llama_runner.def runner/src/commonTest/kotlin/com/debanshu777/runner/LlamaPreflightResultTest.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/platform/RunnerBackendCapabilitySource.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/RunnerEngineCapabilitySource.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/di/AppModule.kt
git commit -m "feat(runner): expose llama memory preflight"
```

### Task 12: Expose stable-diffusion.cpp preflight and runtime auto-fit

**Files:**
- Create: `diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/DiffusionPreflightResult.kt`
- Create: `diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/DiffusionBackendCapability.kt`
- Create: `diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/DiffusionModelFeatureSupport.kt`
- Modify: `diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/DiffusionModelConfig.kt`
- Modify: `diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunner.kt`
- Modify: `diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunnerValidation.kt`
- Modify: `diffusionRunner/src/androidMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunner.android.kt`
- Modify: `diffusionRunner/src/iosMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunner.ios.kt`
- Modify: `diffusionRunner/src/jvmMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunner.jvm.kt`
- Modify: `diffusionRunner/src/commonCpp/diffusion_runner_core.h`
- Modify: `diffusionRunner/src/commonCpp/diffusion_runner_core.cpp`
- Modify: `diffusionRunner/src/commonCpp/diffusion_runner_jni.cpp`
- Modify: `diffusionRunner/src/iosMain/cpp/diffusion_runner.h`
- Modify: `diffusionRunner/src/iosMain/cpp/diffusion_runner.cpp`
- Modify: `diffusionRunner/src/iosMain/cpp/diffusion_runner.def`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/platform/RunnerBackendCapabilitySource.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/RunnerEngineCapabilitySource.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/di/AppModule.kt`
- Test: `diffusionRunner/src/commonTest/kotlin/com/debanshu777/diffusionrunner/DiffusionPreflightResultTest.kt`
- Test: `diffusionRunner/src/commonTest/kotlin/com/debanshu777/diffusionrunner/DiffusionRunnerValidationTest.kt`

**Interfaces:**
- Adds `maxVram`, `streamLayers`, and `autoFit` to `DiffusionModelConfig`.
- Produces: `DiffusionRunner.preflightModel(config): DiffusionPreflightResult`.
- Produces: `DiffusionRunner.backendCapabilities(): List<DiffusionBackendCapability>` from its own compiled registry; `RunnerBackendCapabilitySource` intersects capabilities with the engine required by each plan.
- Produces: `DiffusionRunner.probeModelFeatures(architecture, quantization, mode): DiffusionModelFeatureSupport`, mapped through `RunnerEngineCapabilitySource`.
- Produces: component-role memory, derived backend specs, backend budgets, architecture, and quantization evidence without creating a published inference handle.

- [ ] **Step 1: Write failing config and payload tests**

```kotlin
@Test
fun rejectsUnboundedOrMalformedMaxVramSpec() {
    assertFailsWith<IllegalArgumentException> { validateModelConfig(config(maxVram = "../../bad")) }
}

@Test
fun preflightPayloadMustContainAllDeclaredComponents() {
    assertIs<DiffusionPreflightResult.InvalidModel>(decodeDiffusionPreflight(truncatedPayload()))
}
```

Add feature-probe tests for a supported image architecture, supported video architecture, unknown architecture, quantization label bounds, and the case where a backend exists for llama but is unavailable to the diffusion engine.

- [ ] **Step 2: Run diffusion tests and verify failure**

Run: `./gradlew :diffusionRunner:jvmTest --tests '*DiffusionPreflightResultTest*' --tests '*DiffusionRunnerValidationTest*'`

Expected: FAIL because preflight and new config fields do not exist.

- [ ] **Step 3: Wire upstream fitting fields into actual model load**

```cpp
params.max_vram = config.max_vram.empty() ? nullptr : config.max_vram.c_str();
params.stream_layers = config.stream_layers;
params.auto_fit = config.auto_fit;
```

Validate `maxVram` as either `-1`, a bounded decimal GiB value, or the documented comma-separated backend assignment grammar; reject unknown keys and strings longer than 256 characters before native entry.

- [ ] **Step 4: Implement metadata-only preflight**

Initialize `ModelLoader` with every configured component path, convert tensor names, obtain SD architecture and per-component parameter memory, initialize backend budgets, and invoke `sd::backend_fit::derive_backend_specs`. Do not call `new_sd_ctx` and do not publish a handle. Return Medium memory confidence until an actual load succeeds because activation allocation is still analytical. Catch all C++ exceptions at the ABI boundary, free partially constructed loaders/backends, and sanitize upstream diagnostics so component paths do not reach logs or Kotlin.

```cpp
DiffusionPreflightResultNative diffusion_runner_core_preflight(const DiffusionModelConfig & config);
```

- [ ] **Step 5: Bridge JNI and iOS with bounded payloads**

Use fixed maximum component/backend counts. Preserve pending JNI allocation errors. Validate all returned sizes and UTF-8 lengths before building Kotlin models.
Keep each FFI struct/function declaration only in `diffusion_runner.h` and configure `diffusion_runner.def` to consume that header, eliminating the current duplicated ABI declarations.
Expose architecture/quantization/image-video capability probing from the same pinned stable-diffusion.cpp enums/build flags, with the same 64/32-byte input caps. Update `RunnerEngineCapabilitySource` to route by descriptor kind so llama support cannot incorrectly imply diffusion support.

```kotlin
internal fun decodeDiffusionPreflight(payload: LongArray): DiffusionPreflightResult {
    if (payload.size !in MIN_PREFLIGHT_FIELDS..MAX_PREFLIGHT_FIELDS) {
        return DiffusionPreflightResult.Unavailable(PreflightReason.MALFORMED_NATIVE_PAYLOAD)
    }
    if (payload.any { it < 0L }) {
        return DiffusionPreflightResult.InvalidModel(PreflightReason.INVALID_NATIVE_SIZE)
    }
    return decodeValidatedPayload(payload)
}
```

- [ ] **Step 6: Run tests and platform compile gates**

Run: `./gradlew :diffusionRunner:jvmTest :nativeEngine:compileLlamaRunnerDesktop :composeApp:assembleDebug :composeApp:compileKotlinIosSimulatorArm64`

Expected: PASS and stable-diffusion native sources rebuild on Android/JVM/iOS.

- [ ] **Step 7: Commit diffusion preflight**

```bash
git add -p -- diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/DiffusionPreflightResult.kt diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/DiffusionBackendCapability.kt diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/DiffusionModelFeatureSupport.kt diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/DiffusionModelConfig.kt diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunner.kt diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunnerValidation.kt diffusionRunner/src/androidMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunner.android.kt diffusionRunner/src/iosMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunner.ios.kt diffusionRunner/src/jvmMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunner.jvm.kt diffusionRunner/src/commonCpp/diffusion_runner_core.h diffusionRunner/src/commonCpp/diffusion_runner_core.cpp diffusionRunner/src/commonCpp/diffusion_runner_jni.cpp diffusionRunner/src/iosMain/cpp/diffusion_runner.h diffusionRunner/src/iosMain/cpp/diffusion_runner.cpp diffusionRunner/src/iosMain/cpp/diffusion_runner.def diffusionRunner/src/commonTest/kotlin/com/debanshu777/diffusionrunner/DiffusionPreflightResultTest.kt diffusionRunner/src/commonTest/kotlin/com/debanshu777/diffusionrunner/DiffusionRunnerValidationTest.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/platform/RunnerBackendCapabilitySource.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/RunnerEngineCapabilitySource.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/di/AppModule.kt
git commit -m "feat(diffusion): expose model fit preflight"
```

### Task 13: Enforce just-in-time load admission and suspected-crash recovery

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/LoadAdmissionController.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/LoadRecoveryRepository.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/LocalArtifactIdentityResolver.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/NativeRunPlanAdapter.kt`
- Modify: `huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/StoragePathProvider.kt`
- Modify: `huggingFaceManager/src/androidMain/kotlin/com/debanshu777/huggingfacemanager/download/StoragePathProvider.android.kt`
- Modify: `huggingFaceManager/src/iosMain/kotlin/com/debanshu777/huggingfacemanager/download/StoragePathProvider.ios.kt`
- Modify: `huggingFaceManager/src/jvmMain/kotlin/com/debanshu777/huggingfacemanager/download/StoragePathProvider.jvm.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/inference/InferenceRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/inference/LlamaInferenceRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/inference/DiffusionInferenceRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/ChatViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/ChatScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/di/AppModule.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/LoadAdmissionControllerTest.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/LocalArtifactIdentityResolverTest.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/NativeRunPlanAdapterTest.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/recommendation/LoadRecoveryRepositoryTest.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/recommendation/LoadCrashProbeMain.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/recommendation/LoadRecoveryProcessTest.kt`

**Interfaces:**
- Produces: `LoadRequest`, `RiskAcknowledgement`, and `LoadAdmission.Ready/ConfirmationRequired/AlternativeAvailable/TemporarilyUnavailable/Blocked`.
- Produces: `LocalArtifactIdentityResolver.resolve(model, components)` returning a verified hub-manifest identity, a content-addressed legacy identity, or a stable rejection.
- Extends model loading to accept the exact selected `RunPlan`.
- Produces: `NativeRunPlanAdapter.toLlamaConfig(plan, baseSettings)` and `toDiffusionExecutionConfig(plan, model)` with lossless field-by-field mapping into diffusion load config plus numeric image/video generation settings.
- Produces: `beginLoad`, `markLoadSucceeded`, `markLoadFailed`, `markLoadCancelled`, `recoverPendingLoad`, and `recordSuspectedFailure`.

- [ ] **Step 1: Write failing admission and marker-state tests**

```kotlin
@Test
fun riskyLoadRequiresAcknowledgementAndNoFitOffersOneAlternative() = runTest {
    assertIs<LoadAdmission.ConfirmationRequired>(controller.evaluate(riskyRequest, null))
    assertIs<LoadAdmission.AlternativeAvailable>(controller.evaluate(noFitRequestWithFallback, null))
}

@Test
fun repeatedPendingMarkerQuarantinesOnlyExactConfiguration() = runTest {
    repository.recordSuspectedFailure(marker(modelDigest = "a", configDigest = "one"))
    repository.recordSuspectedFailure(marker(modelDigest = "a", configDigest = "one"))
    assertTrue(repository.isQuarantined("a", "one", ENGINE_VERSION))
    assertFalse(repository.isQuarantined("a", "two", ENGINE_VERSION))
}
```

Add resolver cases for a valid single-file manifest, a valid multi-repository diffusion bundle, stale size/digest evidence, path escape/symlink escape, unreadable files, duplicate/more-than-64 components, a legacy single file, and a legacy bundle. Assert two identical byte sets under different absolute roots produce the same identity, while a one-byte change produces a different identity. Assert cancellation during legacy hashing leaves no manifest and is rethrown.

- [ ] **Step 2: Run tests and verify failure**

Run: `./gradlew :composeApp:jvmTest --tests '*LoadAdmissionControllerTest*' --tests '*LocalArtifactIdentityResolverTest*' --tests '*NativeRunPlanAdapterTest*' --tests '*LoadRecoveryRepositoryTest*'`

Expected: FAIL because admission and recovery types do not exist.

- [ ] **Step 3: Implement typed admission and explicit retry flow**

```kotlin
data class LoadRequest(
    val model: LocalModelEntity,
    val identity: ModelFileIdentity,
    val plan: RunPlan,
    val riskAcknowledgement: RiskAcknowledgement? = null,
)

suspend fun InferenceRepository.loadModel(request: LoadRequest): ModelLoadResult
```

`LocalModelEntity` does not currently persist a revision or digest, so never derive an identity from that row or from `localPath`. Add `StoragePathProvider.inspectDownloadedArtifact(modelId, localPath): StoredArtifactSnapshot?`; each actual implementation resolves symlinks/canonical paths, requires containment under that model's root, rejects special files, and returns only a regular-file/directory kind, byte count, and bounded change stamp. Resolve the local file/component set through this API before constructing `LoadRequest`.

For Task 10 downloads, strictly decode the bounded sidecar, match its repository-relative entries to the expected files, and compare actual sizes/change stamps with the values captured after atomic rename. Reuse the streaming digest produced during download while the stamp is unchanged; if a file changed, rehash it before accepting or replacing the manifest. If a sidecar is absent because the file predates this feature, stream SHA-256 once on the injected non-UI dispatcher and atomically persist a content-addressed manifest with `RevisionIdentity.LocalContent`. Cancellation deletes only the temporary manifest and is rethrown. A legacy diffusion directory hashes only the validated component allowlist from the component repositories, sorted by logical role and normalized repository-relative path—never recursively traverses an untrusted directory. Absolute paths, change stamps, inode values, and device identifiers are excluded from the identity digest. The stamp is only a rehash trigger, not a security credential or model identity.

After identity resolution, capture a fresh `DeviceSnapshot`, reapply policy, and use cached native preflight only when its verified model-identity/config/engine key is current. Risk acknowledgement binds to the assessment key and expires after the resource snapshot freshness window. A fallback is offered once and executed only after user acceptance. A stale/corrupt manifest or content change invalidates preflight and assessment cache entries; an unreadable or escaping path is blocked before native code.

Return `TemporarilyUnavailable` for current OS low-memory pressure or Serious/Critical thermal state, with retry-after-cooling/closing-apps reasons; do not persistently downgrade the model category. Compatibility, storage, and known memory no-fit still use the permanent blocked paths.

Map context, batch/micro-batch, K/V cache types, GPU layers/auto-fit, mmap, diffusion offload, VAE tiling, max-VRAM, streaming layers, dimensions, steps, and frames explicitly. `NativeRunPlanAdapterTest` round-trips every field and fails if a compromise-bearing plan is converted to different values; no repository may reconstruct a config from profile defaults after admission.

- [ ] **Step 4: Implement durable load markers**

Use DataStore and Okio's standard SHA-256 implementation over a canonical, length-prefixed encoding of repository ID, `RevisionIdentity.HubCommit` or `RevisionIdentity.LocalContent`, logical component role, repository-relative file identity, actual byte count, and content digest; raw local paths are excluded. Persist marker state before native entry and clear immediately after successful load.

```kotlin
suspend fun beginLoad(identity: ModelFileIdentity, plan: RunPlan): PendingLoadMarker
suspend fun markLoadSucceeded(marker: PendingLoadMarker)
suspend fun markLoadFailed(marker: PendingLoadMarker, reason: StableLoadFailure)
suspend fun markLoadCancelled(marker: PendingLoadMarker)
suspend fun recoverPendingLoad(nowEpochMs: Long): SuspectedLoadFailure?
```

Expire markers after seven days. One event temporarily quarantines the exact config; two repeated events for the same model/config/engine mark it known unstable until explicit retry or version/config change.

Make `recoverPendingLoad` idempotent by atomically moving the pending marker to a counted suspected-failure record before returning it. Bound recovery history by the same seven-day window and exact-key count; a restart must not count one uncleared marker twice.

- [ ] **Step 5: Integrate both repositories without weakening native session ownership**

Call preflight and load inside each repository's existing exclusive session boundary. On ordinary allocation failure, release partial native state, atomically clear the pending marker through `markLoadFailed`, and return `AlternativeAvailable` or `Error`. On caught cancellation, release state, clear through `markLoadCancelled`, then rethrow the original `CancellationException`. Only an actual process death/abort can leave the pending marker for next-launch recovery. Clear the success marker only after the runner confirms a usable context/handle.

```kotlin
return nativeSessionGate.withExclusiveSession {
    val marker = recoveryRepository.beginLoad(request.identity, request.plan)
    try {
        val loaded = runner.loadExactPlan(request)
        recoveryRepository.markLoadSucceeded(marker)
        ModelLoadResult.Loaded(loaded)
    } catch (cancelled: CancellationException) {
        runner.releasePartialState()
        withContext(NonCancellable) { recoveryRepository.markLoadCancelled(marker) }
        throw cancelled
    } catch (_: NativeAllocationException) {
        runner.releasePartialState()
        recoveryRepository.markLoadFailed(marker, StableLoadFailure.ALLOCATION)
        ModelLoadResult.AllocationFailed
    }
}
```

- [ ] **Step 6: Add Chat UI confirmation and recovery actions**

`ChatViewModel` exposes a pending confirmation/alternative state. `ChatScreen` displays exact compromises and actions for Continue, Use safer plan, Retry explicitly, and Cancel. Stable messages omit raw native errors and paths.

```kotlin
sealed interface PendingLoadAction {
    data class ConfirmRisk(val request: LoadRequest) : PendingLoadAction
    data class AcceptAlternative(val original: LoadRequest, val safer: RunPlan) : PendingLoadAction
    data class RetryQuarantined(val request: LoadRequest) : PendingLoadAction
}
```

- [ ] **Step 7: Run focused and inference tests**

Start a child JVM probe with an isolated temporary DataStore, let it persist `beginLoad`, and terminate before success. On the next process, assert marker detection, exact-config quarantine, one safer-plan offer, and successful-marker cleanup. This test simulates the cross-process behavior; it does not attempt to induce a real native abort in the normal JVM gate.

Run: `./gradlew :composeApp:jvmTest --tests '*LoadAdmissionControllerTest*' --tests '*LocalArtifactIdentityResolverTest*' --tests '*NativeRunPlanAdapterTest*' --tests '*LoadRecoveryRepositoryTest*' --tests '*LoadRecoveryProcessTest*' --tests '*LlamaInferenceRepository*' --tests '*DiffusionInferenceRepository*'`

Expected: PASS.

- [ ] **Step 8: Commit admission and recovery**

```bash
git add -p -- huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/StoragePathProvider.kt huggingFaceManager/src/androidMain/kotlin/com/debanshu777/huggingfacemanager/download/StoragePathProvider.android.kt huggingFaceManager/src/iosMain/kotlin/com/debanshu777/huggingfacemanager/download/StoragePathProvider.ios.kt huggingFaceManager/src/jvmMain/kotlin/com/debanshu777/huggingfacemanager/download/StoragePathProvider.jvm.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/LoadAdmissionController.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/LoadRecoveryRepository.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/LocalArtifactIdentityResolver.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/NativeRunPlanAdapter.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/inference/InferenceRepository.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/inference/LlamaInferenceRepository.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/inference/DiffusionInferenceRepository.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/ChatViewModel.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/ChatScreen.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/di/AppModule.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/LoadAdmissionControllerTest.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/LocalArtifactIdentityResolverTest.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/NativeRunPlanAdapterTest.kt composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/recommendation/LoadRecoveryRepositoryTest.kt composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/recommendation/LoadCrashProbeMain.kt composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/recommendation/LoadRecoveryProcessTest.kt
git commit -m "feat(inference): gate model loads by verified fit"
```

### Task 14: Add disposable calibration storage and local observation updates

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/storage/RecommendationObservationEntity.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/storage/RecommendationObservationDao.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/storage/RecommendationDatabase.kt`
- Create: `composeApp/src/androidMain/kotlin/com/debanshu777/caraml/core/recommendation/storage/RecommendationDatabase.android.kt`
- Create: `composeApp/src/iosMain/kotlin/com/debanshu777/caraml/core/recommendation/storage/RecommendationDatabase.ios.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/debanshu777/caraml/core/recommendation/storage/RecommendationDatabase.jvm.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/CalibrationRepository.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/InferenceObservationRecorder.kt`
- Create: `runner/src/commonMain/kotlin/com/debanshu777/runner/BackendCalibrationResult.kt`
- Modify: `runner/src/commonMain/kotlin/com/debanshu777/runner/LlamaRunner.kt`
- Modify: `runner/src/androidMain/kotlin/com/debanshu777/runner/LlamaRunner.android.kt`
- Modify: `runner/src/iosMain/kotlin/com/debanshu777/runner/LlamaRunner.ios.kt`
- Modify: `runner/src/jvmMain/kotlin/com/debanshu777/runner/LlamaRunner.jvm.kt`
- Modify: `runner/src/commonCpp/llama_runner_core.h`
- Modify: `runner/src/commonCpp/llama_runner_core.cpp`
- Modify: `runner/src/commonCpp/llama_runner_jni.cpp`
- Modify: `runner/src/iosMain/cpp/llama_runner.h`
- Modify: `runner/src/iosMain/cpp/llama_runner.cpp`
- Modify: `runner/src/iosMain/cpp/llama_runner.def`
- Modify: `huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/StoragePathProvider.kt`
- Modify: `huggingFaceManager/src/androidMain/kotlin/com/debanshu777/huggingfacemanager/download/StoragePathProvider.android.kt`
- Modify: `huggingFaceManager/src/iosMain/kotlin/com/debanshu777/huggingfacemanager/download/StoragePathProvider.ios.kt`
- Modify: `huggingFaceManager/src/jvmMain/kotlin/com/debanshu777/huggingfacemanager/download/StoragePathProvider.jvm.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/di/AppModule.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/debanshu777/caraml/core/di/AppModule.android.kt`
- Modify: `composeApp/src/iosMain/kotlin/com/debanshu777/caraml/core/di/AppModule.ios.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/debanshu777/caraml/core/di/AppModule.jvm.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/domain/usecase/GenerateResponseUseCase.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/inference/DiffusionInferenceRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/ModelViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/RecommendationProfileDialog.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/settings/AppSettings.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/settings/SettingsRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/settings/DefaultSettingsRepository.kt`
- Test: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/CalibrationRepositoryTest.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/recommendation/storage/RecommendationDatabaseTest.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/data/settings/DefaultSettingsRepositoryTest.kt`
- Test: `runner/src/commonTest/kotlin/com/debanshu777/runner/BackendCalibrationResultTest.kt`

**Interfaces:**
- Produces: independent Room `RecommendationDatabase` version 1 with one observation table.
- Produces: `CalibrationRepository.correctionFor`, `record`, and `prune`.
- Produces: `InferenceObservationRecorder.measureLoad` and `measureGeneration` with optional process-memory sampling.
- Produces: `LlamaRunner.calibrateBackend(backend, durationMillis, bufferBytes): BackendCalibrationResult` plus `cancelBackendCalibration()` over the shared GGML backend registry.
- Adds: `StoragePathProvider.getRecommendationDatabasePath()` returning a sibling `recommendation_cache.db`.

- [ ] **Step 1: Write failing correction and retention tests**

```kotlin
@Test
fun correctionNeedsFiveSamplesAndHighNeverFallsBelowOne() = runTest {
    val key = memoryKey(MemoryPool.HOST)
    repeat(4) { repository.record(sample(key, observedRatio = 0.8)) }
    assertNull(repository.correctionFor(key))
    repository.record(sample(key, observedRatio = 0.8))
    val correction = requireNotNull(repository.correctionFor(key))
    assertTrue(correction.high >= 1.0)
}

@Test
fun pruningRetainsAtMostFiveHundredRecentRows() = runTest {
    dao.insertAll(samples(600))
    repository.prune(now)
    assertEquals(500, dao.count())
    assertEquals(0, dao.countOlderThan(now - 90.days))
}
```

- [ ] **Step 2: Run focused tests and verify failure**

Run: `./gradlew :composeApp:jvmTest --tests '*CalibrationRepositoryTest*' --tests '*RecommendationDatabaseTest*' --tests '*DefaultSettingsRepositoryTest*'`
Run: `./gradlew :runner:jvmTest --tests '*BackendCalibrationResultTest*'`

Expected: FAIL because calibration storage does not exist.

- [ ] **Step 3: Add a separate Room database**

```kotlin
@Entity(
    tableName = "recommendation_observation",
    indices = [Index(value = ["engineVersion", "estimatorVersion", "backend", "architectureFamily", "quantFamily", "workloadBucket", "metricKind", "memoryPool"])],
)
data class RecommendationObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val engineVersion: String,
    val estimatorVersion: Int,
    val backend: String,
    val architectureFamily: String,
    val quantFamily: String,
    val workloadBucket: String,
    val metricKind: String,
    val memoryPool: String?,
    val predictedValue: Double,
    val observedValue: Double,
    val outcome: String,
    val capturedAtEpochMs: Long,
)

@Database(entities = [RecommendationObservationEntity::class], version = 1, exportSchema = false)
@ConstructedBy(RecommendationDatabaseConstructor::class)
abstract class RecommendationDatabase : RoomDatabase() {
    abstract fun observationDao(): RecommendationObservationDao
}
```

Use explicit platform builders targeting `recommendation_cache.db`. Because this database is derived and disposable, set `exportSchema = false` and allow destructive reset only in its own builder. If creation or decoding fails, delete/reset only this cache database after logging a stable code. Never change or call destructive recovery on `AppDatabase`; add an isolation test that seeds its existing model tables and proves calibration reset leaves them intact.

- [ ] **Step 4: Implement weighted corrections and pruning**

Sort comparable ratios, apply `similarity * exp(-ageDays / 30.0)`, compute weighted median and weighted P90, and clamp memory high to at least 1.0. Key by engine version, estimator version, backend, architecture family, quant family, workload bucket, metric kind, and memory pool where applicable; never apply a speed correction to memory or a host correction to discrete VRAM.

```kotlin
override suspend fun correctionFor(key: CalibrationKey): CalibrationCorrection? {
    val samples = dao.comparableSamples(key).filter(::isValidSample)
    if (samples.size < 5) return null
    val high = weightedP90(samples)
    return CalibrationCorrection(
        likely = weightedMedian(samples),
        high = if (key.metricKind == MetricKind.MEMORY) maxOf(1.0, high) else high,
    )
}
```

Advance the in-process monotonic calibration revision only after an insert/prune/reset transaction commits; on next launch initialize it from the observation table's maximum update timestamp/version. A failed transaction does not invalidate caches or expose a correction assembled from partial rows.

Replace the Task 7 `NoCalibrationSource` Koin binding with `CalibrationRepository`; do not register both definitions.

- [ ] **Step 5: Record bounded numeric observations**

Wrap loads/generation in a recorder that samples `currentProcessBytes` every 100 ms only when the platform reports it reliably, plus native GPU/shared allocation counters only when their provenance is reliable. Keep host, discrete GPU, and shared observations separate; discard negative/non-finite deltas and never infer one pool from another. Validate enum membership, positive bounded counts/durations, timestamp range, and finite ratios before insert. Store timing, token/frame counts, prediction, observed peak delta, and stable enum/version fields. Do not store prompt text, output, full model path, repository credentials, or device identity.

```kotlin
suspend fun <T> measureGeneration(
    key: CalibrationKey,
    prediction: PerformanceEstimate,
    block: suspend () -> MeasuredResult<T>,
): T = coroutineScope {
    val sampler = launch { sampleReliablePoolsEvery(100.milliseconds) }
    try {
        val measured = block()
        recordValidatedObservation(key, prediction, measured.boundedMetrics)
        measured.value
    } finally {
        sampler.cancelAndJoin()
    }
}
```

- [ ] **Step 6: Add optional three-second calibration action**

Expose `runQuickCalibration()` from `ModelViewModel`. The profile dialog explains duration and allows Skip. Persist a separate `recommendationCalibrationOfferComplete` DataStore key atomically on successful completion or Skip so the app does not nag; Settings retains an explicit Run calibration action. Refuse to start while thermal state is Serious/Critical or power saver is active; Unknown power state is allowed only with a visible caution and no automatic start. Cancellation stops the probe and stores no partial result.

The three-second probe performs one untimed warm-up and at least five bounded timing windows for memory bandwidth and supported backend compute. Build `BackendPerformanceProfile` from the weighted median/P90 only when five valid windows complete; persist the window set in one Room transaction, or persist none on cancellation/timeout/error.

Implement the native probe with fixed synthetic buffers sized from current base budget (minimum 4 MiB, maximum 64 MiB), and defer when even the minimum cannot fit. Use a bounded matrix multiply supported by the selected registered GGML backend, five or more windows, and a monotonic clock. Accept durations only in `500..3_000` ms, serialize through `NativeSessionGate`, expose `cancelBackendCalibration()` as an atomic flag checked between windows, and register a coroutine cancellation callback that invokes it while the blocking call runs on the probe dispatcher. Validate every byte/FLOP conversion and release all tensors/backends through RAII. The result contains only backend category, bytes/operations, elapsed nanoseconds, and status—never model/prompt data.

```kotlin
suspend fun runQuickCalibration(): CalibrationRunResult {
    val snapshot = deviceSnapshotProvider.capture()
    if (snapshot.resources.blocksCalibration()) return CalibrationRunResult.Deferred(snapshot.resources.primaryPressureReason())
    return withTimeoutOrNull(4.seconds) {
        calibrationProbe.runAndPersistOnlyIfComplete(targetDuration = 3.seconds)
    } ?: CalibrationRunResult.TimedOut
}
```

- [ ] **Step 7: Run storage, calibration, and platform tests**

Run: `./gradlew :composeApp:jvmTest --tests '*CalibrationRepositoryTest*' --tests '*RecommendationDatabaseTest*' --tests '*DefaultSettingsRepositoryTest*' :composeApp:assembleDebug :composeApp:compileKotlinIosSimulatorArm64`
Run: `./gradlew :runner:jvmTest --tests '*BackendCalibrationResultTest*' :nativeEngine:compileLlamaRunnerDesktop`

Expected: PASS; the main `caraml.db` remains unchanged in the database isolation test.

- [ ] **Step 8: Commit local calibration**

```bash
git add -p -- composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/storage composeApp/src/androidMain/kotlin/com/debanshu777/caraml/core/recommendation/storage composeApp/src/iosMain/kotlin/com/debanshu777/caraml/core/recommendation/storage composeApp/src/jvmMain/kotlin/com/debanshu777/caraml/core/recommendation/storage composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/CalibrationRepository.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/InferenceObservationRecorder.kt huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/StoragePathProvider.kt huggingFaceManager/src/androidMain/kotlin/com/debanshu777/huggingfacemanager/download/StoragePathProvider.android.kt huggingFaceManager/src/iosMain/kotlin/com/debanshu777/huggingfacemanager/download/StoragePathProvider.ios.kt huggingFaceManager/src/jvmMain/kotlin/com/debanshu777/huggingfacemanager/download/StoragePathProvider.jvm.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/di/AppModule.kt composeApp/src/androidMain/kotlin/com/debanshu777/caraml/core/di/AppModule.android.kt composeApp/src/iosMain/kotlin/com/debanshu777/caraml/core/di/AppModule.ios.kt composeApp/src/jvmMain/kotlin/com/debanshu777/caraml/core/di/AppModule.jvm.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/settings/AppSettings.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/settings/SettingsRepository.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/settings/DefaultSettingsRepository.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/domain/usecase/GenerateResponseUseCase.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/inference/DiffusionInferenceRepository.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/ModelViewModel.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/RecommendationProfileDialog.kt composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/CalibrationRepositoryTest.kt composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/recommendation/storage/RecommendationDatabaseTest.kt composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/data/settings/DefaultSettingsRepositoryTest.kt
git add -p -- runner/src/commonMain/kotlin/com/debanshu777/runner/BackendCalibrationResult.kt runner/src/commonMain/kotlin/com/debanshu777/runner/LlamaRunner.kt runner/src/androidMain/kotlin/com/debanshu777/runner/LlamaRunner.android.kt runner/src/iosMain/kotlin/com/debanshu777/runner/LlamaRunner.ios.kt runner/src/jvmMain/kotlin/com/debanshu777/runner/LlamaRunner.jvm.kt runner/src/commonCpp/llama_runner_core.h runner/src/commonCpp/llama_runner_core.cpp runner/src/commonCpp/llama_runner_jni.cpp runner/src/iosMain/cpp/llama_runner.h runner/src/iosMain/cpp/llama_runner.cpp runner/src/iosMain/cpp/llama_runner.def runner/src/commonTest/kotlin/com/debanshu777/runner/BackendCalibrationResultTest.kt
git commit -m "feat(recommendation): calibrate estimates locally"
```

### Task 15: Validate rollout, remove v1, and document the system

**Files:**
- Delete after reference scan: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/rating/ModelSuitabilityCalculator.kt`
- Delete after replacement: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/rating/ModelSuitabilityCalculatorTest.kt`
- Delete: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/rating/SuitabilityRating.kt`
- Delete: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/rating/ui/SuitabilityInfoSheet.kt`
- Delete: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/LegacySuitabilityAdapter.kt`
- Delete: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/RecommendationRolloutMode.kt`
- Add: `composeApp/src/commonTest/resources/recommendation/model-fit-fixtures.json`
- Add: `nativeEngine/src/testFixtures/native-preflight-fixtures.json`
- Modify: `nativeEngine/build.gradle.kts`
- Modify: `runner/build.gradle.kts`
- Modify: `diffusionRunner/build.gradle.kts`
- Test: `runner/src/jvmTest/kotlin/com/debanshu777/runner/LlamaPreflightParityTest.kt`
- Test: `diffusionRunner/src/jvmTest/kotlin/com/debanshu777/diffusionrunner/DiffusionPreflightParityTest.kt`
- Test: `composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/recommendation/RecommendationPerformanceTest.kt`
- Modify: `.github/workflows/ci.yml` to add native preflight parity and performance-budget jobs.
- Modify: `README.md`
- Modify: `composeApp/README.md`
- Modify: `huggingFaceManager/README.md`
- Modify: `runner/README.md`
- Modify: `diffusionRunner/README.md`
- Modify: `nativeEngine/README.md`

**Interfaces:**
- Makes v2 `PersonalizedRecommendation` the sole source of displayed model categories.
- Removes the v1 calculator and rollout bridge only after the full gate is green.
- Adds a versioned fixture corpus with prediction and observed-outcome fields.

- [ ] **Step 1: Add fixture schema and regression loader**

```json
{
  "schemaVersion": 1,
  "cases": [
    {
      "id": "llm-dense-q4-unified-fit",
      "descriptor": { "kind": "LLM", "fileBytes": 4300000000 },
      "device": { "topology": "UNIFIED", "baseHostBytes": 8589934592 },
      "workload": { "contextTokens": 4096, "kvType": "Q8_Q8" },
      "profile": { "risk": "BALANCED", "priority": "BALANCED" },
      "predicted": { "lowBytes": 5600000000, "likelyBytes": 6200000000, "highBytes": 6900000000 },
      "expected": { "category": "USABLE", "primaryReason": "LIKELY_FIT" },
      "observed": null
    }
  ]
}
```

Create the fixed corpus IDs `llm-dense-q4-unified-fit`, `llm-dense-q5-tight`, `llm-dense-q8-no-fit`, `llm-dense-f16-discrete`, `llm-moe-q4-offload`, `llm-hybrid-q4-recurrent`, `llm-recurrent-q8-small`, `llm-unknown-shape`, `sd15-512-unified`, `sdxl-1024-tiling`, `flux-1024-streaming`, `dit-discrete`, `wan-video-8`, `wan-video-32-no-fit`, `bundle-missing-vae`, and `malformed-overflow`. Every case contains descriptor, snapshot, workload, profile, predicted interval, expected category/reasons, and a nullable observed outcome. The JSON above intentionally leaves `observed` null; replace it only with reviewed, license-compatible fixture measurements or recorded device runs and preserve provenance beside the fixture. Do not invent observations. An unavailable observation leaves the corresponding accuracy gate explicitly unverified. This covers dense, MoE/hybrid/recurrent LLMs; Q4/Q5/Q8/F16; SD1.x, SDXL, Flux/DiT and WAN; component bundles; unified and discrete memory.

The native fixture manifest records fixture ID, upstream URL or repository-relative generator, SPDX license, exact SHA-256, decoded byte cap, and expected format. CI verifies the digest before native parsing, never follows a metadata-supplied URL, and caches by digest. Include llama.cpp's tiny Stories GGUF for successful load parity; keep stable-diffusion successful-load parity in the device/native job when no suitably small redistribution-safe fixture exists, while normal CI still covers corrupt/unsupported diffusion inputs and cleanup.

- [ ] **Step 2: Run shadow comparison and inspect every disagreement**

Run: `./gradlew :composeApp:jvmTest --tests '*Recommendation*' --tests '*ModelFitFixture*'`

Expected: PASS. For each v1/v2 disagreement in fixture output, confirm v2 follows hard compatibility/resource invariants; update a fixture only when its observed outcome was wrong, never to make the implementation pass.

- [ ] **Step 3: Prove acceptance metrics**

Add deterministic aggregate assertions:

```kotlin
val measuredCases = releaseCases.mapNotNull { case -> case.observed?.let { case to it } }
assertTrue(measuredCases.isNotEmpty(), "accuracy gate has no measured cases")
assertEquals(requiredCalibrationBucketIds, calibratedBuckets.map { it.id }.toSet())
assertEquals(0, measuredCases.count { (case, observed) ->
    case.v2Category == RECOMMENDED && !observed.loaded
})
assertTrue(calibratedBuckets.all { it.sampleCount >= 5 && it.highCoverage >= 0.95 })
assertTrue(llmBuckets.all { it.sampleCount >= 5 && it.medianAbsolutePercentageError <= 0.25 })
assertTrue(diffusionBuckets.all { it.sampleCount >= 5 && it.medianAbsolutePercentageError <= 0.30 })
```

Run the same configuration for repeated cold-load samples on the documented Android low/mid/high, iOS A-series, Apple Silicon desktop, and discrete-GPU desktop matrix where CI hardware exists. Missing hardware is reported as an unverified platform gate, not silently treated as pass.

- [ ] **Step 4: Benchmark recommendation latency**

Add a JVM benchmark-style test using 100 cached candidates and 1,000 pure analytical iterations after warm-up. Fail when cached rerank p95 exceeds 100 ms or per-variant analytical p95 exceeds 2 ms only on a documented pinned benchmark runner with `CARAML_ENFORCE_RECOMMENDATION_PERF=true`; ordinary variable GitHub-hosted runners report measurements without pretending to prove the gate. Confirm no Compose main-thread test invokes metadata, filesystem, calibration, or native preflight. The release remains gated until a pinned-runner result is recorded.

- [ ] **Step 5: Add native parity and benchmark CI jobs**

Add `verifyNativePreflightFixtures` to `nativeEngine/build.gradle.kts`; it accepts only manifest URLs on the pinned allowlist, streams each response under its decoded byte cap, verifies SHA-256 before renaming into Gradle's cache, and fails closed. Add Linux native parity for the tiny Stories GGUF plus corrupt llama/diffusion fixtures, macOS iOS/native compilation, and a separately labeled pinned-runner performance job.

```yaml
native-preflight-linux:
  runs-on: ubuntu-latest
  timeout-minutes: 30
  steps:
    - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1
      with:
        submodules: recursive
        persist-credentials: false
    - uses: actions/setup-java@dd06d9cba3e5552c54d9f8ea23572deb30010f7c
      with:
        distribution: temurin
        java-version: "21"
    - uses: gradle/actions/setup-gradle@9c971963bec38e04b3d30dcc455b5382be2fdbfb
      with:
        cache-provider: basic
    - run: ./gradlew :nativeEngine:verifyNativePreflightFixtures :nativeEngine:compileLlamaRunnerDesktop :runner:jvmTest :diffusionRunner:jvmTest
      env:
        CARAML_NATIVE_PARITY: "true"

native-preflight-macos:
  runs-on: macos-15
  timeout-minutes: 45
  steps:
    - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1
      with:
        submodules: recursive
        persist-credentials: false
    - uses: actions/setup-java@dd06d9cba3e5552c54d9f8ea23572deb30010f7c
      with:
        distribution: temurin
        java-version: "21"
    - uses: gradle/actions/setup-gradle@9c971963bec38e04b3d30dcc455b5382be2fdbfb
      with:
        cache-provider: basic
    - run: ./gradlew :nativeEngine:compileLlamaRunnerCMakeIosSimulatorArm64 :composeApp:compileKotlinIosSimulatorArm64

recommendation-performance:
  if: ${{ vars.CARAML_BENCHMARK_RUNNER == 'enabled' }}
  runs-on: [self-hosted, caraml-benchmark]
  timeout-minutes: 20
  env:
    CARAML_ENFORCE_RECOMMENDATION_PERF: "true"
  steps:
    - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1
      with:
        persist-credentials: false
    - uses: actions/setup-java@dd06d9cba3e5552c54d9f8ea23572deb30010f7c
      with:
        distribution: temurin
        java-version: "21"
    - uses: gradle/actions/setup-gradle@9c971963bec38e04b3d30dcc455b5382be2fdbfb
      with:
        cache-provider: basic
    - run: ./gradlew :composeApp:jvmTest --tests '*RecommendationPerformanceTest*'
```

Keep `permissions: contents: read`, add job timeouts, and use the already pinned setup-gradle action from the existing workflow. In the two runner build files, enable the desktop native library path and parity-test execution only when `CARAML_NATIVE_PARITY=true`; normal fast JVM tests must not acquire the fixture or compile native code. Do not enable the benchmark enforcement flag on an unpinned shared runner.

- [ ] **Step 6: Switch fully to v2 and remove legacy code**

Run:

```bash
rg -n "ModelSuitabilityCalculator|SuitabilityRating|SuitabilityInfoSheet|LegacySuitabilityAdapter|RecommendationRolloutMode" composeApp
```

Replace remaining production references with `PersonalizedRecommendation`. Delete v1 files only after the search reports test/legacy references have been migrated. Re-run focused tests immediately after deletion.

- [ ] **Step 7: Update relevant Recent Changes documentation**

Document profile semantics, category meanings, metadata limits, native preflight, local-only calibration, cache reset behavior, and verification commands. Keep module details in their owning README and concise cross-module summary in the root README.

- [ ] **Step 8: Run the complete verification matrix**

Run each command separately and preserve its exit code:

```bash
./gradlew :huggingFaceManager:jvmTest
./gradlew :runner:jvmTest
./gradlew :diffusionRunner:jvmTest
./gradlew :composeApp:jvmTest
./gradlew verifyProject --rerun-tasks
./gradlew :composeApp:allTests
./gradlew :nativeEngine:compileLlamaRunnerDesktop
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:compileKotlinIosSimulatorArm64
git diff --check
```

Expected: all supported-host gates PASS. Report unavailable device/native jobs explicitly.

- [ ] **Step 9: Perform final security and scope review**

Inspect the diff for unchecked external arithmetic, arbitrary outbound URLs, raw path/prompt logging, destructive access to `caraml.db`, unbounded caches or retry loops, swallowed cancellation, JNI allocation misuse, C++ exceptions crossing ABIs, and unrelated refactors. Confirm recommendation observations contain only the approved bounded numeric fields.

- [ ] **Step 10: Commit final rollout**

```bash
git add -p -- README.md composeApp/README.md huggingFaceManager/README.md runner/README.md diffusionRunner/README.md nativeEngine/README.md composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/rating composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/LegacySuitabilityAdapter.kt composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/RecommendationRolloutMode.kt composeApp/src/commonTest/resources/recommendation/model-fit-fixtures.json composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/recommendation/RecommendationPerformanceTest.kt nativeEngine/src/testFixtures/native-preflight-fixtures.json nativeEngine/build.gradle.kts runner/build.gradle.kts diffusionRunner/build.gradle.kts runner/src/jvmTest/kotlin/com/debanshu777/runner/LlamaPreflightParityTest.kt diffusionRunner/src/jvmTest/kotlin/com/debanshu777/diffusionrunner/DiffusionPreflightParityTest.kt .github/workflows/ci.yml
git commit -m "feat(modelhub): enable device-aware recommendations"
```

## Execution Completion Gate

Stop only when v2 is the sole displayed category source, every release fixture satisfies the invariant and accuracy gates, `verifyProject` passes, Android and iOS compile gates pass on supported hosts, native preflight ABIs compile, existing model records remain intact, and all relevant README Recent Changes sections are current. Do not expand into cloud telemetry, automatic downloads, arbitrary model benchmarking, or unrelated database cleanup.
