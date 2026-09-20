package com.debanshu777.caraml.core.data.inference

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.recommendation.DiffusionMode
import com.debanshu777.caraml.core.recommendation.DiffusionRunPlan
import com.debanshu777.caraml.core.recommendation.NativeLoadPreflight
import com.debanshu777.diffusionrunner.DiffusionArchitecture
import com.debanshu777.diffusionrunner.DiffusionBackendDeviceType
import com.debanshu777.diffusionrunner.DiffusionBackendKind
import com.debanshu777.diffusionrunner.DiffusionFitReport
import com.debanshu777.diffusionrunner.DiffusionMemoryConfidence
import com.debanshu777.diffusionrunner.DiffusionParameterPlacement
import com.debanshu777.diffusionrunner.DiffusionPreflightBackend
import com.debanshu777.diffusionrunner.DiffusionPreflightComponent
import com.debanshu777.diffusionrunner.DiffusionPreflightResult
import com.debanshu777.diffusionrunner.DiffusionQuantization
import com.debanshu777.diffusionrunner.DiffusionComponentRole
import com.debanshu777.diffusionrunner.DiffusionRuntimePlacement
import kotlin.test.Test
import kotlin.test.assertEquals

class DiffusionInferenceRepositoryTest {
    @Test
    fun cpuPlanRejectsGpuFirstNativeExecution() {
        val result = exactDiffusionNativePreflight(
            cpuPlan(),
            fit(
                components = listOf(
                    component(
                        role = DiffusionComponentRole.MODEL_BUNDLE,
                        runtime = DiffusionRuntimePlacement.GPU,
                        backendMask = 1L,
                        params = DiffusionParameterPlacement.CPU,
                    ),
                ),
                backends = listOf(backend(DiffusionBackendKind.VULKAN, ordinal = 0)),
            ),
        )

        assertEquals(NativeLoadPreflight.Invalid, result)
    }

    @Test
    fun cpuPlanAcceptsOnlyExplicitCpuRuntimeAndParameters() {
        val result = exactDiffusionNativePreflight(
            cpuPlan(),
            fit(
                components = listOf(
                    component(
                        role = DiffusionComponentRole.MODEL_BUNDLE,
                        runtime = DiffusionRuntimePlacement.CPU,
                        params = DiffusionParameterPlacement.CPU,
                    ),
                ),
                backends = listOf(backend(DiffusionBackendKind.CPU, ordinal = 0)),
            ),
        )

        assertEquals(NativeLoadPreflight.Fit, result)
    }

    @Test
    fun acceleratedPlanRequiresItsBackendAndComponentCpuOverrides() {
        val plan = gpuPlan()
        val matching = fit(
            components = listOf(
                component(
                    role = DiffusionComponentRole.DIFFUSION_MODEL,
                    runtime = DiffusionRuntimePlacement.GPU,
                    backendMask = 2L,
                    params = DiffusionParameterPlacement.CPU,
                ),
                component(
                    role = DiffusionComponentRole.TEXT_ENCODER,
                    runtime = DiffusionRuntimePlacement.CPU,
                    params = DiffusionParameterPlacement.CPU,
                    ordinal = 1,
                ),
                component(
                    role = DiffusionComponentRole.VAE,
                    runtime = DiffusionRuntimePlacement.CPU,
                    params = DiffusionParameterPlacement.CPU,
                    ordinal = 2,
                ),
            ),
            backends = listOf(
                backend(DiffusionBackendKind.CPU, ordinal = 0),
                backend(DiffusionBackendKind.VULKAN, ordinal = 1),
            ),
        )
        val wrongVaePlacement = matching.copy(
            report = matching.report.copy(
                components = matching.report.components.map { component ->
                    if (component.role == DiffusionComponentRole.VAE) {
                        component.copy(
                            runtimePlacement = DiffusionRuntimePlacement.GPU,
                            runtimeBackendMask = 2L,
                        )
                    } else {
                        component
                    }
                },
            ),
        )
        val wrongBackend = matching.copy(
            report = matching.report.copy(
                backends = matching.report.backends.map { backend ->
                    if (backend.ordinal == 1) backend.copy(kind = DiffusionBackendKind.CUDA) else backend
                },
            ),
        )

        assertEquals(NativeLoadPreflight.Fit, exactDiffusionNativePreflight(plan, matching))
        assertEquals(NativeLoadPreflight.Invalid, exactDiffusionNativePreflight(plan, wrongVaePlacement))
        assertEquals(NativeLoadPreflight.Invalid, exactDiffusionNativePreflight(plan, wrongBackend))
    }

    @Test
    fun effectiveNativeStreamingMustMatchTheAssessedPlan() {
        val report = fit(
            components = listOf(
                component(
                    role = DiffusionComponentRole.MODEL_BUNDLE,
                    runtime = DiffusionRuntimePlacement.CPU,
                    params = DiffusionParameterPlacement.CPU,
                ),
            ),
            backends = listOf(backend(DiffusionBackendKind.CPU, ordinal = 0)),
            streamLayers = true,
        )

        assertEquals(NativeLoadPreflight.Invalid, exactDiffusionNativePreflight(cpuPlan(), report))
    }

    private fun cpuPlan() = plan(
        backend = BackendKind.CPU,
        keepClipOnCpu = true,
        keepVaeOnCpu = true,
    )

    private fun gpuPlan() = plan(
        backend = BackendKind.VULKAN,
        keepClipOnCpu = true,
        keepVaeOnCpu = true,
    )

    private fun plan(
        backend: BackendKind,
        keepClipOnCpu: Boolean,
        keepVaeOnCpu: Boolean,
    ) = DiffusionRunPlan(
        mode = DiffusionMode.IMAGE,
        width = 512,
        height = 512,
        frameCount = 1,
        batchSize = 1,
        steps = 20,
        vaeTiling = false,
        offloadToCpu = true,
        keepClipOnCpu = keepClipOnCpu,
        keepVaeOnCpu = keepVaeOnCpu,
        maxVramBytes = null,
        layerStreaming = false,
        requiresUserAcceptance = false,
        backend = backend,
        memoryTopology = if (backend == BackendKind.CPU) MemoryTopology.UNKNOWN else MemoryTopology.DISCRETE,
        compromises = emptyList(),
    )

    private fun fit(
        components: List<DiffusionPreflightComponent>,
        backends: List<DiffusionPreflightBackend>,
        streamLayers: Boolean = false,
    ) = DiffusionPreflightResult.Fit(
        DiffusionFitReport(
            architecture = DiffusionArchitecture.SDXL,
            quantization = DiffusionQuantization.Q4_K,
            memoryConfidence = DiffusionMemoryConfidence.MEDIUM,
            streamLayers = streamLayers,
            components = components,
            backends = backends,
        ),
    )

    private fun component(
        role: DiffusionComponentRole,
        runtime: DiffusionRuntimePlacement,
        backendMask: Long = 0L,
        params: DiffusionParameterPlacement,
        ordinal: Int = 0,
    ) = DiffusionPreflightComponent(
        role = role,
        ordinal = ordinal,
        parameterBytes = 1_024L,
        runtimePlacement = runtime,
        runtimeBackendMask = backendMask,
        parameterPlacement = params,
    )

    private fun backend(
        kind: DiffusionBackendKind,
        ordinal: Int,
    ) = DiffusionPreflightBackend(
        kind = kind,
        deviceType = if (kind == DiffusionBackendKind.CPU) {
            DiffusionBackendDeviceType.CPU
        } else {
            DiffusionBackendDeviceType.DISCRETE_GPU
        },
        ordinal = ordinal,
        budgetBytes = 0L,
        freeBytes = 8_000L,
        totalBytes = 16_000L,
    )
}
