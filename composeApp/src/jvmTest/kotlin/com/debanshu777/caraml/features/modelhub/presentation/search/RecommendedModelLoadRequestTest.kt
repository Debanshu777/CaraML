package com.debanshu777.caraml.features.modelhub.presentation.search

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.recommendation.AssessedPlans
import com.debanshu777.caraml.core.recommendation.AssessmentConfidence
import com.debanshu777.caraml.core.recommendation.Compatibility
import com.debanshu777.caraml.core.recommendation.Confidence
import com.debanshu777.caraml.core.recommendation.KvCacheType
import com.debanshu777.caraml.core.recommendation.LlmModelDescriptor
import com.debanshu777.caraml.core.recommendation.LlmRunPlan
import com.debanshu777.caraml.core.recommendation.LoadRequest
import com.debanshu777.caraml.core.recommendation.LoadRequestResolution
import com.debanshu777.caraml.core.recommendation.ModelAssessment
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.ObservationModelIdentity
import com.debanshu777.caraml.core.recommendation.PersonalizedRecommendation
import com.debanshu777.caraml.core.recommendation.QuantizationEvidence
import com.debanshu777.caraml.core.recommendation.RecommendationCategory
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.recommendation.RecommendationRolloutMode
import com.debanshu777.caraml.core.storage.component.DownloadedComponentEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.features.modelhub.domain.RecommendedModelUiState
import com.debanshu777.huggingfacemanager.model.ListModelsResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class RecommendedModelLoadRequestTest {

    @Test
    fun legacySelectionDoesNotResolveOrHashAnExactArtifact() = runTest {
        val fixture = fixture()
        var resolutions = 0
        var legacySelection: LocalModelEntity? = null

        routeRecommendedModelSelection(
            mode = RecommendationRolloutMode.LEGACY,
            selectLegacy = { legacySelection = fixture.model },
            selectAssessed = {
                resolutions++
                error("legacy selection must not resolve exact bytes")
            },
        )

        assertEquals(0, resolutions)
        assertSame(fixture.model, legacySelection)
    }

    @Test
    fun exactRecommendationObjectsAreForwardedWithoutReconstruction() = runTest {
        val fixture = fixture()
        val component = DownloadedComponentEntity(
            id = 7L,
            repoId = fixture.model.modelId,
            filePath = fixture.descriptor.file.path,
            role = "model",
            localPath = fixture.model.localPath,
            sizeBytes = fixture.model.sizeBytes,
            downloadedAt = 1L,
        )
        var componentOwner: String? = null

        val result = resolveSelectedModelLoadRequest(
            model = fixture.model,
            states = listOf(fixture.state),
            componentsForModel = { owner ->
                componentOwner = owner
                listOf(component)
            },
            createRequest = { model, components, descriptor, assessment, recommendation ->
                assertSame(fixture.model, model)
                assertSame(component, components.single())
                assertSame(fixture.descriptor, descriptor)
                assertSame(fixture.assessment, assessment)
                assertSame(fixture.recommendation, recommendation)
                LoadRequestResolution.Ready(fixture.request)
            },
        )

        assertSame(fixture.model.modelId, componentOwner)
        assertSame(fixture.request, result)
    }

    @Test
    fun missingOrAmbiguousRecommendationFailsClosedBeforeArtifactAccess() = runTest {
        val fixture = fixture()
        var accesses = 0
        val componentSource: suspend (String) -> List<DownloadedComponentEntity> = {
            accesses++
            emptyList()
        }
        val resolver: suspend (
            LocalModelEntity,
            List<DownloadedComponentEntity>,
            com.debanshu777.caraml.core.recommendation.ModelDescriptor,
            ModelAssessment,
            PersonalizedRecommendation,
        ) -> LoadRequestResolution = { _, _, _, _, _ -> error("must not resolve") }

        assertNull(resolveSelectedModelLoadRequest(fixture.model, emptyList(), componentSource, resolver))
        assertNull(
            resolveSelectedModelLoadRequest(
                fixture.model,
                listOf(fixture.state, fixture.state.copy(sourceIndex = 1)),
                componentSource,
                resolver,
            ),
        )
        assertNull(
            resolveSelectedModelLoadRequest(
                fixture.model,
                listOf(fixture.state.copy(personalizedResult = null)),
                componentSource,
                resolver,
            ),
        )
        assertEquals(0, accesses)
    }

    private fun fixture(): Fixture {
        val model = LocalModelEntity(
            modelId = "owner/model",
            filename = "model.gguf",
            localPath = "/models/owner/model/model.gguf",
            sizeBytes = 1_024L,
            downloadedAt = 1L,
            author = null,
            libraryName = null,
            pipelineTag = "text-generation",
        )
        val identity = ModelFileIdentity(
            repositoryId = model.modelId,
            revision = "a".repeat(40),
            path = model.filename,
            sizeBytes = requireNotNull(model.sizeBytes),
            gitOid = "b".repeat(40),
            lfsOid = null,
            xetHash = null,
            evidence = emptyList(),
        )
        val descriptor = LlmModelDescriptor(
            repositoryId = model.modelId,
            revision = identity.revision,
            file = identity,
            architecture = "llama",
            quantization = QuantizationEvidence.Unknown,
            parameterCount = null,
            contextLimit = 2_048,
            transformerShape = null,
            ggufVersion = 3,
            requiredEngineFeatures = emptyList(),
            evidence = emptyList(),
        )
        val plan = LlmRunPlan(
            contextTokens = 512,
            batchSize = 32,
            microBatchSize = 32,
            sequenceCount = 1,
            keyCacheType = KvCacheType.F16,
            valueCacheType = KvCacheType.F16,
            backend = BackendKind.CPU,
            memoryTopology = MemoryTopology.UNIFIED,
            gpuLayerCount = 0,
            compromises = emptyList(),
        )
        val key = "assessment-key"
        val assessment = ModelAssessment(
            assessmentKey = key,
            compatibility = Compatibility.Compatible,
            planAssessments = AssessedPlans(emptyList(), assessmentKey = key),
            baseHostBudgetBytes = 1_000_000L,
            baseGpuBudgetBytes = null,
            baseSharedBudgetBytes = null,
            baseStorageBudgetBytes = 1_000_000L,
            confidence = AssessmentConfidence(
                compatibility = Confidence.HIGH,
                memory = Confidence.HIGH,
                storage = Confidence.HIGH,
                performance = Confidence.HIGH,
            ),
            evidence = emptyList(),
        )
        val recommendation = PersonalizedRecommendation(
            assessmentKey = key,
            category = RecommendationCategory.RECOMMENDED,
            selectedPlan = plan,
            reasons = emptyList(),
            profile = RecommendationProfile(),
        )
        val request = LoadRequest(
            model = model,
            identity = identity,
            observationIdentity = requireNotNull(ObservationModelIdentity.fromDescriptor(descriptor)),
            plan = plan,
            assessmentKey = key,
        )
        val state = RecommendedModelUiState(
            sourceModel = ListModelsResponse.Model(id = model.modelId),
            repositoryId = model.modelId,
            descriptorState = DescriptorState.ASSESSED,
            objectiveAssessment = assessment,
            personalizedResult = recommendation,
            selectedVariantName = model.filename,
            stableModelId = model.modelId,
            sourceIndex = 0,
            selectedDescriptor = descriptor,
        )
        return Fixture(model, descriptor, assessment, recommendation, request, state)
    }

    private data class Fixture(
        val model: LocalModelEntity,
        val descriptor: LlmModelDescriptor,
        val assessment: ModelAssessment,
        val recommendation: PersonalizedRecommendation,
        val request: LoadRequest,
        val state: RecommendedModelUiState,
    )
}
