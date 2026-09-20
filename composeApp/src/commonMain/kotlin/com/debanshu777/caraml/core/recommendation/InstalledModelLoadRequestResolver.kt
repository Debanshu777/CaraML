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
    data class SafeAlternative(
        val primaryReason: AssessmentReason,
        val saferRequest: LoadRequest,
    ) : InstalledModelLoadResolution
    data object NeedsNetwork : InstalledModelLoadResolution
    data class NotAdmissible(
        val reason: AssessmentReason,
        val candidateBackend: BackendKind? = null,
    ) : InstalledModelLoadResolution
    data class Rejected(val reason: ArtifactIdentityRejection) : InstalledModelLoadResolution
    data object Failed : InstalledModelLoadResolution
}

sealed interface InstalledModelLoadPreparation {
    @ConsistentCopyVisibility
    data class Ready internal constructor(
        val model: LocalModelEntity,
        val expectedMode: GenerationMode,
        val descriptor: ModelDescriptor,
        val artifact: ResolvedLocalArtifact,
    ) : InstalledModelLoadPreparation

    @ConsistentCopyVisibility
    data class Terminal internal constructor(
        val resolution: InstalledModelLoadResolution,
    ) : InstalledModelLoadPreparation
}

interface InstalledModelLoadResolver {
    suspend fun prepare(
        model: LocalModelEntity,
        expectedMode: GenerationMode,
    ): InstalledModelLoadPreparation

    suspend fun resolve(
        preparation: InstalledModelLoadPreparation.Ready,
    ): InstalledModelLoadResolution
}

class InstalledModelLoadRequestResolver internal constructor(
    private val componentsForModel: suspend (String) -> List<DownloadedComponentEntity>,
    private val requireComplete: suspend (String, GenerationMode) -> EvidenceRepairResult,
    private val resolveArtifact: suspend (
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
) : InstalledModelLoadResolver {
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
        resolveArtifact = artifactResolver::resolve,
        captureSnapshot = snapshotProvider::capture,
        currentSettings = { settingsRepository.getSettings().first() },
        workloadFactory = workloadFactory,
        assess = assessmentRepository::assess,
        personalize = assessmentRepository::personalize,
        createStrictRequest = artifactResolver::createLoadRequestFromVerifiedArtifact,
    )

    override suspend fun prepare(
        model: LocalModelEntity,
        expectedMode: GenerationMode,
    ): InstalledModelLoadPreparation = try {
        prepareChecked(model, expectedMode)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        InstalledModelLoadPreparation.Terminal(InstalledModelLoadResolution.Failed)
    }

    override suspend fun resolve(
        preparation: InstalledModelLoadPreparation.Ready,
    ): InstalledModelLoadResolution = try {
        resolveChecked(preparation)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        InstalledModelLoadResolution.Failed
    }

    private suspend fun prepareChecked(
        model: LocalModelEntity,
        expectedMode: GenerationMode,
    ): InstalledModelLoadPreparation {
        val components = componentsForModel(model.modelId)
        val descriptor = when (val evidence = requireComplete(model.modelId, expectedMode)) {
            is EvidenceRepairResult.Ready -> evidence.descriptor
            EvidenceRepairResult.NeedsNetwork -> return InstalledModelLoadPreparation.Terminal(
                InstalledModelLoadResolution.NeedsNetwork,
            )
            is EvidenceRepairResult.Rejected -> {
                if (AssessmentReason.INVALID_METADATA in evidence.reasons) {
                    val artifactFailure = resolveArtifact(model, components)
                    if (artifactFailure is ArtifactIdentityResolution.Rejected) {
                        return InstalledModelLoadPreparation.Terminal(
                            InstalledModelLoadResolution.Rejected(artifactFailure.reason),
                        )
                    }
                }
                return InstalledModelLoadPreparation.Terminal(
                    InstalledModelLoadResolution.NotAdmissible(
                        evidence.reasons.firstOrNull() ?: AssessmentReason.INVALID_METADATA,
                    ),
                )
            }
        }
        if (descriptor.repositoryId != model.modelId) {
            return InstalledModelLoadPreparation.Terminal(
                InstalledModelLoadResolution.Rejected(ArtifactIdentityRejection.INVALID_INPUT),
            )
        }
        if (!descriptor.matches(expectedMode)) {
            return InstalledModelLoadPreparation.Terminal(
                InstalledModelLoadResolution.NotAdmissible(AssessmentReason.INCOMPATIBLE_MODEL),
            )
        }

        val artifact = when (val resolution = resolveArtifact(model, components)) {
            is ArtifactIdentityResolution.Verified -> resolution.artifact
            is ArtifactIdentityResolution.Rejected -> return InstalledModelLoadPreparation.Terminal(
                InstalledModelLoadResolution.Rejected(resolution.reason),
            )
        }
        if (!artifact.matchesOwner(model.modelId) ||
            !descriptor.requiredInstalledIdentities().hasSameExactInstalledIdentities(
                artifact.components.map(ResolvedArtifactComponent::identity),
            )
        ) {
            return InstalledModelLoadPreparation.Terminal(
                InstalledModelLoadResolution.Rejected(ArtifactIdentityRejection.STALE_MANIFEST),
            )
        }

        return InstalledModelLoadPreparation.Ready(model, expectedMode, descriptor, artifact)
    }

    private suspend fun resolveChecked(
        preparation: InstalledModelLoadPreparation.Ready,
    ): InstalledModelLoadResolution {
        val model = preparation.model
        val expectedMode = preparation.expectedMode
        val descriptor = preparation.descriptor
        val artifact = preparation.artifact
        if (descriptor.repositoryId != model.modelId || !descriptor.matches(expectedMode) ||
            !artifact.matchesOwner(model.modelId) ||
            !descriptor.requiredInstalledIdentities().hasSameExactInstalledIdentities(
                artifact.components.map(ResolvedArtifactComponent::identity),
            )
        ) {
            return InstalledModelLoadResolution.Rejected(ArtifactIdentityRejection.STALE_MANIFEST)
        }

        val capturedSnapshot = captureSnapshot()
        val settings = currentSettings()
        val cpuRequired =
            !settings.useGpu ||
            descriptor is LlmModelDescriptor && descriptor.architecture.requiresCpuOnlyLlmExecution()
        val assessmentSnapshot = if (cpuRequired) {
            capturedSnapshot.cpuOnly()
        } else {
            capturedSnapshot
        }
        val workload = workloadFactory.create(descriptor, expectedMode, settings)
            ?: return InstalledModelLoadResolution.NotAdmissible(AssessmentReason.INVALID_WORKLOAD)
        val primary = assessRequest(
            model = model,
            descriptor = descriptor,
            artifact = artifact,
            expectedMode = expectedMode,
            workload = workload,
            snapshot = assessmentSnapshot,
            profile = settings.recommendationProfile,
            requireCpu = cpuRequired,
        )
        if (cpuRequired) return primary

        if (primary is InstalledModelLoadResolution.NotAdmissible &&
            primary.candidateBackend != null &&
            primary.candidateBackend != BackendKind.CPU &&
            primary.reason in STATIC_CPU_ALTERNATIVE_REASONS
        ) {
            return when (
                val alternative = assessRequest(
                    model = model,
                    descriptor = descriptor,
                    artifact = artifact,
                    expectedMode = expectedMode,
                    workload = workload,
                    snapshot = capturedSnapshot.cpuOnly(),
                    profile = settings.recommendationProfile,
                    requireCpu = true,
                )
            ) {
                is InstalledModelLoadResolution.Ready -> InstalledModelLoadResolution.SafeAlternative(
                    primaryReason = primary.reason,
                    saferRequest = alternative.request.copy(backendAlternative = null),
                )
                is InstalledModelLoadResolution.Rejected -> alternative
                else -> primary
            }
        }

        if (primary !is InstalledModelLoadResolution.Ready) return primary
        val selected = primary.request.plan as? LlmRunPlan
        if (descriptor !is LlmModelDescriptor || selected == null || selected.backend == BackendKind.CPU) return primary

        return when (
            val alternative = assessRequest(
                model = model,
                descriptor = descriptor,
                artifact = artifact,
                expectedMode = expectedMode,
                workload = workload,
                snapshot = capturedSnapshot.cpuOnly(),
                profile = settings.recommendationProfile,
                requireCpu = true,
            )
        ) {
            is InstalledModelLoadResolution.Ready -> InstalledModelLoadResolution.Ready(
                primary.request.copy(
                    backendAlternative = alternative.request.copy(backendAlternative = null),
                ),
            )
            is InstalledModelLoadResolution.Rejected -> alternative
            InstalledModelLoadResolution.NeedsNetwork,
            is InstalledModelLoadResolution.SafeAlternative,
            is InstalledModelLoadResolution.NotAdmissible,
            InstalledModelLoadResolution.Failed,
            -> primary
        }
    }

    private suspend fun assessRequest(
        model: LocalModelEntity,
        descriptor: ModelDescriptor,
        artifact: ResolvedLocalArtifact,
        expectedMode: GenerationMode,
        workload: WorkloadConfig,
        snapshot: DeviceSnapshot,
        profile: RecommendationProfile,
        requireCpu: Boolean,
    ): InstalledModelLoadResolution {
        val assessment = assess(descriptor, snapshot, workload)
        if (!assessment.hasConsistentKeys()) {
            return InstalledModelLoadResolution.Rejected(ArtifactIdentityRejection.INVALID_INPUT)
        }
        val recommendation = personalize(
            assessment,
            snapshot,
            profile,
        )
        if (recommendation.assessmentKey != assessment.assessmentKey) {
            return InstalledModelLoadResolution.Rejected(ArtifactIdentityRejection.INVALID_INPUT)
        }
        recommendation.nonAdmissibleReason(assessment)?.let { reason ->
            return InstalledModelLoadResolution.NotAdmissible(
                reason = reason,
                candidateBackend = recommendation.candidateBackendIn(assessment),
            )
        }
        val selected = recommendation.selectedPlan as? RunPlan
            ?: return InstalledModelLoadResolution.NotAdmissible(
                reason = AssessmentReason.NO_RUN_PLAN,
                candidateBackend = recommendation.candidateBackendIn(assessment),
            )
        if (!selected.matches(expectedMode) || !selected.matches(workload) ||
            requireCpu && selected.backend != BackendKind.CPU
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

    private fun PersonalizedRecommendation.candidateBackendIn(
        assessment: ModelAssessment,
    ): BackendKind? {
        val selected = selectedPlan
        if (selected == null) {
            if (selectedPlanAssessment != null) return null
            val candidatePlans = assessment.planAssessments.values.map { candidate ->
                candidate.plan as? RunPlan ?: return null
            }
            return candidatePlans.map { it.backend }.distinct().singleOrNull()
        }
        val selectedRunPlan = selected as? RunPlan ?: return null
        val matchingAssessment = assessment.planAssessments.values
            .filter { it.plan.stableKey == selectedRunPlan.stableKey }
            .singleOrNull()
            ?: return null
        return selectedRunPlan.backend.takeIf { selectedPlanAssessment == matchingAssessment }
    }

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
        identity.repositoryId == modelId && when (val target = loadTarget) {
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

    private companion object {
        val STATIC_CPU_ALTERNATIVE_REASONS = setOf(
            AssessmentReason.MEMORY_NO_FIT,
            AssessmentReason.NO_RUN_PLAN,
        )
    }
}
