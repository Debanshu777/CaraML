package com.debanshu777.caraml.core.recommendation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface NativeLoadOutcome<out T> {
    data class Succeeded<T>(val value: T) : NativeLoadOutcome<T>
    data class Failed<T>(val value: T, val reason: StableLoadFailure) : NativeLoadOutcome<T>
}

sealed interface CoordinatedLoadResult<out T> {
    data class AdmissionRequired(val admission: LoadAdmission) : CoordinatedLoadResult<Nothing>
    data class ArtifactChanged(val request: LoadRequest) : CoordinatedLoadResult<Nothing>
    data class Completed<T>(val value: T) : CoordinatedLoadResult<T>
}

/**
 * Process-wide ownership for the complete exact-load transaction across both native engines.
 * The lock covers admission, both byte-identity checks, marker persistence, and native entry.
 */
class LoadSessionCoordinator(
    private val recoveryRepository: LoadRecoveryRepository,
) {
    private val sessionMutex = Mutex()
    private var startupRecoveryCompleted = false

    suspend fun recoverAbandonedLoadAtStartup(): SuspectedLoadFailure? = sessionMutex.withLock {
        if (startupRecoveryCompleted) return@withLock null
        val recovered = recoveryRepository.recoverPendingLoad()
        startupRecoveryCompleted = true
        recovered
    }

    suspend fun <T> execute(
        request: LoadRequest,
        evaluateAdmission: suspend () -> LoadAdmission,
        artifactValidator: suspend (LoadRequest) -> Boolean,
        releasePartialState: suspend () -> Unit,
        nativeLoad: suspend () -> NativeLoadOutcome<T>,
    ): CoordinatedLoadResult<T> = sessionMutex.withLock {
        val admission = evaluateAdmission()
        val admitted = admission as? LoadAdmission.Ready
            ?: return@withLock CoordinatedLoadResult.AdmissionRequired(admission)
        if (!admitted.request.matchesExact(request) || !artifactValidator(request)) {
            return@withLock CoordinatedLoadResult.ArtifactChanged(request)
        }

        val marker = recoveryRepository.beginLoad(request.identity, request.plan)
        try {
            when (val outcome = nativeLoad()) {
                is NativeLoadOutcome.Succeeded -> {
                    recoveryRepository.markLoadSucceeded(marker)
                    CoordinatedLoadResult.Completed(outcome.value)
                }
                is NativeLoadOutcome.Failed -> {
                    withContext(NonCancellable) {
                        runCatching { releasePartialState() }
                        recoveryRepository.markLoadFailed(marker, outcome.reason)
                    }
                    CoordinatedLoadResult.Completed(outcome.value)
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { releasePartialState() }
                runCatching { recoveryRepository.markLoadCancelled(marker) }
            }
            throw cancelled
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                runCatching { releasePartialState() }
                runCatching { recoveryRepository.markLoadFailed(marker, StableLoadFailure.UNKNOWN) }
            }
            throw failure
        }
    }

    suspend fun allowExplicitRetry(request: LoadRequest, engineVersion: String) {
        if (request.artifact?.identity == request.identity) {
            recoveryRepository.allowExplicitRetry(request.identity, request.plan, engineVersion)
        }
    }

    private fun LoadRequest.matchesExact(other: LoadRequest): Boolean =
        identity == other.identity &&
            plan.stableKey == other.plan.stableKey &&
            assessmentKey == other.assessmentKey &&
            artifact == other.artifact
}
