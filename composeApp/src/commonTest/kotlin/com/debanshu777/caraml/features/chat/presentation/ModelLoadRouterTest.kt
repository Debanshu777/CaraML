package com.debanshu777.caraml.features.chat.presentation

import com.debanshu777.caraml.core.data.inference.ModelLoadResult
import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.recommendation.KvCacheType
import com.debanshu777.caraml.core.recommendation.AssessedPlans
import com.debanshu777.caraml.core.recommendation.AssessmentConfidence
import com.debanshu777.caraml.core.recommendation.Confidence
import com.debanshu777.caraml.core.recommendation.LlmRunPlan
import com.debanshu777.caraml.core.recommendation.LoadRequest
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.RecommendationRolloutMode
import com.debanshu777.caraml.core.recommendation.PlanAssessment
import com.debanshu777.caraml.core.recommendation.ResolvedArtifactComponent
import com.debanshu777.caraml.core.recommendation.ResolvedLocalArtifact
import com.debanshu777.caraml.core.recommendation.RevisionIdentity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ModelLoadRouterTest {
    @Test
    fun legacyModePreservesModelOnlyLoader() = runTest {
        var legacyCalls = 0
        var exactCalls = 0
        val result = ModelLoadRouter.route(
            rolloutMode = RecommendationRolloutMode.LEGACY,
            model = model,
            request = null,
            legacyLoad = { legacyCalls++; ModelLoadResult.Success(1) },
            exactLoad = { exactCalls++; ModelLoadResult.Success(2) },
        )

        assertIs<ModelLoadResult.Success>(result)
        assertEquals(1, legacyCalls)
        assertEquals(0, exactCalls)
    }

    @Test
    fun v2WithoutExactCarrierFailsClosedBeforeEitherNativeLoader() = runTest {
        var legacyCalls = 0
        var exactCalls = 0
        val result = ModelLoadRouter.route(
            rolloutMode = RecommendationRolloutMode.V2,
            model = model,
            request = null,
            legacyLoad = { legacyCalls++; ModelLoadResult.Success(1) },
            exactLoad = { exactCalls++; ModelLoadResult.Success(2) },
        )

        assertIs<ModelLoadResult.Error>(result)
        assertEquals(0, legacyCalls)
        assertEquals(0, exactCalls)
    }

    @Test
    fun v2WithBoundVerifiedCarrierUsesOnlyExactLoader() = runTest {
        var legacyCalls = 0
        var exactCalls = 0
        val result = ModelLoadRouter.route(
            rolloutMode = RecommendationRolloutMode.V2,
            model = model,
            request = request,
            legacyLoad = { legacyCalls++; ModelLoadResult.Success(1) },
            exactLoad = { exactCalls++; ModelLoadResult.Success(2) },
        )

        assertEquals(2, assertIs<ModelLoadResult.Success>(result).contextSize)
        assertEquals(0, legacyCalls)
        assertEquals(1, exactCalls)
    }

    @Test
    fun v2PlanAndRequestedModeMismatchCallsNeitherNativeLoader() = runTest {
        var legacyCalls = 0
        var exactCalls = 0
        val result = ModelLoadRouter.route(
            rolloutMode = RecommendationRolloutMode.V2,
            model = model,
            request = request,
            expectedMode = GenerationMode.Video,
            legacyLoad = { legacyCalls++; ModelLoadResult.Success(1) },
            exactLoad = { exactCalls++; ModelLoadResult.Success(2) },
        )

        assertIs<ModelLoadResult.Error>(result)
        assertEquals(0, legacyCalls)
        assertEquals(0, exactCalls)
    }

    private companion object {
        val model = LocalModelEntity(
            modelId = "owner/model",
            filename = "model.gguf",
            localPath = "/private/model.gguf",
            sizeBytes = 4,
            downloadedAt = 1,
            author = null,
            libraryName = null,
            pipelineTag = "text-generation",
        )
        private val identity = ModelFileIdentity(
            repositoryId = "owner/model",
            revision = "a".repeat(40),
            path = "model.gguf",
            sizeBytes = 4,
            gitOid = "b".repeat(40),
            lfsOid = null,
            xetHash = null,
            evidence = emptyList(),
        )
        private val plan = LlmRunPlan(
            contextTokens = 4_096,
            batchSize = 128,
            microBatchSize = 64,
            sequenceCount = 1,
            keyCacheType = KvCacheType.Q8_0,
            valueCacheType = KvCacheType.Q8_0,
            backend = BackendKind.CPU,
            memoryTopology = MemoryTopology.UNKNOWN,
            gpuLayerCount = 0,
            compromises = emptyList(),
        )
        val request = LoadRequest(
            model = model,
            identity = identity,
            plan = plan,
            assessmentKey = "assessment",
            artifact = ResolvedLocalArtifact(
                identity = identity,
                revisionIdentity = RevisionIdentity.HubCommit(
                    listOf(com.debanshu777.caraml.core.recommendation.RepositoryCommit("owner/model", "a".repeat(40))),
                ),
                components = listOf(
                    ResolvedArtifactComponent(
                        "model", "owner/model", "model.gguf", "/private/model.gguf", 4,
                        "c".repeat(64), identity,
                    ),
                ),
                loadTarget = com.debanshu777.caraml.core.recommendation.VerifiedArtifactLoadTarget.File(
                    path = "/private/model.gguf",
                    componentRole = "model",
                    repositoryId = "owner/model",
                    localRelativePath = "model.gguf",
                ),
            ),
            assessedPlans = AssessedPlans(
                values = listOf(
                    PlanAssessment(
                        plan = plan,
                        hostMemoryBytes = null,
                        gpuMemoryBytes = null,
                        sharedMemoryBytes = null,
                        storageBytes = null,
                        confidence = AssessmentConfidence(
                            Confidence.LOW, Confidence.LOW, Confidence.LOW, Confidence.LOW,
                        ),
                        evidence = emptyList(),
                    ),
                ),
                assessmentKey = "assessment",
            ),
        )
    }
}
