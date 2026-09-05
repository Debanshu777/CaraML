package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.huggingfacemanager.sdcpp.ComponentRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiffusionRunPlanGeneratorTest {
    private val generator = RunPlanGenerator()

    @Test
    fun candidatesAreFiniteDistinctStableAndStartWithTheExactRequestedPlan() {
        val descriptor = descriptor()
        val workload = workload()
        val settings = settings(
            supportsVaeTiling = true,
            supportsMaxVram = true,
            supportsLayerStreaming = true,
            maxVramBytes = 3L * GIB,
        )

        val first = generator.diffusionCandidates(descriptor, workload, settings)
        val second = generator.diffusionCandidates(descriptor, workload, settings)

        assertTrue(first.size <= 12)
        assertEquals(first.distinct(), first)
        assertEquals(first, second)
        assertEquals(DiffusionMode.IMAGE, first.first().mode)
        assertEquals(1_024, first.first().width)
        assertEquals(768, first.first().height)
        assertEquals(1, first.first().frameCount)
        assertFalse(first.first().vaeTiling)
        assertEquals(null, first.first().maxVramBytes)
        assertFalse(first.first().layerStreaming)
        assertFalse(first.first().requiresUserAcceptance)
        assertTrue(first.first().compromises.isEmpty())
    }

    @Test
    fun lowerResolutionProposalsPreserveAspectMultipleAndMinimumAndRequireAcceptance() {
        val plans = generator.diffusionCandidates(
            descriptor(),
            workload(width = 1_024, height = 768, minimumWidth = 512, minimumHeight = 384),
            settings(),
        )
        val lower = plans.filter { it.width < 1_024 || it.height < 768 }

        assertTrue(lower.isNotEmpty())
        assertTrue(lower.all { it.width * 3 == it.height * 4 })
        assertTrue(lower.all { it.width % 64 == 0 && it.height % 64 == 0 })
        assertTrue(lower.all { it.width >= 512 && it.height >= 384 })
        assertTrue(lower.all { DiffusionPlanCompromise.LOWER_RESOLUTION in it.compromises })
        assertTrue(lower.all { it.requiresUserAcceptance })
        assertTrue(plans.takeWhile { DiffusionPlanCompromise.LOWER_RESOLUTION !in it.compromises }.isNotEmpty())
    }

    @Test
    fun resolutionNeverChangesWhenFallbackIsNotAllowedOrMinimumEqualsRequest() {
        val disabled = generator.diffusionCandidates(
            descriptor(),
            workload(allowResolutionFallback = false),
            settings(),
        )
        val atMinimum = generator.diffusionCandidates(
            descriptor(),
            workload(minimumWidth = 1_024, minimumHeight = 768),
            settings(),
        )

        assertTrue(disabled.all { it.width == 1_024 && it.height == 768 })
        assertTrue(atMinimum.all { it.width == 1_024 && it.height == 768 })
    }

    @Test
    fun videoFrameCountIsNeverReducedWithoutExplicitPermission() {
        val plans = generator.diffusionCandidates(
            descriptor(mode = DiffusionMode.VIDEO, family = "WAN_SMALL"),
            workload(
                mode = DiffusionMode.VIDEO,
                frameCount = 32,
                minimumFrameCount = 8,
                allowFrameCountFallback = false,
            ),
            settings(),
        )

        assertTrue(plans.isNotEmpty())
        assertTrue(plans.all { it.frameCount == 32 })
        assertTrue(plans.none { DiffusionPlanCompromise.FRAME_COUNT_REDUCED in it.compromises })
    }

    @Test
    fun videoFrameFallbackRequiresPermissionAndIsDisclosed() {
        val plans = generator.diffusionCandidates(
            descriptor(mode = DiffusionMode.VIDEO, family = "WAN_SMALL"),
            workload(
                mode = DiffusionMode.VIDEO,
                frameCount = 32,
                minimumFrameCount = 8,
                allowFrameCountFallback = true,
            ),
            settings(),
        )
        val reduced = plans.filter { it.frameCount < 32 }

        assertTrue(reduced.isNotEmpty())
        assertTrue(reduced.all { it.frameCount >= 8 })
        assertTrue(reduced.all { DiffusionPlanCompromise.FRAME_COUNT_REDUCED in it.compromises })
        assertTrue(reduced.all { it.requiresUserAcceptance })
    }

    @Test
    fun unsupportedTilingMaxVramAndStreamingOptionsAreNeverGenerated() {
        val plans = generator.diffusionCandidates(
            descriptor(),
            workload(),
            settings(
                supportsVaeTiling = false,
                supportsMaxVram = false,
                supportsLayerStreaming = false,
                maxVramBytes = 2L * GIB,
            ),
        )

        assertTrue(plans.isNotEmpty())
        assertTrue(plans.none { it.vaeTiling })
        assertTrue(plans.none { it.maxVramBytes != null })
        assertTrue(plans.none { it.layerStreaming })
    }

    @Test
    fun supportedFallbackOptionsAreGeneratedAndIndividuallyDisclosed() {
        val plans = generator.diffusionCandidates(
            descriptor(),
            workload(),
            settings(
                supportsVaeTiling = true,
                supportsMaxVram = true,
                supportsLayerStreaming = true,
                maxVramBytes = 2L * GIB,
            ),
        )

        assertTrue(plans.any { it.vaeTiling && DiffusionPlanCompromise.VAE_TILING in it.compromises })
        assertTrue(plans.any { it.maxVramBytes == 2L * GIB && DiffusionPlanCompromise.MAX_VRAM_LIMIT in it.compromises })
        assertTrue(
            plans.any {
                it.layerStreaming && it.offloadToCpu &&
                    DiffusionPlanCompromise.LAYER_STREAMING in it.compromises &&
                    DiffusionPlanCompromise.CPU_OFFLOAD in it.compromises
            },
        )
    }

    @Test
    fun explicitlyRequestedUnsupportedOptionFailsClosed() {
        val unsupportedTiling = generator.diffusionCandidates(
            descriptor(),
            workload(vaeTiling = true),
            settings(supportsVaeTiling = false),
        )
        val unsupportedStreaming = generator.diffusionCandidates(
            descriptor(),
            workload(layerStreaming = true),
            settings(supportsLayerStreaming = false),
        )

        assertTrue(unsupportedTiling.isEmpty())
        assertTrue(unsupportedStreaming.isEmpty())
    }

    @Test
    fun malformedWorkloadAndSettingsProduceNoCandidates() {
        val invalidDimension = generator.diffusionCandidates(descriptor(), workload(width = 0), settings())
        val invalidMultiple = generator.diffusionCandidates(descriptor(), workload(width = 1_000), settings())
        val invalidLimit = generator.diffusionCandidates(
            descriptor(),
            workload(),
            settings(supportsMaxVram = true, maxVramBytes = -1L),
        )
        val invalidEngineBatch = generator.diffusionCandidates(
            descriptor(),
            workload(batchSize = 2),
            settings(engineMaxBatchSize = 1),
        )

        assertTrue(invalidDimension.isEmpty())
        assertTrue(invalidMultiple.isEmpty())
        assertTrue(invalidLimit.isEmpty())
        assertTrue(invalidEngineBatch.isEmpty())
    }

    @Test
    fun invalidDescriptorCannotProduceExecutableCandidates() {
        val invalidDescriptor = descriptor(
            components = listOf(component("diffusion.safetensors", 0L, role = null, isPrimary = true)),
        )
        val invalidNativeDimension = descriptor(nativeWidth = 0)
        val conflictingPrimaryRole = descriptor(
            components = listOf(
                component("diffusion.safetensors", 2L * GIB, role = null, isPrimary = true),
                component("vae.safetensors", 256L * MIB, role = ComponentRole.VAE, isPrimary = true),
            ),
        )
        val duplicatePrimary = descriptor(
            components = listOf(
                component("primary-a.safetensors", 2L * GIB, role = null, isPrimary = true),
                component("primary-b.safetensors", 1L * GIB, role = null, isPrimary = true),
            ),
        )

        assertTrue(generator.diffusionCandidates(invalidDescriptor, workload(), settings()).isEmpty())
        assertTrue(generator.diffusionCandidates(invalidNativeDimension, workload(), settings()).isEmpty())
        assertTrue(generator.diffusionCandidates(conflictingPrimaryRole, workload(), settings()).isEmpty())
        assertTrue(generator.diffusionCandidates(duplicatePrimary, workload(), settings()).isEmpty())
    }

    @Test
    fun cpuNormalizesOffloadToTheExecutableFlagAndNeverGeneratesGpuOnlyFallbacks() {
        val requestedFalse = generator.diffusionCandidates(
            descriptor(),
            workload(),
            settings(
                backend = BackendKind.CPU,
                topology = MemoryTopology.DISCRETE,
                supportsMaxVram = true,
                supportsLayerStreaming = true,
                maxVramBytes = 2L * GIB,
            ),
        )
        val requestedTrue = generator.diffusionCandidates(
            descriptor(),
            workload(offloadToCpu = true),
            settings(
                backend = BackendKind.CPU,
                topology = MemoryTopology.DISCRETE,
                supportsMaxVram = true,
                supportsLayerStreaming = true,
                maxVramBytes = 2L * GIB,
            ),
        )

        assertTrue(requestedFalse.isNotEmpty())
        assertTrue(requestedTrue.isNotEmpty())
        assertTrue(requestedFalse.all { it.offloadToCpu })
        assertTrue(requestedTrue.all { it.offloadToCpu })
        assertEquals(requestedTrue.first().stableKey, requestedFalse.first().stableKey)
        assertTrue(requestedFalse.none { it.maxVramBytes != null || it.layerStreaming })
        assertTrue(requestedTrue.none { it.maxVramBytes != null || it.layerStreaming })
    }

    @Test
    fun unifiedMemoryNeverGeneratesADiscreteVramLimit() {
        val plans = generator.diffusionCandidates(
            descriptor(),
            workload(),
            settings(
                backend = BackendKind.METAL,
                topology = MemoryTopology.UNIFIED,
                supportsMaxVram = true,
                maxVramBytes = 2L * GIB,
            ),
        )

        assertTrue(plans.isNotEmpty())
        assertTrue(plans.none { it.maxVramBytes != null })
    }

    @Test
    fun planSnapshotsCallerOwnedCompromisesAndHasAStableCompleteIdentity() {
        val compromises = mutableListOf(DiffusionPlanCompromise.LOWER_RESOLUTION)
        val plan = DiffusionRunPlan(
            mode = DiffusionMode.IMAGE,
            width = 768,
            height = 576,
            frameCount = 1,
            batchSize = 1,
            steps = 20,
            vaeTiling = true,
            offloadToCpu = false,
            keepClipOnCpu = true,
            keepVaeOnCpu = true,
            maxVramBytes = 2L * GIB,
            layerStreaming = true,
            requiresUserAcceptance = true,
            backend = BackendKind.CUDA,
            memoryTopology = MemoryTopology.DISCRETE,
            compromises = compromises,
        )
        compromises.clear()

        assertEquals(listOf(DiffusionPlanCompromise.LOWER_RESOLUTION), plan.compromises)
        assertTrue(plan.stableKey.contains("768:576:1:1:20"))
        assertTrue(plan.stableKey.contains("CUDA:DISCRETE"))
    }

    private fun workload(
        mode: DiffusionMode = DiffusionMode.IMAGE,
        width: Int = 1_024,
        height: Int = 768,
        minimumWidth: Int = 512,
        minimumHeight: Int = 384,
        frameCount: Int = if (mode == DiffusionMode.IMAGE) 1 else 32,
        minimumFrameCount: Int = if (mode == DiffusionMode.IMAGE) 1 else 8,
        vaeTiling: Boolean = false,
        offloadToCpu: Boolean = false,
        layerStreaming: Boolean = false,
        allowResolutionFallback: Boolean = true,
        allowFrameCountFallback: Boolean = false,
        batchSize: Int = 1,
    ) = DiffusionWorkloadConfig(
        mode = mode,
        width = width,
        height = height,
        minimumWidth = minimumWidth,
        minimumHeight = minimumHeight,
        frameCount = frameCount,
        minimumFrameCount = minimumFrameCount,
        batchSize = batchSize,
        steps = 20,
        vaeTiling = vaeTiling,
        offloadToCpu = offloadToCpu,
        keepClipOnCpu = false,
        keepVaeOnCpu = false,
        maxVramBytes = null,
        layerStreaming = layerStreaming,
        allowResolutionFallback = allowResolutionFallback,
        allowFrameCountFallback = allowFrameCountFallback,
        allowVaeTilingFallback = true,
        allowMaxVramFallback = true,
        allowLayerStreamingFallback = true,
        evidence = emptyList(),
    )

    private fun settings(
        supportsVaeTiling: Boolean = false,
        supportsMaxVram: Boolean = false,
        supportsLayerStreaming: Boolean = false,
        maxVramBytes: Long? = null,
        backend: BackendKind = BackendKind.CUDA,
        topology: MemoryTopology = MemoryTopology.DISCRETE,
        engineMaxBatchSize: Int = 1_024,
    ) = PlanningSettings(
        engineMaxContextTokens = 131_072,
        engineMaxBatchSize = engineMaxBatchSize,
        engineMaxMicroBatchSize = 512,
        engineMaxSequenceCount = 8,
        allowContextFallback = true,
        allowBatchFallback = true,
        allowKvCacheFallback = true,
        allowedKvCacheTypes = KvCacheType.entries,
        backend = backend,
        memoryTopology = topology,
        gpuLayerCount = null,
        engineImageDimensionMultiple = 64,
        engineMaxImageDimension = DescriptorLimits.MAX_IMAGE_DIMENSION,
        engineMaxDiffusionFrames = 4_096,
        engineMaxDiffusionSteps = 10_000,
        supportsVaeTiling = supportsVaeTiling,
        supportsMaxVram = supportsMaxVram,
        supportsLayerStreaming = supportsLayerStreaming,
        maxVramBytes = maxVramBytes,
    )

    private fun descriptor(
        mode: DiffusionMode = DiffusionMode.IMAGE,
        family: String = "FLUX",
        nativeWidth: Int? = 1_024,
        components: List<DiffusionComponentDescriptor> = listOf(
            component("diffusion.safetensors", 2L * GIB, role = null, isPrimary = true),
            component("vae.safetensors", 256L * MIB, ComponentRole.VAE),
            component("clip.safetensors", 384L * MIB, ComponentRole.CLIP_L),
        ),
    ) = diffusionDescriptor(
        mode = mode,
        family = family,
        components = components,
        width = nativeWidth,
    )

    private companion object {
        const val MIB = 1_048_576L
        const val GIB = 1_073_741_824L
    }
}
