package com.debanshu777.caraml.core.platform

import com.debanshu777.caraml.core.recommendation.AssessmentReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceSnapshotPolicyTest {
    @Test
    fun baseBudgetSubtractsTheLargestReserveExactlyOnce() {
        assertEquals(
            600L,
            computeBaseBudget(
                allocatable = 1_000L,
                osThreshold = 200L,
                noiseP95 = 400L,
                minimum = 300L,
            ),
        )
        assertEquals(
            100L,
            computeBaseBudget(
                allocatable = 500L,
                osThreshold = 400L,
                noiseP95 = 200L,
                minimum = 300L,
            ),
        )
    }

    @Test
    fun storageReserveIsProfileIndependentAndBounded() {
        assertEquals(512L * MIB, computeStorageReserve(4L * GIB))
        assertEquals(10L * GIB, computeStorageReserve(1L * TIB))
        assertEquals(0L, computeStorageBudget(256L * MIB))
    }

    @Test
    fun invalidBudgetInputsRemainUnavailableInsteadOfWrapping() {
        assertNull(computeBaseBudget(-1L, 0L, 0L, 0L))
        assertNull(computeBaseBudget(Long.MAX_VALUE, -1L, 0L, 0L))
        assertNull(computeStorageReserve(-1L))
        assertEquals(10L * GIB, computeStorageReserve(Long.MAX_VALUE))
    }

    @Test
    fun oneAndTwoCoreFallbacksNeverUseAnEmptyRange() {
        assertEquals(1, fallbackPerformanceCoreCount(1))
        assertEquals(1, fallbackPerformanceCoreCount(2))
        assertNull(fallbackPerformanceCoreCount(0))
        assertNull(fallbackPerformanceCoreCount(1_025))
    }

    @Test
    fun invalidCoreReadingsAreReplacedAndPreservedAsEvidence() {
        val profile = validatedHardwareProfile(
            cpuArchitecture = "test",
            logicalCoreCountReading = 0,
            performanceCoreCountReading = 4,
            instructionSets = emptySet(),
            backends = emptyList(),
            memoryTopology = MemoryTopology.UNKNOWN,
        )

        assertEquals(1, profile.logicalCoreCount)
        assertNull(profile.performanceCoreCount)
        assertTrue(profile.evidence.any { it.reason == AssessmentReason.INVALID_CORE_COUNT })
    }

    @Test
    fun unsignedCoreCountsAreValidatedBeforeNarrowingToInt() {
        val valid = validatedUnsignedCoreCount(8uL, "active-processor-count")
        val justOverLimit = validatedUnsignedCoreCount(1_025uL, "active-processor-count")
        val wrapsToEightIfNarrowedFirst = validatedUnsignedCoreCount(
            4_294_967_304uL,
            "active-processor-count",
        )
        val maximum = validatedUnsignedCoreCount(ULong.MAX_VALUE, "active-processor-count")

        assertEquals(8, valid.value)
        assertTrue(valid.evidence.isEmpty())
        listOf(justOverLimit, wrapsToEightIfNarrowedFirst, maximum).forEach { reading ->
            assertEquals(1, reading.value)
            assertTrue(reading.evidence.any { it.reason == AssessmentReason.INVALID_CORE_COUNT })
        }
    }

    @Test
    fun ownedMachPortIsReleasedAfterSuccessfulCollection() {
        var releasedPort: UInt? = null

        val result = withOwnedMachPort(
            port = 7u,
            deallocate = { releasedPort = it },
            block = { 42L },
        )

        assertEquals(42L, result)
        assertEquals(7u, releasedPort)
    }

    @Test
    fun ownedMachPortIsReleasedWhenCollectionThrows() {
        var releasedPort: UInt? = null

        assertFailsWith<IllegalStateException> {
            withOwnedMachPort(
                port = 11u,
                deallocate = { releasedPort = it },
                block = { error("collection failed") },
            )
        }

        assertEquals(11u, releasedPort)
    }

    @Test
    fun timestampFreshnessRejectsStaleAndFutureSnapshots() {
        val resources = resourceSnapshot(capturedAtEpochMs = 1_000L)

        assertTrue(resources.isFreshAt(nowEpochMs = 31_000L, maxAgeMs = 30_000L))
        assertFalse(resources.isFreshAt(nowEpochMs = 31_001L, maxAgeMs = 30_000L))
        assertFalse(resources.isFreshAt(nowEpochMs = 999L, maxAgeMs = 30_000L))
    }

    private fun resourceSnapshot(capturedAtEpochMs: Long) = ResourceSnapshot(
        additionalAllocatableHostBytes = 2L * GIB,
        additionalAllocatableGpuBytes = null,
        currentProcessBytes = 128L * MIB,
        freeStorageBytes = null,
        osPressureReserveHostBytes = null,
        observedAppFootprintNoiseP95Bytes = null,
        platformMinimumReserveHostBytes = 512L * MIB,
        lowMemory = null,
        thermalState = ThermalState.UNKNOWN,
        powerPolicyState = PowerPolicyState.UNKNOWN,
        capturedAtEpochMs = capturedAtEpochMs,
        evidence = emptyList(),
    )

    private companion object {
        const val MIB = 1_048_576L
        const val GIB = 1_073_741_824L
        const val TIB = 1_099_511_627_776L
    }
}
