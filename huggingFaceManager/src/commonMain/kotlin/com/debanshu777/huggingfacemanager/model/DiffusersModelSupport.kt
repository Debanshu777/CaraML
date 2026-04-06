package com.debanshu777.huggingfacemanager.model

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

/**
 * Returns true if [rootDir] contains the minimal diffusers layout expected by stable-diffusion.cpp
 * (UNet + text encoder weights; VAE may be optional in the native loader).
 */
fun isDiffusersModelDirectory(rootDir: String, fileExists: (String) -> Boolean): Boolean {
    if (rootDir.isBlank()) return false
    val base = rootDir.trimEnd('/', '\\')
    val unetOk = sequenceOf(
        "$base/unet/diffusion_pytorch_model.safetensors",
        "$base/unet/diffusion_pytorch_model.fp16.safetensors",
    ).any(fileExists)
    val teOk = sequenceOf(
        "$base/text_encoder/model.safetensors",
        "$base/text_encoder/model.fp16.safetensors",
    ).any(fileExists)
    return unetOk && teOk
}

/**
 * Renames fp16-suffixed weight files to the standard names that stable-diffusion.cpp
 * expects (e.g. `diffusion_pytorch_model.fp16.safetensors` → `diffusion_pytorch_model.safetensors`).
 *
 * A rename is only attempted when the fp16 file exists **and** the standard file does not,
 * so previously-downloaded non-fp16 weights are never overwritten.
 */
fun normalizeDiffusersFileNames(
    rootDir: String,
    fileExists: (String) -> Boolean,
    renameFile: (from: String, to: String) -> Boolean,
) {
    if (rootDir.isBlank()) return
    val base = rootDir.trimEnd('/', '\\')
    for ((fp16Rel, stdRel) in FP16_RENAME_PAIRS) {
        val fp16Path = "$base/$fp16Rel"
        val stdPath = "$base/$stdRel"
        if (fileExists(fp16Path) && !fileExists(stdPath)) {
            renameFile(fp16Path, stdPath)
        }
    }
}
