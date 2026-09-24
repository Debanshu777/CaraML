package com.debanshu777.caraml.core.data.inference

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.recommendation.DiffusionMode
import com.debanshu777.caraml.core.recommendation.DiffusionRunPlan
import com.debanshu777.caraml.core.recommendation.NativeLoadPreflight
import com.debanshu777.caraml.core.recommendation.NativeRunPlanAdapter
import com.debanshu777.diffusionrunner.DiffusionArchitecture
import com.debanshu777.diffusionrunner.DiffusionBackendDeviceType
import com.debanshu777.diffusionrunner.DiffusionBackendKind
import com.debanshu777.diffusionrunner.DiffusionFitReport
import com.debanshu777.diffusionrunner.DiffusionMemoryConfidence
import com.debanshu777.diffusionrunner.DiffusionModelConfig
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
            bundledConfig(),
            fit(
                components = listOf(
                    component(
                        role = DiffusionComponentRole.DIFFUSION_MODEL,
                        sourceRole = DiffusionComponentRole.MODEL_BUNDLE,
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
            bundledConfig(),
            fit(
                components = listOf(
                    component(
                        role = DiffusionComponentRole.DIFFUSION_MODEL,
                        sourceRole = DiffusionComponentRole.MODEL_BUNDLE,
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
                    sourceRole = DiffusionComponentRole.MODEL_BUNDLE,
                    runtime = DiffusionRuntimePlacement.GPU,
                    backendMask = 2L,
                    params = DiffusionParameterPlacement.CPU,
                ),
                component(
                    role = DiffusionComponentRole.TEXT_ENCODER,
                    sourceRole = DiffusionComponentRole.MODEL_BUNDLE,
                    sourceOrdinal = 0,
                    runtime = DiffusionRuntimePlacement.CPU,
                    params = DiffusionParameterPlacement.CPU,
                    ordinal = 1,
                ),
                component(
                    role = DiffusionComponentRole.VAE,
                    sourceRole = DiffusionComponentRole.MODEL_BUNDLE,
                    sourceOrdinal = 0,
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
                    if (component.subdivisionRole == DiffusionComponentRole.VAE) {
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

        assertEquals(NativeLoadPreflight.Fit, exactDiffusionNativePreflight(plan, bundledConfig(), matching))
        assertEquals(
            NativeLoadPreflight.Invalid,
            exactDiffusionNativePreflight(plan, bundledConfig(), wrongVaePlacement),
        )
        assertEquals(
            NativeLoadPreflight.Invalid,
            exactDiffusionNativePreflight(plan, bundledConfig(), wrongBackend),
        )
    }

    @Test
    fun effectiveNativeStreamingMustMatchTheAssessedPlan() {
        val report = fit(
            components = listOf(
                component(
                    role = DiffusionComponentRole.DIFFUSION_MODEL,
                    sourceRole = DiffusionComponentRole.MODEL_BUNDLE,
                    runtime = DiffusionRuntimePlacement.CPU,
                    params = DiffusionParameterPlacement.CPU,
                ),
            ),
            backends = listOf(backend(DiffusionBackendKind.CPU, ordinal = 0)),
            segmentedCompute = true,
        )

        assertEquals(
            NativeLoadPreflight.Invalid,
            exactDiffusionNativePreflight(cpuPlan(), bundledConfig(), report),
        )
    }

    @Test
    fun nativeBudgetAndAutoFitMustMatchTheAdmittedPlan() {
        val budget = 1_073_741_824L
        val plan = plan(
            backend = BackendKind.VULKAN,
            keepClipOnCpu = false,
            keepVaeOnCpu = false,
            offloadToCpu = false,
            maxVramBytes = budget,
        )
        val config = bundledConfig().copy(
            runtimeBackend = com.debanshu777.diffusionrunner.DiffusionRuntimeBackend.VULKAN,
            maxVram = "1.0",
        )
        val matching = fit(
            components = listOf(
                component(
                    role = DiffusionComponentRole.DIFFUSION_MODEL,
                    sourceRole = DiffusionComponentRole.MODEL_BUNDLE,
                    runtime = DiffusionRuntimePlacement.GPU,
                    backendMask = 1L,
                    params = DiffusionParameterPlacement.DEFAULT,
                ),
            ),
            backends = listOf(backend(DiffusionBackendKind.VULKAN, 0, budget)),
        )

        assertEquals(NativeLoadPreflight.Fit, exactDiffusionNativePreflight(plan, config, matching))
        assertEquals(
            NativeLoadPreflight.Invalid,
            exactDiffusionNativePreflight(
                plan,
                config,
                matching.copy(report = matching.report.copy(
                    backends = listOf(backend(DiffusionBackendKind.VULKAN, 0, budget - 1)),
                )),
            ),
        )
        assertEquals(
            NativeLoadPreflight.Invalid,
            exactDiffusionNativePreflight(
                plan,
                config,
                matching.copy(report = matching.report.copy(autoFit = true)),
            ),
        )

        val unalignedPlan = plan(
            backend = BackendKind.VULKAN,
            keepClipOnCpu = false,
            keepVaeOnCpu = false,
            offloadToCpu = false,
            maxVramBytes = 3_435_973_837L,
        )
        val normalized = NativeRunPlanAdapter.toDiffusionExecutionConfig(
            unalignedPlan,
            bundledConfig(),
        )
        val normalizedReport = matching.copy(
            report = matching.report.copy(
                backends = listOf(
                    backend(
                        DiffusionBackendKind.VULKAN,
                        0,
                        requireNotNull(normalized.maxVramBytes),
                    ),
                ),
            ),
        )
        assertEquals(
            NativeLoadPreflight.Fit,
            exactDiffusionNativePreflight(unalignedPlan, normalized.model, normalizedReport),
        )
    }

    @Test
    fun splitConfigAcceptsExactlyConfiguredRolesAndPlacements() {
        val (plan, matching) = matchingSplitCudaPreflight()

        assertEquals(
            NativeLoadPreflight.Fit,
            exactDiffusionNativePreflight(plan, splitConfig(), matching),
        )
    }

    @Test
    fun splitConfigWithTaesdKeepsDistinctSourceAndVaeSubdivision() {
        val (plan, matching) = matchingSplitCudaPreflight()
        val withTaesd = matching.copy(
            report = matching.report.copy(
                components = matching.report.components + component(
                    role = DiffusionComponentRole.VAE,
                    sourceRole = DiffusionComponentRole.TAESD,
                    sourceOrdinal = 3,
                    runtime = DiffusionRuntimePlacement.GPU,
                    backendMask = 1L,
                    params = DiffusionParameterPlacement.DEFAULT,
                    ordinal = 3,
                ),
            ),
        )

        assertEquals(
            NativeLoadPreflight.Fit,
            exactDiffusionNativePreflight(plan, splitTaesdConfig(), withTaesd),
        )
    }

    @Test
    fun splitConfigRejectsAnOmittedConfiguredComponent() {
        val (plan, matching) = matchingSplitCudaPreflight()
        val omittedVae = matching.copy(
            report = matching.report.copy(
                components = matching.report.components.filterNot {
                    it.sourceRole == DiffusionComponentRole.VAE
                },
            ),
        )

        assertEquals(
            NativeLoadPreflight.Invalid,
            exactDiffusionNativePreflight(plan, splitConfig(), omittedVae),
        )
    }

    @Test
    fun splitConfigRejectsAnUnconfiguredExtraComponent() {
        val (plan, matching) = matchingSplitCudaPreflight()
        val withExtra = matching.copy(
            report = matching.report.copy(
                components = matching.report.components + component(
                    role = DiffusionComponentRole.T5XXL,
                    runtime = DiffusionRuntimePlacement.CPU,
                    params = DiffusionParameterPlacement.DEFAULT,
                    ordinal = 3,
                ),
            ),
        )

        assertEquals(
            NativeLoadPreflight.Invalid,
            exactDiffusionNativePreflight(plan, splitConfig(), withExtra),
        )
    }

    @Test
    fun splitConfigRejectsADuplicateConfiguredRole() {
        val (plan, matching) = matchingSplitCudaPreflight()
        val withDuplicate = matching.copy(
            report = matching.report.copy(
                components = matching.report.components + matching.report.components.last().copy(ordinal = 3),
            ),
        )

        assertEquals(
            NativeLoadPreflight.Invalid,
            exactDiffusionNativePreflight(plan, splitConfig(), withDuplicate),
        )
    }

    @Test
    fun splitConfigRejectsSwappedClipAndVaePlacements() {
        val (plan, matching) = matchingSplitCudaPreflight()
        val swapped = matching.copy(
            report = matching.report.copy(
                components = matching.report.components.map { component ->
                    when (component.sourceRole) {
                        DiffusionComponentRole.CLIP_L -> component.copy(sourceRole = DiffusionComponentRole.VAE)
                        DiffusionComponentRole.VAE -> component.copy(sourceRole = DiffusionComponentRole.CLIP_L)
                        else -> component
                    }
                },
            ),
        )

        assertEquals(
            NativeLoadPreflight.Invalid,
            exactDiffusionNativePreflight(plan, splitConfig(), swapped),
        )
    }

    @Test
    fun bundleRejectsDeclaredBundleAsAnInternalSubdivision() {
        val matching = matchingBundledCpuPreflight()
        val contradictory = matching.copy(
            report = matching.report.copy(
                components = matching.report.components + component(
                    role = DiffusionComponentRole.MODEL_BUNDLE,
                    sourceRole = DiffusionComponentRole.MODEL_BUNDLE,
                    sourceOrdinal = 0,
                    runtime = DiffusionRuntimePlacement.CPU,
                    params = DiffusionParameterPlacement.CPU,
                    ordinal = 2,
                ),
            ),
        )

        assertEquals(
            NativeLoadPreflight.Invalid,
            exactDiffusionNativePreflight(cpuPlan(), bundledConfig(), contradictory),
        )
    }

    @Test
    fun bundleRejectsDuplicateDiffusionPrimarySubdivision() {
        val matching = matchingBundledCpuPreflight()
        val duplicatePrimary = matching.copy(
            report = matching.report.copy(
                components = matching.report.components + matching.report.components.first().copy(ordinal = 2),
            ),
        )

        assertEquals(
            NativeLoadPreflight.Invalid,
            exactDiffusionNativePreflight(cpuPlan(), bundledConfig(), duplicatePrimary),
        )
    }

    @Test
    fun bundleRejectsContradictoryDiffusionModelSource() {
        val matching = matchingBundledCpuPreflight()
        val contradictoryPrimarySource = matching.copy(
            report = matching.report.copy(
                components = matching.report.components + component(
                    role = DiffusionComponentRole.DIFFUSION_MODEL,
                    sourceRole = DiffusionComponentRole.DIFFUSION_MODEL,
                    sourceOrdinal = 1,
                    runtime = DiffusionRuntimePlacement.CPU,
                    params = DiffusionParameterPlacement.CPU,
                    ordinal = 2,
                ),
            ),
        )

        assertEquals(
            NativeLoadPreflight.Invalid,
            exactDiffusionNativePreflight(cpuPlan(), bundledConfig(), contradictoryPrimarySource),
        )
    }

    @Test
    fun bundleRejectsArbitraryInternalSubdivision() {
        val matching = matchingBundledCpuPreflight()
        val extraSubdivision = matching.copy(
            report = matching.report.copy(
                components = matching.report.components + component(
                    role = DiffusionComponentRole.CLIP_L,
                    sourceRole = DiffusionComponentRole.MODEL_BUNDLE,
                    sourceOrdinal = 0,
                    runtime = DiffusionRuntimePlacement.CPU,
                    params = DiffusionParameterPlacement.CPU,
                    ordinal = 2,
                ),
            ),
        )

        assertEquals(
            NativeLoadPreflight.Invalid,
            exactDiffusionNativePreflight(cpuPlan(), bundledConfig(), extraSubdivision),
        )
    }

    @Test
    fun bundleRejectsAnUnconfiguredSource() {
        val matching = matchingBundledCpuPreflight()
        val extraSource = matching.copy(
            report = matching.report.copy(
                components = matching.report.components + component(
                    role = DiffusionComponentRole.VAE,
                    sourceRole = DiffusionComponentRole.VAE,
                    sourceOrdinal = 1,
                    runtime = DiffusionRuntimePlacement.CPU,
                    params = DiffusionParameterPlacement.CPU,
                    ordinal = 2,
                ),
            ),
        )

        assertEquals(
            NativeLoadPreflight.Invalid,
            exactDiffusionNativePreflight(cpuPlan(), bundledConfig(), extraSource),
        )
    }

    @Test
    fun bundleRejectsMissingConfiguredPrimarySource() {
        val missingBundle = fit(
            components = listOf(
                component(
                    role = DiffusionComponentRole.VAE,
                    sourceRole = DiffusionComponentRole.VAE,
                    runtime = DiffusionRuntimePlacement.CPU,
                    params = DiffusionParameterPlacement.CPU,
                ),
            ),
            backends = listOf(backend(DiffusionBackendKind.CPU, ordinal = 0)),
        )

        assertEquals(
            NativeLoadPreflight.Invalid,
            exactDiffusionNativePreflight(cpuPlan(), bundledConfig(), missingBundle),
        )
    }

    @Test
    fun bundleWithExternalTaesdAcceptsDistinctSourceEvidence() {
        assertEquals(
            NativeLoadPreflight.Fit,
            exactDiffusionNativePreflight(
                cpuPlan(),
                bundledTaesdConfig(),
                matchingBundledCpuPreflight(includeExternalTaesd = true),
            ),
        )
    }

    @Test
    fun bundleVaeSubdivisionCannotSatisfyMissingExternalTaesd() {
        assertEquals(
            NativeLoadPreflight.Invalid,
            exactDiffusionNativePreflight(
                cpuPlan(),
                bundledTaesdConfig(),
                matchingBundledCpuPreflight(),
            ),
        )
    }

    @Test
    fun bundleRejectsDuplicateExternalTaesdSourceEvidence() {
        val matching = matchingBundledCpuPreflight(includeExternalTaesd = true)
        val duplicateTaesd = matching.copy(
            report = matching.report.copy(
                components = matching.report.components + matching.report.components.last().copy(ordinal = 3),
            ),
        )

        assertEquals(
            NativeLoadPreflight.Invalid,
            exactDiffusionNativePreflight(cpuPlan(), bundledTaesdConfig(), duplicateTaesd),
        )
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
        offloadToCpu: Boolean = true,
        maxVramBytes: Long? = null,
    ) = DiffusionRunPlan(
        mode = DiffusionMode.IMAGE,
        width = 512,
        height = 512,
        frameCount = 1,
        batchSize = 1,
        steps = 20,
        vaeTiling = false,
        offloadToCpu = offloadToCpu,
        keepClipOnCpu = keepClipOnCpu,
        keepVaeOnCpu = keepVaeOnCpu,
        maxVramBytes = maxVramBytes,
        segmentedCompute = false,
        requiresUserAcceptance = false,
        backend = backend,
        memoryTopology = if (backend == BackendKind.CPU) MemoryTopology.UNKNOWN else MemoryTopology.DISCRETE,
        compromises = emptyList(),
    )

    private fun bundledConfig() = DiffusionModelConfig(modelPath = "/models/bundle.gguf")

    private fun bundledTaesdConfig() = DiffusionModelConfig(
        modelPath = "/models/bundle.gguf",
        taesdPath = "/models/taesd.safetensors",
    )

    private fun splitConfig() = DiffusionModelConfig(
        modelPath = "/models/diffusion.gguf",
        vaePath = "/models/vae.gguf",
        clipLPath = "/models/clip-l.gguf",
    )

    private fun splitTaesdConfig() = splitConfig().copy(
        taesdPath = "/models/taesd.safetensors",
    )

    private fun matchingSplitCudaPreflight(): Pair<DiffusionRunPlan, DiffusionPreflightResult.Fit> {
        val plan = plan(
            backend = BackendKind.CUDA,
            keepClipOnCpu = false,
            keepVaeOnCpu = false,
            offloadToCpu = false,
        )
        return plan to fit(
            components = listOf(
                component(
                    role = DiffusionComponentRole.DIFFUSION_MODEL,
                    runtime = DiffusionRuntimePlacement.GPU,
                    backendMask = 1L,
                    params = DiffusionParameterPlacement.DEFAULT,
                ),
                component(
                    role = DiffusionComponentRole.VAE,
                    runtime = DiffusionRuntimePlacement.GPU,
                    backendMask = 1L,
                    params = DiffusionParameterPlacement.DEFAULT,
                    ordinal = 1,
                ),
                component(
                    role = DiffusionComponentRole.CLIP_L,
                    runtime = DiffusionRuntimePlacement.GPU,
                    backendMask = 1L,
                    params = DiffusionParameterPlacement.DEFAULT,
                    ordinal = 2,
                ),
            ),
            backends = listOf(
                backend(DiffusionBackendKind.CUDA, ordinal = 0),
            ),
        )
    }

    private fun matchingBundledCpuPreflight(
        includeExternalTaesd: Boolean = false,
    ): DiffusionPreflightResult.Fit = fit(
        components = buildList {
            add(
                component(
                    role = DiffusionComponentRole.DIFFUSION_MODEL,
                    sourceRole = DiffusionComponentRole.MODEL_BUNDLE,
                    sourceOrdinal = 0,
                    runtime = DiffusionRuntimePlacement.CPU,
                    params = DiffusionParameterPlacement.CPU,
                ),
            )
            add(
                component(
                    role = DiffusionComponentRole.VAE,
                    sourceRole = DiffusionComponentRole.MODEL_BUNDLE,
                    sourceOrdinal = 0,
                    runtime = DiffusionRuntimePlacement.CPU,
                    params = DiffusionParameterPlacement.CPU,
                    ordinal = 1,
                ),
            )
            if (includeExternalTaesd) {
                add(
                    component(
                        role = DiffusionComponentRole.VAE,
                        sourceRole = DiffusionComponentRole.TAESD,
                        sourceOrdinal = 1,
                        runtime = DiffusionRuntimePlacement.CPU,
                        params = DiffusionParameterPlacement.CPU,
                        ordinal = 2,
                    ),
                )
            }
        },
        backends = listOf(backend(DiffusionBackendKind.CPU, ordinal = 0)),
    )

    private fun fit(
        components: List<DiffusionPreflightComponent>,
        backends: List<DiffusionPreflightBackend>,
        segmentedCompute: Boolean = false,
        prefetch: Boolean = false,
        autoFit: Boolean = false,
    ) = DiffusionPreflightResult.Fit(
        DiffusionFitReport(
            architecture = DiffusionArchitecture.SDXL,
            quantization = DiffusionQuantization.Q4_K,
            memoryConfidence = DiffusionMemoryConfidence.MEDIUM,
            segmentedCompute = segmentedCompute,
            prefetch = prefetch,
            autoFit = autoFit,
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
        sourceRole: DiffusionComponentRole = role,
        sourceOrdinal: Int = ordinal,
    ) = DiffusionPreflightComponent(
        sourceRole = sourceRole,
        sourceOrdinal = sourceOrdinal,
        subdivisionRole = role,
        ordinal = ordinal,
        parameterBytes = 1_024L,
        runtimePlacement = runtime,
        runtimeBackendMask = backendMask,
        parameterPlacement = params,
    )

    private fun backend(
        kind: DiffusionBackendKind,
        ordinal: Int,
        budgetBytes: Long = 0L,
    ) = DiffusionPreflightBackend(
        kind = kind,
        deviceType = if (kind == DiffusionBackendKind.CPU) {
            DiffusionBackendDeviceType.CPU
        } else {
            DiffusionBackendDeviceType.DISCRETE_GPU
        },
        ordinal = ordinal,
        budgetBytes = budgetBytes,
        freeBytes = 8_000L,
        totalBytes = 16_000L,
    )
}
