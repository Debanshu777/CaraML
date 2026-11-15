package com.debanshu777.caraml.core.download.diffusion

/**
 * Local DB filename for a downloaded Hugging Face diffusers layout (full model directory).
 * The row's `localPath` should be the model root directory passed to stable-diffusion.cpp.
 */
const val DIFFUSERS_BUNDLE_DB_FILENAME: String = "__diffusers_bundle__"

/**
 * Pairs of (fp16 relative path → standard relative path) for every component that
 * stable-diffusion.cpp looks up by the standard name.
 */
private val FP16_RENAME_PAIRS: List<Pair<String, String>> = listOf(
    "unet/diffusion_pytorch_model.fp16.safetensors" to "unet/diffusion_pytorch_model.safetensors",
    "vae/diffusion_pytorch_model.fp16.safetensors" to "vae/diffusion_pytorch_model.safetensors",
    "text_encoder/model.fp16.safetensors" to "text_encoder/model.safetensors",
    "text_encoder_2/model.fp16.safetensors" to "text_encoder_2/model.safetensors",
)

fun normalizedDiffusersRelativePath(relativePath: String): String =
    FP16_RENAME_PAIRS.firstOrNull { it.first == relativePath }?.second ?: relativePath
