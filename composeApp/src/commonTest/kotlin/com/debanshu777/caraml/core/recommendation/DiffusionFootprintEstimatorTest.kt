package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.huggingfacemanager.sdcpp.ComponentRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiffusionFootprintEstimatorTest {
    private val estimator = DiffusionFootprintEstimator()

    @Test
    fun exactComponentWeightsAreSummedByValidatedRoleAndStorageStaysExact() {
        val descriptor = descriptor()
        val estimate = estimator.estimate(descriptor, plan(backend = BackendKind.CPU), MemoryCalibration.None)
        val expectedWeights = 2L * GIB + 256L * MIB + 384L * MIB

        val storage = assertNotNull(estimate.storageBytes)
        assertEquals(expectedWeights, storage.lowBytes)
        assertEquals(expectedWeights, storage.likelyBytes)
        assertEquals(expectedWeights, storage.highBytes)
        assertTrue(assertNotNull(estimate.hostMemoryBytes).lowBytes >= expectedWeights)
        assertTrue(estimate.evidence.any { it.detail == "component-weights:primary:2147483648" })
        assertTrue(estimate.evidence.any { it.detail == "component-weights:vae:268435456" })
        assertTrue(estimate.evidence.any { it.detail == "component-weights:conditioning:402653184" })
    }

    @Test
    fun unknownArchitectureNeedsInformationInsteadOfUsingAFavorableFallback() {
        val estimate = estimator.estimate(
            descriptor(family = "unrecognized-experimental-family"),
            plan(backend = BackendKind.CPU),
            MemoryCalibration.None,
        )

        assertNull(estimate.hostMemoryBytes)
        assertNull(estimate.gpuMemoryBytes)
        assertNull(estimate.sharedMemoryBytes)
        assertEquals(Confidence.LOW, estimate.confidence.memory)
        assertTrue(estimate.evidence.any { it.reason == AssessmentReason.UNKNOWN_ARCHITECTURE })
        assertNotNull(estimate.storageBytes)
    }

    @Test
    fun unknownNonPrimaryComponentRoleNeedsInformation() {
        val estimate = estimator.estimate(
            descriptor(
                components = listOf(
                    component("diffusion.safetensors", 2L * GIB, null, isPrimary = true),
                    component("mystery.safetensors", 128L * MIB, null, isPrimary = false),
                    component("vae.safetensors", 256L * MIB, ComponentRole.VAE),
                ),
            ),
            plan(backend = BackendKind.CPU),
            MemoryCalibration.None,
        )

        assertNull(estimate.hostMemoryBytes)
        assertTrue(estimate.evidence.any { it.reason == AssessmentReason.COMPONENT_ROLE_UNKNOWN })
        assertEquals(2L * GIB + 384L * MIB, assertNotNull(estimate.storageBytes).highBytes)
    }

    @Test
    fun pixelFrameAndBatchGrowthNeverReduceAnyMemoryBound() {
        val descriptor = descriptor(mode = DiffusionMode.VIDEO, family = "WAN_SMALL")
        val baseline = shared(estimator.estimate(descriptor, plan(
            mode = DiffusionMode.VIDEO,
            width = 512,
            height = 512,
            frames = 8,
            batch = 1,
            backend = BackendKind.METAL,
            topology = MemoryTopology.UNIFIED,
        ), MemoryCalibration.None))
        val morePixels = shared(estimator.estimate(descriptor, plan(
            mode = DiffusionMode.VIDEO,
            width = 1_024,
            height = 512,
            frames = 8,
            batch = 1,
            backend = BackendKind.METAL,
            topology = MemoryTopology.UNIFIED,
        ), MemoryCalibration.None))
        val moreFrames = shared(estimator.estimate(descriptor, plan(
            mode = DiffusionMode.VIDEO,
            width = 1_024,
            height = 512,
            frames = 32,
            batch = 1,
            backend = BackendKind.METAL,
            topology = MemoryTopology.UNIFIED,
        ), MemoryCalibration.None))
        val moreBatches = shared(estimator.estimate(descriptor, plan(
            mode = DiffusionMode.VIDEO,
            width = 1_024,
            height = 512,
            frames = 32,
            batch = 2,
            backend = BackendKind.METAL,
            topology = MemoryTopology.UNIFIED,
        ), MemoryCalibration.None))

        assertRangeNotLower(morePixels, baseline)
        assertRangeNotLower(moreFrames, morePixels)
        assertRangeNotLower(moreBatches, moreFrames)
        assertTrue(moreFrames.highBytes > morePixels.highBytes)
    }

    @Test
    fun cpuUnifiedAndDiscretePlansKeepCapacityPoolsIndependent() {
        val descriptor = descriptor()
        val cpu = estimator.estimate(descriptor, plan(backend = BackendKind.CPU), MemoryCalibration.None)
        val unified = estimator.estimate(
            descriptor,
            plan(backend = BackendKind.METAL, topology = MemoryTopology.UNIFIED),
            MemoryCalibration.None,
        )
        val discrete = estimator.estimate(
            descriptor,
            plan(backend = BackendKind.CUDA, topology = MemoryTopology.DISCRETE),
            MemoryCalibration.None,
        )

        assertNotNull(cpu.hostMemoryBytes)
        assertNull(cpu.gpuMemoryBytes)
        assertNull(cpu.sharedMemoryBytes)
        assertNull(unified.hostMemoryBytes)
        assertNull(unified.gpuMemoryBytes)
        assertNotNull(unified.sharedMemoryBytes)
        assertNotNull(discrete.hostMemoryBytes)
        assertNotNull(discrete.gpuMemoryBytes)
        assertNull(discrete.sharedMemoryBytes)
    }

    @Test
    fun discreteCpuResidencyMovesOnlyTheRequestedComponentRoles() {
        val descriptor = descriptor()
        val gpuResident = estimator.estimate(
            descriptor,
            plan(backend = BackendKind.CUDA, topology = MemoryTopology.DISCRETE),
            MemoryCalibration.None,
        )
        val cpuResident = estimator.estimate(
            descriptor,
            plan(
                backend = BackendKind.CUDA,
                topology = MemoryTopology.DISCRETE,
                offloadToCpu = true,
                keepClipOnCpu = true,
                keepVaeOnCpu = true,
            ),
            MemoryCalibration.None,
        )

        assertTrue(assertNotNull(cpuResident.hostMemoryBytes).lowBytes > assertNotNull(gpuResident.hostMemoryBytes).lowBytes)
        assertTrue(assertNotNull(cpuResident.gpuMemoryBytes).lowBytes < assertNotNull(gpuResident.gpuMemoryBytes).lowBytes)
        assertNull(cpuResident.sharedMemoryBytes)
    }

    @Test
    fun parameterOffloadKeepsEveryComponentWeightInHostMemory() {
        val estimate = estimator.estimate(
            descriptor(),
            plan(
                backend = BackendKind.CUDA,
                topology = MemoryTopology.DISCRETE,
                offloadToCpu = true,
                keepClipOnCpu = false,
                keepVaeOnCpu = false,
            ),
            MemoryCalibration.None,
        )

        assertTrue(assertNotNull(estimate.hostMemoryBytes).lowBytes >= 2L * GIB + 640L * MIB)
        assertTrue(assertNotNull(estimate.gpuMemoryBytes).lowBytes < 2L * GIB)
    }

    @Test
    fun vaeTilingReducesOnlyVaeActivationAndNeverComponentStorage() {
        val descriptor = descriptor()
        val normal = estimator.estimate(descriptor, plan(backend = BackendKind.CPU), MemoryCalibration.None)
        val tiled = estimator.estimate(
            descriptor,
            plan(backend = BackendKind.CPU, vaeTiling = true),
            MemoryCalibration.None,
        )

        assertTrue(host(tiled).highBytes < host(normal).highBytes)
        assertEquals(normal.storageBytes, tiled.storageBytes)
        assertTrue(host(tiled).lowBytes >= assertNotNull(tiled.storageBytes).lowBytes)
    }

    @Test
    fun streamingReducesOnlyVersionedLayerResidentShare() {
        val descriptor = descriptor()
        val normal = estimator.estimate(
            descriptor,
            plan(backend = BackendKind.CUDA, topology = MemoryTopology.DISCRETE),
            MemoryCalibration.None,
        )
        val streaming = estimator.estimate(
            descriptor,
            plan(
                backend = BackendKind.CUDA,
                topology = MemoryTopology.DISCRETE,
                offloadToCpu = true,
                layerStreaming = true,
            ),
            MemoryCalibration.None,
        )

        val normalGpu = assertNotNull(normal.gpuMemoryBytes)
        val streamingGpu = assertNotNull(streaming.gpuMemoryBytes)
        assertTrue(streamingGpu.highBytes < normalGpu.highBytes)
        assertTrue(streamingGpu.lowBytes >= 256L * MIB + 384L * MIB)
        assertTrue(assertNotNull(streaming.hostMemoryBytes).highBytes >= 2L * GIB)
        assertTrue(streaming.evidence.any { it.detail == "layer-residency:diffusion-v1" })
    }

    @Test
    fun maxVramCapsOnlyGpuPoolAndWidensHostForTheUncertainSpill() {
        val estimate = estimator.estimate(
            descriptor(),
            plan(
                backend = BackendKind.CUDA,
                topology = MemoryTopology.DISCRETE,
                maxVramBytes = 4L * GIB,
            ),
            MemoryCalibration.None,
        )

        assertTrue(assertNotNull(estimate.gpuMemoryBytes).highBytes <= 4L * GIB)
        assertTrue(assertNotNull(estimate.hostMemoryBytes).highBytes >= 2L * GIB)
        assertNull(estimate.sharedMemoryBytes)
        assertEquals(Confidence.LOW, estimate.confidence.memory)
        assertTrue(estimate.evidence.any { it.reason == AssessmentReason.GPU_ALLOCATION_UNKNOWN })
    }

    @Test
    fun maxVramBelowNonStreamableGpuFloorNeedsInformation() {
        val estimate = estimator.estimate(
            descriptor(),
            plan(
                backend = BackendKind.CUDA,
                topology = MemoryTopology.DISCRETE,
                maxVramBytes = 64L * MIB,
            ),
            MemoryCalibration.None,
        )

        assertNull(estimate.hostMemoryBytes)
        assertNull(estimate.gpuMemoryBytes)
        assertTrue(estimate.evidence.any { it.reason == AssessmentReason.GPU_ALLOCATION_UNKNOWN })
    }

    @Test
    fun mismatchedModeAndInvalidInputsReturnStructuredConservativeAssessment() {
        val mismatch = estimator.estimate(
            descriptor(mode = DiffusionMode.IMAGE),
            plan(mode = DiffusionMode.VIDEO, frames = 8, backend = BackendKind.CPU),
            MemoryCalibration.None,
        )
        val invalidDimension = estimator.estimate(
            descriptor(),
            plan(width = 0, backend = BackendKind.CPU),
            MemoryCalibration.None,
        )

        assertNull(mismatch.hostMemoryBytes)
        assertTrue(mismatch.evidence.any { it.reason == AssessmentReason.INVALID_WORKLOAD })
        assertNull(invalidDimension.hostMemoryBytes)
        assertTrue(invalidDimension.evidence.any { it.reason == AssessmentReason.INVALID_WORKLOAD })
    }

    @Test
    fun validMaximumInputsThatOverflowArithmeticNeverWrapOrThrow() {
        val estimate = estimator.estimate(
            descriptor(mode = DiffusionMode.VIDEO, family = "WAN_LARGE"),
            plan(
                mode = DiffusionMode.VIDEO,
                width = DescriptorLimits.MAX_IMAGE_DIMENSION,
                height = DescriptorLimits.MAX_IMAGE_DIMENSION,
                frames = WorkloadLimits.MAX_DIFFUSION_FRAMES,
                batch = WorkloadLimits.MAX_BATCH_SIZE,
                backend = BackendKind.CPU,
            ),
            MemoryCalibration.None,
        )

        assertNull(estimate.hostMemoryBytes)
        assertEquals(Confidence.LOW, estimate.confidence.memory)
        assertTrue(estimate.evidence.any { it.reason == AssessmentReason.ARITHMETIC_OVERFLOW })
    }

    @Test
    fun estimatorRecordsTheVersionedArchitectureCoefficientSource() {
        val estimate = estimator.estimate(descriptor(family = "FLUX"), plan(backend = BackendKind.CPU), MemoryCalibration.None)

        assertTrue(estimate.evidence.any { it.detail == "diffusion-coefficients:v1:FLUX" })
        assertEquals(Confidence.MEDIUM, estimate.confidence.memory)
    }

    private fun plan(
        mode: DiffusionMode = DiffusionMode.IMAGE,
        width: Int = 1_024,
        height: Int = 768,
        frames: Int = if (mode == DiffusionMode.IMAGE) 1 else 8,
        batch: Int = 1,
        vaeTiling: Boolean = false,
        offloadToCpu: Boolean = false,
        keepClipOnCpu: Boolean = false,
        keepVaeOnCpu: Boolean = false,
        maxVramBytes: Long? = null,
        layerStreaming: Boolean = false,
        backend: BackendKind = BackendKind.CUDA,
        topology: MemoryTopology = if (backend == BackendKind.CPU) MemoryTopology.UNKNOWN else MemoryTopology.DISCRETE,
    ) = DiffusionRunPlan(
        mode = mode,
        width = width,
        height = height,
        frameCount = frames,
        batchSize = batch,
        steps = 20,
        vaeTiling = vaeTiling,
        offloadToCpu = offloadToCpu,
        keepClipOnCpu = keepClipOnCpu,
        keepVaeOnCpu = keepVaeOnCpu,
        maxVramBytes = maxVramBytes,
        layerStreaming = layerStreaming,
        requiresUserAcceptance = false,
        backend = backend,
        memoryTopology = topology,
        compromises = emptyList(),
    )

    private fun descriptor(
        mode: DiffusionMode = DiffusionMode.IMAGE,
        family: String = "FLUX",
        components: List<DiffusionComponentDescriptor> = listOf(
            component("diffusion.safetensors", 2L * GIB, null, isPrimary = true),
            component("vae.safetensors", 256L * MIB, ComponentRole.VAE),
            component("clip.safetensors", 384L * MIB, ComponentRole.CLIP_L),
        ),
    ) = diffusionDescriptor(mode, family, components)

    private fun host(assessment: PlanAssessment): EstimateRange = assertNotNull(assessment.hostMemoryBytes)

    private fun shared(assessment: PlanAssessment): EstimateRange = assertNotNull(assessment.sharedMemoryBytes)

    private fun assertRangeNotLower(actual: EstimateRange, previous: EstimateRange) {
        assertTrue(actual.lowBytes >= previous.lowBytes)
        assertTrue(actual.likelyBytes >= previous.likelyBytes)
        assertTrue(actual.highBytes >= previous.highBytes)
    }

    private companion object {
        const val MIB = 1_048_576L
        const val GIB = 1_073_741_824L
    }
}

internal fun diffusionDescriptor(
    mode: DiffusionMode,
    family: String,
    components: List<DiffusionComponentDescriptor>,
    width: Int? = 1_024,
    height: Int? = 768,
): DiffusionModelDescriptor = DiffusionModelDescriptor(
    repositoryId = "owner/model",
    revision = "0123456789abcdef0123456789abcdef01234567",
    components = components,
    mode = mode,
    family = family,
    width = width,
    height = height,
    quantizationDistribution = setOf("F16"),
    requiredComponentsPresent = true,
    requiredEngineFeatures = emptySet(),
    evidence = emptyList(),
)

internal fun component(
    path: String,
    sizeBytes: Long,
    role: ComponentRole?,
    isPrimary: Boolean = false,
): DiffusionComponentDescriptor = DiffusionComponentDescriptor(
    file = ModelFileIdentity(
        repositoryId = "owner/model",
        revision = "0123456789abcdef0123456789abcdef01234567",
        path = path,
        sizeBytes = sizeBytes,
        gitOid = null,
        lfsOid = "sha256:$path",
        xetHash = null,
        evidence = emptyList(),
    ),
    role = role,
    required = true,
    isPrimary = isPrimary,
    quantization = QuantizationEvidence.Known("F16"),
)
