package com.debanshu777.diffusionrunner

/**
 * Lightweight metadata returned by the native stable-diffusion.cpp layer
 * for an already-downloaded model file. Used to produce the most accurate
 * post-download suitability rating.
 *
 * @param architecture      The SDVersion family string, e.g. "FLUX", "SDXL", "SD1".
 *                          Maps to [com.debanshu777.caraml.core.rating.SdArchitecture]
 *                          via SdArchitecture.fromNativeString().
 * @param dominantQuantType The ggml_type_name() of the most frequent weight tensor type,
 *                          e.g. "q4_k", "q8_0", "f16". Null if indeterminate.
 * @param estimatedRamBytes RAM estimate from ModelLoader.get_params_mem_size() in bytes.
 */
data class DiffusionModelMetadata(
    val architecture: String,
    val dominantQuantType: String?,
    val estimatedRamBytes: Long,
)
