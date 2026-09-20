package com.debanshu777.caraml.core.storage.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class InstalledModelPublicationCoordinator(
    stripeCount: Int = DEFAULT_STRIPE_COUNT,
) {
    private val publicationStripes: Array<Mutex>
    private val repairFlightStripes: Array<RepairFlightStripe>

    init {
        require(stripeCount in 2..MAX_STRIPE_COUNT) { "Invalid publication stripe count" }
        publicationStripes = Array(stripeCount) { Mutex() }
        repairFlightStripes = Array(stripeCount) { RepairFlightStripe() }
    }

    suspend fun <T> withOwnerPublication(
        ownerModelId: String,
        block: suspend () -> T,
    ): T {
        val ownerKey = normalizedOwnerKey(ownerModelId)
        return publicationStripes[stripeIndex(ownerKey)].withLock { block() }
    }

    internal suspend fun <T : Any> coalesceRepair(
        ownerModelId: String,
        operationKey: String,
        block: suspend () -> T,
    ): T {
        val exactOwnerKey = validatedOwnerKey(ownerModelId)
        val stripeOwnerKey = exactOwnerKey.lowercase()
        require(
            operationKey.isNotBlank() && operationKey.length <= MAX_OPERATION_KEY_LENGTH &&
                operationKey.none(Char::isISOControl),
        ) { "Invalid repair operation" }
        val flightKey = "$exactOwnerKey\u0000$operationKey"
        val stripe = repairFlightStripes[stripeIndex(stripeOwnerKey)]
        var abortedGeneration: RepairGeneration? = null
        while (true) {
            val selection = abortedGeneration
                ?.let { stripe.selectSuccessor(it) }
                ?: stripe.select(flightKey)
            when (selection) {
                is FlightSelection.Follower -> when (val outcome = selection.outcome.await()) {
                    FlightOutcome.LeaderAborted -> {
                        abortedGeneration = selection.generation
                    }
                    else -> return outcome.valueOrThrow()
                }
                is FlightSelection.Collision -> {
                    selection.outcome.await()
                    continue
                }
                is FlightSelection.Leader -> {
                    abortedGeneration = null
                    val outcome = try {
                        FlightOutcome.Success(block())
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable) {
                            stripe.abort(selection)
                        }
                        throw cancelled
                    } catch (failure: Throwable) {
                        FlightOutcome.Failure(failure)
                    }
                    withContext(NonCancellable) {
                        stripe.complete(selection, outcome)
                    }
                    return outcome.valueOrThrow()
                }
            }
        }
    }

    internal suspend fun repairCoordinationSnapshot(): RepairCoordinationSnapshot {
        var activeGenerationCount = 0
        var retainedOutcomeCount = 0
        repairFlightStripes.forEach { stripe ->
            val snapshot = stripe.snapshot()
            activeGenerationCount += snapshot.activeGenerationCount
            retainedOutcomeCount += snapshot.retainedOutcomeCount
        }
        return RepairCoordinationSnapshot(
            stripeCount = repairFlightStripes.size,
            activeGenerationCount = activeGenerationCount,
            retainedOutcomeCount = retainedOutcomeCount,
            maximumOutcomesPerGeneration = MAX_OUTCOMES_PER_GENERATION,
        )
    }

    private fun stripeIndex(ownerKey: String): Int =
        (ownerKey.hashCode() and Int.MAX_VALUE) % publicationStripes.size

    private class RepairFlightStripe {
        private val mutex = Mutex()
        private var active: RepairGeneration? = null

        suspend fun select(key: String): FlightSelection = mutex.withLock {
            val current = active
            when {
                current == null -> createRootLeader(key)
                current.key == key -> selectSameGeneration(current)
                current.phase == RepairGenerationPhase.ROOT_ABORTED ||
                    current.phase == RepairGenerationPhase.COMPLETE -> createRootLeader(key)
                else -> FlightSelection.Collision(current.currentOutcome())
            }
        }

        suspend fun selectSuccessor(aborted: RepairGeneration): FlightSelection = mutex.withLock {
            when (aborted.phase) {
                RepairGenerationPhase.ROOT_ABORTED -> {
                    val current = active
                    when {
                        current == null || current === aborted ||
                            current.phase == RepairGenerationPhase.ROOT_ABORTED ||
                            current.phase == RepairGenerationPhase.COMPLETE -> {
                            val terminal = CompletableDeferred<FlightOutcome>()
                            aborted.successorOutcome = terminal
                            aborted.phase = RepairGenerationPhase.SUCCESSOR_RUNNING
                            active = aborted
                            FlightSelection.Leader(aborted, RepairAttempt.SUCCESSOR)
                        }
                        else -> FlightSelection.Collision(current.currentOutcome())
                    }
                }
                RepairGenerationPhase.SUCCESSOR_RUNNING,
                RepairGenerationPhase.COMPLETE,
                -> FlightSelection.Follower(
                    aborted,
                    requireNotNull(aborted.successorOutcome),
                )
                RepairGenerationPhase.ROOT_RUNNING -> error("Repair root is not aborted")
            }
        }

        suspend fun abort(selection: FlightSelection.Leader) = mutex.withLock {
            val generation = selection.generation
            when (selection.attempt) {
                RepairAttempt.ROOT -> {
                    generation.phase = RepairGenerationPhase.ROOT_ABORTED
                    generation.rootOutcome.complete(FlightOutcome.LeaderAborted)
                }
                RepairAttempt.SUCCESSOR -> {
                    generation.phase = RepairGenerationPhase.COMPLETE
                    if (active === generation) active = null
                    requireNotNull(generation.successorOutcome)
                        .complete(FlightOutcome.RetryExhausted)
                }
            }
        }

        suspend fun complete(
            selection: FlightSelection.Leader,
            outcome: FlightOutcome,
        ) = mutex.withLock {
            val generation = selection.generation
            generation.phase = RepairGenerationPhase.COMPLETE
            if (active === generation) active = null
            when (selection.attempt) {
                RepairAttempt.ROOT -> generation.rootOutcome.complete(outcome)
                RepairAttempt.SUCCESSOR -> requireNotNull(generation.successorOutcome).complete(outcome)
            }
        }

        suspend fun snapshot(): RepairStripeSnapshot = mutex.withLock {
            val current = active
            RepairStripeSnapshot(
                activeGenerationCount = if (current == null) 0 else 1,
                retainedOutcomeCount = when {
                    current == null -> 0
                    current.successorOutcome == null -> 1
                    else -> MAX_OUTCOMES_PER_GENERATION
                },
            )
        }

        private fun createRootLeader(key: String): FlightSelection.Leader {
            val created = RepairGeneration(key)
            active = created
            return FlightSelection.Leader(created, RepairAttempt.ROOT)
        }

        private fun selectSameGeneration(generation: RepairGeneration): FlightSelection =
            when (generation.phase) {
                RepairGenerationPhase.ROOT_RUNNING ->
                    FlightSelection.Follower(generation, generation.rootOutcome)
                RepairGenerationPhase.ROOT_ABORTED -> {
                    val terminal = CompletableDeferred<FlightOutcome>()
                    generation.successorOutcome = terminal
                    generation.phase = RepairGenerationPhase.SUCCESSOR_RUNNING
                    FlightSelection.Leader(generation, RepairAttempt.SUCCESSOR)
                }
                RepairGenerationPhase.SUCCESSOR_RUNNING,
                RepairGenerationPhase.COMPLETE,
                -> FlightSelection.Follower(
                    generation,
                    generation.successorOutcome ?: generation.rootOutcome,
                )
            }

        private fun RepairGeneration.currentOutcome(): CompletableDeferred<FlightOutcome> =
            when (phase) {
                RepairGenerationPhase.ROOT_RUNNING,
                RepairGenerationPhase.ROOT_ABORTED,
                -> rootOutcome
                RepairGenerationPhase.SUCCESSOR_RUNNING,
                RepairGenerationPhase.COMPLETE,
                -> successorOutcome ?: rootOutcome
            }
    }

    private class RepairGeneration(val key: String) {
        val rootOutcome = CompletableDeferred<FlightOutcome>()
        var successorOutcome: CompletableDeferred<FlightOutcome>? = null
        var phase: RepairGenerationPhase = RepairGenerationPhase.ROOT_RUNNING
    }

    private enum class RepairGenerationPhase {
        ROOT_RUNNING,
        ROOT_ABORTED,
        SUCCESSOR_RUNNING,
        COMPLETE,
    }

    private enum class RepairAttempt {
        ROOT,
        SUCCESSOR,
    }

    private data class RepairStripeSnapshot(
        val activeGenerationCount: Int,
        val retainedOutcomeCount: Int,
    )

    private sealed interface FlightSelection {
        data class Leader(
            val generation: RepairGeneration,
            val attempt: RepairAttempt,
        ) : FlightSelection

        data class Follower(
            val generation: RepairGeneration,
            val outcome: CompletableDeferred<FlightOutcome>,
        ) : FlightSelection

        data class Collision(
            val outcome: CompletableDeferred<FlightOutcome>,
        ) : FlightSelection
    }

    private sealed interface FlightOutcome {
        data class Success(val value: Any) : FlightOutcome
        data class Failure(val cause: Throwable) : FlightOutcome
        data object LeaderAborted : FlightOutcome
        data object RetryExhausted : FlightOutcome

        @Suppress("UNCHECKED_CAST")
        fun <T : Any> valueOrThrow(): T = when (this) {
            is Success -> value as T
            is Failure -> throw cause
            LeaderAborted -> error("Aborted repair root has no value")
            RetryExhausted -> throw RepairFlightRetryExhaustedException()
        }
    }

    private companion object {
        const val DEFAULT_STRIPE_COUNT = 64
        const val MAX_STRIPE_COUNT = 256
        const val MAX_OPERATION_KEY_LENGTH = 64
        const val MAX_OUTCOMES_PER_GENERATION = 2
    }
}

internal data class RepairCoordinationSnapshot(
    val stripeCount: Int,
    val activeGenerationCount: Int,
    val retainedOutcomeCount: Int,
    val maximumOutcomesPerGeneration: Int,
)

internal class RepairFlightRetryExhaustedException :
    IllegalStateException("Repair could not elect a live leader")

private fun normalizedOwnerKey(ownerModelId: String): String =
    validatedOwnerKey(ownerModelId).lowercase()

private fun validatedOwnerKey(ownerModelId: String): String {
    require(ownerModelId.isNotEmpty() && ownerModelId == ownerModelId.trim() && ownerModelId.length <= 193) {
        "Invalid model identifier"
    }
    require('\\' !in ownerModelId) { "Invalid model identifier" }
    val segments = ownerModelId.split('/')
    require(segments.size in 1..2 && segments.all(::isSafeOwnerSegment)) { "Invalid model identifier" }
    return ownerModelId
}

private fun isSafeOwnerSegment(segment: String): Boolean =
    segment.isNotEmpty() && segment.length <= 96 && segment != "." && segment != ".." &&
        ".." !in segment && "--" !in segment && segment.first() !in ".-" && segment.last() !in ".-" &&
        segment.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
