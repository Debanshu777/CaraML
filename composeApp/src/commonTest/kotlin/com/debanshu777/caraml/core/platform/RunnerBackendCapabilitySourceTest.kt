package com.debanshu777.caraml.core.platform

import com.debanshu777.diffusionrunner.DiffusionBackendCapability
import com.debanshu777.diffusionrunner.DiffusionBackendDeviceType
import com.debanshu777.diffusionrunner.DiffusionBackendKind
import com.debanshu777.runner.NativeBackendCapability
import com.debanshu777.runner.NativeBackendDeviceType
import com.debanshu777.runner.NativeBackendKind
import kotlin.test.Test
import kotlin.test.assertEquals

class RunnerBackendCapabilitySourceTest {
    @Test
    fun backendPresentForLlamaButUnavailableToDiffusionIsNotAdvertised() {
        val capabilities = mapRunnerBackendCapabilities(
            llamaCapabilities = listOf(
                native(NativeBackendKind.CPU, NativeBackendDeviceType.CPU, 1_000L),
                native(NativeBackendKind.CUDA, NativeBackendDeviceType.DISCRETE_GPU, 8_000L),
            ),
            diffusionCapabilities = listOf(
                diffusionNative(
                    DiffusionBackendKind.CPU,
                    DiffusionBackendDeviceType.CPU,
                    1_000L,
                ),
            ),
        )

        val cuda = capabilities.single { it.kind == BackendKind.CUDA }
        assertEquals(BackendStatus.UNAVAILABLE, cuda.status)
        assertEquals(null, cuda.additionalAllocatableBytes)
    }

    @Test
    fun cpuAndBlasDoesNotAdvertiseBlasAsGpuOffload() {
        val capabilities = mapRunnerBackendCapabilities(
            llamaCapabilities = listOf(
                native(NativeBackendKind.CPU, NativeBackendDeviceType.CPU, 1_000L),
                native(NativeBackendKind.CUDA, NativeBackendDeviceType.ACCELERATOR, 8_000L),
                native(NativeBackendKind.OTHER, NativeBackendDeviceType.META, 9_000L),
            ),
            diffusionCapabilities = listOf(
                diffusionNative(DiffusionBackendKind.CPU, DiffusionBackendDeviceType.CPU, 1_000L),
            ),
        )

        assertEquals(BackendStatus.AVAILABLE, capabilities.single { it.kind == BackendKind.CPU }.status)
        assertEquals(BackendStatus.UNAVAILABLE, capabilities.single { it.kind == BackendKind.CUDA }.status)
        assertEquals(BackendStatus.UNAVAILABLE, capabilities.single { it.kind == BackendKind.OTHER }.status)
    }

    @Test
    fun cpuGpuAndBlasCountsOnlyGpuDeviceMemoryAsOffloadEvidence() {
        val capabilities = mapRunnerBackendCapabilities(
            llamaCapabilities = listOf(
                native(NativeBackendKind.CPU, NativeBackendDeviceType.CPU, 1_000L),
                native(NativeBackendKind.CUDA, NativeBackendDeviceType.DISCRETE_GPU, 3_000L),
                native(NativeBackendKind.CUDA, NativeBackendDeviceType.ACCELERATOR, 8_000L),
            ),
            diffusionCapabilities = listOf(
                diffusionNative(DiffusionBackendKind.CPU, DiffusionBackendDeviceType.CPU, 1_000L),
                diffusionNative(DiffusionBackendKind.CUDA, DiffusionBackendDeviceType.DISCRETE_GPU, 4_000L),
            ),
        )

        val cuda = capabilities.single { it.kind == BackendKind.CUDA }
        assertEquals(BackendStatus.AVAILABLE, cuda.status)
        assertEquals(3_000L, cuda.additionalAllocatableBytes)
    }

    @Test
    fun unknownGpuKindsDoNotBecomeCommonBackendEvidence() {
        val capabilities = mapRunnerBackendCapabilities(
            llamaCapabilities = listOf(
                native(NativeBackendKind.CPU, NativeBackendDeviceType.CPU, 1_000L),
                native(NativeBackendKind.OTHER, NativeBackendDeviceType.DISCRETE_GPU, 3_000L),
            ),
            diffusionCapabilities = listOf(
                diffusionNative(DiffusionBackendKind.CPU, DiffusionBackendDeviceType.CPU, 1_000L),
                diffusionNative(DiffusionBackendKind.OTHER, DiffusionBackendDeviceType.DISCRETE_GPU, 4_000L),
            ),
        )

        assertEquals(BackendStatus.UNAVAILABLE, capabilities.single { it.kind == BackendKind.OTHER }.status)
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

    private fun diffusionNative(
        kind: DiffusionBackendKind,
        type: DiffusionBackendDeviceType,
        freeBytes: Long,
    ) = DiffusionBackendCapability(
        kind = kind,
        deviceType = type,
        freeBytes = freeBytes,
        totalBytes = freeBytes * 2,
    )
}
