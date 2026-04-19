package com.debanshu777.huggingfacemanager.sdcpp

/**
 * Component roles for stable-diffusion.cpp inference, mapped to CLI flags.
 */
enum class ComponentRole(val cliFlag: String, val displayLabel: String) {
    VAE("--vae", "VAE"),
    CLIP_L("--clip_l", "Text Encoder (CLIP-L)"),
    CLIP_G("--clip_g", "Text Encoder (CLIP-G)"),
    T5XXL("--t5xxl", "Text Encoder (T5-XXL)"),
    UMT5XXL("--t5xxl", "Text Encoder (UMT5-XXL)"),
    LLM("--llm", "Language Model"),
    CLIP_VISION("--clip_vision", "CLIP Vision Encoder"),
    HIGH_NOISE_MODEL("--high-noise-diffusion-model", "High-Noise Diffusion Model"),
    LLM_VISION("--llm_vision", "Vision Language Model"),
}

/**
 * A required auxiliary component for a diffusion model setup.
 */
data class SdCppComponent(
    val role: ComponentRole,
    val repoId: String,
    val filePath: String,
    val required: Boolean = true,
    val sizeHint: String? = null,
    val alternatives: List<SdCppComponent> = emptyList(),
)

/**
 * Recommended inference parameters for a model family.
 */
data class SdCppRecommendedParams(
    val cfgScale: Float? = null,
    val steps: Int? = null,
    val samplingMethod: String? = null,
    val offloadToCpu: Boolean = false,
    val clipOnCpu: Boolean = false,
    val keepVaeOnCpu: Boolean = false,
    val diffusionFlashAttn: Boolean = false,
    val flowShift: Float? = null,
    val width: Int? = null,
    val height: Int? = null,
)

/**
 * Complete setup requirements for a diffusion model family.
 */
data class SdCppModelSetup(
    val familyLabel: String,
    val description: String,
    val components: List<SdCppComponent>,
    val recommendedParams: SdCppRecommendedParams? = null,
    val selfContained: Boolean = false,
)

/**
 * Registry of model setups keyed by HuggingFace repo ID.
 */
private val SETUP_REGISTRY: Map<String, SdCppModelSetup> = buildMap {
    
    // FLUX.1 family - requires VAE, CLIP-L, T5-XXL
    val flux1Setup = SdCppModelSetup(
        familyLabel = "FLUX.1",
        description = "High-quality text-to-image generation. Requires 6GB+ VRAM.",
        components = listOf(
            SdCppComponent(
                role = ComponentRole.VAE,
                repoId = "black-forest-labs/FLUX.1-dev",
                filePath = "ae.safetensors",
                sizeHint = "335 MB"
            ),
            SdCppComponent(
                role = ComponentRole.CLIP_L,
                repoId = "comfyanonymous/flux_text_encoders",
                filePath = "clip_l.safetensors",
                sizeHint = "246 MB"
            ),
            SdCppComponent(
                role = ComponentRole.T5XXL,
                repoId = "comfyanonymous/flux_text_encoders", 
                filePath = "t5xxl_fp16.safetensors",
                sizeHint = "9.79 GB"
            )
        ),
        recommendedParams = SdCppRecommendedParams(
            cfgScale = 1.0f,
            samplingMethod = "euler",
            clipOnCpu = true
        )
    )
    
    // Add FLUX.1 entries
    put("black-forest-labs/FLUX.1-dev", flux1Setup)
    put("black-forest-labs/FLUX.1-schnell", flux1Setup) 
    put("leejet/FLUX.1-dev-gguf", flux1Setup)
    put("leejet/FLUX.1-schnell-gguf", flux1Setup)
    
    // FLUX.2-dev - requires VAE and Mistral LLM
    val flux2DevSetup = SdCppModelSetup(
        familyLabel = "FLUX.2-dev",
        description = "Advanced image editing model. Supports reference images.",
        components = listOf(
            SdCppComponent(
                role = ComponentRole.VAE,
                repoId = "black-forest-labs/FLUX.2-dev",
                filePath = "flux2_ae.safetensors",
                sizeHint = "335 MB"
            ),
            SdCppComponent(
                role = ComponentRole.LLM,
                repoId = "unsloth/Mistral-Small-3.2-24B-Instruct-2506-GGUF",
                filePath = "Mistral-Small-3.2-24B-Instruct-2506-Q4_K_M.gguf",
                sizeHint = "14.8 GB"
            )
        ),
        recommendedParams = SdCppRecommendedParams(
            cfgScale = 1.0f,
            samplingMethod = "euler",
            offloadToCpu = true,
            diffusionFlashAttn = true
        )
    )
    put("black-forest-labs/FLUX.2-dev", flux2DevSetup)
    put("city96/FLUX.2-dev-gguf", flux2DevSetup)
    
    // FLUX.2-klein-4B - requires VAE and Qwen3-4B LLM
    val flux2Klein4BSetup = SdCppModelSetup(
        familyLabel = "FLUX.2-klein-4B", 
        description = "Lightweight FLUX.2 variant. Supports text-to-image and image editing.",
        components = listOf(
            SdCppComponent(
                role = ComponentRole.VAE,
                repoId = "black-forest-labs/FLUX.2-dev",
                filePath = "flux2_ae.safetensors",
                sizeHint = "335 MB"
            ),
            SdCppComponent(
                role = ComponentRole.LLM,
                repoId = "unsloth/Qwen3-4B-GGUF",
                filePath = "Qwen3-4B-Q4_K_M.gguf",
                sizeHint = "2.4 GB"
            )
        ),
        recommendedParams = SdCppRecommendedParams(
            cfgScale = 1.0f,
            steps = 4,
            samplingMethod = "euler",
            offloadToCpu = true,
            diffusionFlashAttn = true
        )
    )
    put("black-forest-labs/FLUX.2-klein-4B", flux2Klein4BSetup)
    put("leejet/FLUX.2-klein-4B-GGUF", flux2Klein4BSetup)
    put("black-forest-labs/FLUX.2-klein-base-4B", flux2Klein4BSetup)
    put("leejet/FLUX.2-klein-base-4B-GGUF", flux2Klein4BSetup)
    
    // FLUX.2-klein-9B - requires VAE and Qwen3-8B LLM  
    val flux2Klein9BSetup = SdCppModelSetup(
        familyLabel = "FLUX.2-klein-9B",
        description = "Mid-size FLUX.2 variant with better quality than 4B.",
        components = listOf(
            SdCppComponent(
                role = ComponentRole.VAE,
                repoId = "black-forest-labs/FLUX.2-dev", 
                filePath = "flux2_ae.safetensors",
                sizeHint = "335 MB"
            ),
            SdCppComponent(
                role = ComponentRole.LLM,
                repoId = "unsloth/Qwen3-8B-GGUF",
                filePath = "Qwen3-8B-Q4_K_M.gguf", 
                sizeHint = "4.9 GB"
            )
        ),
        recommendedParams = SdCppRecommendedParams(
            cfgScale = 1.0f,
            steps = 4,
            samplingMethod = "euler", 
            offloadToCpu = true,
            diffusionFlashAttn = true
        )
    )
    put("black-forest-labs/FLUX.2-klein-9B", flux2Klein9BSetup)
    put("leejet/FLUX.2-klein-9B-GGUF", flux2Klein9BSetup)
    put("black-forest-labs/FLUX.2-klein-base-9B", flux2Klein9BSetup)
    put("leejet/FLUX.2-klein-base-9B-GGUF", flux2Klein9BSetup)
    
    // SD3.5 Large - requires CLIP-L, CLIP-G, T5-XXL
    val sd35Setup = SdCppModelSetup(
        familyLabel = "Stable Diffusion 3.5",
        description = "Latest high-quality Stable Diffusion model with excellent text rendering.",
        components = listOf(
            SdCppComponent(
                role = ComponentRole.CLIP_L,
                repoId = "Comfy-Org/stable-diffusion-3.5-fp8",
                filePath = "text_encoders/clip_l.safetensors",
                sizeHint = "246 MB"
            ),
            SdCppComponent(
                role = ComponentRole.CLIP_G,
                repoId = "Comfy-Org/stable-diffusion-3.5-fp8",
                filePath = "text_encoders/clip_g.safetensors", 
                sizeHint = "1.39 GB"
            ),
            SdCppComponent(
                role = ComponentRole.T5XXL,
                repoId = "Comfy-Org/stable-diffusion-3.5-fp8",
                filePath = "text_encoders/t5xxl_fp16.safetensors",
                sizeHint = "9.79 GB"
            )
        ),
        recommendedParams = SdCppRecommendedParams(
            cfgScale = 4.5f,
            samplingMethod = "euler",
            clipOnCpu = true,
            width = 1024,
            height = 1024
        )
    )
    put("stabilityai/stable-diffusion-3.5-large", sd35Setup)
    put("Comfy-Org/stable-diffusion-3.5-fp8", sd35Setup)
    
    // Anima - requires VAE and Qwen3-0.6B LLM
    val animaSetup = SdCppModelSetup(
        familyLabel = "Anima",
        description = "Chinese text-to-image model with excellent Asian character rendering.",
        components = listOf(
            SdCppComponent(
                role = ComponentRole.VAE,
                repoId = "circlestone-labs/Anima", 
                filePath = "split_files/vae/qwen_image_vae.safetensors",
                sizeHint = "167 MB"
            ),
            SdCppComponent(
                role = ComponentRole.LLM,
                repoId = "mradermacher/Qwen3-0.6B-Base-GGUF",
                filePath = "Qwen3-0.6B-Base.Q4_K_M.gguf",
                sizeHint = "378 MB"
            )
        ),
        recommendedParams = SdCppRecommendedParams(
            cfgScale = 6.0f,
            samplingMethod = "euler",
            offloadToCpu = true,
            diffusionFlashAttn = true
        )
    )
    put("circlestone-labs/Anima", animaSetup)
    put("Bedovyy/Anima-GGUF", animaSetup) 
    put("JusteLeo/Anima2-GGUF", animaSetup)
    
    // Chroma - requires FLUX VAE and T5-XXL
    val chromaSetup = SdCppModelSetup(
        familyLabel = "Chroma",
        description = "Creative image generation with unique style and excellent prompt following.",
        components = listOf(
            SdCppComponent(
                role = ComponentRole.VAE,
                repoId = "black-forest-labs/FLUX.1-dev",
                filePath = "ae.safetensors", 
                sizeHint = "335 MB"
            ),
            SdCppComponent(
                role = ComponentRole.T5XXL,
                repoId = "comfyanonymous/flux_text_encoders",
                filePath = "t5xxl_fp16.safetensors",
                sizeHint = "9.79 GB"
            )
        ),
        recommendedParams = SdCppRecommendedParams(
            cfgScale = 4.0f,
            samplingMethod = "euler",
            clipOnCpu = true
        )
    )
    put("lodestones/Chroma", chromaSetup)
    put("silveroxides/Chroma-GGUF", chromaSetup)
    
    // Chroma-Radiance - requires only T5-XXL (no VAE)
    val chromaRadianceSetup = SdCppModelSetup(
        familyLabel = "Chroma-Radiance",
        description = "Enhanced Chroma model with improved quality and detail.",
        components = listOf(
            SdCppComponent(
                role = ComponentRole.T5XXL,
                repoId = "comfyanonymous/flux_text_encoders",
                filePath = "t5xxl_fp16.safetensors",
                sizeHint = "9.79 GB"
            )
        ),
        recommendedParams = SdCppRecommendedParams(
            cfgScale = 4.0f,
            samplingMethod = "euler"
        )
    )
    put("lodestones/Chroma1-Radiance", chromaRadianceSetup)
    put("silveroxides/Chroma1-Radiance-GGUF", chromaRadianceSetup)
    
    // FLUX-Kontext - same as FLUX.1 setup
    val kontextSetup = SdCppModelSetup(
        familyLabel = "FLUX-Kontext",
        description = "FLUX.1-based image editing model. Requires reference images.",
        components = listOf(
            SdCppComponent(
                role = ComponentRole.VAE,
                repoId = "black-forest-labs/FLUX.1-dev",
                filePath = "ae.safetensors",
                sizeHint = "335 MB"
            ),
            SdCppComponent(
                role = ComponentRole.CLIP_L,
                repoId = "comfyanonymous/flux_text_encoders",
                filePath = "clip_l.safetensors",
                sizeHint = "246 MB"
            ),
            SdCppComponent(
                role = ComponentRole.T5XXL,
                repoId = "comfyanonymous/flux_text_encoders",
                filePath = "t5xxl_fp16.safetensors",
                sizeHint = "9.79 GB"
            )
        ),
        recommendedParams = SdCppRecommendedParams(
            cfgScale = 1.0f,
            samplingMethod = "euler",
            clipOnCpu = true
        )
    )
    put("black-forest-labs/FLUX.1-Kontext-dev", kontextSetup)
    put("QuantStack/FLUX.1-Kontext-dev-GGUF", kontextSetup)
    
    // Qwen-Image - requires VAE and Qwen2.5-VL-7B LLM
    val qwenImageSetup = SdCppModelSetup(
        familyLabel = "Qwen-Image",
        description = "Chinese text-to-image model with excellent Chinese text rendering.",
        components = listOf(
            SdCppComponent(
                role = ComponentRole.VAE,
                repoId = "Comfy-Org/Qwen-Image_ComfyUI",
                filePath = "split_files/vae/qwen_image_vae.safetensors",
                sizeHint = "167 MB"
            ),
            SdCppComponent(
                role = ComponentRole.LLM,
                repoId = "mradermacher/Qwen2.5-VL-7B-Instruct-GGUF",
                filePath = "Qwen2.5-VL-7B-Instruct.Q4_K_M.gguf",
                sizeHint = "4.4 GB"
            )
        ),
        recommendedParams = SdCppRecommendedParams(
            cfgScale = 2.5f,
            samplingMethod = "euler",
            offloadToCpu = true,
            diffusionFlashAttn = true,
            flowShift = 3.0f,
            width = 1024,
            height = 1024
        )
    )
    put("Comfy-Org/Qwen-Image_ComfyUI", qwenImageSetup)
    put("QuantStack/Qwen-Image-GGUF", qwenImageSetup)
    
    // Qwen-Image-Edit - requires VAE and Qwen2.5-VL-7B LLM
    val qwenImageEditSetup = SdCppModelSetup(
        familyLabel = "Qwen-Image-Edit",
        description = "Chinese image editing model. Supports reference images.",
        components = listOf(
            SdCppComponent(
                role = ComponentRole.VAE,
                repoId = "Comfy-Org/Qwen-Image_ComfyUI",
                filePath = "split_files/vae/qwen_image_vae.safetensors",
                sizeHint = "167 MB"
            ),
            SdCppComponent(
                role = ComponentRole.LLM,
                repoId = "mradermacher/Qwen2.5-VL-7B-Instruct-GGUF",
                filePath = "Qwen2.5-VL-7B-Instruct.Q4_K_M.gguf",
                sizeHint = "4.4 GB"
            )
        ),
        recommendedParams = SdCppRecommendedParams(
            cfgScale = 2.5f,
            samplingMethod = "euler",
            offloadToCpu = true,
            diffusionFlashAttn = true,
            flowShift = 3.0f
        )
    )
    put("Comfy-Org/Qwen-Image-Edit_ComfyUI", qwenImageEditSetup)
    put("QuantStack/Qwen-Image-Edit-GGUF", qwenImageEditSetup)
    put("QuantStack/Qwen-Image-Edit-2509-GGUF", qwenImageEditSetup)
    put("unsloth/Qwen-Image-Edit-2511-GGUF", qwenImageEditSetup)
    
    // Z-Image - requires FLUX VAE and Qwen3-4B LLM
    val zImageSetup = SdCppModelSetup(
        familyLabel = "Z-Image", 
        description = "Compact image generation model optimized for 4GB VRAM.",
        components = listOf(
            SdCppComponent(
                role = ComponentRole.VAE,
                repoId = "black-forest-labs/FLUX.1-schnell",
                filePath = "ae.safetensors",
                sizeHint = "335 MB"
            ),
            SdCppComponent(
                role = ComponentRole.LLM,
                repoId = "unsloth/Qwen3-4B-Instruct-2507-GGUF",
                filePath = "Qwen3-4B-Instruct-2507-Q4_K_M.gguf", 
                sizeHint = "2.4 GB"
            )
        ),
        recommendedParams = SdCppRecommendedParams(
            cfgScale = 1.0f,
            samplingMethod = "euler",
            offloadToCpu = true,
            diffusionFlashAttn = true,
            width = 1024,
            height = 512
        )
    )
    put("Comfy-Org/z_image_turbo", zImageSetup)
    put("leejet/Z-Image-Turbo-GGUF", zImageSetup)
    put("unsloth/Z-Image-GGUF", zImageSetup)
    
    // Ovis-Image - requires FLUX VAE and Ovis 2.5 LLM
    val ovisImageSetup = SdCppModelSetup(
        familyLabel = "Ovis-Image",
        description = "Multimodal image generation model.", 
        components = listOf(
            SdCppComponent(
                role = ComponentRole.VAE,
                repoId = "black-forest-labs/FLUX.1-schnell",
                filePath = "ae.safetensors",
                sizeHint = "335 MB" 
            ),
            SdCppComponent(
                role = ComponentRole.LLM,
                repoId = "Comfy-Org/Ovis-Image",
                filePath = "split_files/text_encoders/ovis_2.5.safetensors",
                sizeHint = "15 GB"
            )
        ),
        recommendedParams = SdCppRecommendedParams(
            cfgScale = 5.0f,
            samplingMethod = "euler",
            offloadToCpu = true,
            diffusionFlashAttn = true
        )
    )
    put("Comfy-Org/Ovis-Image", ovisImageSetup)
    put("leejet/Ovis-Image-7B-GGUF", ovisImageSetup)
    
    // Wan2.1 T2V - requires VAE and UMT5-XXL
    val wan21T2VSetup = SdCppModelSetup(
        familyLabel = "Wan2.1 T2V",
        description = "Text-to-video generation model. Requires significant VRAM.",
        components = listOf(
            SdCppComponent(
                role = ComponentRole.VAE,
                repoId = "Comfy-Org/Wan_2.1_ComfyUI_repackaged",
                filePath = "split_files/vae/wan_2.1_vae.safetensors",
                sizeHint = "167 MB"
            ),
            SdCppComponent(
                role = ComponentRole.UMT5XXL,
                repoId = "city96/umt5-xxl-encoder-gguf",
                filePath = "umt5-xxl-encoder-Q8_0.gguf",
                sizeHint = "4.9 GB"
            )
        ),
        recommendedParams = SdCppRecommendedParams(
            cfgScale = 6.0f,
            samplingMethod = "euler",
            diffusionFlashAttn = true,
            flowShift = 3.0f,
            width = 832,
            height = 480
        )
    )
    put("Comfy-Org/Wan_2.1_ComfyUI_repackaged", wan21T2VSetup)
    put("city96/Wan2.1-T2V-14B-gguf", wan21T2VSetup)
    put("calcuis/wan-1.3b-gguf", wan21T2VSetup)
    put("QuantStack/Wan2.1_14B_VACE-GGUF", wan21T2VSetup)
    
    // Wan2.1 I2V - same as T2V plus CLIP_VISION
    val wan21I2VSetup = SdCppModelSetup(
        familyLabel = "Wan2.1 I2V", 
        description = "Image-to-video generation model. Requires init images.",
        components = listOf(
            SdCppComponent(
                role = ComponentRole.VAE,
                repoId = "Comfy-Org/Wan_2.1_ComfyUI_repackaged",
                filePath = "split_files/vae/wan_2.1_vae.safetensors",
                sizeHint = "167 MB"
            ),
            SdCppComponent(
                role = ComponentRole.UMT5XXL,
                repoId = "city96/umt5-xxl-encoder-gguf", 
                filePath = "umt5-xxl-encoder-Q8_0.gguf",
                sizeHint = "4.9 GB"
            ),
            SdCppComponent(
                role = ComponentRole.CLIP_VISION,
                repoId = "Comfy-Org/Wan_2.1_ComfyUI_repackaged",
                filePath = "split_files/clip_vision/clip_vision_h.safetensors",
                sizeHint = "2.5 GB"
            )
        ),
        recommendedParams = SdCppRecommendedParams(
            cfgScale = 6.0f,
            samplingMethod = "euler", 
            diffusionFlashAttn = true,
            flowShift = 3.0f,
            width = 480,
            height = 832
        )
    )
    put("city96/Wan2.1-I2V-14B-480P-gguf", wan21I2VSetup)
    put("city96/Wan2.1-I2V-14B-720P-gguf", wan21I2VSetup)
    put("city96/Wan2.1-FLF2V-14B-720P-gguf", wan21I2VSetup)
    
    // Wan2.2 models - different VAE
    val wan22Setup = SdCppModelSetup(
        familyLabel = "Wan2.2",
        description = "Next-generation video model with improved quality.",
        components = listOf(
            SdCppComponent(
                role = ComponentRole.VAE,
                repoId = "Comfy-Org/Wan_2.2_ComfyUI_Repackaged",
                filePath = "split_files/vae/wan2.2_vae.safetensors", 
                sizeHint = "167 MB"
            ),
            SdCppComponent(
                role = ComponentRole.UMT5XXL,
                repoId = "city96/umt5-xxl-encoder-gguf",
                filePath = "umt5-xxl-encoder-Q8_0.gguf",
                sizeHint = "4.9 GB"
            )
        ),
        recommendedParams = SdCppRecommendedParams(
            cfgScale = 6.0f,
            samplingMethod = "euler",
            diffusionFlashAttn = true,
            flowShift = 3.0f,
            width = 480,
            height = 832
        )
    )
    put("Comfy-Org/Wan_2.2_ComfyUI_Repackaged", wan22Setup)
    put("QuantStack/Wan2.2-TI2V-5B-GGUF", wan22Setup)
    put("QuantStack/Wan2.2-T2V-A14B-GGUF", wan22Setup)
    put("QuantStack/Wan2.2-I2V-A14B-GGUF", wan22Setup)
    
    // Self-contained models (no auxiliary components needed)
    val selfContainedSetup = SdCppModelSetup(
        familyLabel = "Self-Contained",
        description = "Complete model checkpoint with no additional downloads required.",
        components = emptyList(),
        selfContained = true
    )
    
    // SD 1.x models
    put("CompVis/stable-diffusion-v-1-4-original", selfContainedSetup.copy(familyLabel = "Stable Diffusion v1.4"))
    put("runwayml/stable-diffusion-v1-5", selfContainedSetup.copy(familyLabel = "Stable Diffusion v1.5"))
    put("stabilityai/sd-turbo", selfContainedSetup.copy(familyLabel = "SD-Turbo"))
    
    // SD 2.x models  
    put("stabilityai/stable-diffusion-2-1", selfContainedSetup.copy(familyLabel = "Stable Diffusion v2.1"))
    
    // SDXL models
    put("stabilityai/stable-diffusion-xl-base-1.0", selfContainedSetup.copy(familyLabel = "SDXL Base 1.0"))
    put("stabilityai/sdxl-turbo", selfContainedSetup.copy(familyLabel = "SDXL-Turbo"))
    
    // SD3 models
    put("stabilityai/stable-diffusion-3-medium", selfContainedSetup.copy(familyLabel = "Stable Diffusion 3 Medium"))
    
    // Distilled models
    put("segmind/SSD-1B", selfContainedSetup.copy(familyLabel = "SSD-1B (Distilled SDXL)"))
    put("segmind/Segmind-Vega", selfContainedSetup.copy(familyLabel = "Segmind-Vega"))
    put("nota-ai/bk-sdm-v2-tiny", selfContainedSetup.copy(familyLabel = "BK-SDM v2 Tiny"))
    put("segmind/tiny-sd", selfContainedSetup.copy(familyLabel = "Tiny-SD"))
    put("segmind/portrait-finetuned", selfContainedSetup.copy(familyLabel = "Portrait Finetuned"))
    put("nota-ai/bk-sdm-tiny", selfContainedSetup.copy(familyLabel = "BK-SDM Tiny"))
    
    // PhotoMaker models
    put("bssrdf/PhotoMaker", selfContainedSetup.copy(familyLabel = "PhotoMaker"))
    put("bssrdf/PhotoMakerV2", selfContainedSetup.copy(familyLabel = "PhotoMaker v2"))
    
    // LCM models
    put("latent-consistency/lcm-lora-sdv1-5", selfContainedSetup.copy(familyLabel = "LCM-LoRA SD1.5"))
    
    // TAESD models
    put("madebyollin/taesd", selfContainedSetup.copy(familyLabel = "TAESD (Fast VAE)"))
}

/**
 * Get the setup requirements for a model by its HuggingFace repo ID.
 */
fun getModelSetup(repoId: String): SdCppModelSetup? = SETUP_REGISTRY[repoId]

/**
 * Get all registered model setups.
 */
fun getAllModelSetups(): Map<String, SdCppModelSetup> = SETUP_REGISTRY