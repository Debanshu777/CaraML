@file:OptIn(io.github.debanshu777.caraml.core.api.ExperimentalCaraMLApi::class)

package io.github.debanshu777.caraml.core

import kotlinx.coroutines.test.runCurrent

import kotlin.test.assertFalse

import kotlin.test.assertSame

import kotlinx.coroutines.CancellationException

import io.github.debanshu777.caraml.core.api.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

@OptIn(ExperimentalCaraMLApi::class)
class InferenceClientTest {
    @Test
    fun thirdPartyRequestFlowsThroughAssessmentLoadAndGeneration() = runTest {
        val artifactAccess = FakeArtifactAccess()
        val provider = FakeProvider()
        val client = InferenceClient.Builder()
            .addEngine(provider)
            .artifactAccess(artifactAccess)
            .resourceSource(FakeResources())
            .build()
        val request = FakeRequest(
            operationId = OperationId("op-1"),
            customMode = "strict-json",
            model = ModelDescriptor(ModelId("foreign-model"), "Foreign model", metadata = mapOf("format-family" to "unfamiliar-v4")),
            artifact = artifactAccess.ref,
        )

        val assessed = assertIs<PlanAssessment.Ready>(client.assess(request)).plan
        assertEquals("strict-json", provider.lastOption)
        assertEquals("unfamiliar-v4", provider.lastMetadata)
        val session = client.load(assessed)
        val events = session.generate(request).toList()

        assertEquals("custom=strict-json", (events.first() as GenerationEvent.TextDelta).text)
        assertIs<GenerationEvent.Completed>(events.last())
        session.close()
        client.close()
        assertEquals(1, provider.session.unloadCalls)
        assertEquals(1, artifactAccess.closedLeases)
    }

    @Test
    fun planCannotBeReusedWithChangedTypedOptionsAndStreamCannotBeCollectedTwice() = runTest {
        val artifacts = FakeArtifactAccess()
        val client = InferenceClient.Builder().addEngine(FakeProvider()).artifactAccess(artifacts)
            .resourceSource(FakeResources()).build()
        val original = FakeRequest(OperationId("first"), "typed-a", artifact = artifacts.ref)
        val session = client.load(assertIs<PlanAssessment.Ready>(client.assess(original)).plan)
        val wrong = original.copy(operationId = OperationId("second"), customMode = "typed-b")
        assertFailsWith<InferenceException.InvalidPlan> { session.generate(wrong) }

        val flow = session.generate(original.copy(operationId = OperationId("third")))
        flow.toList()
        assertFailsWith<InferenceException.InvalidPlan> { flow.toList() }
        session.close()
        client.close()
    }

    @Test
    fun processCapacityIsSharedByIndependentClients() = runTest {
        val provider1 = FakeProvider()
        val provider2 = FakeProvider()
        val artifacts1 = FakeArtifactAccess()
        val artifacts2 = FakeArtifactAccess()
        val client1 = InferenceClient.Builder().addEngine(provider1).artifactAccess(artifacts1)
            .resourceSource(FakeResources()).build()
        val client2 = InferenceClient.Builder().addEngine(provider2).artifactAccess(artifacts2)
            .resourceSource(FakeResources()).build()
        val request1 = FakeRequest(OperationId("owner-1"), "custom", artifact = artifacts1.ref)
        val request2 = FakeRequest(OperationId("owner-2"), "custom", artifact = artifacts2.ref)
        val session1 = client1.load(assertIs<PlanAssessment.Ready>(client1.assess(request1)).plan)
        val plan2 = assertIs<PlanAssessment.Ready>(client2.assess(request2)).plan

        assertFailsWith<InferenceException.Busy> { client2.load(plan2) }
        client2.close()
        assertEquals(0, provider1.session.cancelCalls)
        assertEquals(0, provider1.session.unloadCalls)

        session1.close()
        client1.close()
        assertEquals(1, provider1.session.unloadCalls)
    }

    @Test
    fun clientWithoutEnginesBuildsAndReportsTypedNoEngine() = runTest {
        val client = InferenceClient.Builder().build()
        val request = FakeRequest(OperationId("none"), "custom", artifact = ArtifactRef(ArtifactId("a")))
        assertFailsWith<InferenceException.NoEngine> { client.assess(request) }
        client.close()
    }

    @Test
    fun closingAnAssessedPlanReleasesItsLeaseOnlyOnce() = runTest {
        val artifacts = FakeArtifactAccess()
        val client = InferenceClient.Builder().addEngine(FakeProvider()).artifactAccess(artifacts)
            .resourceSource(FakeResources()).build()
        val request = FakeRequest(OperationId("close-plan"), "custom", artifact = artifacts.ref)
        val plan = assertIs<PlanAssessment.Ready>(client.assess(request)).plan
        plan.close()
        plan.close()
        client.close()
        assertEquals(1, artifacts.closedLeases)
    }

    @Test
    fun planAndSessionRejectChangedArtifactRevisionOrDigestWithSameOpaqueId() = runTest {
        val artifacts = FakeArtifactAccess()
        val initialArtifact = artifacts.ref.copy(revision = "revision-a", expectedSha256 = "digest-a")
        val request = FakeRequest(OperationId("artifact-identity"), "custom", artifact = initialArtifact)
        val client = InferenceClient.Builder().addEngine(FakeProvider()).artifactAccess(artifacts)
            .resourceSource(FakeResources()).build()
        val changedBeforeLoad = MutableArtifactRequest(request)
        val plan = assertIs<PlanAssessment.Ready>(client.assess(changedBeforeLoad)).plan
        changedBeforeLoad.artifact = initialArtifact.copy(revision = "revision-b")
        assertFailsWith<InferenceException.InvalidPlan> { client.load(plan) }

        val secondRequest = request.copy(operationId = OperationId("artifact-identity-loaded"))
        val session = client.load(assertIs<PlanAssessment.Ready>(client.assess(secondRequest)).plan)
        assertFailsWith<InferenceException.InvalidPlan> {
            session.generate(secondRequest.copy(operationId = OperationId("revision-change"), artifact = initialArtifact.copy(revision = "revision-b")))
        }
        assertFailsWith<InferenceException.InvalidPlan> {
            session.generate(secondRequest.copy(operationId = OperationId("digest-change"), artifact = initialArtifact.copy(expectedSha256 = "digest-b")))
        }
        session.close()
        client.close()
        assertEquals(2, artifacts.closedLeases)
    }

    @Test
    fun cancellationSignalsEngineAndReleasesOperationBeforeSessionCleanup() = runTest {
        val artifacts = FakeArtifactAccess()
        val provider = FakeProvider()
        provider.generationGate = CompletableDeferred()
        val client = InferenceClient.Builder().addEngine(provider).artifactAccess(artifacts)
            .resourceSource(FakeResources()).build()
        val request = FakeRequest(OperationId("load"), "custom", artifact = artifacts.ref)
        val session = client.load(assertIs<PlanAssessment.Ready>(client.assess(request)).plan)
        val generation = launch { session.generate(request.copy(operationId = OperationId("cancel-me"))).collect { } }
        provider.session.generationStarted.await()

        generation.cancelAndJoin()
        assertEquals(1, provider.session.cancelCalls)
        session.close()
        client.close()
        assertEquals(1, provider.session.unloadCalls)
        assertEquals(1, artifacts.closedLeases)
    }

    @Test
    fun terminalCallbackCanCloseSessionAfterAdmissionAndOperationGateRelease() = runTest {
        val artifacts = FakeArtifactAccess()
        val provider = FakeProvider()
        val client = InferenceClient.Builder().addEngine(provider).artifactAccess(artifacts)
            .resourceSource(FakeResources()).build()
        val request = FakeRequest(OperationId("terminal-close"), "custom", artifact = artifacts.ref)
        val session = client.load(assertIs<PlanAssessment.Ready>(client.assess(request)).plan)
        session.generate(request).collect { event ->
            if (event is GenerationEvent.Completed) session.close()
        }
        client.close()
        assertEquals(1, provider.session.unloadCalls)
        assertEquals(1, artifacts.closedLeases)
    }

    @Test
    fun clientCloseRacingSuspendedLoadCleansUpTheLateNativeSession() = runTest {
        val artifacts = FakeArtifactAccess()
        val provider = FakeProvider()
        provider.loadGate = CompletableDeferred()
        val client = InferenceClient.Builder().addEngine(provider).artifactAccess(artifacts)
            .resourceSource(FakeResources()).build()
        val request = FakeRequest(OperationId("load-race"), "custom", artifact = artifacts.ref)
        val plan = assertIs<PlanAssessment.Ready>(client.assess(request)).plan
        val loading = async { runCatching { client.load(plan) } }
        provider.loadStarted.await()
        val closing = async { client.close() }
        yield()
        provider.loadGate?.complete(Unit)

        assertFailsWith<InferenceException.InvalidPlan> { loading.await().getOrThrow() }
        closing.await()
        assertEquals(1, provider.session.unloadCalls)
        assertEquals(1, artifacts.closedLeases)
    }

    @Test
    fun newerResourceSnapshotStillAccountsForAnotherClientsInFlightLoad() = runTest {
        val firstArtifacts = FakeArtifactAccess()
        val secondArtifacts = FakeArtifactAccess()
        val firstProvider = FakeProvider(EngineId("third-party.first"))
        val secondProvider = FakeProvider(EngineId("third-party.second"))
        firstProvider.loadGate = CompletableDeferred()
        val firstClient = InferenceClient.Builder().addEngine(firstProvider).artifactAccess(firstArtifacts)
            .resourceSource(FakeResources(availableMemory = 1_000)).build()
        val secondClient = InferenceClient.Builder().addEngine(secondProvider).artifactAccess(secondArtifacts)
            .resourceSource(FakeResources(availableMemory = 1_000, capturedAt = 2)).build()
        val firstRequest = FakeRequest(OperationId("first-load"), "custom", artifact = firstArtifacts.ref)
        val secondRequest = FakeRequest(OperationId("second-load"), "custom", artifact = secondArtifacts.ref)
        val firstPlan = assertIs<PlanAssessment.Ready>(firstClient.assess(firstRequest)).plan
        val secondPlan = assertIs<PlanAssessment.Ready>(secondClient.assess(secondRequest)).plan
        val firstLoading = async { firstClient.load(firstPlan) }
        firstProvider.loadStarted.await()

        assertFailsWith<InferenceException.ResourceUnavailable> { secondClient.load(secondPlan) }
        firstProvider.loadGate?.complete(Unit)
        val firstSession = firstLoading.await()
        firstSession.close()
        secondClient.close()
        firstClient.close()
    }

    @Test
    fun failedSessionTeardownRetainsArtifactAndProcessCapacity() = runTest {
        val artifacts = FakeArtifactAccess()
        val engineId = EngineId("third-party.quarantine.session-close")
        val provider = FakeProvider(engineId).apply {
            resourceEstimate = ResourceEstimate()
            unloadGate = CompletableDeferred()
            unloadFailure = true
            closeFailure = true
        }
        val client = InferenceClient.Builder().addEngine(provider).artifactAccess(artifacts)
            .resourceSource(FakeResources()).build()
        val request = FakeRequest(OperationId("quarantine-session"), "custom", artifact = artifacts.ref)
        val session = client.load(assertIs<PlanAssessment.Ready>(client.assess(request)).plan)

        val firstClose = async { runCatching { session.close() } }
        provider.session.unloadStarted.await()
        val secondClose = async { runCatching { session.close() } }
        yield()
        provider.unloadGate?.complete(Unit)
        val failure = assertIs<IllegalStateException>(firstClose.await().exceptionOrNull())
        assertEquals(failure.message, assertIs<IllegalStateException>(secondClose.await().exceptionOrNull()).message)
        assertEquals(0, artifacts.closedLeases)
        assertEquals(failure.message, assertFailsWith<IllegalStateException> { client.close() }.message)
        assertEquals(failure.message, assertFailsWith<IllegalStateException> { client.close() }.message)
        assertEquals(0, artifacts.closedLeases)
        assertEquals(1, provider.session.unloadCalls)
        assertEquals(1, provider.session.closeCalls)

        val competingArtifacts = FakeArtifactAccess()
        val competingClient = InferenceClient.Builder().addEngine(FakeProvider(engineId)).artifactAccess(competingArtifacts)
            .resourceSource(FakeResources()).build()
        val competingRequest = FakeRequest(OperationId("quarantine-session-contender"), "custom", artifact = competingArtifacts.ref)
        val competingPlan = assertIs<PlanAssessment.Ready>(competingClient.assess(competingRequest)).plan
        assertFailsWith<InferenceException.Busy> { competingClient.load(competingPlan) }
        competingClient.close()
        assertEquals(0, artifacts.closedLeases)
    }

    @Test
    fun failedLoadTeardownQuarantinesTransferredPlanLeaseAndCapacity() = runTest {
        val artifacts = FakeArtifactAccess()
        val engineId = EngineId("third-party.quarantine.failed-load")
        val provider = FakeProvider(engineId).apply {
            resourceEstimate = ResourceEstimate()
            invalidSessionEngineId = EngineId("third-party.quarantine.failed-load.invalid")
            unloadFailure = true
            closeFailure = true
        }
        val client = InferenceClient.Builder().addEngine(provider).artifactAccess(artifacts)
            .resourceSource(FakeResources()).build()
        val request = FakeRequest(OperationId("quarantine-load"), "custom", artifact = artifacts.ref)
        val plan = assertIs<PlanAssessment.Ready>(client.assess(request)).plan
        assertFailsWith<InferenceException.InvalidPlan> { client.load(plan) }

        plan.close()
        client.close()
        assertEquals(0, artifacts.closedLeases)

        val competingArtifacts = FakeArtifactAccess()
        val competingClient = InferenceClient.Builder().addEngine(FakeProvider(engineId)).artifactAccess(competingArtifacts)
            .resourceSource(FakeResources()).build()
        val competingRequest = FakeRequest(OperationId("quarantine-load-contender"), "custom", artifact = competingArtifacts.ref)
        val competingPlan = assertIs<PlanAssessment.Ready>(competingClient.assess(competingRequest)).plan
        assertFailsWith<InferenceException.Busy> { competingClient.load(competingPlan) }
        competingClient.close()
        assertEquals(0, artifacts.closedLeases)
    }
    @Test
    fun concurrentClientAndSessionCloseWaitForNativeTeardown() = runTest {
        val artifacts = FakeArtifactAccess()
        val provider = FakeProvider(EngineId("third-party.concurrent-close")).apply { unloadGate = CompletableDeferred() }
        val client = InferenceClient.Builder().addEngine(provider).artifactAccess(artifacts)
            .resourceSource(FakeResources()).build()
        val request = FakeRequest(OperationId("close"), "custom", artifact = artifacts.ref)
        val session = client.load(assertIs<PlanAssessment.Ready>(client.assess(request)).plan)
        val first = async { client.close() }
        provider.session.unloadStarted.await()
        val second = async { client.close() }
        val direct = async { session.close() }
        runCurrent()
        val returnedEarly = second.isCompleted || direct.isCompleted
        provider.unloadGate!!.complete(Unit)
        first.await()
        second.await()
        direct.await()
        assertFalse(returnedEarly)
        assertEquals(1, artifacts.closedLeases)
    }

    @Test
    fun partialLoadFailureRetainsArtifactAndCapacityAndPreservesCancellation() = runTest {
        val artifacts = FakeArtifactAccess()
        val cause = CancellationException("cancel during native load")
        val owner = object : SuspendCloseable { override suspend fun close() = Unit }
        val provider = FakeProvider(EngineId("third-party.partial-load")).apply {
            resourceEstimate = ResourceEstimate()
            loadFailure = UnreleasedEngineLoad(owner, cause)
        }
        val client = InferenceClient.Builder().addEngine(provider).artifactAccess(artifacts)
            .resourceSource(FakeResources()).build()
        val request = FakeRequest(OperationId("partial"), "custom", artifact = artifacts.ref)
        val plan = assertIs<PlanAssessment.Ready>(client.assess(request)).plan
        assertSame(cause, assertFailsWith<CancellationException> { client.load(plan) })
        plan.close()
        client.close()
        assertEquals(0, artifacts.closedLeases)

        val competitor = InferenceClient.Builder().addEngine(FakeProvider(provider.id)).artifactAccess(artifacts)
            .resourceSource(FakeResources()).build()
        val next = assertIs<PlanAssessment.Ready>(competitor.assess(request)).plan
        assertFailsWith<InferenceException.Busy> { competitor.load(next) }
        competitor.close()
        assertEquals(1, artifacts.closedLeases) // Only the competitor's unused plan is released.
    }

}

private val TEST_CAPABILITY = CapabilityId("third-party.test.generate")

private data class FakeRequest(
    override val operationId: OperationId,
    val customMode: String,
    override val model: ModelDescriptor = ModelDescriptor(ModelId("foreign-model"), "Foreign model"),
    override val artifact: ArtifactRef,
) : EngineRequest {
    override val capability: CapabilityId get() = TEST_CAPABILITY
    override val requestedResources = ResourceEstimate(transientBytes = 256)
    override val requestFingerprint: String get() = "mode=$customMode;context=128;output=32"
}

private class MutableArtifactRequest(private val delegate: FakeRequest) : EngineRequest {
    override val operationId get() = delegate.operationId
    override val capability get() = delegate.capability
    override val model get() = delegate.model
    override var artifact = delegate.artifact
    override val requestedResources get() = delegate.requestedResources
    override val requestFingerprint get() = delegate.requestFingerprint
    override val preferredEngineId get() = delegate.preferredEngineId
}

private class FakeProvider(override val id: EngineId = EngineId("third-party.engine")) : EngineProvider {
    override val version = EngineVersion("7.3")
    override val capabilities = setOf(TEST_CAPABILITY)
    override val capacity = EngineCapacity(maxLoadedSessions = 1)
    var lastOption: String? = null
    var lastMetadata: String? = null
    lateinit var session: FakeSession
    var loadFailure: Throwable? = null
    var loadGate: CompletableDeferred<Unit>? = null
    var generationGate: CompletableDeferred<Unit>? = null
    var invalidSessionEngineId: EngineId? = null
    var resourceEstimate = ResourceEstimate(residentBytes = 512, transientBytes = 256)
    var unloadFailure = false
    var closeFailure = false
    var unloadGate: CompletableDeferred<Unit>? = null
    val loadStarted = CompletableDeferred<Unit>()

    override suspend fun preflight(request: EngineRequest, artifact: ArtifactLease): PreflightResult {
        val typed = request as? FakeRequest
        lastOption = typed?.customMode
        lastMetadata = request.model.metadata["format-family"]
        return PreflightResult.Ready(
            FakePlan(
                EnginePlanSummary(
                    engineId = id,
                    engineVersion = version,
                    artifactId = artifact.artifact.id,
                    capability = request.capability,
                    modelId = request.model.id,
                    requestFingerprint = request.requestFingerprint,
                    resources = resourceEstimate,
                    effectiveOptionsDescription = "custom=${typed?.customMode ?: "generic"}",
                ),
                typed?.customMode ?: "generic",
                request.model,
            ),
        )
    }

    override suspend fun load(plan: EnginePlan): EngineSession {
        loadFailure?.let { throw it }
        loadStarted.complete(Unit)
        loadGate?.await()
        val fakePlan = plan as FakePlan
        session = FakeSession(
            invalidSessionEngineId ?: id,
            fakePlan.model,
            fakePlan.mode,
            generationGate,
            unloadFailure,
            closeFailure,
            unloadGate,
        )
        return session
    }
}

private data class FakePlan(override val summary: EnginePlanSummary, val mode: String, val model: ModelDescriptor) : EnginePlan

private class FakeSession(
    override val engineId: EngineId,
    override val model: ModelDescriptor,
    private val mode: String,
    private val generationGate: CompletableDeferred<Unit>? = null,
    private val unloadFailure: Boolean = false,
    private val closeFailure: Boolean = false,
    private val unloadGate: CompletableDeferred<Unit>? = null,
) : EngineSession {
    var cancelCalls = 0
    var unloadCalls = 0
    var closeCalls = 0
    val generationStarted = CompletableDeferred<Unit>()
    val unloadStarted = CompletableDeferred<Unit>()

    override suspend fun generate(operationId: OperationId, request: EngineRequest): Flow<GenerationEvent> = flow {
        emit(GenerationEvent.TextDelta("custom=$mode"))
        generationStarted.complete(Unit)
        generationGate?.await()
        emit(GenerationEvent.Completed(CompletionReason.END_OF_TURN))
    }

    override suspend fun cancel(operationId: OperationId) { cancelCalls++ }
    override suspend fun unload() {
        unloadCalls++
        unloadStarted.complete(Unit)
        unloadGate?.await()
        if (unloadFailure) error("native unload failed")
    }
    override suspend fun close() {
        closeCalls++
        if (closeFailure) error("native close failed")
    }
}

private class FakeResources(private val availableMemory: Long = 1_000_000, private val capturedAt: Long = 1) : DeviceResourceSource {
    override suspend fun snapshot() = ResourceSnapshot(
        availableMemoryBytes = availableMemory,
        availableStorageBytes = 1_000_000,
        capturedAtEpochMillis = capturedAt,
    )
}

private class FakeArtifactAccess : ArtifactAccess {
    val ref = ArtifactRef(ArtifactId("fake-artifact"))
    var closedLeases = 0
    override suspend fun acquire(ref: ArtifactRef): ArtifactLease? = if (ref.id == this.ref.id) object : ArtifactLease {
        override val artifact = ref
        override val location: ArtifactLocation = ArtifactLocation.LocalFile("/fake/model.bin")
        override suspend fun close() { closedLeases++ }
    } else null
}
