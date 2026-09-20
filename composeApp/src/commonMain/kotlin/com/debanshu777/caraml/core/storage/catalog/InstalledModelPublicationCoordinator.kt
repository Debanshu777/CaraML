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
        var observedLeaderAborts = 0
        var abortedFlight: RepairFlight? = null
        while (true) {
            val selection = abortedFlight
                ?.let { stripe.selectSuccessor(it) }
                ?: stripe.select(flightKey)
            when (selection) {
                is FlightSelection.Follower -> when (val outcome = selection.flight.result.await()) {
                    FlightOutcome.LeaderAborted -> {
                        observedLeaderAborts += 1
                        if (observedLeaderAborts > MAX_LEADER_ABORT_RETRIES) {
                            throw RepairFlightRetryExhaustedException()
                        }
                        abortedFlight = selection.flight
                    }
                    else -> return outcome.valueOrThrow()
                }
                is FlightSelection.Collision -> {
                    selection.flight.result.await()
                    continue
                }
                is FlightSelection.Leader -> {
                    abortedFlight = null
                    val outcome = try {
                        FlightOutcome.Success(block())
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable) {
                            stripe.complete(selection.flight, FlightOutcome.LeaderAborted)
                        }
                        throw cancelled
                    } catch (failure: Throwable) {
                        FlightOutcome.Failure(failure)
                    }
                    withContext(NonCancellable) {
                        stripe.complete(selection.flight, outcome)
                    }
                    return outcome.valueOrThrow()
                }
            }
        }
    }

    private fun stripeIndex(ownerKey: String): Int =
        (ownerKey.hashCode() and Int.MAX_VALUE) % publicationStripes.size

    private class RepairFlightStripe {
        private val mutex = Mutex()
        private var active: RepairFlight? = null

        suspend fun select(key: String): FlightSelection = mutex.withLock {
            val current = active
            when {
                current == null -> {
                    val created = RepairFlight(key)
                    active = created
                    FlightSelection.Leader(created)
                }
                current.key == key -> FlightSelection.Follower(current)
                current.leaderAborted -> {
                    val created = RepairFlight(key)
                    active = created
                    FlightSelection.Leader(created)
                }
                else -> FlightSelection.Collision(current)
            }
        }

        suspend fun selectSuccessor(aborted: RepairFlight): FlightSelection = mutex.withLock {
            aborted.successor?.let { return@withLock FlightSelection.Follower(it) }
            val current = active
            when {
                current == null || current === aborted || current.leaderAborted -> {
                    val created = RepairFlight(aborted.key)
                    aborted.successor = created
                    active = created
                    FlightSelection.Leader(created)
                }
                current.key == aborted.key -> {
                    aborted.successor = current
                    FlightSelection.Follower(current)
                }
                else -> FlightSelection.Collision(current)
            }
        }

        suspend fun complete(flight: RepairFlight, outcome: FlightOutcome) = mutex.withLock {
            flight.leaderAborted = outcome === FlightOutcome.LeaderAborted
            if (active === flight && !flight.leaderAborted) active = null
            flight.result.complete(outcome)
        }
    }

    private class RepairFlight(val key: String) {
        val result = CompletableDeferred<FlightOutcome>()
        var leaderAborted: Boolean = false
        // Late waiters from one aborted generation must observe the same successor.
        var successor: RepairFlight? = null
    }

    private sealed interface FlightSelection {
        data class Leader(val flight: RepairFlight) : FlightSelection
        data class Follower(val flight: RepairFlight) : FlightSelection
        data class Collision(val flight: RepairFlight) : FlightSelection
    }

    private sealed interface FlightOutcome {
        data class Success(val value: Any) : FlightOutcome
        data class Failure(val cause: Throwable) : FlightOutcome
        data object LeaderAborted : FlightOutcome

        @Suppress("UNCHECKED_CAST")
        fun <T : Any> valueOrThrow(): T = when (this) {
            is Success -> value as T
            is Failure -> throw cause
            LeaderAborted -> error("Aborted repair flight has no value")
        }
    }

    private class RepairFlightRetryExhaustedException :
        IllegalStateException("Repair could not elect a live leader")

    private companion object {
        const val DEFAULT_STRIPE_COUNT = 64
        const val MAX_STRIPE_COUNT = 256
        const val MAX_OPERATION_KEY_LENGTH = 64
        const val MAX_LEADER_ABORT_RETRIES = 1
    }
}

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
