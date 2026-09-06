package com.debanshu777.caraml.features.modelhub.domain

import com.debanshu777.caraml.core.platform.DeviceSnapshot
import com.debanshu777.caraml.core.recommendation.ModelAssessment
import com.debanshu777.caraml.core.recommendation.PersonalizedRecommendation
import com.debanshu777.caraml.core.recommendation.RecommendationSortKey
import com.debanshu777.caraml.core.recommendation.WorkloadConfig
import com.debanshu777.huggingfacemanager.model.ListModelsResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    internal val sortKey: RecommendationSortKey? = null,
)

internal data class RecommendationCandidate(
    val model: ListModelsResponse.Model,
    val repositoryId: String?,
    val sourceIndex: Int,
)

internal data class AssessedVariant(
    val variant: RepositoryVariant,
    val stableIdentity: String,
    val assessment: ModelAssessment,
)

internal data class RepositoryEvaluation(
    val variants: List<AssessedVariant>,
)

class RecommendationQuerySession internal constructor(
    val queryId: String,
    internal val candidates: List<RecommendationCandidate>,
    val workload: WorkloadConfig,
    initialStates: List<RecommendedModelUiState>,
) {
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
            val callerJob = currentCoroutineContext()[Job]
            val evaluationJob = SupervisorJob(lifecycle)
            val callerHandle: DisposableHandle? = callerJob?.invokeOnCompletion { cause ->
                if (cause is CancellationException) evaluationJob.cancel(cause)
            }
            try {
                withContext(evaluationJob) {
                    currentCoroutineContext().ensureActive()
                    block()
                }
            } finally {
                callerHandle?.dispose()
                evaluationJob.cancel()
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
                val stable = left.stableModelId.compareTo(right.stableModelId)
                if (stable != 0) stable else left.sourceIndex.compareTo(right.sourceIndex)
            },
        )
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
