package com.debanshu777.caraml.features.modelhub.domain

import com.debanshu777.caraml.core.platform.DeviceSnapshot
import com.debanshu777.caraml.core.recommendation.ModelAssessment
import com.debanshu777.caraml.core.recommendation.BrowseFitEstimate
import com.debanshu777.caraml.core.recommendation.BrowseResourceFit
import com.debanshu777.caraml.core.recommendation.PersonalizedRecommendation
import com.debanshu777.caraml.core.recommendation.RecommendationSortKey
import com.debanshu777.caraml.core.recommendation.Compatibility
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.WorkloadConfig
import com.debanshu777.caraml.core.recommendation.ModelDescriptor
import com.debanshu777.huggingfacemanager.model.ListModelsResponse
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class DescriptorState {
    PENDING,
    CHECKING,
    ASSESSED,
    SELECT_VARIANT,
    NEEDS_INFORMATION,
}

enum class RecommendationOrdering {
    SERVER,
    PERSONALIZED,
}

data class RecommendedModelUiState(
    val sourceModel: ListModelsResponse.Model,
    val repositoryId: String?,
    val descriptorState: DescriptorState,
    val objectiveAssessment: ModelAssessment?,
    val personalizedResult: PersonalizedRecommendation?,
    val selectedVariantName: String?,
    val stableModelId: String,
    val sourceIndex: Int,
    val selectedDescriptor: ModelDescriptor? = null,
    /** Presentation-only estimate for a provisional browse candidate; never a load permit. */
    val browseFit: BrowseFitEstimate? = null,
    val provisionalVariantName: String? = null,
    val browseVariants: List<BrowseVariantUiState> = emptyList(),
    val workload: WorkloadConfig? = null,
    internal val sortKey: RecommendationSortKey? = null,
)

data class BrowseVariantUiState(
    val stableIdentity: String,
    val displayName: String,
    val filePaths: List<String>,
    val fileIdentities: List<ModelFileIdentity>,
    val estimate: BrowseFitEstimate,
)

data class BrowseSelectionProjection(
    val stableIdentity: String,
    val filePaths: List<String>,
    val displayName: String,
    val estimate: BrowseFitEstimate,
)

/** Projects fit and action identity together so a detail view cannot mix variants. */
internal fun projectBrowseSelection(
    variants: List<BrowseVariantUiState>,
    selectedPath: String?,
): BrowseSelectionProjection? {
    val selected = if (selectedPath != null) {
        variants.singleOrNull { selectedPath in it.filePaths && it.estimate.compatibility !is Compatibility.Incompatible }
            ?: return null
    } else {
        variants.firstOrNull { it.filePaths.size == 1 && it.estimate.compatibility !is Compatibility.Incompatible }
            ?: variants.firstOrNull { it.estimate.compatibility !is Compatibility.Incompatible }
    } ?: return null
    return BrowseSelectionProjection(
        stableIdentity = selected.stableIdentity,
        filePaths = selected.filePaths,
        displayName = selected.displayName,
        estimate = selected.estimate,
    )
}

internal fun matchesExactBrowseGroup(
    variant: BrowseVariantUiState,
    artifacts: List<DownloadArtifactIdentity>,
): Boolean {
    if (artifacts.isEmpty() || artifacts.size != variant.fileIdentities.size ||
        artifacts.map { it.relativePath }.toSet().size != artifacts.size
    ) return false
    return variant.fileIdentities.all { expected ->
        artifacts.singleOrNull { artifact ->
            val expectedObjectId = expected.lfsOid?.let { "sha256:$it" } ?: expected.xetHash ?: expected.gitOid
            expected.repositoryId == artifact.repositoryId &&
                expected.revision.equals(artifact.immutableRevision, ignoreCase = true) &&
                expected.path == artifact.relativePath &&
                expected.sizeBytes == artifact.expectedBytes &&
                expectedObjectId?.equals(artifact.remoteObjectId, ignoreCase = true) == true
        } != null
    }
}

internal data class RecommendationCandidate(
    val model: ListModelsResponse.Model,
    val repositoryId: String?,
    val sourceIndex: Int,
)

internal data class AssessedVariant(
    val variant: RepositoryVariant,
    val stableIdentity: String,
    val assessment: ModelAssessment,
    val browseFit: BrowseFitEstimate? = null,
)

internal data class RepositoryEvaluation(
    val variants: List<AssessedVariant>,
)

class RecommendationQuerySession internal constructor(
    val queryId: String,
    initialCandidates: List<RecommendationCandidate>,
    val workload: WorkloadConfig,
    initialStates: List<RecommendedModelUiState>,
) {
    internal var candidates: List<RecommendationCandidate> = initialCandidates
        private set
    private val lifecycle = SupervisorJob()
    private val evaluationMutex = Mutex()
    private val mutex = Mutex()
    private val _state = MutableStateFlow(initialStates.toList())

    val state: StateFlow<List<RecommendedModelUiState>> = _state.asStateFlow()

    internal var evaluatedCount: Int = 0
        private set
    internal var ordering: RecommendationOrdering = RecommendationOrdering.SERVER
        private set
    internal var snapshot: DeviceSnapshot? = null
        private set
    internal var evaluations: Map<Int, RepositoryEvaluation> = emptyMap()
        private set

    internal suspend fun reserveTo(targetCount: Int): IntRange = mutex.withLock {
        lifecycle.ensureActive()
        val boundedTarget = targetCount.coerceIn(evaluatedCount, candidates.size)
        val start = evaluatedCount
        evaluatedCount = boundedTarget
        if (boundedTarget > start) {
            val reserved = start until boundedTarget
            _state.value = _state.value.map { item ->
                if (item.sourceIndex in reserved && item.descriptorState == DescriptorState.PENDING) {
                    item.copy(descriptorState = DescriptorState.CHECKING)
                } else {
                    item
                }
            }
        }
        start until boundedTarget
    }

    internal suspend fun appendCandidates(newCandidates: List<RecommendationCandidate>): Int = mutex.withLock {
        lifecycle.ensureActive()
        if (newCandidates.isEmpty()) return@withLock 0
        require(newCandidates.first().sourceIndex == candidates.size)
        require(newCandidates.map { it.sourceIndex } == (candidates.size until candidates.size + newCandidates.size).toList())
        candidates = candidates + newCandidates
        _state.value = ordered(_state.value + newCandidates.map { candidate ->
            RecommendedModelUiState(
                sourceModel = candidate.model,
                repositoryId = candidate.repositoryId,
                descriptorState = if (candidate.repositoryId == null) DescriptorState.NEEDS_INFORMATION else DescriptorState.PENDING,
                objectiveAssessment = null,
                personalizedResult = null,
                selectedVariantName = null,
                stableModelId = candidate.repositoryId ?: "invalid-model-${candidate.sourceIndex}",
                sourceIndex = candidate.sourceIndex,
            )
        }, ordering)
        newCandidates.size
    }

    internal suspend fun snapshotOrCapture(capture: suspend () -> DeviceSnapshot): DeviceSnapshot = mutex.withLock {
        lifecycle.ensureActive()
        snapshot ?: capture().also { snapshot = it }
    }

    internal suspend fun replaceSnapshot(value: DeviceSnapshot) = mutex.withLock {
        lifecycle.ensureActive()
        snapshot = value
    }

    internal suspend fun recordEvaluation(
        sourceIndex: Int,
        evaluation: RepositoryEvaluation,
        state: RecommendedModelUiState,
    ) = mutex.withLock {
        lifecycle.ensureActive()
        evaluations = evaluations + (sourceIndex to evaluation)
        replaceAndPublish(state)
    }

    internal suspend fun recordTerminal(state: RecommendedModelUiState) = mutex.withLock {
        lifecycle.ensureActive()
        replaceAndPublish(state)
    }

    internal suspend fun recordsSnapshot(): Pair<DeviceSnapshot?, Map<Int, RepositoryEvaluation>> = mutex.withLock {
        lifecycle.ensureActive()
        snapshot to evaluations
    }

    internal suspend fun replaceReranked(
        value: Map<Int, RepositoryEvaluation>,
        states: Map<Int, RecommendedModelUiState>,
    ) = mutex.withLock {
        lifecycle.ensureActive()
        evaluations = value.toMap()
        val replacements = states.toMap()
        _state.value = ordered(
            _state.value.map { replacements[it.sourceIndex] ?: it },
            ordering,
        )
    }

    internal suspend fun setOrdering(value: RecommendationOrdering) = mutex.withLock {
        lifecycle.ensureActive()
        ordering = value
        _state.value = ordered(_state.value, value)
    }

    internal suspend fun <T> runEvaluation(block: suspend () -> T): T {
        return evaluationMutex.withLock {
            lifecycle.ensureActive()
            coroutineScope {
                val evaluationJob = checkNotNull(currentCoroutineContext()[Job])
                val lifecycleHandle = lifecycle.invokeOnCompletion { cause ->
                    if (cause != null) {
                        evaluationJob.cancel(cause as? CancellationException ?: QuerySupersededCancellationException())
                    }
                }
                try {
                    lifecycle.ensureActive()
                    currentCoroutineContext().ensureActive()
                    block()
                } finally {
                    lifecycleHandle.dispose()
                }
            }
        }
    }

    internal fun cancel() {
        lifecycle.cancel(QuerySupersededCancellationException())
    }

    private fun replaceAndPublish(value: RecommendedModelUiState) {
        _state.value = ordered(
            _state.value.map { if (it.sourceIndex == value.sourceIndex) value else it },
            ordering,
        )
    }

    private fun ordered(
        values: List<RecommendedModelUiState>,
        value: RecommendationOrdering,
    ): List<RecommendedModelUiState> = when (value) {
        RecommendationOrdering.SERVER -> values.sortedBy { it.sourceIndex }
        RecommendationOrdering.PERSONALIZED -> values.sortedWith(
            Comparator { left, right ->
                val leftTerminal = left.descriptorState.isTerminal()
                val rightTerminal = right.descriptorState.isTerminal()
                if (leftTerminal != rightTerminal) {
                    return@Comparator if (leftTerminal) -1 else 1
                }
                if (!leftTerminal) return@Comparator left.sourceIndex.compareTo(right.sourceIndex)
                val leftKey = left.sortKey
                val rightKey = right.sortKey
                if (leftKey != null && rightKey != null) {
                    val ranked = leftKey.compareTo(rightKey)
                    if (ranked != 0) return@Comparator ranked
                } else if (leftKey != null || rightKey != null) {
                    return@Comparator if (leftKey != null) -1 else 1
                }
                val provisional = provisionalRank(left).compareTo(provisionalRank(right))
                if (provisional != 0) provisional else left.sourceIndex.compareTo(right.sourceIndex)
            },
        )
    }
}

private fun provisionalRank(value: RecommendedModelUiState): Int {
    val fit = value.browseFit ?: return 6
    if (fit.compatibility is Compatibility.Incompatible) return 5
    return when (fit.memoryFit) {
        BrowseResourceFit.LIKELY_FIT -> when (fit.storageFit) {
            BrowseResourceFit.LIKELY_FIT -> 0
            BrowseResourceFit.TIGHT_FIT, BrowseResourceFit.UNKNOWN -> 1
            BrowseResourceFit.TOO_LARGE -> 4
        }
        BrowseResourceFit.TIGHT_FIT -> if (fit.storageFit == BrowseResourceFit.TOO_LARGE) 4 else 2
        BrowseResourceFit.UNKNOWN -> if (fit.storageFit == BrowseResourceFit.TOO_LARGE) 4 else 3
        BrowseResourceFit.TOO_LARGE -> 4
    }
}

private fun DescriptorState.isTerminal(): Boolean = when (this) {
    DescriptorState.ASSESSED,
    DescriptorState.SELECT_VARIANT,
    DescriptorState.NEEDS_INFORMATION,
    -> true
    DescriptorState.PENDING,
    DescriptorState.CHECKING,
    -> false
}

internal class QuerySupersededCancellationException : CancellationException("Recommendation query superseded")
