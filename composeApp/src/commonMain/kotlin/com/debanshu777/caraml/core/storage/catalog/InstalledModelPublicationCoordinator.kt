package com.debanshu777.caraml.core.storage.catalog

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
        while (true) {
            when (val selection = stripe.select(flightKey)) {
                is FlightSelection.Follower -> return selection.flight.result.await().valueOrThrow()
                is FlightSelection.Collision -> {
                    selection.flight.result.await()
                    continue
                }
                is FlightSelection.Leader -> {
                    val outcome = try {
                        FlightOutcome.Success(block())
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
                else -> FlightSelection.Collision(current)
            }
        }

        suspend fun complete(flight: RepairFlight, outcome: FlightOutcome) = mutex.withLock {
            if (active === flight) active = null
            flight.result.complete(outcome)
        }
    }

    private class RepairFlight(val key: String) {
        val result = CompletableDeferred<FlightOutcome>()
    }

    private sealed interface FlightSelection {
        data class Leader(val flight: RepairFlight) : FlightSelection
        data class Follower(val flight: RepairFlight) : FlightSelection
        data class Collision(val flight: RepairFlight) : FlightSelection
    }

    private sealed interface FlightOutcome {
        data class Success(val value: Any) : FlightOutcome
        data class Failure(val cause: Throwable) : FlightOutcome

        @Suppress("UNCHECKED_CAST")
        fun <T : Any> valueOrThrow(): T = when (this) {
            is Success -> value as T
            is Failure -> throw cause
        }
    }

    private companion object {
        const val DEFAULT_STRIPE_COUNT = 64
        const val MAX_STRIPE_COUNT = 256
        const val MAX_OPERATION_KEY_LENGTH = 64
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
