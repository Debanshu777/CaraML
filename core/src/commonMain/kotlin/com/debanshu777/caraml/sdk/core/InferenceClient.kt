@file:OptIn(com.debanshu777.caraml.sdk.api.ExperimentalCaraMLApi::class)

package com.debanshu777.caraml.sdk.core

import com.debanshu777.caraml.sdk.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface PlanAssessment {
    data class Ready(val plan: AssessedPlan) : PlanAssessment
    data class Unsupported(val reason: String) : PlanAssessment
    data class RequiresConfirmation(val reason: String, val alternative: EnginePlanSummary? = null) : PlanAssessment
}

/** A client-scoped assessment. It owns an artifact lease until it is loaded or closed. */
class AssessedPlan internal constructor(
    internal val owner: InferenceClient,
    internal val provider: EngineProvider,
    internal val pluginPlan: EnginePlan,
    internal val artifactLease: ArtifactLease,
    internal val request: EngineRequest,
    internal val artifactRef: ArtifactRef,
    val summary: EnginePlanSummary,
) : SuspendCloseable {
    internal val gate = Mutex()
    internal var consumed = false
    internal var transferred = false
    internal var leaseClosed = false

    internal suspend fun closeLease() {
        if (!transferred && !leaseClosed) {
            leaseClosed = true
            artifactLease.close()
        }
    }

    override suspend fun close() {
        withContext(NonCancellable) {
            gate.withLock {
                try {
                    consumed = true
                    closeLease()
                } finally {
                    // Drop closed plans from the client registry even if lease cleanup fails.
                    owner.remove(this@AssessedPlan)
                }
            }
        }
    }
}

/**
 * Builder-created facade over the host's selected engine providers and artifact access.
 * Building a client performs no I/O. The host supplies configuration, artifacts and resource
 * readings; application preferences, dependency injection and scheduling remain host concerns.
 *
 * The client owns its assessed plans and sessions. Closing it awaits their cleanup without
 * closing the supplied providers, artifact access or resource source. Capacity arbitration is
 * shared within one linked core runtime, not across independent SDK copies or classloaders.
 *
 * If native teardown fails, unresolved native ownership, the artifact lease and admission
 * reservations remain quarantined for the process lifetime. Retrying close reports the failure;
 * it does not make that capacity reusable. A recovery API is not currently provided.
 */
@OptIn(ExperimentalCaraMLApi::class)
class InferenceClient private constructor(
    providers: List<EngineProvider>,
    private val artifacts: ArtifactAccess?,
    private val resources: DeviceResourceSource?,
    private val arbiter: ProcessArbiter,
) : SuspendCloseable {
    private val byId = providers.associateBy { it.id }
    private val plans = mutableSetOf<AssessedPlan>()
    private val sessions = mutableSetOf<InferenceSession>()
    private val lifecycle = Mutex()
    private val shutdown = Mutex()
    private var shutdownFailure: Throwable? = null
    private var closed = false

    class Builder {
        private val providers = mutableListOf<EngineProvider>()
        private var artifacts: ArtifactAccess? = null
        private var resources: DeviceResourceSource? = null

        fun addEngine(provider: EngineProvider) = apply { providers += provider }
        fun artifactAccess(access: ArtifactAccess) = apply { artifacts = access }
        fun resourceSource(source: DeviceResourceSource) = apply { resources = source }

        fun build(): InferenceClient {
            require(providers.map { it.id }.distinct().size == providers.size) { "Duplicate engine IDs are not allowed" }
            if (providers.isNotEmpty()) {
                requireNotNull(artifacts) { "artifactAccess is required when an engine is registered" }
                requireNotNull(resources) { "resourceSource is required when an engine is registered" }
            }
            return InferenceClient(providers.toList(), artifacts, resources, ProcessArbiter.Shared)
        }
    }

    suspend fun assess(request: EngineRequest): PlanAssessment {
        lifecycle.withLock { ensureOpen() }
        val candidates = byId.values.filter { request.capability in it.capabilities }
        val chosen = when {
            request.preferredEngineId != null -> candidates.singleOrNull { it.id == request.preferredEngineId }
                ?: throw InferenceException.NoEngine(request.capability)
            candidates.isEmpty() -> throw InferenceException.NoEngine(request.capability)
            candidates.size == 1 -> candidates.single()
            else -> throw InferenceException.Unsupported("Multiple engines support ${request.capability.value}; specify preferredEngineId")
        }
        val artifactRef = request.artifact
        val lease = requireNotNull(artifacts) { "artifactAccess is required when using an engine" }.acquire(artifactRef)
            ?: throw InferenceException.Unsupported("Artifact ${request.artifact.id.value} is unavailable")
        return try {
            if (lease.artifact != artifactRef) throw InferenceException.InvalidPlan("Artifact lease identity does not match the requested revision or digest")
            when (val result = chosen.preflight(request, lease)) {
                is PreflightResult.Unsupported -> PlanAssessment.Unsupported(result.reason)
                is PreflightResult.RequiresConfirmation -> PlanAssessment.RequiresConfirmation(result.reason, result.alternative?.summary)
                is PreflightResult.Ready -> {
                    if (request.artifact != artifactRef) throw InferenceException.InvalidPlan("Request artifact identity changed during assessment")
                    validateSummary(chosen, request, result.plan.summary)
                    val plan = AssessedPlan(this, chosen, result.plan, lease, request, artifactRef, result.plan.summary)
                    lifecycle.withLock {
                        ensureOpen()
                        plans += plan
                    }
                    PlanAssessment.Ready(plan)
                }
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { lease.close() }
            throw failure
        }.also { assessment ->
            if (assessment !is PlanAssessment.Ready) withContext(NonCancellable) { lease.close() }
        }
    }

    suspend fun load(plan: AssessedPlan): InferenceSession {
        require(plan.owner === this) { "Plan belongs to another inference client" }
        plan.gate.withLock {
            val clientClosed = lifecycle.withLock { closed }
            if (plan.consumed || clientClosed) throw InferenceException.InvalidPlan("Plan has already been consumed or the client is closed")
            if (plan.request.artifact != plan.artifactRef) throw InferenceException.InvalidPlan("Request artifact revision or digest changed after assessment")
            validateSummary(plan.provider, plan.request, plan.summary)
            val resourceSource = requireNotNull(resources)
            val snapshot = resourceSource.snapshot()
            val reservation = arbiter.reserveLoad(plan.provider, plan.summary.resources, snapshot)
            var loadedEngineSession: EngineSession? = null
            try {
                val engineSession = plan.provider.load(plan.pluginPlan)
                loadedEngineSession = engineSession
                if (engineSession.engineId != plan.provider.id || engineSession.model.id != plan.request.model.id) {
                    throw InferenceException.InvalidPlan("Engine returned a session for a different engine or model")
                }
                reservation.finishLoading(resourceSource.snapshot())
                val session = InferenceSession(
                    plan.provider,
                    engineSession,
                    plan.request,
                    plan.artifactRef,
                    plan.summary,
                    plan.artifactLease,
                    reservation,
                    resourceSource,
                    arbiter,
                    onClosed = { remove(it) },
                )
                lifecycle.withLock {
                    ensureOpen()
                    // Ownership transfers only after the client accepts the session into its registry.
                    plan.consumed = true
                    plan.transferred = true
                    plans -= plan
                    sessions += session
                }
                return session
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    val nativeSession = loadedEngineSession
                    val unresolvedOwner = (failure as? UnreleasedEngineLoad)?.owner
                    var teardownFailure: Throwable? = null
                    if (nativeSession != null) {
                        try { nativeSession.unload() } catch (cleanupFailure: Throwable) { teardownFailure = cleanupFailure }
                        try { nativeSession.close() } catch (cleanupFailure: Throwable) {
                            if (teardownFailure == null) teardownFailure = cleanupFailure
                        }
                    }
                    if (unresolvedOwner != null || (nativeSession != null && teardownFailure != null)) {
                        // Keep the artifact mapped and process capacity charged for process life;
                        // AssessedPlan.close must not reclaim this lease.
                        reservation.quarantine(unresolvedOwner ?: requireNotNull(nativeSession), plan.artifactLease)
                        plan.consumed = true
                        plan.transferred = true
                    } else {
                        try { reservation.close() } catch (_: Throwable) { }
                        try { plan.closeLease() } catch (_: Throwable) { }
                    }
                    try { remove(plan) } catch (_: Throwable) { }
                }
                plan.consumed = true
                throw (if (failure is UnreleasedEngineLoad) failure.cause ?: failure else failure)
            }
        }
    }

    override suspend fun close(): Unit = withContext(NonCancellable) {
        shutdown.withLock {
            val (ownedPlans, ownedSessions) = lifecycle.withLock {
                if (closed) {
                    shutdownFailure?.let { throw it }
                    return@withContext
                }
                closed = true
                plans.toList() to sessions.toList()
            }
            var firstFailure: Throwable? = null
            ownedSessions.forEach { session ->
                try { session.close() } catch (failure: Throwable) { if (firstFailure == null) firstFailure = failure }
            }
            ownedPlans.forEach { plan ->
                try { plan.close() } catch (failure: Throwable) { if (firstFailure == null) firstFailure = failure }
            }
            shutdownFailure = firstFailure
            firstFailure?.let { throw it }
        }
    }

    internal suspend fun remove(session: InferenceSession) = lifecycle.withLock { sessions -= session }
    internal suspend fun remove(plan: AssessedPlan) = lifecycle.withLock { plans -= plan }

    private fun ensureOpen() { if (closed) throw InferenceException.InvalidPlan("Inference client is closed") }

    private fun validateSummary(provider: EngineProvider, request: EngineRequest, summary: EnginePlanSummary) {
        if (summary.engineId != provider.id || summary.engineVersion != provider.version ||
            summary.artifactId != request.artifact.id || summary.capability != request.capability ||
            summary.modelId != request.model.id || summary.requestFingerprint != request.requestFingerprint
        ) throw InferenceException.InvalidPlan("Plan does not match its engine, artifact, model, capability, or request")
    }
}

/** Serialized, cancellation-aware session. Native/plugin ownership remains with the engine. */
@OptIn(ExperimentalCaraMLApi::class)
class InferenceSession internal constructor(
    private val provider: EngineProvider,
    private val engineSession: EngineSession,
    private val loadRequest: EngineRequest,
    private val artifactRef: ArtifactRef,
    private val plan: EnginePlanSummary,
    private val artifactLease: ArtifactLease,
    private val residentReservation: ProcessArbiter.Reservation,
    private val resources: DeviceResourceSource,
    private val arbiter: ProcessArbiter,
    private val onClosed: suspend (InferenceSession) -> Unit,
) : SuspendCloseable {
    private val shutdown = Mutex()
    private var shutdownFailure: Throwable? = null
    private val operations = Mutex()
    private val state = Mutex()
    private var activeOperation: OperationId? = null
    private var closing = false
    private var closed = false
    private var quarantined = false
    private val usedOperations = mutableSetOf<OperationId>()

    val engineId: EngineId get() = provider.id
    val model: ModelDescriptor get() = loadRequest.model

    suspend fun generate(request: EngineRequest): Flow<GenerationEvent> {
        validateRequest(request)
        state.withLock {
            if (closing || closed) throw InferenceException.InvalidPlan("Session is closing or closed")
        }
        val collectionGate = Mutex()
        var collected = false
        return flow {
            collectionGate.withLock {
                if (collected) throw InferenceException.InvalidPlan("Generation streams are single-use")
                collected = true
            }
            operations.lock()
            var reserved: ProcessArbiter.Reservation? = null
            var terminalEvent: GenerationEvent? = null
            try {
                state.withLock {
                    if (closing || closed) throw InferenceException.InvalidPlan("Session is closing or closed")
                    if (!usedOperations.add(request.operationId)) throw InferenceException.InvalidPlan("Operation ID has already been used")
                    activeOperation = request.operationId
                }
                val snapshot = resources.snapshot()
                reserved = arbiter.reserveOperation(provider, plan.resources.copy(residentBytes = 0), snapshot)
                var terminal = false
                try {
                    engineSession.generate(request.operationId, request)
                        .catch { failure ->
                            if (failure is CancellationException) throw failure
                            if (terminal) throw failure
                            terminal = true
                            terminalEvent = GenerationEvent.Failed(EngineError("ENGINE_FAILURE", "Engine operation failed"))
                        }
                        .collect { event ->
                        if (terminal) throw InferenceException.InvalidPlan("Engine emitted an event after the terminal event")
                        if (event is GenerationEvent.Completed || event is GenerationEvent.Failed) {
                            terminal = true
                            terminalEvent = event
                        } else {
                            emit(event)
                        }
                    }
                    if (!terminal) {
                        terminal = true
                        terminalEvent = GenerationEvent.Failed(EngineError("MISSING_TERMINAL", "Engine stream completed without a terminal event"))
                    }
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) {
                        try { engineSession.cancel(request.operationId) } catch (_: Throwable) { }
                    }
                    throw cancelled
                }
            } finally {
                withContext(NonCancellable) {
                    try {
                        reserved?.close()
                    } finally {
                        try {
                            state.withLock { if (activeOperation == request.operationId) activeOperation = null }
                        } finally {
                            operations.unlock()
                        }
                    }
                }
            }
            // Terminal delivery means native finalization and process admission cleanup are complete.
            terminalEvent?.let { emit(it) }
        }
    }

    suspend fun cancel(operationId: OperationId) {
        val active = state.withLock { activeOperation }
        if (active == operationId) engineSession.cancel(operationId)
    }

    override suspend fun close(): Unit = withContext(NonCancellable) {
        shutdown.withLock {
            val active = state.withLock {
                if (closed || quarantined) {
                    shutdownFailure?.let { throw it }
                    return@withContext
                }
                closing = true
                activeOperation
            }
            var firstFailure: Throwable? = null
            if (active != null) try { engineSession.cancel(active) } catch (failure: Throwable) { firstFailure = failure }
            operations.lock()
            try {
                val alreadyClosed = state.withLock { closed || quarantined }
                if (alreadyClosed) return@withContext
                withContext(NonCancellable) {
                    var teardownFailure: Throwable? = null
                    try { engineSession.unload() } catch (failure: Throwable) { teardownFailure = failure }
                    try { engineSession.close() } catch (failure: Throwable) {
                        if (teardownFailure == null) teardownFailure = failure
                    }
                    if (teardownFailure != null) {
                        state.withLock { quarantined = true }
                        residentReservation.quarantine(engineSession, artifactLease)
                        if (firstFailure == null) firstFailure = teardownFailure
                    } else {
                        state.withLock { closed = true }
                        suspend fun cleanup(block: suspend () -> Unit) {
                            try { block() } catch (failure: Throwable) { if (firstFailure == null) firstFailure = failure }
                        }
                        cleanup { artifactLease.close() }
                        cleanup { residentReservation.close() }
                        cleanup { onClosed(this@InferenceSession) }
                    }
                }
                shutdownFailure = firstFailure
                firstFailure?.let { throw it }
            } finally {
                operations.unlock()
            }
        }
    }

    private fun validateRequest(request: EngineRequest) {
        if (request.capability != plan.capability || request.model.id != plan.modelId ||
            request.artifact != artifactRef || request.artifact.id != plan.artifactId || request.requestFingerprint != plan.requestFingerprint
        ) throw InferenceException.InvalidPlan("Generation request changes the admitted capability, model, artifact, or bounded options")
        val actual = request.requestedResources
        val admitted = plan.resources
        if (actual.residentBytes > admitted.residentBytes || actual.transientBytes > admitted.transientBytes ||
            actual.outputBytes > admitted.outputBytes || actual.diskBytes > admitted.diskBytes
        ) throw InferenceException.InvalidPlan("Generation request exceeds the assessed resource bounds")
    }
}

/** Process-wide admission domain shared by every client facade in this classloader. */
internal class ProcessArbiter private constructor() {
    private val mutex = Mutex()
    private val engines = mutableMapOf<EngineId, EngineState>()
    private val reservations = mutableMapOf<Long, Charge>()
    /** Strong references keep unresolved native owners and their mapped artifacts alive. */
    private val quarantined = mutableMapOf<Long, QuarantinedOwnership>()
    private var nextId = 0L

    private data class EngineState(val capacity: EngineCapacity, var loaded: Int = 0, var operations: Int = 0)
    private data class Charge(val estimate: ResourceEstimate, val capturedAfterMillis: Long, val pending: Boolean)
    private data class QuarantinedOwnership(val owner: SuspendCloseable, val artifactLease: ArtifactLease)

    suspend fun reserveLoad(provider: EngineProvider, estimate: ResourceEstimate, snapshot: ResourceSnapshot): Reservation = mutex.withLock {
        val state = engine(provider)
        if (state.loaded >= state.capacity.maxLoadedSessions) throw InferenceException.Busy(provider.id, "loaded-session capacity is exhausted")
        admit(checkedAdd(checkedAdd(estimate.residentBytes, estimate.transientBytes), estimate.outputBytes), estimate.diskBytes, snapshot)
        state.loaded++
        val reservation = add(estimate, snapshot, pending = true)
        Reservation(this, reservation, provider.id, loaded = true)
    }

    suspend fun reserveOperation(provider: EngineProvider, estimate: ResourceEstimate, snapshot: ResourceSnapshot): Reservation = mutex.withLock {
        val state = engine(provider)
        if (state.operations >= state.capacity.maxConcurrentOperations) throw InferenceException.Busy(provider.id, "operation capacity is exhausted")
        admit(checkedAdd(estimate.transientBytes, estimate.outputBytes), estimate.diskBytes, snapshot)
        state.operations++
        val reservation = add(estimate.copy(residentBytes = 0), snapshot, pending = true)
        Reservation(this, reservation, provider.id, loaded = false, operation = true)
    }

    private fun admit(memory: Long, disk: Long, snapshot: ResourceSnapshot) {
        if (snapshot.lowMemory == true || snapshot.thermalStatus == ThermalStatus.CRITICAL || snapshot.thermalStatus == ThermalStatus.SERIOUS) {
            throw InferenceException.ResourceUnavailable("Device pressure currently blocks new inference work")
        }
        val availableMemory = snapshot.availableMemoryBytes
            ?: throw InferenceException.ResourceUnavailable("Available memory is unknown; inference admission is conservative")
        val pendingMemory = checkedAddAll(reservations.values.filter { it.pending || it.capturedAfterMillis > snapshot.capturedAtEpochMillis }.map { it.estimate.memoryBytes })
        if (pendingMemory > availableMemory || memory > availableMemory - pendingMemory) throw InferenceException.ResourceUnavailable("Estimated memory does not fit the current device budget")
        if (disk > 0) {
            val availableDisk = snapshot.availableStorageBytes
                ?: throw InferenceException.ResourceUnavailable("Available storage is unknown for an operation that needs disk space")
            val pendingDisk = checkedAddAll(reservations.values.filter { it.pending || it.capturedAfterMillis > snapshot.capturedAtEpochMillis }.map { it.estimate.diskBytes })
            if (pendingDisk > availableDisk || disk > availableDisk - pendingDisk) throw InferenceException.ResourceUnavailable("Estimated disk output does not fit the destination volume")
        }
    }

    private fun engine(provider: EngineProvider): EngineState {
        val current = engines[provider.id]
        if (current != null) {
            if (current.capacity != provider.capacity) throw InferenceException.Unsupported("Engine ${provider.id.value} has conflicting process capacity declarations")
            return current
        }
        return EngineState(provider.capacity).also { engines[provider.id] = it }
    }

    private fun add(estimate: ResourceEstimate, snapshot: ResourceSnapshot, pending: Boolean): Long {
        val id = ++nextId
        reservations[id] = Charge(estimate, snapshot.capturedAtEpochMillis + 1, pending)
        return id
    }

    private suspend fun finishLoading(reservation: Reservation, snapshot: ResourceSnapshot) = mutex.withLock {
        val charge = reservations[reservation.id] ?: return@withLock
        if (!charge.pending) return@withLock
        reservations[reservation.id] = Charge(
            estimate = charge.estimate.copy(transientBytes = 0, outputBytes = 0, diskBytes = 0),
            capturedAfterMillis = maxOf(charge.capturedAfterMillis, snapshot.capturedAtEpochMillis),
            pending = false,
        )
    }

    private suspend fun release(reservation: Reservation) = mutex.withLock {
        if (reservation.released) return@withLock
        if (reservation.id in quarantined) return@withLock
        reservation.released = true
        reservations.remove(reservation.id)
        val state = engines.getValue(reservation.engineId)
        if (reservation.loaded) state.loaded--
        if (reservation.operation) state.operations--
    }

    private suspend fun quarantine(reservation: Reservation, owner: SuspendCloseable, artifactLease: ArtifactLease) = mutex.withLock {
        if (reservation.released) throw IllegalStateException("Cannot quarantine a released native reservation")
        quarantined[reservation.id] = QuarantinedOwnership(owner, artifactLease)
    }

    class Reservation internal constructor(
        private val arbiter: ProcessArbiter,
        internal val id: Long,
        internal val engineId: EngineId,
        internal val loaded: Boolean,
        internal val operation: Boolean = false,
    ) : SuspendCloseable {
        internal var released = false
        suspend fun finishLoading(snapshot: ResourceSnapshot) = arbiter.finishLoading(this, snapshot)
        suspend fun quarantine(owner: SuspendCloseable, artifactLease: ArtifactLease) = arbiter.quarantine(this, owner, artifactLease)
        override suspend fun close() = arbiter.release(this)
    }

    private fun checkedAdd(left: Long, right: Long): Long {
        if (left > Long.MAX_VALUE - right) throw InferenceException.ResourceUnavailable("Resource estimate overflow")
        return left + right
    }

    private fun checkedAddAll(values: List<Long>): Long = values.fold(0L, ::checkedAdd)

    companion object { val Shared = ProcessArbiter() }
}
