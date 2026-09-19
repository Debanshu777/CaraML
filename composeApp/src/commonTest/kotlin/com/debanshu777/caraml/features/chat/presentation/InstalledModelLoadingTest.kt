package com.debanshu777.caraml.features.chat.presentation

import com.debanshu777.caraml.core.data.inference.ModelLoadResult
import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.recommendation.ArtifactIdentityRejection
import com.debanshu777.caraml.core.recommendation.AssessedPlans
import com.debanshu777.caraml.core.recommendation.AssessmentConfidence
import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.Confidence
import com.debanshu777.caraml.core.recommendation.InstalledModelLoadResolution
import com.debanshu777.caraml.core.recommendation.KvCacheType
import com.debanshu777.caraml.core.recommendation.LlmRunPlan
import com.debanshu777.caraml.core.recommendation.LoadAdmission
import com.debanshu777.caraml.core.recommendation.LoadRequest
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.ObservationModelIdentity
import com.debanshu777.caraml.core.recommendation.PlanAssessment
import com.debanshu777.caraml.core.recommendation.ResolvedArtifactComponent
import com.debanshu777.caraml.core.recommendation.ResolvedLocalArtifact
import com.debanshu777.caraml.core.recommendation.RevisionIdentity
import com.debanshu777.caraml.core.recommendation.task6LlmDescriptor
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class InstalledModelLoadingTest {
    @Test
    fun textSelectionResolvesBeforeCallingOnlyTheExactTextLoader() = runTest {
        val calls = mutableListOf<String>()

        val result = loadInstalledModel(
            model = model,
            mode = GenerationMode.Text,
            resolve = { resolvedModel, resolvedMode ->
                assertSame(model, resolvedModel)
                assertEquals(GenerationMode.Text, resolvedMode)
                calls += "resolve"
                InstalledModelLoadResolution.Ready(request)
            },
            loadText = { exact ->
                calls += "release-diffusion"
                calls += "text:${exact.assessmentKey}"
                ModelLoadResult.Success(4_096)
            },
            loadDiffusion = { error("diffusion loader must not be called for text") },
        )

        assertEquals(4_096, assertIs<ModelLoadResult.Success>(result).contextSize)
        assertEquals(listOf("resolve", "release-diffusion", "text:assessment"), calls)
    }

    @Test
    fun imageAndVideoSelectionsCallOnlyTheExactDiffusionLoader() = runTest {
        listOf(GenerationMode.Image, GenerationMode.Video).forEach { mode ->
            val calls = mutableListOf<String>()

            val result = loadInstalledModel(
                model = model,
                mode = mode,
                resolve = { _, resolvedMode ->
                    calls += "resolve:$resolvedMode"
                    InstalledModelLoadResolution.Ready(request)
                },
                loadText = { error("text loader must not be called for $mode") },
                loadDiffusion = { exact ->
                    calls += "release-text"
                    calls += "diffusion:${exact.assessmentKey}"
                    ModelLoadResult.Success(0)
                },
            )

            assertIs<ModelLoadResult.Success>(result)
            assertEquals(
                listOf("resolve:$mode", "release-text", "diffusion:assessment"),
                calls,
            )
        }
    }

    @Test
    fun needsNetworkUsesOneTimeVerificationCopyWithoutCallingInference() = runTest {
        val calls = mutableListOf<String>()

        val result = loadInstalledModel(
            model = model,
            mode = GenerationMode.Text,
            resolve = { _, _ -> InstalledModelLoadResolution.NeedsNetwork },
            loadText = { calls += "text"; ModelLoadResult.Success(1) },
            loadDiffusion = { calls += "diffusion"; ModelLoadResult.Success(1) },
        )

        assertEquals(emptyList(), calls)
        assertEquals(
            ModelLoadResult.Error(
                "Connect once to verify this installed model's metadata, then try again.",
            ),
            result,
        )
    }

    @Test
    fun terminalResolutionFailuresUseFixedSafeCopyWithoutCallingInference() = runTest {
        val outcomes = listOf(
            InstalledModelLoadResolution.NotAdmissible(AssessmentReason.INVALID_METADATA) to
                "This installed model is not admissible on this device.",
            InstalledModelLoadResolution.Rejected(ArtifactIdentityRejection.STALE_MANIFEST) to
                "The installed model could not be verified.",
            InstalledModelLoadResolution.Failed to
                "The installed model could not be prepared right now. Try again.",
        )

        outcomes.forEach { (resolution, expectedMessage) ->
            var loadCalls = 0
            val result = loadInstalledModel(
                model = model,
                mode = GenerationMode.Text,
                resolve = { _, _ -> resolution },
                loadText = { loadCalls++; ModelLoadResult.Success(1) },
                loadDiffusion = { loadCalls++; ModelLoadResult.Success(1) },
            )

            assertEquals(0, loadCalls)
            assertEquals(ModelLoadResult.Error(expectedMessage), result)
        }
    }

    @Test
    fun resolverCancellationPropagatesWithoutCallingEitherRunner() = runTest {
        var loadCalls = 0

        assertFailsWith<CancellationException> {
            loadInstalledModel(
                model = model,
                mode = GenerationMode.Text,
                resolve = { _, _ -> throw CancellationException("cancelled") },
                loadText = { loadCalls++; ModelLoadResult.Success(1) },
                loadDiffusion = { loadCalls++; ModelLoadResult.Success(1) },
            )
        }

        assertEquals(0, loadCalls)
    }

    @Test
    fun exactRepositoryAdmissionResultIsPreserved() = runTest {
        val admission = LoadAdmission.Ready(request)

        val result = loadInstalledModel(
            model = model,
            mode = GenerationMode.Text,
            resolve = { _, _ -> InstalledModelLoadResolution.Ready(request) },
            loadText = { ModelLoadResult.AdmissionRequired(admission) },
            loadDiffusion = { error("unexpected diffusion load") },
        )

        assertSame(admission, assertIs<ModelLoadResult.AdmissionRequired>(result).admission)
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
            observationIdentity = requireNotNull(ObservationModelIdentity.fromDescriptor(task6LlmDescriptor())),
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
                            Confidence.LOW,
                            Confidence.LOW,
                            Confidence.LOW,
                            Confidence.LOW,
                        ),
                        evidence = emptyList(),
                    ),
                ),
                assessmentKey = "assessment",
            ),
        )
    }
}
