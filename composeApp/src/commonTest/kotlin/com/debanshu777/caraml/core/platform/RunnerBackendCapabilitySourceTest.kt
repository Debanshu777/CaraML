package com.debanshu777.caraml.core.platform

import com.debanshu777.runner.NativeBackendCapability
import com.debanshu777.runner.NativeBackendDeviceType
import com.debanshu777.runner.NativeBackendKind
import kotlin.test.Test
import kotlin.test.assertEquals

class RunnerBackendCapabilitySourceTest {
    @Test
    fun cpuAndBlasDoesNotAdvertiseBlasAsGpuOffload() {
        val capabilities = mapRunnerBackendCapabilities(
            listOf(
                native(NativeBackendKind.CPU, NativeBackendDeviceType.CPU, 1_000L),
                native(NativeBackendKind.CUDA, NativeBackendDeviceType.ACCELERATOR, 8_000L),
                native(NativeBackendKind.OTHER, NativeBackendDeviceType.META, 9_000L),
            ),
        )

        assertEquals(BackendStatus.AVAILABLE, capabilities.single { it.kind == BackendKind.CPU }.status)
        assertEquals(BackendStatus.UNAVAILABLE, capabilities.single { it.kind == BackendKind.CUDA }.status)
        assertEquals(BackendStatus.UNAVAILABLE, capabilities.single { it.kind == BackendKind.OTHER }.status)
    }

    @Test
    fun cpuGpuAndBlasCountsOnlyGpuDeviceMemoryAsOffloadEvidence() {
        val capabilities = mapRunnerBackendCapabilities(
            listOf(
                native(NativeBackendKind.CPU, NativeBackendDeviceType.CPU, 1_000L),
                native(NativeBackendKind.CUDA, NativeBackendDeviceType.DISCRETE_GPU, 3_000L),
                native(NativeBackendKind.CUDA, NativeBackendDeviceType.ACCELERATOR, 8_000L),
            ),
        )

        val cuda = capabilities.single { it.kind == BackendKind.CUDA }
        assertEquals(BackendStatus.AVAILABLE, cuda.status)
        assertEquals(3_000L, cuda.additionalAllocatableBytes)
    }

    private fun native(
        kind: NativeBackendKind,
        type: NativeBackendDeviceType,
        freeBytes: Long,
    ) = NativeBackendCapability(
        kind = kind,
        deviceType = type,
        freeBytes = freeBytes,
        totalBytes = freeBytes * 2,
    )
}
