package com.debanshu777.huggingfacemanager.model

/**
 * Which weight file extensions to show from the Hugging Face repo file tree.
 * [StableDiffusionCppWeights] matches formats supported by stable-diffusion.cpp
 * (PyTorch checkpoint, Safetensors, GGUF).
 */
enum class ModelFileWeightFilter {
    /** LLM hubs: GGUF only (llama.cpp style). */
    GgufOnly,

    /**
     * Image/video (stable-diffusion.cpp): checkpoints and safetensors as well as GGUF.
     * (.ckpt, .pth, .safetensors, .gguf)
     */
    StableDiffusionCppWeights,
}

fun ModelFileWeightFilter.matchesRepoFilePath(path: String?): Boolean {
    if (path.isNullOrBlank()) return false
    return when (this) {
        ModelFileWeightFilter.GgufOnly ->
            path.endsWith(suffix = ".gguf", ignoreCase = true)

        ModelFileWeightFilter.StableDiffusionCppWeights ->
            path.endsWith(".gguf", ignoreCase = true) ||
                path.endsWith(".safetensors", ignoreCase = true) ||
                path.endsWith(".ckpt", ignoreCase = true) ||
                path.endsWith(".pth", ignoreCase = true)
    }
}
