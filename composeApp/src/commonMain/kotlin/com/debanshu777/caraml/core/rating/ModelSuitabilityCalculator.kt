package com.debanshu777.caraml.core.rating

import com.debanshu777.caraml.core.platform.DeviceHints
import kotlin.math.max
import kotlin.math.min

/**
 * Pure device-fit calculator. Stateless object — no Compose imports, no DI.
 *
 * **What it does**
 *   Estimates how much RAM a model will need at inference time and compares
 *   that against the device's memory budget ([DeviceHints.memoryBudgetMB]).
 *   Returns one of POOR / AVERAGE / GOOD / BEST / UNKNOWN.
 *
 * **Why the numbers are what they are**
 *   - Bits-per-weight values come from the canonical llama.cpp quantize README
 *     (measured against Llama-3.1-8B): https://github.com/ggml-org/llama.cpp/blob/master/tools/quantize/README.md
 *   - The runtime-overhead factor (weights × 0.20, floored at 500 MB) follows
 *     HuggingFace Accelerate's `estimate_memory()` convention (the +20% inference
 *     overhead originally published by EleutherAI).
 *   - The KV cache formula is standard transformer math
 *     (`2 × n_layers × n_kv_heads × head_dim × ctx × bytes_per_element`); when we
 *     don't know the architecture shape we fall back to a parameter-proportional
 *     proxy calibrated against Llama-3.1-8B @ 4096 ctx.
 *   - The 4-tier bucketing mirrors LM Studio's "Full / Partial / Likely too large"
 *     model-fit indicator, extended with a "comfortable" top tier.
 */
object ModelSuitabilityCalculator {

    /**
     * Canonical bits-per-weight values from llama.cpp's quantize README (measured
     * on Llama-3.1-8B). Keys are uppercase tag names as parsed from filenames.
     * Fallback is [DEFAULT_BPW] for unknown tags (conservative ~ Q5).
     */
    private val QUANT_BPW: Map<String, Double> = mapOf(
        "Q2_K" to 3.16,
        "IQ2_XS" to 2.43,
        "IQ2_S" to 2.55,
        "IQ2_M" to 2.74,
        "Q3_K_S" to 3.50,
        "Q3_K_M" to 4.00,
        "Q3_K_L" to 4.27,
        "IQ3_XS" to 3.32,
        "IQ3_S" to 3.55,
        "IQ3_M" to 3.70,
        "Q4_0" to 4.55,
        "Q4_1" to 5.06,
        "Q4_K_S" to 4.67,
        "Q4_K_M" to 4.89,
        "IQ4_NL" to 4.62,
        "IQ4_XS" to 4.34,
        "Q5_0" to 5.55,
        "Q5_1" to 6.06,
        "Q5_K_S" to 5.57,
        "Q5_K_M" to 5.70,
        "Q6_K" to 6.56,
        "Q8_0" to 8.50,
        "F16" to 16.00,
        "FP16" to 16.00,
        "BF16" to 16.00,
        "F32" to 32.00,
        "FP32" to 32.00,
        "INT8" to 8.00,
        "INT4" to 4.00,
    )

    private const val DEFAULT_BPW: Double = 5.00

    private val SD_QUANT_BPW: Map<String, Double> = mapOf(
        "q2_k"  to 3.16,
        "q3_k"  to 3.75,
        "q4_0"  to 4.55,
        "q4_1"  to 5.06,
        "q4_k"  to 4.89,
        "q5_0"  to 5.57,
        "q5_1"  to 6.06,
        "q5_k"  to 5.70,
        "q6_k"  to 6.56,
        "q8_0"  to 8.50,
        "f16"   to 16.0,
        "bf16"  to 16.0,
        "f32"   to 32.0,
    )
    private const val SD_DEFAULT_BPW = 4.89
    private const val SD_Q4_BPW      = 4.89
    private const val VAE_OFFLOAD_BYTES  = 400L * 1024 * 1024
    private const val FLASH_ATTN_BYTES   = 600L * 1024 * 1024
    private val DIT_ARCHITECTURES = setOf(
        SdArchitecture.FLUX,
        SdArchitecture.SD3,
        SdArchitecture.WAN_SMALL,
        SdArchitecture.WAN_LARGE,
    )
    private val VIDEO_ARCHITECTURES = setOf(
        SdArchitecture.WAN_SMALL,
        SdArchitecture.WAN_LARGE,
    )

    /** Default assumption when no variant is selected yet (search list). */
    const val DEFAULT_QUANT_TAG: String = "Q4_K_M"

    /** Known architecture shape lookups for accurate KV cache estimates.
     *  Triple of (numLayers, numKvHeads, headDim). Keys are lowercase
     *  GGUF architecture strings (matching [com.debanshu777.huggingfacemanager.model.ModelDetailResponse.Gguf.architecture]).
     *  Sized per published model cards on HuggingFace. */
    private data class ArchShape(val numLayers: Int, val numKvHeads: Int, val headDim: Int)

    private val ARCH_SHAPES: Map<String, ArchShape> = mapOf(
        // Llama-3-8B / Llama-3.1-8B: 32 layers, 8 KV heads (GQA), head_dim 128
        "llama" to ArchShape(32, 8, 128),
        "llama3" to ArchShape(32, 8, 128),
        // Mistral-7B: 32 layers, 8 KV heads, 128 head_dim
        "mistral" to ArchShape(32, 8, 128),
        // Qwen2 7B: 28 layers, 4 KV heads, 128 head_dim
        "qwen2" to ArchShape(28, 4, 128),
        "qwen3" to ArchShape(36, 8, 128),
        // Phi-3-mini: 32 layers, 32 KV heads (no GQA at 3.8B), 96 head_dim
        "phi3" to ArchShape(32, 32, 96),
        // Gemma2-9B: 42 layers, 8 KV heads, 256 head_dim
        "gemma2" to ArchShape(42, 8, 256),
    )

    private const val FP16_BYTES = 2
    private const val MIN_OVERHEAD_BYTES = 500L * 1024 * 1024            // 500 MB floor
    private const val OVERHEAD_RATIO = 0.20                              // HF Accelerate +20%
    private const val DIFFUSION_OVERHEAD_BYTES = 400L * 1024 * 1024      // 400 MB scratch + VAE
    private const val KV_PROXY_FACTOR = 1.2e-5                           // calibrated @ Llama-3.1-8B / 4096 ctx
    private const val KV_PROXY_CAP_BYTES = (1.5 * 1024 * 1024 * 1024).toLong()
    private const val DEFAULT_CTX = 4096

    /** Quantization tag regex — pulled from VariantPickerRow's original
     *  `quantizationLabel()` so callers stop duplicating it. */
    private val QUANT_TAG_REGEX = Regex(
        """(IQ\d[\w]*|Q\d[\w]*|FP16|BF16|F16|F32|FP32|INT8|INT4)""",
        RegexOption.IGNORE_CASE,
    )

    /** Extracts the quantization tag from a GGUF filename, normalized to uppercase.
     *  Returns null when no recognizable tag is present. */
    fun parseQuantTag(filename: String): String? {
        val stem = filename.substringBeforeLast('.')
        return QUANT_TAG_REGEX.find(stem)?.value?.uppercase()
    }

    /** Bits-per-weight for a given tag, falling back to [DEFAULT_BPW]. */
    fun bitsPerWeight(quantTag: String?): Double =
        quantTag?.let { QUANT_BPW[it.uppercase()] } ?: DEFAULT_BPW

    /**
     * Rate an LLM/GGUF model against the device.
     *
     * @param hints          device capabilities snapshot (from [DeviceHints])
     * @param numParameters  HF-reported parameter count, or null
     * @param sizeBytes      actual file size on disk if a variant is known (preferred over BPW math)
     * @param quantTag       quantization tag ("Q4_K_M"); if null and no [sizeBytes], we assume [DEFAULT_QUANT_TAG]
     * @param contextLength  model max context; defaults to [DEFAULT_CTX] when null
     * @param architecture   GGUF architecture string; enables accurate KV cache lookup when present in [ARCH_SHAPES]
     * @param pipelineTag    HF pipeline tag; only used to detect text-to-image edge cases on the LLM path
     */
    fun rateLlm(
        hints: DeviceHints,
        numParameters: Long?,
        sizeBytes: Long? = null,
        quantTag: String? = null,
        contextLength: Int? = null,
        architecture: String? = null,
        pipelineTag: String? = null,
    ): SuitabilityResult {
        val budgetBytes = hints.memoryBudgetMB * 1024L * 1024L

        // Need at least one of (sizeBytes) or (numParameters) to estimate weights.
        if (sizeBytes == null && (numParameters == null || numParameters <= 0)) {
            return SuitabilityResult.unknown(budgetBytes)
        }

        val effectiveQuant = quantTag ?: if (sizeBytes == null) DEFAULT_QUANT_TAG else null
        val bpw = bitsPerWeight(effectiveQuant)

        // Weights: prefer real on-disk size when known (more accurate than params × bpw).
        val weightsBytes: Long = sizeBytes
            ?: ((numParameters!!.toDouble() * bpw / 8.0).toLong())

        val isEstimate = sizeBytes == null
        val ctx = contextLength?.takeIf { it > 0 } ?: DEFAULT_CTX

        // KV cache — accurate path when arch shape known + params known; proxy otherwise.
        val kvBytes: Long = kvCacheBytes(
            architecture = architecture,
            contextLength = ctx,
            numParameters = numParameters,
        )

        val overheadBytes: Long = max(
            (weightsBytes.toDouble() * OVERHEAD_RATIO).toLong(),
            MIN_OVERHEAD_BYTES,
        )

        val estimatedBytes = weightsBytes + kvBytes + overheadBytes
        val ratio = estimatedBytes.toDouble() / budgetBytes.toDouble()
        val baseRating = bucketize(ratio)

        val (adjusted, reason) = applyModifiers(
            base = baseRating,
            ratio = ratio,
            estimatedBytes = estimatedBytes,
            hints = hints,
            pipelineTag = pipelineTag,
        )

        return SuitabilityResult(
            rating = adjusted,
            estimatedBytes = estimatedBytes,
            budgetBytes = budgetBytes,
            weightsBytes = weightsBytes,
            kvBytes = kvBytes,
            overheadBytes = overheadBytes,
            quantAssumed = effectiveQuant,
            isEstimate = isEstimate,
            reason = reason,
        )
    }

    /**
     * Rate a diffusion bundle. Caller sums the component file sizes
     * (UNet + VAE + CLIP + T5 etc.) and passes the total.
     * No KV cache; flat 400 MB overhead for scratch + scheduler buffers.
     */
    fun rateDiffusion(
        hints: DeviceHints,
        totalComponentBytes: Long?,
        pipelineTag: String? = "text-to-image",
    ): SuitabilityResult {
        val budgetBytes = hints.memoryBudgetMB * 1024L * 1024L
        if (totalComponentBytes == null || totalComponentBytes <= 0) {
            return SuitabilityResult.unknown(budgetBytes)
        }

        val weightsBytes = totalComponentBytes
        val overheadBytes = max(
            (weightsBytes.toDouble() * OVERHEAD_RATIO).toLong(),
            DIFFUSION_OVERHEAD_BYTES,
        )
        val estimatedBytes = weightsBytes + overheadBytes
        val ratio = estimatedBytes.toDouble() / budgetBytes.toDouble()
        val baseRating = bucketize(ratio)

        val (adjusted, reason) = applyModifiers(
            base = baseRating,
            ratio = ratio,
            estimatedBytes = estimatedBytes,
            hints = hints,
            pipelineTag = pipelineTag,
        )

        return SuitabilityResult(
            rating = adjusted,
            estimatedBytes = estimatedBytes,
            budgetBytes = budgetBytes,
            weightsBytes = weightsBytes,
            kvBytes = 0L,
            overheadBytes = overheadBytes,
            quantAssumed = null,
            isEstimate = false,
            reason = reason,
        )
    }

    /**
     * Architecture-aware rating for stable-diffusion.cpp models.
     *
     * Weight resolution priority:
     * 1. [totalComponentBytes] — real file sizes (isEstimate = false).
     * 2. [architecture] baseline scaled by [dominantQuantTag] BPW (isEstimate = true).
     * 3. UNKNOWN when both unavailable.
     */
    fun rateDiffusion(
        hints: DeviceHints,
        architecture: SdArchitecture,
        dominantQuantTag: String? = null,
        totalComponentBytes: Long? = null,
        canOffloadVae: Boolean = true,
        flashAttnAvailable: Boolean = false,
    ): SuitabilityResult {
        val budgetBytes = hints.memoryBudgetMB * 1024L * 1024L

        val rawWeightsBytes: Long? = when {
            totalComponentBytes != null && totalComponentBytes > 0 -> totalComponentBytes
            architecture != SdArchitecture.UNKNOWN -> {
                val bpwRatio = (SD_QUANT_BPW[dominantQuantTag?.lowercase()] ?: SD_DEFAULT_BPW) / SD_Q4_BPW
                (architecture.baseRamBytesQ4 * bpwRatio).toLong()
            }
            else -> null
        }

        if (rawWeightsBytes == null) return SuitabilityResult.unknown(budgetBytes)

        val isEstimate = totalComponentBytes == null

        var effectiveBytes = rawWeightsBytes
        if (canOffloadVae)      effectiveBytes -= VAE_OFFLOAD_BYTES
        if (flashAttnAvailable) effectiveBytes -= FLASH_ATTN_BYTES
        effectiveBytes = max(effectiveBytes, rawWeightsBytes / 4)

        val overheadBytes = max(
            (effectiveBytes.toDouble() * OVERHEAD_RATIO).toLong(),
            DIFFUSION_OVERHEAD_BYTES,
        )
        val estimatedBytes = effectiveBytes + overheadBytes
        val ratio = estimatedBytes.toDouble() / budgetBytes.toDouble()
        val baseRating = bucketize(ratio)

        var current = baseRating
        val notes = mutableListOf("SD RAM ratio ${formatRatio(ratio)} → ${baseRating.shortLabel()}")

        if (hints.gpuBackendAvailable && architecture in DIT_ARCHITECTURES) {
            val bumped = bumpUp(current)
            if (bumped != current) {
                notes += "GPU + DiT arch → bumped to ${bumped.shortLabel()}"
                current = bumped
            }
        }
        if (hints.performanceCoreCount in 1..3 && architecture in VIDEO_ARCHITECTURES) {
            val bumped = bumpDown(current)
            if (bumped != current) {
                notes += "Only ${hints.performanceCoreCount} perf core(s) for video → dropped to ${bumped.shortLabel()}"
                current = bumped
            }
        }

        return SuitabilityResult(
            rating         = current,
            estimatedBytes = estimatedBytes,
            budgetBytes    = budgetBytes,
            weightsBytes   = effectiveBytes,
            kvBytes        = 0L,
            overheadBytes  = overheadBytes,
            quantAssumed   = dominantQuantTag ?: "Q4 baseline",
            isEstimate     = isEstimate,
            reason         = notes.joinToString("; "),
        )
    }

    /**
     * Accurate KV cache when the architecture shape is in [ARCH_SHAPES], else
     * a parameter-proportional proxy calibrated against Llama-3.1-8B @ 4096 ctx.
     * Always clamped at [KV_PROXY_CAP_BYTES] to prevent absurd estimates from
     * misreported metadata. Returns 0 when neither path has inputs.
     */
    private fun kvCacheBytes(
        architecture: String?,
        contextLength: Int,
        numParameters: Long?,
    ): Long {
        val arch = architecture?.lowercase()?.let { ARCH_SHAPES[it] }
        if (arch != null) {
            // 2 (K + V) × layers × kvHeads × headDim × ctx × bytes_per_element (FP16)
            val bytes = 2L * arch.numLayers * arch.numKvHeads * arch.headDim *
                contextLength * FP16_BYTES
            return min(bytes, KV_PROXY_CAP_BYTES)
        }
        if (numParameters == null || numParameters <= 0) return 0L
        val proxy = (contextLength.toDouble() * numParameters.toDouble() * KV_PROXY_FACTOR).toLong()
        return min(proxy, KV_PROXY_CAP_BYTES)
    }

    private fun bucketize(ratio: Double): SuitabilityRating = when {
        ratio < 0.40 -> SuitabilityRating.BEST
        ratio < 0.65 -> SuitabilityRating.GOOD
        ratio < 0.90 -> SuitabilityRating.AVERAGE
        else -> SuitabilityRating.POOR
    }

    /**
     * Bump tiers up/down for GPU offload availability and CPU constraints.
     * Capped at BEST / POOR (modifiers never escape the enum range).
     * Returned [reason] explains the modifier chain for the info sheet.
     */
    private fun applyModifiers(
        base: SuitabilityRating,
        ratio: Double,
        estimatedBytes: Long,
        hints: DeviceHints,
        pipelineTag: String?,
    ): Pair<SuitabilityRating, String> {
        var current = base
        val notes = mutableListOf("RAM ratio ${formatRatio(ratio)} → ${base.shortLabel()}")

        // GPU bump: meaningful only when the model is large enough that GPU offload helps.
        val twoGb = 2L * 1024 * 1024 * 1024
        if (hints.gpuBackendAvailable && estimatedBytes > twoGb) {
            val bumped = bumpUp(current)
            if (bumped != current) {
                notes += "GPU backend available → bumped to ${bumped.shortLabel()}"
                current = bumped
            }
        }

        // Low-perf-core penalty for large models (token throughput tanks on weak CPUs).
        val fourGb = 4L * 1024 * 1024 * 1024
        if (hints.performanceCoreCount in 1..3 && estimatedBytes > fourGb) {
            val bumped = bumpDown(current)
            if (bumped != current) {
                notes += "Only ${hints.performanceCoreCount} perf core(s) → dropped to ${bumped.shortLabel()}"
                current = bumped
            }
        }

        // Diffusion is compute-bound; few total cores hurts more than RAM ratio suggests.
        if (
            hints.totalCoreCount in 1..3 &&
            pipelineTag?.contains("text-to-image", ignoreCase = true) == true
        ) {
            val bumped = bumpDown(current)
            if (bumped != current) {
                notes += "Few total cores for diffusion → dropped to ${bumped.shortLabel()}"
                current = bumped
            }
        }

        return current to notes.joinToString("; ")
    }

    private fun bumpUp(r: SuitabilityRating): SuitabilityRating = when (r) {
        SuitabilityRating.POOR -> SuitabilityRating.AVERAGE
        SuitabilityRating.AVERAGE -> SuitabilityRating.GOOD
        SuitabilityRating.GOOD -> SuitabilityRating.BEST
        SuitabilityRating.BEST -> SuitabilityRating.BEST
        SuitabilityRating.UNKNOWN -> SuitabilityRating.UNKNOWN
    }

    private fun bumpDown(r: SuitabilityRating): SuitabilityRating = when (r) {
        SuitabilityRating.BEST -> SuitabilityRating.GOOD
        SuitabilityRating.GOOD -> SuitabilityRating.AVERAGE
        SuitabilityRating.AVERAGE -> SuitabilityRating.POOR
        SuitabilityRating.POOR -> SuitabilityRating.POOR
        SuitabilityRating.UNKNOWN -> SuitabilityRating.UNKNOWN
    }
}

/** Multiplatform 2-decimal formatter (no `String.format` on Kotlin/Native). */
private fun formatRatio(value: Double): String {
    val rounded = kotlin.math.round(value * 100).toInt()
    val whole = rounded / 100
    val frac = (rounded % 100).toString().padStart(2, '0')
    return "$whole.$frac"
}
