package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendCapability
import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.BackendStatus
import com.debanshu777.caraml.core.platform.DeviceSnapshot
import com.debanshu777.caraml.core.platform.HardwareProfile
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.platform.PowerPolicyState
import com.debanshu777.caraml.core.platform.ResourceSnapshot
import com.debanshu777.caraml.core.platform.ThermalState
import com.debanshu777.caraml.core.rating.SdArchitecture
import com.debanshu777.huggingfacemanager.sdcpp.ComponentRole
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecommendationPolicyTest {
    private val policy = RecommendationPolicy()

    @Test
    fun mapsEveryFitBandByRiskProfileWithExactBudgetEquality() {
        val rows = listOf(
            Row(FitBand.COMFORTABLE, RiskTolerance.CONSERVATIVE, 750, RecommendationCategory.RECOMMENDED),
            Row(FitBand.COMFORTABLE, RiskTolerance.BALANCED, 850, RecommendationCategory.RECOMMENDED),
            Row(FitBand.COMFORTABLE, RiskTolerance.EXPERIMENTAL, 950, RecommendationCategory.RECOMMENDED),
            Row(FitBand.LIKELY, RiskTolerance.CONSERVATIVE, 750, RecommendationCategory.RISKY),
            Row(FitBand.LIKELY, RiskTolerance.BALANCED, 850, RecommendationCategory.USABLE),
            Row(FitBand.LIKELY, RiskTolerance.EXPERIMENTAL, 950, RecommendationCategory.RECOMMENDED),
            Row(FitBand.BORDERLINE, RiskTolerance.CONSERVATIVE, 750, RecommendationCategory.NOT_SUITABLE),
            Row(FitBand.BORDERLINE, RiskTolerance.BALANCED, 850, RecommendationCategory.RISKY),
            Row(FitBand.BORDERLINE, RiskTolerance.EXPERIMENTAL, 950, RecommendationCategory.RISKY),
            Row(FitBand.NO_FIT, RiskTolerance.CONSERVATIVE, 750, RecommendationCategory.NOT_SUITABLE),
            Row(FitBand.NO_FIT, RiskTolerance.BALANCED, 850, RecommendationCategory.NOT_SUITABLE),
            Row(FitBand.NO_FIT, RiskTolerance.EXPERIMENTAL, 950, RecommendationCategory.NOT_SUITABLE),
        )

        rows.forEach { row ->
            val recommendation = recommend(
                range = rangeFor(row.band, row.policyBudget),
                risk = row.risk,
            )
            assertEquals(row.category, recommendation.category, row.toString())
        }
    }

    @Test
    fun incompatibleAndUnknownCompatibilityPrecedeOtherwiseComfortableFit() {
        val comfortable = task6Range(1, 1, 1)
        val incompatible = policy.recommend(
            task6Assessment(
                compatibility = Compatibility.Incompatible(listOf(AssessmentReason.UNSUPPORTED_FORMAT)),
                plans = listOf(task6PlanAssessment(host = comfortable)),
            ),
            task6Snapshot(),
            RecommendationProfile(riskTolerance = RiskTolerance.EXPERIMENTAL),
        )
        val unknown = policy.recommend(
            task6Assessment(
                compatibility = Compatibility.Unknown(listOf(AssessmentReason.ENGINE_SUPPORT_UNKNOWN)),
                plans = listOf(task6PlanAssessment(host = comfortable)),
            ),
            task6Snapshot(),
            RecommendationProfile(riskTolerance = RiskTolerance.EXPERIMENTAL),
        )

        assertEquals(RecommendationCategory.INCOMPATIBLE, incompatible.category)
        assertEquals(listOf(AssessmentReason.UNSUPPORTED_FORMAT), incompatible.reasons)
        assertEquals(RecommendationCategory.NEEDS_INFORMATION, unknown.category)
        assertEquals(listOf(AssessmentReason.ENGINE_SUPPORT_UNKNOWN), unknown.reasons)
    }

    @Test
    fun missingSafeMemoryOrStorageUpperBoundsNeedInformation() {
        val missingMemory = policy.recommend(
            task6Assessment(plans = listOf(task6PlanAssessment(host = null))),
            task6Snapshot(),
            RecommendationProfile(),
        )
        val missingStorage = policy.recommend(
            task6Assessment(plans = listOf(task6PlanAssessment(storage = null))),
            task6Snapshot(),
            RecommendationProfile(),
        )
        val missingBudget = policy.recommend(
            task6Assessment(),
            task6Snapshot(hostBudget = null),
            RecommendationProfile(),
        )

        assertEquals(RecommendationCategory.NEEDS_INFORMATION, missingMemory.category)
        assertEquals(RecommendationCategory.NEEDS_INFORMATION, missingStorage.category)
        assertEquals(RecommendationCategory.NEEDS_INFORMATION, missingBudget.category)
        assertTrue(missingMemory.reasons.contains(AssessmentReason.MEMORY_BOUNDS_UNKNOWN))
        assertTrue(missingStorage.reasons.contains(AssessmentReason.STORAGE_BOUNDS_UNKNOWN))
    }

    @Test
    fun fallbackAndSafetyConfidenceCapsCanOnlyWorsenTheFitCategory() {
        val fallback = task6PlanAssessment(
            plan = task6LlmPlan(compromises = listOf(RunPlanCompromise.CONTEXT_REDUCED)),
            host = task6Range(100, 100, 100),
        )
        val fallbackResult = policy.recommend(
            task6Assessment(plans = listOf(fallback)),
            task6Snapshot(),
            RecommendationProfile(),
        )
        assertEquals(RecommendationCategory.USABLE, fallbackResult.category)
        assertTrue(fallbackResult.reasons.contains(AssessmentReason.FALLBACK_PLAN_REQUIRED))

        val medium = task6PlanAssessment(
            confidence = task6Confidence(memory = Confidence.MEDIUM),
        )
        val mediumConservative = policy.recommend(
            task6Assessment(plans = listOf(medium)),
            task6Snapshot(),
            RecommendationProfile(riskTolerance = RiskTolerance.CONSERVATIVE),
        )
        assertEquals(RecommendationCategory.USABLE, mediumConservative.category)
        assertTrue(mediumConservative.reasons.contains(AssessmentReason.SAFETY_EVIDENCE_LIMITED))

        RiskTolerance.entries.forEach { risk ->
            val low = task6PlanAssessment(confidence = task6Confidence(memory = Confidence.LOW))
            val result = policy.recommend(
                task6Assessment(plans = listOf(low)),
                task6Snapshot(),
                RecommendationProfile(riskTolerance = risk),
            )
            assertEquals(RecommendationCategory.RISKY, result.category, risk.name)
        }
    }

    @Test
    fun experimentalLikelyFitKeepsExplicitOrderedTightFitWarning() {
        val result = recommend(
            range = task6Range(100, 900, 951),
            risk = RiskTolerance.EXPERIMENTAL,
        )

        assertEquals(RecommendationCategory.RECOMMENDED, result.category)
        assertEquals(
            listOf(
                AssessmentReason.MEMORY_FIT_LIKELY,
                AssessmentReason.TIGHT_MEMORY_FIT,
                AssessmentReason.SPEED_NOT_VERIFIED,
            ),
            result.reasons,
        )
    }

    @Test
    fun performanceMissesDowngradeButCannotUpgradeHardResourceFailure() {
        val missedTarget = recommend(
            range = task6Range(100, 100, 100),
            performance = task6LlmPerformance(decodeLikely = 3.0, confidence = Confidence.MEDIUM),
        )
        val belowHardFloor = recommend(
            range = task6Range(100, 100, 100),
            performance = task6LlmPerformance(decodeLikely = 1.0, confidence = Confidence.MEDIUM),
        )
        val unsafeButFast = recommend(
            range = task6Range(851, 900, 950),
            performance = task6LlmPerformance(decodeLikely = 1_000.0, confidence = Confidence.HIGH),
            metrics = PlanUtilityMetrics(1.0, 1.0, 1.0, 1.0, 1.0),
        )

        assertEquals(RecommendationCategory.USABLE, missedTarget.category)
        assertTrue(missedTarget.reasons.contains(AssessmentReason.PERFORMANCE_TARGET_MISSED))
        assertEquals(RecommendationCategory.NOT_SUITABLE, belowHardFloor.category)
        assertEquals(RecommendationCategory.NOT_SUITABLE, unsafeButFast.category)
        assertTrue(unsafeButFast.reasons.contains(AssessmentReason.MEMORY_NO_FIT))
    }

    @Test
    fun lowPerformanceConfidenceIsVisibleAndCannotMakeAConfidentHardFloorClaim() {
        val result = recommend(
            range = task6Range(100, 100, 100),
            performance = task6LlmPerformance(decodeLikely = 0.1, confidence = Confidence.LOW),
        )

        assertEquals(RecommendationCategory.RISKY, result.category)
        assertTrue(result.reasons.contains(AssessmentReason.SPEED_NOT_VERIFIED))
        assertTrue(result.reasons.contains(AssessmentReason.PERFORMANCE_UNCERTAIN))
    }

    @Test
    fun storageReserveAndNoFitAreProfileIndependent() {
        RiskTolerance.entries.forEach { risk ->
            val result = policy.recommend(
                task6Assessment(
                    plans = listOf(task6PlanAssessment(storage = task6Range(1_001, 1_001, 1_001))),
                ),
                task6Snapshot(storageBudget = 1_000),
                RecommendationProfile(riskTolerance = risk),
            )
            assertEquals(RecommendationCategory.NOT_SUITABLE, result.category, risk.name)
            assertTrue(result.reasons.contains(AssessmentReason.STORAGE_NO_FIT))
        }
    }

    @Test
    fun staleSnapshotCannotBeUsedForFinalPersonalization() {
        val stale = policy.recommend(
            task6Assessment(),
            task6Snapshot(isFresh = false),
            RecommendationProfile(riskTolerance = RiskTolerance.EXPERIMENTAL),
        )

        assertEquals(RecommendationCategory.NEEDS_INFORMATION, stale.category)
        assertEquals(listOf(AssessmentReason.RESOURCE_SNAPSHOT_STALE), stale.reasons)
    }

    @Test
    fun freshFlagCannotOverrideAnExpiredOrFutureCaptureTimestamp() {
        listOf(1L, Clock.System.now().toEpochMilliseconds() + 60_000L).forEach { capturedAt ->
            val stale = policy.recommend(
                task6Assessment(),
                task6Snapshot(isFresh = true, capturedAtEpochMs = capturedAt),
                RecommendationProfile(),
            )

            assertEquals(RecommendationCategory.NEEDS_INFORMATION, stale.category)
            assertEquals(listOf(AssessmentReason.RESOURCE_SNAPSHOT_STALE), stale.reasons)
        }
    }

    @Test
    fun diffusionReferenceTargetUsesTheSameDowngradeAndHardFloorRules() {
        fun image(seconds: Double) = PerformanceEstimate.DiffusionImage(
            secondsPerStep = task6PerformanceRange(seconds / 20.0, Confidence.MEDIUM),
            totalTimeSeconds = task6PerformanceRange(seconds, Confidence.MEDIUM),
            referenceTotalTimeSeconds = task6PerformanceRange(seconds, Confidence.MEDIUM),
            evidence = emptyList(),
        )

        assertEquals(
            RecommendationCategory.USABLE,
            recommend(task6Range(100, 100, 100), performance = image(100.0)).category,
        )
        assertEquals(
            RecommendationCategory.NOT_SUITABLE,
            recommend(task6Range(100, 100, 100), performance = image(181.0)).category,
        )
    }

    @Test
    fun videoPerformanceRemainsNonBlocking() {
        val performance = PerformanceEstimate.DiffusionVideo(
            secondsPerStep = task6PerformanceRange(10.0, Confidence.HIGH),
            secondsPerFrame = task6PerformanceRange(10_000.0, Confidence.HIGH),
            totalTimeSeconds = task6PerformanceRange(80_000.0, Confidence.HIGH),
            comparableForPolicy = false,
            evidence = emptyList(),
        )
        val result = recommend(task6Range(100, 100, 100), performance = performance)

        assertEquals(RecommendationCategory.RECOMMENDED, result.category)
        assertTrue(result.reasons.contains(AssessmentReason.SPEED_NOT_VERIFIED))
    }

    private fun recommend(
        range: EstimateRange,
        risk: RiskTolerance = RiskTolerance.BALANCED,
        performance: PerformanceEstimate = PerformanceEstimate.Unknown(AssessmentReason.SPEED_NOT_VERIFIED),
        metrics: PlanUtilityMetrics = PlanUtilityMetrics(),
    ): PersonalizedRecommendation = policy.recommend(
        task6Assessment(
            plans = listOf(
                task6PlanAssessment(
                    host = range,
                    confidence = task6Confidence(
                        performance = when (performance) {
                            is PerformanceEstimate.Unknown -> Confidence.LOW
                            is PerformanceEstimate.Llm -> performance.decodeTokensPerSecond.confidence
                            is PerformanceEstimate.DiffusionImage -> performance.referenceTotalTimeSeconds.confidence
                            is PerformanceEstimate.DiffusionVideo -> performance.totalTimeSeconds.confidence
                        },
                    ),
                    performance = performance,
                    metrics = metrics,
                ),
            ),
        ),
        task6Snapshot(hostBudget = 1_000, storageBudget = 2_000),
        RecommendationProfile(riskTolerance = risk),
    )

    private fun rangeFor(band: FitBand, budget: Long): EstimateRange = when (band) {
        FitBand.COMFORTABLE -> task6Range(budget, budget, budget)
        FitBand.LIKELY -> task6Range(budget - 2, budget, budget + 1)
        FitBand.BORDERLINE -> task6Range(budget, budget + 1, budget + 2)
        FitBand.NO_FIT -> task6Range(budget + 1, budget + 2, budget + 3)
    }

    private data class Row(
        val band: FitBand,
        val risk: RiskTolerance,
        val policyBudget: Long,
        val category: RecommendationCategory,
    )
}

internal fun task6Range(low: Long, likely: Long, high: Long): EstimateRange =
    (EstimateRange.create(low, likely, high) as CheckedEstimateRange.Value).range

internal fun task6PerformanceRange(likely: Double, confidence: Confidence): PerformanceRange =
    requireNotNull(PerformanceRange.create(likely, likely, likely, confidence))

internal fun task6Confidence(
    compatibility: Confidence = Confidence.HIGH,
    memory: Confidence = Confidence.HIGH,
    storage: Confidence = Confidence.HIGH,
    performance: Confidence = Confidence.LOW,
) = AssessmentConfidence(compatibility, memory, storage, performance)

internal fun task6LlmPerformance(
    decodeLikely: Double,
    confidence: Confidence,
) = PerformanceEstimate.Llm(
    promptTokensPerSecond = task6PerformanceRange(decodeLikely, confidence),
    decodeTokensPerSecond = task6PerformanceRange(decodeLikely, confidence),
    timeToFirstTokenSeconds = task6PerformanceRange(1.0, confidence),
    loadTimeSeconds = task6PerformanceRange(1.0, confidence),
    evidence = emptyList(),
)

internal fun task6PlanAssessment(
    plan: RunPlan = task6LlmPlan(),
    host: EstimateRange? = task6Range(100, 100, 100),
    gpu: EstimateRange? = null,
    shared: EstimateRange? = null,
    storage: EstimateRange? = task6Range(100, 100, 100),
    confidence: AssessmentConfidence = task6Confidence(),
    performance: PerformanceEstimate = PerformanceEstimate.Unknown(AssessmentReason.SPEED_NOT_VERIFIED),
    metrics: PlanUtilityMetrics = PlanUtilityMetrics(0.5, null, 0.5, 0.5, 0.5),
) = PlanAssessment(
    plan = plan,
    hostMemoryBytes = host,
    gpuMemoryBytes = gpu,
    sharedMemoryBytes = shared,
    storageBytes = storage,
    confidence = confidence,
    evidence = emptyList(),
    performance = performance,
    utilityMetrics = metrics,
)

internal fun task6Assessment(
    compatibility: Compatibility = Compatibility.Compatible,
    plans: List<PlanAssessment> = listOf(task6PlanAssessment()),
    confidence: AssessmentConfidence = task6Confidence(),
) = ModelAssessment(
    assessmentKey = "owner/model@0123456789abcdef0123456789abcdef01234567:model-Q4_K_M.gguf",
    compatibility = compatibility,
    planAssessments = AssessedPlans(plans),
    baseHostBudgetBytes = 1_000,
    baseGpuBudgetBytes = null,
    baseSharedBudgetBytes = null,
    baseStorageBudgetBytes = 2_000,
    confidence = confidence,
    evidence = emptyList(),
)

internal fun task6Snapshot(
    hostBudget: Long? = 1_000,
    gpuBudget: Long? = null,
    sharedBudget: Long? = null,
    storageBudget: Long? = 2_000,
    isFresh: Boolean = true,
    capturedAtEpochMs: Long = Clock.System.now().toEpochMilliseconds(),
) = DeviceSnapshot(
    hardwareProfile = task6Hardware(
        backend = if (gpuBudget == null) BackendKind.CPU else BackendKind.CUDA,
        topology = if (gpuBudget == null) MemoryTopology.UNKNOWN else MemoryTopology.DISCRETE,
    ),
    resources = task6ResourceSnapshot(
        hostBytes = hostBudget,
        gpuBytes = gpuBudget,
        storageBytes = storageBudget,
        capturedAtEpochMs = capturedAtEpochMs,
    ),
    baseHostBudgetBytes = hostBudget,
    baseGpuBudgetBytes = gpuBudget,
    baseSharedBudgetBytes = sharedBudget,
    baseStorageBudgetBytes = storageBudget,
    isFresh = isFresh,
    evidence = emptyList(),
)

internal fun task6ResourceSnapshot(
    hostBytes: Long? = 1_000,
    gpuBytes: Long? = null,
    storageBytes: Long? = 2_000,
    capturedAtEpochMs: Long = Clock.System.now().toEpochMilliseconds(),
) = ResourceSnapshot(
    additionalAllocatableHostBytes = hostBytes,
    additionalAllocatableGpuBytes = gpuBytes,
    currentProcessBytes = 100,
    freeStorageBytes = storageBytes,
    osPressureReserveHostBytes = 0,
    observedAppFootprintNoiseP95Bytes = 0,
    platformMinimumReserveHostBytes = 0,
    lowMemory = false,
    thermalState = ThermalState.NOMINAL,
    powerPolicyState = PowerPolicyState.NORMAL,
    capturedAtEpochMs = capturedAtEpochMs,
    evidence = emptyList(),
)

internal fun task6Hardware(
    logicalCores: Int = 8,
    performanceCores: Int? = 4,
    backend: BackendKind = BackendKind.CPU,
    topology: MemoryTopology = MemoryTopology.UNKNOWN,
) = HardwareProfile(
    cpuArchitecture = "fixture",
    logicalCoreCount = logicalCores,
    performanceCoreCount = performanceCores,
    instructionSets = emptySet(),
    backends = listOf(BackendCapability(backend, BackendStatus.AVAILABLE, null, emptyList())),
    memoryTopology = topology,
    evidence = emptyList(),
)

internal fun task6LlmPlan(
    keyContext: Int = 4_096,
    backend: BackendKind = BackendKind.CPU,
    topology: MemoryTopology = MemoryTopology.UNKNOWN,
    gpuLayers: Int? = if (backend == BackendKind.CPU) 0 else 16,
    compromises: List<RunPlanCompromise> = emptyList(),
) = LlmRunPlan(
    contextTokens = keyContext,
    batchSize = 128,
    microBatchSize = 128,
    sequenceCount = 1,
    keyCacheType = KvCacheType.Q8_0,
    valueCacheType = KvCacheType.Q8_0,
    backend = backend,
    memoryTopology = topology,
    gpuLayerCount = gpuLayers,
    compromises = compromises,
)

internal fun task6LlmDescriptor(
    sizeBytes: Long = 1_073_741_824L,
    parameterCount: Long? = 7_000_000_000L,
) = LlmModelDescriptor(
    repositoryId = "owner/model",
    revision = "0123456789abcdef0123456789abcdef01234567",
    file = ModelFileIdentity(
        repositoryId = "owner/model",
        revision = "0123456789abcdef0123456789abcdef01234567",
        path = "model-Q4_K_M.gguf",
        sizeBytes = sizeBytes,
        gitOid = null,
        lfsOid = "sha256:fixture",
        xetHash = null,
        evidence = emptyList(),
    ),
    architecture = "llama",
    quantization = QuantizationEvidence.Known("Q4_K_M"),
    parameterCount = parameterCount,
    contextLimit = 16_384,
    transformerShape = TransformerShape(32, 8, 32, 4_096, 128),
    ggufVersion = 3,
    requiredEngineFeatures = emptyList(),
    evidence = emptyList(),
)

internal fun task6LlmWorkload(allowFallbacks: Boolean = false) = LlmWorkloadConfig(
    userRequestedContextTokens = 4_096,
    contextTokens = 4_096,
    minimumContextTokens = 512,
    promptTokens = 512,
    generationReserveTokens = 256,
    batchSize = 128,
    microBatchSize = 128,
    sequenceCount = 1,
    kvCacheSelection = KvCacheSelection.Auto,
    allowContextFallback = allowFallbacks,
    allowBatchFallback = allowFallbacks,
    allowKvCacheFallback = allowFallbacks,
    allowedKvCacheTypes = KvCacheType.entries,
    evidence = emptyList(),
)

internal fun task6InvalidLlmWorkload() = LlmWorkloadConfig(
    userRequestedContextTokens = 0,
    contextTokens = 0,
    minimumContextTokens = 0,
    promptTokens = 0,
    generationReserveTokens = 0,
    batchSize = 0,
    microBatchSize = 0,
    sequenceCount = 0,
    kvCacheSelection = KvCacheSelection.Auto,
    allowContextFallback = true,
    allowBatchFallback = true,
    allowKvCacheFallback = true,
    allowedKvCacheTypes = KvCacheType.entries,
    evidence = emptyList(),
)

internal fun task6DiffusionDescriptor(mode: DiffusionMode = DiffusionMode.IMAGE): DiffusionModelDescriptor {
    val primary = ModelFileIdentity(
        repositoryId = "owner/diffusion",
        revision = "0123456789abcdef0123456789abcdef01234567",
        path = "model.safetensors",
        sizeBytes = 2_147_483_648L,
        gitOid = null,
        lfsOid = "sha256:diffusion",
        xetHash = null,
        evidence = emptyList(),
    )
    val vae = ModelFileIdentity(
        repositoryId = primary.repositoryId,
        revision = primary.revision,
        path = "vae.safetensors",
        sizeBytes = 268_435_456L,
        gitOid = null,
        lfsOid = "sha256:vae",
        xetHash = null,
        evidence = emptyList(),
    )
    return DiffusionModelDescriptor(
        repositoryId = primary.repositoryId,
        revision = primary.revision,
        components = listOf(
            DiffusionComponentDescriptor(primary, null, required = true, isPrimary = true),
            DiffusionComponentDescriptor(vae, ComponentRole.VAE, required = true, isPrimary = false),
        ),
        mode = mode,
        family = "SDXL",
        architecture = SdArchitecture.SDXL,
        width = 1_024,
        height = 1_024,
        quantizationDistribution = setOf("F16"),
        requiredComponentsPresent = true,
        requiredEngineFeatures = emptyList(),
        evidence = emptyList(),
    )
}

internal fun task6DiffusionPlan(
    mode: DiffusionMode = DiffusionMode.IMAGE,
    width: Int = 512,
    height: Int = 512,
    frames: Int = if (mode == DiffusionMode.IMAGE) 1 else 8,
    steps: Int = 20,
) = DiffusionRunPlan(
    mode = mode,
    width = width,
    height = height,
    frameCount = frames,
    batchSize = 1,
    steps = steps,
    vaeTiling = false,
    offloadToCpu = false,
    keepClipOnCpu = false,
    keepVaeOnCpu = false,
    maxVramBytes = null,
    layerStreaming = false,
    requiresUserAcceptance = false,
    backend = BackendKind.METAL,
    memoryTopology = MemoryTopology.UNIFIED,
    compromises = emptyList(),
)
