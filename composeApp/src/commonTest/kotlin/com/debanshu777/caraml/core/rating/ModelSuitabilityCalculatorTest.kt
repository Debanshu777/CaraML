package com.debanshu777.caraml.core.rating

import com.debanshu777.caraml.core.platform.DeviceHints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelSuitabilityCalculatorTest {

    private fun hints(
        ramMb: Long,
        gpu: Boolean = false,
        perfCores: Int = 8,
        totalCores: Int = 8,
    ) = DeviceHints(
        performanceCoreCount = perfCores,
        totalCoreCount = totalCores,
        memoryBudgetMB = ramMb,
        gpuBackendAvailable = gpu,
        perfCoreMask = "",
    )

    // --- quant parsing ---

    @Test
    fun parsesStandardQuantTags() {
        assertEquals("Q4_K_M", ModelSuitabilityCalculator.parseQuantTag("llama-3-8b-q4_k_m.gguf"))
        assertEquals("Q8_0", ModelSuitabilityCalculator.parseQuantTag("model-Q8_0.gguf"))
        assertEquals("F16", ModelSuitabilityCalculator.parseQuantTag("flux1-f16.gguf"))
        assertEquals("IQ3_M", ModelSuitabilityCalculator.parseQuantTag("model-iq3_m.gguf"))
    }

    @Test
    fun returnsNullWhenNoTagPresent() {
        assertNull(ModelSuitabilityCalculator.parseQuantTag("some-model.gguf"))
    }

    // --- weight estimation ---

    @Test
    fun llamaQ4KM_uses_4_89_bpw() {
        // 8B params * 4.89 bpw / 8 = ~4.89 GB weights
        val result = ModelSuitabilityCalculator.rateLlm(
            hints = hints(ramMb = 64 * 1024),
            numParameters = 8_000_000_000L,
            quantTag = "Q4_K_M",
        )
        val weightsGb = result.weightsBytes / 1024.0 / 1024.0 / 1024.0
        assertTrue(weightsGb in 4.5..5.0, "expected ~4.89 GB weights, got $weightsGb")
    }

    // --- bucket boundaries (vendored reference values) ---

    @Test
    fun llama3_8b_Q4KM_on_16GB_isGoodOrBest() {
        // ~5 GB weights + ~0.5 GB KV + ~1 GB overhead = ~6.5 GB / 16 GB = ~0.4
        val r = ModelSuitabilityCalculator.rateLlm(
            hints = hints(ramMb = 16 * 1024),
            numParameters = 8_000_000_000L,
            quantTag = "Q4_K_M",
            architecture = "llama",
            contextLength = 4096,
        )
        assertTrue(
            r.rating == SuitabilityRating.GOOD || r.rating == SuitabilityRating.BEST,
            "expected GOOD or BEST, got ${r.rating}; reason: ${r.reason}",
        )
    }

    @Test
    fun llama70B_Q4_on_6GB_isPoor() {
        // ~40 GB footprint vs 6 GB → r >> 0.9
        val r = ModelSuitabilityCalculator.rateLlm(
            hints = hints(ramMb = 6 * 1024),
            numParameters = 70_000_000_000L,
            quantTag = "Q4_K_M",
        )
        assertEquals(SuitabilityRating.POOR, r.rating)
    }

    @Test
    fun small_3B_Q4_on_24GB_with_GPU_isBest() {
        // ~2 GB / 24 GB = 0.08
        val r = ModelSuitabilityCalculator.rateLlm(
            hints = hints(ramMb = 24 * 1024, gpu = true),
            numParameters = 3_000_000_000L,
            quantTag = "Q4_K_M",
        )
        assertEquals(SuitabilityRating.BEST, r.rating)
    }

    @Test
    fun thirteenB_Q8_on_12GB_isPoor() {
        // 13B * 8.5 / 8 ~= 13.8 GB > 12 GB budget
        val r = ModelSuitabilityCalculator.rateLlm(
            hints = hints(ramMb = 12 * 1024),
            numParameters = 13_000_000_000L,
            quantTag = "Q8_0",
        )
        assertEquals(SuitabilityRating.POOR, r.rating)
    }

    // --- unknown handling ---

    @Test
    fun unknownWhenNoParamsAndNoSize() {
        val r = ModelSuitabilityCalculator.rateLlm(
            hints = hints(ramMb = 16 * 1024),
            numParameters = null,
            sizeBytes = null,
        )
        assertEquals(SuitabilityRating.UNKNOWN, r.rating)
        assertNull(r.estimatedBytes)
    }

    @Test
    fun usesSizeBytesWhenProvided_isEstimateFalse() {
        val r = ModelSuitabilityCalculator.rateLlm(
            hints = hints(ramMb = 16 * 1024),
            numParameters = 7_000_000_000L,
            sizeBytes = 4_500_000_000L,
            quantTag = "Q4_K_M",
        )
        assertEquals(4_500_000_000L, r.weightsBytes)
        assertEquals(false, r.isEstimate)
    }

    // --- modifiers ---

    @Test
    fun gpuBumpsTierUp_forLargeModels() {
        // 7B Q4: ~3.6 GB weights + KV + overhead → ~5 GB on 8 GB → AVERAGE without GPU
        val noGpu = ModelSuitabilityCalculator.rateLlm(
            hints = hints(ramMb = 8 * 1024, gpu = false),
            numParameters = 7_000_000_000L,
            quantTag = "Q4_K_M",
        )
        val withGpu = ModelSuitabilityCalculator.rateLlm(
            hints = hints(ramMb = 8 * 1024, gpu = true),
            numParameters = 7_000_000_000L,
            quantTag = "Q4_K_M",
        )
        // GPU result should be same tier or better than no-GPU
        assertTrue(
            withGpu.rating.ordinal >= noGpu.rating.ordinal,
            "GPU should not lower tier: noGpu=${noGpu.rating}, withGpu=${withGpu.rating}",
        )
    }

    @Test
    fun lowPerfCores_bumpsLargeModelsDown() {
        // 13B Q4 on 32 GB device: r ~ 0.25 → BEST normally
        val many = ModelSuitabilityCalculator.rateLlm(
            hints = hints(ramMb = 32 * 1024, perfCores = 8),
            numParameters = 13_000_000_000L,
            quantTag = "Q4_K_M",
        )
        val few = ModelSuitabilityCalculator.rateLlm(
            hints = hints(ramMb = 32 * 1024, perfCores = 2),
            numParameters = 13_000_000_000L,
            quantTag = "Q4_K_M",
        )
        assertTrue(
            few.rating.ordinal <= many.rating.ordinal,
            "low perf cores should not improve tier: many=${many.rating}, few=${few.rating}",
        )
    }

    @Test
    fun modifierCaps_neverEscapeBounds() {
        // Tiny model, GPU on, many cores — already BEST, GPU bump shouldn't go higher
        val r = ModelSuitabilityCalculator.rateLlm(
            hints = hints(ramMb = 64 * 1024, gpu = true, perfCores = 12),
            numParameters = 1_000_000_000L,
            quantTag = "Q4_K_M",
        )
        assertEquals(SuitabilityRating.BEST, r.rating)

        // Huge model, low cores — POOR can't go below
        val poor = ModelSuitabilityCalculator.rateLlm(
            hints = hints(ramMb = 4 * 1024, gpu = false, perfCores = 1),
            numParameters = 70_000_000_000L,
            quantTag = "Q4_K_M",
        )
        assertEquals(SuitabilityRating.POOR, poor.rating)
    }

    // --- diffusion ---

    @Test
    fun diffusionBundle_sumsComponents() {
        // SDXL-ish: 2.3 GB UNet + 300 MB VAE + 250 MB CLIP ~ 2.85 GB
        val totalBytes = (2_300L + 300L + 250L) * 1024 * 1024
        val r = ModelSuitabilityCalculator.rateDiffusion(
            hints = hints(ramMb = 16 * 1024, gpu = true),
            totalComponentBytes = totalBytes,
        )
        // ~3.25 GB / 16 GB = 0.20 → BEST
        assertEquals(0L, r.kvBytes)
        assertEquals(SuitabilityRating.BEST, r.rating)
    }

    @Test
    fun diffusion_unknown_whenNoSize() {
        val r = ModelSuitabilityCalculator.rateDiffusion(
            hints = hints(ramMb = 8 * 1024),
            totalComponentBytes = null,
        )
        assertEquals(SuitabilityRating.UNKNOWN, r.rating)
    }

    // --- result envelope ---

    @Test
    fun resultIncludesFootprintBreakdown() {
        val r = ModelSuitabilityCalculator.rateLlm(
            hints = hints(ramMb = 16 * 1024),
            numParameters = 7_000_000_000L,
            quantTag = "Q4_K_M",
            architecture = "llama",
        )
        assertNotNull(r.estimatedBytes)
        assertTrue(r.weightsBytes > 0)
        assertTrue(r.kvBytes > 0)
        assertTrue(r.overheadBytes > 0)
        assertTrue(r.reason.contains("RAM ratio"))
        assertEquals(16L * 1024 * 1024 * 1024, r.budgetBytes)
    }
}
