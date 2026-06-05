package com.debanshu777.caraml.core.rating

/**
 * Maps HuggingFace model tags and model IDs to a known [SdArchitecture].
 *
 * Detection is purely lexical — no network calls, no file reads. Precision is sufficient
 * for pre-download RAM estimates; post-download metadata from DiffusionRunner.getDiffusionModelMetadata
 * provides ground-truth architecture.
 *
 * Priority order (first match wins): FLUX > SD3 > WAN_LARGE > WAN_SMALL > SDXL > SD1 > UNKNOWN
 */
object SdArchitectureClassifier {

    private val FLUX_TAGS = setOf(
        "flux", "flux.1", "flux1", "flux-dev", "flux-schnell",
        "flux-fill", "flux-controls", "flex-2",
    )
    private val SD3_TAGS = setOf(
        "stable-diffusion-3", "sd3", "sd-3", "stable-diffusion3", "sd3.5",
    )
    private val WAN_LARGE_TAGS = setOf(
        "wan2.1-14b", "wan-14b", "wan2-14b", "wan2.2-14b",
    )
    private val WAN_SMALL_TAGS = setOf(
        "wan", "wan2", "wan2.1", "wan2.1-1.3b", "wan-1.3b",
    )
    private val SDXL_TAGS = setOf(
        "stable-diffusion-xl", "stable-diffusion-xl-base-1.0",
        "sdxl", "sdxl-base",
    )
    private val SD1_TAGS = setOf(
        "stable-diffusion", "stable-diffusion-v1", "stable-diffusion-v1-5",
        "stable-diffusion-v2", "sd1", "sd-1", "sd2", "sd-2",
    )
    private val DISTILLED_SIGNALS = setOf(
        "turbo", "schnell", "lightning", "lcm", "flash", "bk-sdm", "tiny-sd", "ssd-1b", "distilled",
    )

    /**
     * Classifies an SD model from HuggingFace tags and model ID.
     *
     * @param tags    List of HF tags from the model card. Pass empty list when unavailable.
     * @param modelId The HF repo ID, e.g. "black-forest-labs/FLUX.1-dev". Path segments
     *                after "/" and separated by "-", "_", "." are scanned as additional candidates.
     */
    fun classify(tags: List<String>, modelId: String = ""): SdArchitecture {
        val normalizedId = modelId.lowercase()
        val normalized = buildSet<String> {
            tags.forEach { tag -> add(tag.lowercase().trim()) }
            normalizedId.split("/", "-", "_", ".").forEach { segment ->
                if (segment.length >= 2) add(segment)
            }
        }

        // Check tag sets via exact segment match OR substring of the full model ID.
        // Substring match catches compound tags like "stable-diffusion-xl" that never
        // appear as individual split segments (e.g. "stabilityai/stable-diffusion-xl-base-1.0").
        fun matches(tagSet: Set<String>) =
            normalized.any { it in tagSet } || tagSet.any { normalizedId.contains(it) }

        return when {
            matches(FLUX_TAGS)      -> SdArchitecture.FLUX
            matches(SD3_TAGS)       -> SdArchitecture.SD3
            matches(WAN_LARGE_TAGS) -> SdArchitecture.WAN_LARGE
            matches(WAN_SMALL_TAGS) -> SdArchitecture.WAN_SMALL
            matches(SDXL_TAGS)      -> SdArchitecture.SDXL
            matches(SD1_TAGS)       -> SdArchitecture.SD1
            else                    -> SdArchitecture.UNKNOWN
        }
    }

    /** Detects whether a model is a distilled/few-step variant based on its ID and tags. */
    fun isDistilled(modelId: String, tags: List<String> = emptyList()): Boolean {
        val normalizedId = modelId.lowercase()
        val normalized = buildSet<String> {
            tags.forEach { tag -> add(tag.lowercase().trim()) }
            normalizedId.split("/", "-", "_", ".").forEach { segment ->
                if (segment.length >= 2) add(segment)
            }
        }
        return normalized.any { it in DISTILLED_SIGNALS } ||
            DISTILLED_SIGNALS.any { normalizedId.contains(it) }
    }
}
