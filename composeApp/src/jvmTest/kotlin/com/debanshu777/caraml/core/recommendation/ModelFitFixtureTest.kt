package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.huggingfacemanager.model.ModelDetailResponse
import com.debanshu777.huggingfacemanager.model.ModelFileTreeResponse
import com.debanshu777.huggingfacemanager.model.TransformerConfigResponse
import com.debanshu777.huggingfacemanager.sdcpp.ComponentRole
import com.debanshu777.huggingfacemanager.sdcpp.SdCppComponent
import com.debanshu777.huggingfacemanager.sdcpp.SdCppModelSetup
import com.debanshu777.huggingfacemanager.sdcpp.SdCppRecommendedParams
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelFitFixtureTest {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = true
    }
    private val descriptorFactory = ModelDescriptorFactory()
    private val compatibilityChecker = CompatibilityChecker { SupportEvidence.Supported }

    @Test
    fun versionedFixtureCorpusIsPackagedForJvmRegressionTests() {
        assertNotNull(
            resourceUrl(),
            "The versioned model-fit fixture corpus must be available to the JVM gate",
        )
    }

    @Test
    fun corpusCoversEveryFixedCaseAndV2MatchesVersionedExpectations() {
        val corpus = loadCorpus()

        assertEquals(1, corpus.schemaVersion)
        assertEquals(REQUIRED_IDS, corpus.cases.map { it.id }.toSet())
        assertEquals(REQUIRED_IDS.size, corpus.cases.size)
        var estimatorExecutions = 0
        corpus.cases.forEach { case ->
            case.validateShape()
            val result = recommendationFor(case)
            val actualReasons = result.recommendation.reasons.map { it.name }
            assertEquals(case.expected.category, result.recommendation.category.name, case.id)
            assertEquals(case.expected.reasons, actualReasons, case.id)
            result.planAssessment?.let { assessment ->
                estimatorExecutions += 1
                val range = assessment.rangeFor(MemoryTopology.valueOf(case.device.topology))
                if (range == null) {
                    assertEquals(PredictedFixture.ZERO, case.predicted, case.id)
                } else {
                    assertEquals(case.predicted.lowBytes, range.lowBytes, case.id)
                    assertEquals(case.predicted.likelyBytes, range.likelyBytes, case.id)
                    assertEquals(case.predicted.highBytes, range.highBytes, case.id)
                }
                assertTrue(
                    assessment.evidence.map { it.reason.name }.containsAll(case.expected.estimatorReasons),
                    "${case.id}: expected estimator evidence ${case.expected.estimatorReasons}, " +
                        "actual=${assessment.evidence.map { it.reason.name }}",
                )
            }
        }
        assertEquals(
            15,
            estimatorExecutions,
            "Only the real missing-VAE build failure may stop before estimation",
        )
    }

    @Test
    fun releasePromotionRemainsBlockedWhenReviewedMeasurementsAreAbsent() {
        val corpus = loadCorpus()
        val measuredCases = corpus.cases.mapNotNull { case -> case.observed?.let { case to it } }

        if (measuredCases.isEmpty()) {
            assertFalse(corpus.releaseEvidence.deviceMatrixVerified)
        } else {
            if (corpus.releaseEvidence.deviceMatrixVerified) {
                assertEquals(
                    corpus.cases.map { it.id }.toSet(),
                    measuredCases.map { it.first.id }.toSet(),
                    "Every release fixture needs reviewed device evidence",
                )
            }
            assertEquals(
                0,
                measuredCases.count { (case, observed) ->
                    case.expected.category == RecommendationCategory.RECOMMENDED.name && !observed.loaded
                },
                "A recommended case must never fail its reviewed cold load",
            )
            assertTrue(measuredCases.all { (_, observed) ->
                observed.sampleCount >= 5 && observed.highCoverage >= 0.95
            })
            assertTrue(measuredCases.filter { it.first.descriptor.kind == "LLM" }.all { (_, observed) ->
                observed.medianAbsolutePercentageError <= 0.25
            })
            assertTrue(measuredCases.filter { it.first.descriptor.kind == "DIFFUSION" }.all { (_, observed) ->
                observed.medianAbsolutePercentageError <= 0.30
            })
        }
        assertFalse(corpus.releaseEvidence.deviceMatrixVerified)
        assertFalse(corpus.releaseEvidence.pinnedPerformanceRunnerVerified)
        assertEquals(
            RecommendationRolloutMode.LEGACY,
            DefaultRecommendationRolloutModeSource(isDebugBuild = false).current(),
        )
    }

    private fun loadCorpus(): ModelFitCorpus =
        json.decodeFromString(resourceUrl().readText())

    private fun resourceUrl() = assertNotNull(
        javaClass.getResource("/recommendation/model-fit-fixtures.json"),
        "Missing model-fit fixture corpus",
    )

    private fun recommendationFor(case: ModelFitCase): FixturePipelineResult {
        val topology = MemoryTopology.valueOf(case.device.topology)
        val backend = when (topology) {
            MemoryTopology.UNIFIED -> BackendKind.METAL
            MemoryTopology.DISCRETE -> BackendKind.CUDA
            MemoryTopology.UNKNOWN -> BackendKind.CPU
        }
        val buildResult = buildDescriptor(case)
        val descriptor = (buildResult as? DescriptorBuildResult.Ready)?.descriptor
        val compatibility = when (buildResult) {
            is DescriptorBuildResult.Ready -> compatibilityChecker.check(
                buildResult.descriptor,
                task6Hardware(topology = topology, backends = listOf(task6Backend(backend))),
            )
            is DescriptorBuildResult.Invalid -> Compatibility.Incompatible(
                reasons = buildResult.reasons,
                evidence = buildResult.reasons.map { Evidence(it, Confidence.HIGH, "descriptor-factory") },
            )
            is DescriptorBuildResult.NeedsVariant -> Compatibility.Unknown(
                reasons = buildResult.reasons,
                evidence = buildResult.reasons.map { Evidence(it, Confidence.LOW, "descriptor-factory") },
            )
        }
        val planAssessment = if (compatibility is Compatibility.Compatible) {
            when (descriptor) {
                is LlmModelDescriptor -> LlmFootprintEstimator().estimate(
                    descriptor = descriptor,
                    plan = case.workload.toLlmPlan(backend, topology),
                    calibration = MemoryCalibration.None,
                )
                is DiffusionModelDescriptor -> DiffusionFootprintEstimator().estimate(
                    descriptor = descriptor,
                    plan = case.workload.toDiffusionPlan(backend, topology),
                    calibration = MemoryCalibration.None,
                )
                null -> null
            }
        } else {
            null
        }
        val assessmentKey = "fixture:${case.id}"
        val assessment = ModelAssessment(
            assessmentKey = assessmentKey,
            compatibility = compatibility,
            planAssessments = AssessedPlans(
                values = listOfNotNull(planAssessment),
                assessmentKey = assessmentKey,
                compatibility = compatibility,
                memoryTopology = topology,
            ),
            baseHostBudgetBytes = case.device.baseHostBytes.takeUnless { topology == MemoryTopology.UNIFIED },
            baseGpuBudgetBytes = case.device.baseGpuBytes,
            baseSharedBudgetBytes = case.device.baseSharedBytes,
            baseStorageBudgetBytes = Long.MAX_VALUE / 4,
            confidence = planAssessment?.confidence ?: task6Confidence(),
            evidence = descriptor?.evidence.orEmpty(),
        )
        val snapshot = case.device.toSnapshot(topology)
        val recommendation = RecommendationPolicy().recommend(
            assessment,
            snapshot,
            RecommendationProfile(
                riskTolerance = RiskTolerance.valueOf(case.profile.risk),
                optimizationPriority = OptimizationPriority.valueOf(case.profile.priority),
            ),
        )
        return FixturePipelineResult(recommendation, descriptor, planAssessment)
    }

    private fun buildDescriptor(case: ModelFitCase): DescriptorBuildResult {
        val descriptor = case.descriptor
        val detail = ModelDetailResponse(
            modelId = descriptor.repositoryId,
            sha = descriptor.revision,
            gguf = if (descriptor.kind == "LLM") {
                ModelDetailResponse.Gguf(
                    architecture = descriptor.architecture,
                    contextLength = descriptor.contextLimit,
                    total = descriptor.parameterCount,
                )
            } else {
                null
            },
            tags = descriptor.ggufVersion?.let { listOf("gguf-v$it") },
        )
        val files = descriptor.files.map(FileFixture::toHubFile)
        return when (descriptor.kind) {
            "LLM" -> descriptorFactory.buildLlm(
                detail = detail,
                files = files,
                transformerConfig = descriptor.shape?.toTransformerConfig(descriptor.contextLimit),
            )
            "DIFFUSION" -> descriptorFactory.buildDiffusion(
                detail = detail,
                files = files,
                setup = SdCppModelSetup(
                    familyLabel = descriptor.architecture,
                    description = "Deterministic Task 15 fixture",
                    components = descriptor.requirements.map { requirement ->
                        SdCppComponent(
                            role = ComponentRole.valueOf(requirement.role),
                            repoId = descriptor.repositoryId,
                            filePath = requirement.path,
                            required = true,
                        )
                    },
                    recommendedParams = SdCppRecommendedParams(
                        width = descriptor.nativeWidth,
                        height = descriptor.nativeHeight,
                    ),
                    selfContained = false,
                ),
                mode = if (requireNotNull(case.workload.frames) > 1) DiffusionMode.VIDEO else DiffusionMode.IMAGE,
            )
            else -> error("${case.id}: unsupported descriptor kind")
        }.also { result ->
            if (result is DescriptorBuildResult.Ready) {
                assertEquals(descriptor.repositoryId, result.descriptor.repositoryId, case.id)
                assertEquals(descriptor.revision, result.descriptor.revision, case.id)
                when (val normalized = result.descriptor) {
                    is LlmModelDescriptor -> {
                        assertEquals(descriptor.architecture, normalized.architecture, case.id)
                        assertEquals(descriptor.quantization?.let(::setOf).orEmpty(), normalized.quantization.quantizations, case.id)
                        assertEquals(descriptor.shape?.layerCount, normalized.transformerShape?.layerCount, case.id)
                        assertEquals(descriptor.shape?.kvHeadCount, normalized.transformerShape?.kvHeadCount, case.id)
                    }
                    is DiffusionModelDescriptor -> {
                        assertEquals(descriptor.architecture, normalized.architecture?.name, case.id)
                        assertEquals(descriptor.quantization?.let(::setOf).orEmpty(), normalized.quantizationDistribution, case.id)
                        assertEquals(descriptor.files.size, normalized.components.size, case.id)
                        val normalizedByPath = normalized.components.associateBy { it.file.path }
                        descriptor.files.forEach { file ->
                            assertEquals(file.primary, normalizedByPath[file.path]?.isPrimary, "${case.id}:${file.path}")
                            assertEquals(file.role, normalizedByPath[file.path]?.role?.name, "${case.id}:${file.path}")
                        }
                    }
                }
            }
        }
    }

    private fun ModelFitCase.validateShape() {
        assertTrue(id.length in 1..64, id)
        assertTrue(descriptor.kind in setOf("LLM", "DIFFUSION"), id)
        assertTrue(descriptor.repositoryId.length in 1..DescriptorLimits.MAX_MODEL_ID_LENGTH, id)
        assertTrue(descriptor.revision.length == 40 && descriptor.revision.all { it in '0'..'9' || it in 'a'..'f' }, id)
        assertTrue(descriptor.architecture.length in 1..64, id)
        assertEquals(descriptor.fileBytes, descriptor.files.sumOf { it.sizeBytes }, id)
        assertTrue(descriptor.fileBytes in 1..DescriptorLimits.MAX_BUNDLE_BYTES, id)
        assertTrue(descriptor.files.size in 1..DescriptorLimits.MAX_COMPONENTS, id)
        assertEquals(1, descriptor.files.count { it.primary }, id)
        assertTrue(descriptor.files.all { it.path.length in 1..DescriptorLimits.MAX_RELATIVE_PATH_LENGTH }, id)
        assertTrue(descriptor.files.all { it.oid.length == 64 && it.oid.all { char -> char in '0'..'9' || char in 'a'..'f' } }, id)
        assertTrue(descriptor.requirements.size <= DescriptorLimits.MAX_COMPONENTS, id)
        when (descriptor.kind) {
            "LLM" -> {
                assertTrue(descriptor.files.all { it.role == null }, id)
                assertTrue(descriptor.requirements.isEmpty(), id)
                assertNotNull(descriptor.contextLimit, id)
                assertNotNull(descriptor.ggufVersion, id)
            }
            "DIFFUSION" -> {
                assertTrue(descriptor.files.single { it.primary }.role == null, id)
                assertTrue(descriptor.files.filterNot { it.primary }.all { it.role != null }, id)
                val resolvedRequirements = descriptor.files.filterNot { it.primary }
                    .map { ComponentRequirementFixture(requireNotNull(it.role), it.path) }
                    .toSet()
                assertTrue(descriptor.requirements.toSet().containsAll(resolvedRequirements), id)
                assertNotNull(descriptor.nativeWidth, id)
                assertNotNull(descriptor.nativeHeight, id)
            }
        }
        assertTrue(workload.isValidFor(descriptor.kind), id)
        assertTrue(profile.risk in RiskTolerance.entries.map { it.name }, id)
        assertTrue(profile.priority in OptimizationPriority.entries.map { it.name }, id)
        assertTrue(expected.reasons.isNotEmpty(), id)
        assertTrue(expected.reasons.size <= RecommendationPolicyV1.MAX_ASSESSMENT_REASONS, id)
        assertTrue(expected.reasons.all { reason -> AssessmentReason.entries.any { it.name == reason } }, id)
        assertTrue(expected.estimatorReasons.all { reason -> AssessmentReason.entries.any { it.name == reason } }, id)
        assertTrue(
            EstimateRange.create(predicted.lowBytes, predicted.likelyBytes, predicted.highBytes) is CheckedEstimateRange.Value,
            id,
        )
        observed?.let {
            assertTrue(it.provenance.isNotBlank() && it.provenance.length <= 256, id)
            assertTrue(it.sampleCount in 1..10_000, id)
            assertTrue(it.highCoverage.isFinite() && it.highCoverage in 0.0..1.0, id)
            assertTrue(it.medianAbsolutePercentageError.isFinite() && it.medianAbsolutePercentageError >= 0.0, id)
        }
    }

    private fun WorkloadFixture.isValidFor(kind: String): Boolean = when (kind) {
        "LLM" -> contextTokens in 1..DescriptorLimits.MAX_CONTEXT_TOKENS &&
            batchSize in 1..WorkloadLimits.MAX_BATCH_SIZE && microBatchSize in 1..batchSize &&
            sequenceCount in 1..WorkloadLimits.MAX_SEQUENCE_COUNT &&
            keyCacheType in KvCacheType.entries.map { it.name } && valueCacheType in KvCacheType.entries.map { it.name } &&
            width == null && height == null && steps == null && frames == null
        "DIFFUSION" -> contextTokens == null && keyCacheType == null && valueCacheType == null &&
            width in 8..DescriptorLimits.MAX_IMAGE_DIMENSION && height in 8..DescriptorLimits.MAX_IMAGE_DIMENSION &&
            steps in 1..WorkloadLimits.MAX_DIFFUSION_STEPS && frames in 1..WorkloadLimits.MAX_DIFFUSION_FRAMES &&
            batchSize in 1..WorkloadLimits.MAX_BATCH_SIZE
        else -> false
    }

    private fun WorkloadFixture.toLlmPlan(backend: BackendKind, topology: MemoryTopology) = LlmRunPlan(
        contextTokens = requireNotNull(contextTokens),
        batchSize = batchSize,
        microBatchSize = microBatchSize,
        sequenceCount = sequenceCount,
        keyCacheType = KvCacheType.valueOf(requireNotNull(keyCacheType)),
        valueCacheType = KvCacheType.valueOf(requireNotNull(valueCacheType)),
        backend = backend,
        memoryTopology = topology,
        gpuLayerCount = gpuLayerCount,
        useMmap = useMmap,
        compromises = emptyList(),
    )

    private fun WorkloadFixture.toDiffusionPlan(backend: BackendKind, topology: MemoryTopology) = DiffusionRunPlan(
        mode = if (requireNotNull(frames) > 1) DiffusionMode.VIDEO else DiffusionMode.IMAGE,
        width = requireNotNull(width),
        height = requireNotNull(height),
        frameCount = requireNotNull(frames),
        batchSize = batchSize,
        steps = requireNotNull(steps),
        vaeTiling = vaeTiling,
        offloadToCpu = offloadToCpu,
        keepClipOnCpu = keepClipOnCpu,
        keepVaeOnCpu = keepVaeOnCpu,
        maxVramBytes = maxVramBytes,
        layerStreaming = layerStreaming,
        requiresUserAcceptance = false,
        backend = backend,
        memoryTopology = topology,
        compromises = emptyList(),
    )

    private fun DeviceFixture.toSnapshot(topology: MemoryTopology) = when (topology) {
        MemoryTopology.UNIFIED -> task6Snapshot(
            hostBudget = null,
            sharedBudget = requireNotNull(baseSharedBytes),
            storageBudget = Long.MAX_VALUE / 4,
            topology = topology,
            backends = listOf(task6Backend(BackendKind.METAL)),
        )
        MemoryTopology.DISCRETE -> task6Snapshot(
            hostBudget = baseHostBytes,
            gpuBudget = requireNotNull(baseGpuBytes),
            storageBudget = Long.MAX_VALUE / 4,
            topology = topology,
            backends = listOf(task6Backend(BackendKind.CUDA)),
        )
        MemoryTopology.UNKNOWN -> task6Snapshot(
            hostBudget = baseHostBytes,
            storageBudget = Long.MAX_VALUE / 4,
            topology = topology,
        )
    }

    private fun PlanAssessment.rangeFor(topology: MemoryTopology): EstimateRange? = when (topology) {
        MemoryTopology.UNIFIED -> sharedMemoryBytes
        MemoryTopology.DISCRETE -> gpuMemoryBytes
        MemoryTopology.UNKNOWN -> hostMemoryBytes
    }

    private companion object {
        val REQUIRED_IDS = setOf(
            "llm-dense-q4-unified-fit",
            "llm-dense-q5-tight",
            "llm-dense-q8-no-fit",
            "llm-dense-f16-discrete",
            "llm-moe-q4-offload",
            "llm-hybrid-q4-recurrent",
            "llm-recurrent-q8-small",
            "llm-unknown-shape",
            "sd15-512-unified",
            "sdxl-1024-tiling",
            "flux-1024-streaming",
            "dit-discrete",
            "wan-video-8",
            "wan-video-32-no-fit",
            "bundle-missing-vae",
            "malformed-overflow",
        )
    }
}

private data class FixturePipelineResult(
    val recommendation: PersonalizedRecommendation,
    val descriptor: ModelDescriptor?,
    val planAssessment: PlanAssessment?,
)

@Serializable
private data class ModelFitCorpus(
    val schemaVersion: Int,
    val releaseEvidence: ReleaseEvidenceFixture,
    val cases: List<ModelFitCase>,
)

@Serializable
private data class ReleaseEvidenceFixture(
    val deviceMatrixVerified: Boolean,
    val pinnedPerformanceRunnerVerified: Boolean,
)

@Serializable
private data class ModelFitCase(
    val id: String,
    val descriptor: DescriptorFixture,
    val device: DeviceFixture,
    val workload: WorkloadFixture,
    val profile: ProfileFixture,
    val predicted: PredictedFixture,
    val expected: ExpectedFixture,
    val observed: ObservedFixture?,
)

@Serializable
private data class DescriptorFixture(
    val kind: String,
    val repositoryId: String,
    val revision: String,
    val architecture: String,
    val quantization: String?,
    val fileBytes: Long,
    val files: List<FileFixture>,
    val requirements: List<ComponentRequirementFixture>,
    val parameterCount: Long? = null,
    val contextLimit: Int? = null,
    val ggufVersion: Int? = null,
    val shape: TransformerShapeFixture? = null,
    val nativeWidth: Int? = null,
    val nativeHeight: Int? = null,
)

@Serializable
private data class FileFixture(
    val path: String,
    val sizeBytes: Long,
    val oid: String,
    val role: String?,
    val primary: Boolean,
) {
    fun toHubFile() = ModelFileTreeResponse(
        oid = oid,
        path = path,
        size = sizeBytes,
        type = "file",
    )
}

@Serializable
private data class ComponentRequirementFixture(
    val role: String,
    val path: String,
)

@Serializable
private data class TransformerShapeFixture(
    val layerCount: Int,
    val kvHeadCount: Int,
    val attentionHeadCount: Int,
    val hiddenSize: Int,
    val headDim: Int,
) {
    fun toTransformerConfig(contextLimit: Int?) = TransformerConfigResponse(
        numHiddenLayers = layerCount,
        numKeyValueHeads = kvHeadCount,
        numAttentionHeads = attentionHeadCount,
        hiddenSize = hiddenSize,
        headDim = headDim,
        maxPositionEmbeddings = contextLimit,
    )
}

@Serializable
private data class DeviceFixture(
    val topology: String,
    val baseHostBytes: Long?,
    val baseGpuBytes: Long?,
    val baseSharedBytes: Long?,
)

@Serializable
private data class WorkloadFixture(
    val contextTokens: Int? = null,
    val keyCacheType: String? = null,
    val valueCacheType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val steps: Int? = null,
    val frames: Int? = null,
    val batchSize: Int,
    val microBatchSize: Int = 1,
    val sequenceCount: Int = 1,
    val gpuLayerCount: Int? = null,
    val useMmap: Boolean = true,
    val vaeTiling: Boolean = false,
    val offloadToCpu: Boolean = false,
    val keepClipOnCpu: Boolean = false,
    val keepVaeOnCpu: Boolean = false,
    val maxVramBytes: Long? = null,
    val layerStreaming: Boolean = false,
)

@Serializable
private data class ProfileFixture(
    val risk: String,
    val priority: String,
)

@Serializable
private data class PredictedFixture(
    val lowBytes: Long,
    val likelyBytes: Long,
    val highBytes: Long,
) {
    companion object {
        val ZERO = PredictedFixture(0, 0, 0)
    }
}

@Serializable
private data class ExpectedFixture(
    val category: String,
    val reasons: List<String>,
    val estimatorReasons: List<String> = emptyList(),
)

@Serializable
private data class ObservedFixture(
    val loaded: Boolean,
    val sampleCount: Int,
    val highCoverage: Double,
    val medianAbsolutePercentageError: Double,
    val provenance: String,
)
