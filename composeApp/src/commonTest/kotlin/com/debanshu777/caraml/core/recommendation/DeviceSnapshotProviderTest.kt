package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendCapability
import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.BackendStatus
import com.debanshu777.caraml.core.platform.HardwareProfile
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.platform.PowerPolicyState
import com.debanshu777.caraml.core.platform.ResourceSnapshot
import com.debanshu777.caraml.core.platform.ResourcePoolConfidence
import com.debanshu777.caraml.core.platform.ThermalState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DeviceSnapshotProviderTest {
    @Test
    fun unifiedMemoryUsesTheSmallerKnownPoolInsteadOfAddingThem() = runBlocking {
        val snapshot = provider(
            topology = MemoryTopology.UNIFIED,
            hostBytes = 4L * GIB,
            gpuBytes = 2L * GIB,
        ).capture()

        assertNull(snapshot.baseHostBudgetBytes)
        assertNull(snapshot.baseGpuBudgetBytes)
        assertEquals(2L * GIB, snapshot.baseSharedBudgetBytes)
    }

    @Test
    fun discreteMemoryKeepsHostAndGpuPoolsSeparate() = runBlocking {
        val snapshot = provider(
            topology = MemoryTopology.DISCRETE,
            hostBytes = 4L * GIB,
            gpuBytes = 2L * GIB,
        ).capture()

        assertEquals(3L * GIB, snapshot.baseHostBudgetBytes)
        assertEquals(2L * GIB, snapshot.baseGpuBudgetBytes)
        assertNull(snapshot.baseSharedBudgetBytes)
    }

    @Test
    fun unifiedBudgetConfidenceUsesTheWeakestContributingPool() = runBlocking {
        val snapshot = provider(
            topology = MemoryTopology.UNIFIED,
            hostBytes = 4L * GIB,
            gpuBytes = 2L * GIB,
            hostConfidence = Confidence.HIGH,
            gpuConfidence = Confidence.LOW,
        ).capture()

        assertEquals(Confidence.LOW, snapshot.budgetConfidence.shared)
        assertNull(snapshot.budgetConfidence.host)
        assertNull(snapshot.budgetConfidence.gpu)
    }

    @Test
    fun unifiedPresentButUntrustedConstraintSuppressesSharedBudget() = runBlocking {
        val snapshot = provider(
            topology = MemoryTopology.UNIFIED,
            hostBytes = 4L * GIB,
            gpuBytes = 1L * GIB,
            hostConfidence = Confidence.HIGH,
            gpuConfidence = null,
        ).capture()

        assertNull(snapshot.baseSharedBudgetBytes)
        assertNull(snapshot.budgetConfidence.shared)
    }

    @Test
    fun discreteBudgetConfidenceKeepsHostAndGpuEvidenceSeparate() = runBlocking {
        val snapshot = provider(
            topology = MemoryTopology.DISCRETE,
            hostBytes = 4L * GIB,
            gpuBytes = 2L * GIB,
            hostConfidence = Confidence.HIGH,
            gpuConfidence = Confidence.LOW,
        ).capture()

        assertEquals(Confidence.HIGH, snapshot.budgetConfidence.host)
        assertEquals(Confidence.LOW, snapshot.budgetConfidence.gpu)
        assertNull(snapshot.budgetConfidence.shared)
    }

    @Test
    fun budgetWithUnknownSourceConfidenceRemainsUnavailable() = runBlocking {
        val snapshot = provider(
            topology = MemoryTopology.UNKNOWN,
            hostBytes = 4L * GIB,
            gpuBytes = null,
            hostConfidence = null,
        ).capture()

        assertNull(snapshot.baseHostBudgetBytes)
        assertNull(snapshot.budgetConfidence.host)
    }

    @Test
    fun lowConfidenceDesktopFallbackCannotBecomeBalancedRecommended() = runBlocking {
        val snapshot = provider(
            topology = MemoryTopology.UNKNOWN,
            hostBytes = 4L * GIB,
            gpuBytes = null,
            hostConfidence = Confidence.LOW,
        ).capture()

        val recommendation = RecommendationPolicy(RunPlanOptimizer(clock = { 1_000L })).recommend(
            task6Assessment(plans = listOf(task6PlanAssessment(host = task6Range(100, 100, 100)))),
            snapshot,
            RecommendationProfile(riskTolerance = RiskTolerance.BALANCED),
        )

        assertEquals(RecommendationCategory.RISKY, recommendation.category, recommendation.toString())
        assertTrue(recommendation.reasons.contains(AssessmentReason.SAFETY_EVIDENCE_LIMITED))
    }

    @Test
    fun unifiedMemoryIncludesRegisteredBackendHeadroomInTheMinimum() = runBlocking {
        val snapshot = provider(
            topology = MemoryTopology.UNIFIED,
            hostBytes = 4L * GIB,
            gpuBytes = null,
            backendCapabilities = {
                listOf(
                    cpu(),
                    BackendCapability(
                        kind = BackendKind.METAL,
                        status = BackendStatus.AVAILABLE,
                        additionalAllocatableBytes = 1L * GIB,
                        availabilityConfidence = Confidence.HIGH,
                        headroomConfidence = Confidence.HIGH,
                        evidence = listOf(
                            Evidence(
                                AssessmentReason.RESOURCE_READING_VALIDATED,
                                Confidence.HIGH,
                                "metal-headroom-fixture",
                            ),
                        ),
                    ),
                )
            },
        ).capture()

        assertEquals(1L * GIB, snapshot.baseSharedBudgetBytes)
        assertEquals(Confidence.HIGH, snapshot.budgetConfidence.shared)
    }

    @Test
    fun backendAvailabilityEvidenceDoesNotEstablishHeadroomConfidence() = runBlocking {
        val snapshot = provider(
            topology = MemoryTopology.UNIFIED,
            hostBytes = 4L * GIB,
            gpuBytes = null,
            backendCapabilities = {
                listOf(
                    cpu(),
                    BackendCapability(
                        kind = BackendKind.METAL,
                        status = BackendStatus.AVAILABLE,
                        additionalAllocatableBytes = 1L * GIB,
                        availabilityConfidence = Confidence.HIGH,
                        headroomConfidence = null,
                        evidence = listOf(
                            Evidence(
                                AssessmentReason.BACKEND_CAPABILITY_VERIFIED,
                                Confidence.HIGH,
                                "availability-only",
                            ),
                        ),
                    ),
                )
            },
        ).capture()

        assertNull(snapshot.baseSharedBudgetBytes)
        assertNull(snapshot.budgetConfidence.shared)
    }

    @Test
    fun unifiedTrustworthySingleHostSourceProducesSharedBudget() = runBlocking {
        val snapshot = provider(
            topology = MemoryTopology.UNIFIED,
            hostBytes = 4L * GIB,
            gpuBytes = null,
            hostConfidence = Confidence.MEDIUM,
        ).capture()

        assertEquals(3L * GIB, snapshot.baseSharedBudgetBytes)
        assertEquals(Confidence.MEDIUM, snapshot.budgetConfidence.shared)
    }

    @Test
    fun missingAndInvalidReadingsStayUnknownWithEvidence() = runBlocking {
        val snapshot = provider(
            topology = MemoryTopology.DISCRETE,
            hostBytes = -1L,
            gpuBytes = null,
            storageBytes = -1L,
        ).capture()

        assertNull(snapshot.baseHostBudgetBytes)
        assertNull(snapshot.baseGpuBudgetBytes)
        assertNull(snapshot.baseStorageBudgetBytes)
        assertTrue(snapshot.evidence.any { it.reason == AssessmentReason.INVALID_OS_MEMORY_READING })
        assertTrue(snapshot.evidence.any { it.reason == AssessmentReason.RESOURCE_READING_UNAVAILABLE })
        assertTrue(snapshot.evidence.any { it.reason == AssessmentReason.INVALID_STORAGE_READING })
    }

    @Test
    fun staleAndLowMemorySignalsRemainVisible() = runBlocking {
        val snapshot = provider(
            topology = MemoryTopology.UNIFIED,
            hostBytes = 4L * GIB,
            gpuBytes = null,
            capturedAtEpochMs = 1_000L,
            nowEpochMs = 40_000L,
            lowMemory = true,
        ).capture()

        assertFalse(snapshot.isFresh)
        assertTrue(snapshot.evidence.any { it.reason == AssessmentReason.RESOURCE_SNAPSHOT_STALE })
        assertTrue(snapshot.evidence.any { it.reason == AssessmentReason.LOW_MEMORY_PRESSURE })
        assertTrue(snapshot.resources.evidence.any { it.reason == AssessmentReason.RESOURCE_SNAPSHOT_STALE })
        assertTrue(snapshot.resources.evidence.any { it.reason == AssessmentReason.LOW_MEMORY_PRESSURE })
    }

    @Test
    fun captureRunsThroughTheInjectedDispatcherAndCopiesBackendCollections() = runBlocking {
        val dispatcher = RecordingDispatcher()
        val mutableBackends = mutableListOf(cpu())
        val snapshot = provider(
            topology = MemoryTopology.UNIFIED,
            hostBytes = 4L * GIB,
            gpuBytes = null,
            dispatcher = dispatcher,
            backendCapabilities = { mutableBackends },
        ).capture()
        mutableBackends += BackendCapability(
            kind = BackendKind.VULKAN,
            status = BackendStatus.UNKNOWN,
            additionalAllocatableBytes = null,
            availabilityConfidence = null,
            headroomConfidence = null,
            evidence = emptyList(),
        )

        assertTrue(dispatcher.wasDispatched)
        assertEquals(listOf(BackendKind.CPU), snapshot.hardwareProfile.backends.map { it.kind })
    }

    @Test
    fun cancellationIsRethrownUnchanged() {
        val cancellation = CancellationException("cancel snapshot")
        val provider = DeviceSnapshotProvider(
            hardwareProfileSource = { hardware(MemoryTopology.UNIFIED) },
            resourceSnapshotSource = { throw cancellation },
            backendCapabilitySource = { listOf(cpu()) },
            storageBytesSource = { 8L * GIB },
            probeDispatcher = ImmediateDispatcher,
            clock = { 1_000L },
        )

        val thrown = assertFailsWith<CancellationException> { runBlocking { provider.capture() } }
        assertSame(cancellation, thrown)
    }

    @Test
    fun collectorFailureReturnsUnknownResourcesInsteadOfCrashing() = runBlocking {
        val provider = DeviceSnapshotProvider(
            hardwareProfileSource = { hardware(MemoryTopology.UNIFIED) },
            resourceSnapshotSource = { error("collector failed") },
            backendCapabilitySource = { listOf(cpu()) },
            storageBytesSource = { 8L * GIB },
            probeDispatcher = ImmediateDispatcher,
            clock = { 1_000L },
        )

        val snapshot = provider.capture()

        assertNull(snapshot.baseSharedBudgetBytes)
        assertTrue(snapshot.evidence.any { it.reason == AssessmentReason.RESOURCE_READING_UNAVAILABLE })
    }

    @Test
    fun hardwareCollectorFailureReturnsConservativeUnknownProfileInsteadOfCrashing() = runBlocking {
        val provider = DeviceSnapshotProvider(
            hardwareProfileSource = { error("hardware collector failed") },
            resourceSnapshotSource = {
                ResourceSnapshot(
                    additionalAllocatableHostBytes = 4L * GIB,
                    additionalAllocatableGpuBytes = null,
                    currentProcessBytes = null,
                    freeStorageBytes = null,
                    osPressureReserveHostBytes = null,
                    observedAppFootprintNoiseP95Bytes = null,
                    platformMinimumReserveHostBytes = 512L * MIB,
                    lowMemory = null,
                    thermalState = ThermalState.UNKNOWN,
                    powerPolicyState = PowerPolicyState.UNKNOWN,
                    capturedAtEpochMs = 1_000L,
                    evidence = emptyList(),
                )
            },
            backendCapabilitySource = { emptyList() },
            storageBytesSource = { 8L * GIB },
            probeDispatcher = ImmediateDispatcher,
            clock = { 1_000L },
        )

        val snapshot = provider.capture()

        assertEquals(1, snapshot.hardwareProfile.logicalCoreCount)
        assertEquals(MemoryTopology.UNKNOWN, snapshot.hardwareProfile.memoryTopology)
        assertTrue(snapshot.evidence.any { it.reason == AssessmentReason.RESOURCE_READING_UNAVAILABLE })
    }

    @Test
    fun captureRevalidatesEveryHardwareProfileReturnedByItsSource() = runBlocking {
        val invalidProfiles = listOf(
            hardware(MemoryTopology.UNIFIED, logicalCoreCount = 0, performanceCoreCount = 1),
            hardware(MemoryTopology.UNIFIED, logicalCoreCount = 1_025, performanceCoreCount = 1),
            hardware(MemoryTopology.UNIFIED, logicalCoreCount = 8, performanceCoreCount = 9),
        )

        invalidProfiles.forEach { invalidProfile ->
            val snapshot = provider(
                topology = MemoryTopology.UNIFIED,
                hostBytes = 4L * GIB,
                gpuBytes = null,
                hardwareProfile = invalidProfile,
            ).capture()

            assertEquals(
                invalidProfile.logicalCoreCount.takeIf { it in 1..1_024 } ?: 1,
                snapshot.hardwareProfile.logicalCoreCount,
            )
            assertNull(snapshot.hardwareProfile.performanceCoreCount)
            assertTrue(snapshot.evidence.any { it.reason == AssessmentReason.INVALID_CORE_COUNT })
        }
    }

    @Test
    fun resourceOnlyRefreshDoesNotRepeatHardwareOrBackendCapabilityProbes() = runBlocking {
        var hardwareReads = 0
        var backendReads = 0
        var resourceReads = 0
        val provider = DeviceSnapshotProvider(
            hardwareProfileSource = {
                hardwareReads += 1
                hardware(MemoryTopology.UNKNOWN)
            },
            resourceSnapshotSource = {
                resourceReads += 1
                ResourceSnapshot(
                    additionalAllocatableHostBytes = 4L * GIB,
                    additionalAllocatableGpuBytes = null,
                    currentProcessBytes = 128L * MIB,
                    freeStorageBytes = null,
                    osPressureReserveHostBytes = 0L,
                    observedAppFootprintNoiseP95Bytes = 0L,
                    platformMinimumReserveHostBytes = 512L * MIB,
                    lowMemory = false,
                    thermalState = ThermalState.NOMINAL,
                    powerPolicyState = PowerPolicyState.NORMAL,
                    capturedAtEpochMs = 1_000L,
                    evidence = emptyList(),
                    confidence = ResourcePoolConfidence(host = Confidence.HIGH),
                )
            },
            backendCapabilitySource = {
                backendReads += 1
                listOf(cpu())
            },
            storageBytesSource = { 8L * GIB },
            probeDispatcher = ImmediateDispatcher,
            clock = { 1_000L },
        )
        val initial = provider.capture()

        provider.refreshResources(initial)

        assertEquals(1, hardwareReads)
        assertEquals(1, backendReads)
        assertEquals(2, resourceReads)
    }

    private fun provider(
        topology: MemoryTopology,
        hostBytes: Long?,
        gpuBytes: Long?,
        storageBytes: Long = 20L * GIB,
        capturedAtEpochMs: Long = 1_000L,
        nowEpochMs: Long = 1_000L,
        lowMemory: Boolean? = null,
        dispatcher: CoroutineDispatcher = ImmediateDispatcher,
        backendCapabilities: () -> List<BackendCapability> = { listOf(cpu()) },
        hardwareProfile: HardwareProfile = hardware(topology),
        hostConfidence: Confidence? = hostBytes?.let { Confidence.HIGH },
        gpuConfidence: Confidence? = gpuBytes?.let { Confidence.HIGH },
    ) = DeviceSnapshotProvider(
        hardwareProfileSource = { hardwareProfile },
        resourceSnapshotSource = {
            ResourceSnapshot(
                additionalAllocatableHostBytes = hostBytes,
                additionalAllocatableGpuBytes = gpuBytes,
                currentProcessBytes = 128L * MIB,
                freeStorageBytes = null,
                osPressureReserveHostBytes = 512L * MIB,
                observedAppFootprintNoiseP95Bytes = 1L * GIB,
                platformMinimumReserveHostBytes = 512L * MIB,
                lowMemory = lowMemory,
                thermalState = ThermalState.UNKNOWN,
                powerPolicyState = PowerPolicyState.UNKNOWN,
                capturedAtEpochMs = capturedAtEpochMs,
                evidence = emptyList(),
                confidence = ResourcePoolConfidence(
                    host = hostConfidence,
                    gpu = gpuConfidence,
                ),
            )
        },
        backendCapabilitySource = backendCapabilities,
        storageBytesSource = { storageBytes },
        probeDispatcher = dispatcher,
        clock = { nowEpochMs },
    )

    private fun hardware(
        topology: MemoryTopology,
        logicalCoreCount: Int = 8,
        performanceCoreCount: Int? = 4,
    ) = HardwareProfile(
        cpuArchitecture = "test",
        logicalCoreCount = logicalCoreCount,
        performanceCoreCount = performanceCoreCount,
        instructionSets = emptySet(),
        backends = listOf(cpu()),
        memoryTopology = topology,
        evidence = emptyList(),
    )

    private fun cpu() = BackendCapability(
        kind = BackendKind.CPU,
        status = BackendStatus.AVAILABLE,
        additionalAllocatableBytes = null,
        availabilityConfidence = Confidence.HIGH,
        headroomConfidence = null,
        evidence = emptyList(),
    )

    private class RecordingDispatcher : CoroutineDispatcher() {
        var wasDispatched = false

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            wasDispatched = true
            block.run()
        }
    }

    private object ImmediateDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
    }

    private companion object {
        const val MIB = 1_048_576L
        const val GIB = 1_073_741_824L
    }
}
