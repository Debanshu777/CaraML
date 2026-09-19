package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.DeviceSnapshot
import com.debanshu777.caraml.core.platform.HardwareProfile
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.storage.component.ComponentRepository
import com.debanshu777.caraml.core.storage.component.DownloadedComponentEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

sealed interface InstalledModelLoadResolution {
    data class Ready(val request: LoadRequest) : InstalledModelLoadResolution
    data object NeedsNetwork : InstalledModelLoadResolution
    data class NotAdmissible(val reason: AssessmentReason) : InstalledModelLoadResolution
    data class Rejected(val reason: ArtifactIdentityRejection) : InstalledModelLoadResolution
    data object Failed : InstalledModelLoadResolution
}

class InstalledModelLoadRequestResolver internal constructor(
    private val componentsForModel: suspend (String) -> List<DownloadedComponentEntity>,
    private val requireComplete: suspend (
        LocalModelEntity,
        List<DownloadedComponentEntity>,
        GenerationMode,
    ) -> EvidenceRepairResult,
    private val resolvePersistedHub: suspend (
        LocalModelEntity,
        List<DownloadedComponentEntity>,
    ) -> ArtifactIdentityResolution,
    private val captureSnapshot: suspend () -> DeviceSnapshot,
    private val currentSettings: suspend () -> AppSettings,
    private val workloadFactory: InstalledModelWorkloadFactory,
    private val assess: suspend (ModelDescriptor, DeviceSnapshot, WorkloadConfig) -> ModelAssessment,
    private val personalize: (
        ModelAssessment,
        DeviceSnapshot,
        RecommendationProfile,
    ) -> PersonalizedRecommendation,
    private val createStrictRequest: suspend (
        LocalModelEntity,
        ModelDescriptor,
        ResolvedLocalArtifact,
        ModelAssessment,
        PersonalizedRecommendation,
    ) -> LoadRequestResolution,
) {
    constructor(
        componentRepository: ComponentRepository,
        evidenceRepairer: InstalledModelEvidenceRepairer,
        artifactResolver: LocalArtifactIdentityResolver,
        snapshotProvider: DeviceSnapshotProvider,
        assessmentRepository: ModelAssessmentRepository,
        settingsRepository: SettingsRepository,
        workloadFactory: InstalledModelWorkloadFactory,
    ) : this(
        componentsForModel = componentRepository::getComponentsForModel,
        requireComplete = evidenceRepairer::requireComplete,
        resolvePersistedHub = artifactResolver::resolvePersistedHub,
        captureSnapshot = snapshotProvider::capture,
        currentSettings = { settingsRepository.getSettings().first() },
        workloadFactory = workloadFactory,
        assess = assessmentRepository::assess,
        personalize = assessmentRepository::personalize,
        createStrictRequest = artifactResolver::createLoadRequestFromVerifiedArtifact,
    )

    suspend fun resolve(
        model: LocalModelEntity,
        expectedMode: GenerationMode,
    ): InstalledModelLoadResolution = try {
        resolveChecked(model, expectedMode)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        InstalledModelLoadResolution.Failed
    }

    private suspend fun resolveChecked(
        model: LocalModelEntity,
        expectedMode: GenerationMode,
    ): InstalledModelLoadResolution {
        val components = componentsForModel(model.modelId)
        val descriptor = when (val evidence = requireComplete(model, components, expectedMode)) {
            is EvidenceRepairResult.Ready -> evidence.descriptor
            EvidenceRepairResult.NeedsNetwork -> return InstalledModelLoadResolution.NeedsNetwork
            is EvidenceRepairResult.Rejected -> {
                if (AssessmentReason.INVALID_METADATA in evidence.reasons) {
                    val artifactFailure = resolvePersistedHub(model, components)
                    if (artifactFailure is ArtifactIdentityResolution.Rejected) {
                        return InstalledModelLoadResolution.Rejected(artifactFailure.reason)
                    }
                }
                return InstalledModelLoadResolution.NotAdmissible(
                    evidence.reasons.firstOrNull() ?: AssessmentReason.INVALID_METADATA,
                )
            }
        }
        if (descriptor.repositoryId != model.modelId) {
            return InstalledModelLoadResolution.Rejected(ArtifactIdentityRejection.INVALID_INPUT)
        }
        if (!descriptor.matches(expectedMode)) {
            return InstalledModelLoadResolution.NotAdmissible(AssessmentReason.INCOMPATIBLE_MODEL)
        }

        val artifact = when (val resolution = resolvePersistedHub(model, components)) {
            is ArtifactIdentityResolution.Verified -> resolution.artifact
            is ArtifactIdentityResolution.Rejected -> return InstalledModelLoadResolution.Rejected(resolution.reason)
        }
        if (!artifact.matchesOwner(model.modelId) ||
            !descriptor.requiredInstalledIdentities().hasSameExactInstalledIdentities(
                artifact.components.map(ResolvedArtifactComponent::identity),
            )
        ) {
            return InstalledModelLoadResolution.Rejected(ArtifactIdentityRejection.STALE_MANIFEST)
        }

        val capturedSnapshot = captureSnapshot()
        val settings = currentSettings()
        val assessmentSnapshot = if (
            !settings.useGpu ||
            descriptor is LlmModelDescriptor && descriptor.architecture.requiresCpuOnlyLlmExecution()
        ) {
            capturedSnapshot.cpuOnly()
        } else {
            capturedSnapshot
        }
        val workload = workloadFactory.create(descriptor, expectedMode, settings)
            ?: return InstalledModelLoadResolution.NotAdmissible(AssessmentReason.INVALID_WORKLOAD)
        val assessment = assess(descriptor, assessmentSnapshot, workload)
        if (!assessment.hasConsistentKeys()) {
            return InstalledModelLoadResolution.Rejected(ArtifactIdentityRejection.INVALID_INPUT)
        }
        val recommendation = personalize(
            assessment,
            assessmentSnapshot,
            settings.recommendationProfile,
        )
        if (recommendation.assessmentKey != assessment.assessmentKey) {
            return InstalledModelLoadResolution.Rejected(ArtifactIdentityRejection.INVALID_INPUT)
        }
        recommendation.nonAdmissibleReason(assessment)?.let { reason ->
            return InstalledModelLoadResolution.NotAdmissible(reason)
        }
        val selected = recommendation.selectedPlan as? RunPlan
            ?: return InstalledModelLoadResolution.NotAdmissible(AssessmentReason.NO_RUN_PLAN)
        if (!selected.matches(expectedMode) || !selected.matches(workload) ||
            (!settings.useGpu ||
                descriptor is LlmModelDescriptor && descriptor.architecture.requiresCpuOnlyLlmExecution()) &&
            selected.backend != BackendKind.CPU
        ) {
            return InstalledModelLoadResolution.Rejected(ArtifactIdentityRejection.INVALID_INPUT)
        }
        val matchingAssessment = assessment.planAssessments.values
            .filter { it.plan.stableKey == selected.stableKey }
            .singleOrNull()
        if (matchingAssessment == null || recommendation.selectedPlanAssessment != matchingAssessment) {
            return InstalledModelLoadResolution.Rejected(ArtifactIdentityRejection.INVALID_INPUT)
        }
        return when (
            val request = createStrictRequest(model, descriptor, artifact, assessment, recommendation)
        ) {
            is LoadRequestResolution.Ready -> InstalledModelLoadResolution.Ready(request.request)
            is LoadRequestResolution.Rejected -> InstalledModelLoadResolution.Rejected(request.reason)
        }
    }

    private fun ModelAssessment.hasConsistentKeys(): Boolean =
        assessmentKey.isNotBlank() && assessmentKey == planAssessments.assessmentKey &&
            compatibility == planAssessments.compatibility

    private fun PersonalizedRecommendation.nonAdmissibleReason(
        assessment: ModelAssessment,
    ): AssessmentReason? = when (category) {
        RecommendationCategory.RECOMMENDED,
        RecommendationCategory.USABLE,
        RecommendationCategory.RISKY,
        -> null
        RecommendationCategory.NEEDS_INFORMATION -> reasonFrom(
            assessment,
            AssessmentReason.RECOMMENDATION_EVIDENCE_INCOMPLETE,
        )
        RecommendationCategory.NOT_SUITABLE -> reasonFrom(assessment, AssessmentReason.NO_RUN_PLAN)
        RecommendationCategory.INCOMPATIBLE -> reasonFrom(assessment, AssessmentReason.INCOMPATIBLE_MODEL)
    }

    private fun PersonalizedRecommendation.reasonFrom(
        assessment: ModelAssessment,
        fallback: AssessmentReason,
    ): AssessmentReason = reasons.firstOrNull()
        ?: assessment.planAssessments.reasons.firstOrNull()
        ?: when (val compatibility = assessment.compatibility) {
            is Compatibility.Incompatible -> compatibility.reasons.firstOrNull()
            is Compatibility.Unknown -> compatibility.reasons.firstOrNull()
            Compatibility.Compatible -> null
        }
        ?: fallback

    private fun ModelDescriptor.matches(mode: GenerationMode): Boolean = when (this) {
        is LlmModelDescriptor -> mode == GenerationMode.Text
        is DiffusionModelDescriptor -> when (this.mode) {
            DiffusionMode.IMAGE -> mode == GenerationMode.Image
            DiffusionMode.VIDEO -> mode == GenerationMode.Video
        }
    }

    private fun RunPlan.matches(mode: GenerationMode): Boolean = when (this) {
        is LlmRunPlan -> mode == GenerationMode.Text
        is DiffusionRunPlan -> when (this.mode) {
            DiffusionMode.IMAGE -> mode == GenerationMode.Image
            DiffusionMode.VIDEO -> mode == GenerationMode.Video
        }
    }

    private fun RunPlan.matches(workload: WorkloadConfig): Boolean = when {
        this is LlmRunPlan && workload is LlmWorkloadConfig ->
            contextTokens in workload.minimumContextTokens..workload.contextTokens &&
                batchSize in 1..workload.batchSize &&
                microBatchSize in 1..minOf(workload.microBatchSize, batchSize) &&
                sequenceCount == workload.sequenceCount &&
                keyCacheType in workload.allowedKvCacheTypes && valueCacheType in workload.allowedKvCacheTypes &&
                when (val selection = workload.kvCacheSelection) {
                    KvCacheSelection.Auto -> true
                    is KvCacheSelection.Explicit ->
                        keyCacheType == selection.keyType && valueCacheType == selection.valueType
                }
        this is DiffusionRunPlan && workload is DiffusionWorkloadConfig ->
            mode == workload.mode &&
                width in workload.minimumWidth..workload.width &&
                height in workload.minimumHeight..workload.height &&
                frameCount in workload.minimumFrameCount..workload.frameCount &&
                batchSize == workload.batchSize && steps == workload.steps &&
                (width == workload.width && height == workload.height || workload.allowResolutionFallback) &&
                (frameCount == workload.frameCount || workload.allowFrameCountFallback)
        else -> false
    }

    private fun ResolvedLocalArtifact.matchesOwner(modelId: String): Boolean =
        revisionIdentity is RevisionIdentity.HubCommit && identity.repositoryId == modelId && when (val target = loadTarget) {
            is VerifiedArtifactLoadTarget.File -> target.repositoryId == modelId
            is VerifiedArtifactLoadTarget.Directory -> target.storageOwner == modelId
        }

    private fun DeviceSnapshot.cpuOnly(): DeviceSnapshot = DeviceSnapshot(
        hardwareProfile = hardwareProfile.cpuOnly(),
        resources = resources,
        baseHostBudgetBytes = baseHostBudgetBytes,
        baseGpuBudgetBytes = null,
        baseSharedBudgetBytes = baseSharedBudgetBytes,
        baseStorageBudgetBytes = baseStorageBudgetBytes,
        isFresh = isFresh,
        evidence = evidence,
        budgetConfidence = budgetConfidence.copy(gpu = null),
    )

    private fun HardwareProfile.cpuOnly(): HardwareProfile = HardwareProfile(
        cpuArchitecture = cpuArchitecture,
        logicalCoreCount = logicalCoreCount,
        performanceCoreCount = performanceCoreCount,
        instructionSets = instructionSets,
        backends = backends.filter { it.kind == BackendKind.CPU },
        memoryTopology = memoryTopology,
        evidence = evidence,
    )
}
