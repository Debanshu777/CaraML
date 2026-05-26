package com.debanshu777.caraml.core.rating

/**
 * Coarse device-fit tier for a model. Surfaced in the model browse UI as a
 * color-coded chip. UNKNOWN is used when we lack enough metadata to estimate
 * (no parameter count AND no file size).
 */
enum class SuitabilityRating {
    POOR,
    AVERAGE,
    GOOD,
    BEST,
    UNKNOWN,
    ;

    /** Short label rendered inside the chip. */
    fun shortLabel(): String = when (this) {
        BEST -> "Best"
        GOOD -> "Good"
        AVERAGE -> "Avg"
        POOR -> "Poor"
        UNKNOWN -> "?"
    }
}

/**
 * Full result of a suitability computation, including the math used to derive
 * the bucket. Carried into the info bottom sheet so the user can see exactly
 * how we arrived at the rating.
 *
 * All `Bytes` fields are absolute byte counts (not MB) so callers can format
 * with a single helper without unit confusion.
 *
 * @property rating         the bucket the model fell into
 * @property estimatedBytes total in-memory footprint estimate; null when [rating] is UNKNOWN
 * @property budgetBytes    device memory budget the model is being compared against
 * @property weightsBytes   estimated weights component of the footprint
 * @property kvBytes        estimated KV cache (LLM) or 0 (diffusion)
 * @property overheadBytes  runtime / scratch overhead
 * @property quantAssumed   quantization tag used in the estimate ("Q4_K_M" assumed, "Q5_K_M" parsed, etc.); null for diffusion/UNKNOWN
 * @property isEstimate     true when [weightsBytes] came from `params * bpw` rather than a real file size on disk
 * @property reason         short human-readable description of the modifier chain ("RAM ratio 0.42 → GOOD; +GPU bump → BEST")
 */
data class SuitabilityResult(
    val rating: SuitabilityRating,
    val estimatedBytes: Long?,
    val budgetBytes: Long,
    val weightsBytes: Long,
    val kvBytes: Long,
    val overheadBytes: Long,
    val quantAssumed: String?,
    val isEstimate: Boolean,
    val reason: String,
) {
    companion object {
        fun unknown(budgetBytes: Long): SuitabilityResult = SuitabilityResult(
            rating = SuitabilityRating.UNKNOWN,
            estimatedBytes = null,
            budgetBytes = budgetBytes,
            weightsBytes = 0L,
            kvBytes = 0L,
            overheadBytes = 0L,
            quantAssumed = null,
            isEstimate = true,
            reason = "Not enough metadata to estimate memory footprint.",
        )
    }
}
