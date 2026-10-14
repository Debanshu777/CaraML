package com.debanshu777.huggingfacemanager.sdcpp

import com.debanshu777.huggingfacemanager.model.ListModelsResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SdCppCuratedCatalogRoot(
    @SerialName("version")
    val version: Int = 1,
    @SerialName("image")
    val image: List<SdCppCuratedEntry> = emptyList(),
    @SerialName("video")
    val video: List<SdCppCuratedEntry> = emptyList(),
)

@Serializable
data class SdCppCuratedEntry(
    @SerialName("repoId")
    val repoId: String,
    @SerialName("label")
    val label: String,
    @SerialName("family")
    val family: String,
    @SerialName("kind")
    val kind: String,
    @SerialName("docRef")
    val docRef: String,
)

val SD_CPP_CURATED_REPO_ID_REGEX: Regex =
    Regex("^[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+$")

private val sdCppCatalogJson = Json {
    ignoreUnknownKeys = true
    isLenient = false
}

internal fun sdCppCuratedCatalogBundledJson(): String = SD_CPP_CATALOG_JSON_BUNDLED

fun loadSdCppCuratedCatalog(json: String = sdCppCuratedCatalogBundledJson()): SdCppCuratedCatalogRoot =
    sdCppCatalogJson.decodeFromString(SdCppCuratedCatalogRoot.serializer(), json)

fun SdCppCuratedEntry.toListModel(isVideoSection: Boolean): ListModelsResponse.Model {
    val taskTag = when {
        isVideoSection -> "text-to-video"
        kind == "edit" -> "image-to-image"
        else -> "text-to-image"
    }
    val author = repoId.substringBefore('/', missingDelimiterValue = repoId)
    return ListModelsResponse.Model(
        id = repoId,
        author = author,
        pipelineTag = "$family • $taskTag",
    )
}

fun List<SdCppCuratedEntry>.toListModelsResponse(): ListModelsResponse {
    val models = map { it.toListModel(isVideoSection = false) }
    return ListModelsResponse(
        models = models,
        numItemsPerPage = models.size,
        numTotalItems = models.size,
        pageIndex = 0,
    )
}

fun List<SdCppCuratedEntry>.toVideoListModelsResponse(): ListModelsResponse {
    val models = map { it.toListModel(isVideoSection = true) }
    return ListModelsResponse(
        models = models,
        numItemsPerPage = models.size,
        numTotalItems = models.size,
        pageIndex = 0,
    )
}

private val SD_CPP_CATALOG_JSON_BUNDLED = """
{"version":1,"image":[
{"repoId":"CompVis/stable-diffusion-v-1-4-original","label":"Stable Diffusion v1.4","family":"SD1.x","kind":"checkpoint","docRef":"sd.md"},
{"repoId":"runwayml/stable-diffusion-v-1-5","label":"Stable Diffusion v1.5","family":"SD1.x","kind":"checkpoint","docRef":"sd.md"},
{"repoId":"stabilityai/stable-diffusion-2-1","label":"Stable Diffusion v2.1","family":"SD2.x","kind":"checkpoint","docRef":"sd.md"},
{"repoId":"stabilityai/sd-turbo","label":"SD-Turbo","family":"SD1.x","kind":"checkpoint","docRef":"README.md"},
{"repoId":"stabilityai/stable-diffusion-xl-base-1.0","label":"SDXL Base 1.0","family":"SDXL","kind":"checkpoint","docRef":"sd.md"},
{"repoId":"stabilityai/sdxl-turbo","label":"SDXL-Turbo","family":"SDXL","kind":"checkpoint","docRef":"README.md"},
{"repoId":"stabilityai/stable-diffusion-3-medium","label":"SD3 Medium (2B / 3B)","family":"SD3","kind":"checkpoint","docRef":"sd.md"},
{"repoId":"stabilityai/stable-diffusion-3.5-large","label":"SD3.5 Large","family":"SD3.5","kind":"checkpoint","docRef":"sd3.md"},
{"repoId":"Comfy-Org/stable-diffusion-3.5-fp8","label":"SD3.5 FP8 (text encoders bundle)","family":"SD3.5","kind":"checkpoint","docRef":"sd3.md"},
{"repoId":"black-forest-labs/FLUX.1-dev","label":"FLUX.1-dev (safetensors)","family":"FLUX.1","kind":"checkpoint","docRef":"flux.md"},
{"repoId":"black-forest-labs/FLUX.1-schnell","label":"FLUX.1-schnell (safetensors)","family":"FLUX.1","kind":"checkpoint","docRef":"flux.md"},
{"repoId":"leejet/FLUX.1-dev-gguf","label":"FLUX.1-dev (GGUF)","family":"FLUX.1","kind":"checkpoint","docRef":"flux.md"},
{"repoId":"leejet/FLUX.1-schnell-gguf","label":"FLUX.1-schnell (GGUF)","family":"FLUX.1","kind":"checkpoint","docRef":"flux.md"},
{"repoId":"black-forest-labs/FLUX.2-dev","label":"FLUX.2-dev","family":"FLUX.2","kind":"checkpoint","docRef":"flux2.md"},
{"repoId":"city96/FLUX.2-dev-gguf","label":"FLUX.2-dev (GGUF)","family":"FLUX.2","kind":"checkpoint","docRef":"flux2.md"},
{"repoId":"black-forest-labs/FLUX.2-klein-4B","label":"FLUX.2-klein 4B","family":"FLUX.2","kind":"checkpoint","docRef":"flux2.md"},
{"repoId":"leejet/FLUX.2-klein-4B-GGUF","label":"FLUX.2-klein 4B (GGUF)","family":"FLUX.2","kind":"checkpoint","docRef":"flux2.md"},
{"repoId":"black-forest-labs/FLUX.2-klein-base-4B","label":"FLUX.2-klein base 4B","family":"FLUX.2","kind":"checkpoint","docRef":"flux2.md"},
{"repoId":"leejet/FLUX.2-klein-base-4B-GGUF","label":"FLUX.2-klein base 4B (GGUF)","family":"FLUX.2","kind":"checkpoint","docRef":"flux2.md"},
{"repoId":"black-forest-labs/FLUX.2-klein-9B","label":"FLUX.2-klein 9B","family":"FLUX.2","kind":"checkpoint","docRef":"flux2.md"},
{"repoId":"leejet/FLUX.2-klein-9B-GGUF","label":"FLUX.2-klein 9B (GGUF)","family":"FLUX.2","kind":"checkpoint","docRef":"flux2.md"},
{"repoId":"black-forest-labs/FLUX.2-klein-base-9B","label":"FLUX.2-klein base 9B","family":"FLUX.2","kind":"checkpoint","docRef":"flux2.md"},
{"repoId":"leejet/FLUX.2-klein-base-9B-GGUF","label":"FLUX.2-klein base 9B (GGUF)","family":"FLUX.2","kind":"checkpoint","docRef":"flux2.md"},
{"repoId":"lodestones/Chroma","label":"Chroma (safetensors)","family":"Chroma","kind":"checkpoint","docRef":"chroma.md"},
{"repoId":"silveroxides/Chroma-GGUF","label":"Chroma (GGUF)","family":"Chroma","kind":"checkpoint","docRef":"chroma.md"},
{"repoId":"lodestones/Chroma1-Radiance","label":"Chroma1-Radiance (safetensors)","family":"Chroma","kind":"checkpoint","docRef":"chroma_radiance.md"},
{"repoId":"silveroxides/Chroma1-Radiance-GGUF","label":"Chroma1-Radiance (GGUF)","family":"Chroma","kind":"checkpoint","docRef":"chroma_radiance.md"},
{"repoId":"Comfy-Org/Qwen-Image_ComfyUI","label":"Qwen-Image (Comfy repack)","family":"Qwen-Image","kind":"checkpoint","docRef":"qwen_image.md"},
{"repoId":"QuantStack/Qwen-Image-GGUF","label":"Qwen-Image (GGUF)","family":"Qwen-Image","kind":"checkpoint","docRef":"qwen_image.md"},
{"repoId":"Comfy-Org/z_image_turbo","label":"Z-Image Turbo (Comfy repack)","family":"Z-Image","kind":"checkpoint","docRef":"z_image.md"},
{"repoId":"leejet/Z-Image-Turbo-GGUF","label":"Z-Image Turbo (GGUF)","family":"Z-Image","kind":"checkpoint","docRef":"z_image.md"},
{"repoId":"unsloth/Z-Image-GGUF","label":"Z-Image (GGUF)","family":"Z-Image","kind":"checkpoint","docRef":"z_image.md"},
{"repoId":"Comfy-Org/Ovis-Image","label":"Ovis-Image (Comfy repack)","family":"Ovis-Image","kind":"checkpoint","docRef":"ovis_image.md"},
{"repoId":"leejet/Ovis-Image-7B-GGUF","label":"Ovis-Image 7B (GGUF)","family":"Ovis-Image","kind":"checkpoint","docRef":"ovis_image.md"},
{"repoId":"circlestone-labs/Anima","label":"Anima (safetensors)","family":"Anima","kind":"checkpoint","docRef":"anima.md"},
{"repoId":"Bedovyy/Anima-GGUF","label":"Anima (GGUF)","family":"Anima","kind":"checkpoint","docRef":"anima.md"},
{"repoId":"JusteLeo/Anima2-GGUF","label":"Anima2 (GGUF)","family":"Anima","kind":"checkpoint","docRef":"anima.md"},
{"repoId":"black-forest-labs/FLUX.1-Kontext-dev","label":"FLUX.1-Kontext-dev","family":"FLUX-Kontext","kind":"edit","docRef":"kontext.md"},
{"repoId":"QuantStack/FLUX.1-Kontext-dev-GGUF","label":"FLUX.1-Kontext-dev (GGUF)","family":"FLUX-Kontext","kind":"edit","docRef":"kontext.md"},
{"repoId":"Comfy-Org/Qwen-Image-Edit_ComfyUI","label":"Qwen-Image-Edit (Comfy repack)","family":"Qwen-Image-Edit","kind":"edit","docRef":"qwen_image_edit.md"},
{"repoId":"QuantStack/Qwen-Image-Edit-GGUF","label":"Qwen-Image-Edit (GGUF)","family":"Qwen-Image-Edit","kind":"edit","docRef":"qwen_image_edit.md"},
{"repoId":"QuantStack/Qwen-Image-Edit-2509-GGUF","label":"Qwen-Image-Edit 2509 (GGUF)","family":"Qwen-Image-Edit","kind":"edit","docRef":"qwen_image_edit.md"},
{"repoId":"unsloth/Qwen-Image-Edit-2511-GGUF","label":"Qwen-Image-Edit 2511 (GGUF)","family":"Qwen-Image-Edit","kind":"edit","docRef":"qwen_image_edit.md"},
{"repoId":"segmind/SSD-1B","label":"SSD-1B (distilled SDXL)","family":"Distilled","kind":"distilled","docRef":"distilled_sd.md"},
{"repoId":"segmind/Segmind-Vega","label":"Segmind-Vega (distilled)","family":"Distilled","kind":"distilled","docRef":"distilled_sd.md"},
{"repoId":"nota-ai/bk-sdm-v2-tiny","label":"BK-SDM v2 tiny","family":"Distilled","kind":"distilled","docRef":"distilled_sd.md"},
{"repoId":"segmind/tiny-sd","label":"Tiny-SD","family":"Distilled","kind":"distilled","docRef":"distilled_sd.md"},
{"repoId":"segmind/portrait-finetuned","label":"Portrait finetuned (tiny)","family":"Distilled","kind":"distilled","docRef":"distilled_sd.md"},
{"repoId":"nota-ai/bk-sdm-tiny","label":"BK-SDM tiny","family":"Distilled","kind":"distilled","docRef":"distilled_sd.md"},
{"repoId":"bssrdf/PhotoMaker","label":"PhotoMaker (SDXL)","family":"PhotoMaker","kind":"aux","docRef":"photo_maker.md"},
{"repoId":"bssrdf/PhotoMakerV2","label":"PhotoMaker v2 (SDXL)","family":"PhotoMaker","kind":"aux","docRef":"photo_maker.md"},
{"repoId":"latent-consistency/lcm-lora-sdv1-5","label":"LCM-LoRA SD1.5","family":"LCM","kind":"aux","docRef":"lcm.md"},
{"repoId":"madebyollin/taesd","label":"TAESD (fast VAE decode)","family":"TAESD","kind":"aux","docRef":"taesd.md"}
],"video":[
{"repoId":"Comfy-Org/Wan_2.1_ComfyUI_repackaged","label":"Wan 2.1 (Comfy repackaged)","family":"Wan2.1","kind":"checkpoint","docRef":"wan.md"},
{"repoId":"city96/Wan2.1-T2V-14B-gguf","label":"Wan2.1 T2V 14B (GGUF)","family":"Wan2.1","kind":"checkpoint","docRef":"wan.md"},
{"repoId":"city96/Wan2.1-I2V-14B-480P-gguf","label":"Wan2.1 I2V 14B 480p (GGUF)","family":"Wan2.1","kind":"checkpoint","docRef":"wan.md"},
{"repoId":"city96/Wan2.1-I2V-14B-720P-gguf","label":"Wan2.1 I2V 14B 720p (GGUF)","family":"Wan2.1","kind":"checkpoint","docRef":"wan.md"},
{"repoId":"city96/Wan2.1-FLF2V-14B-720P-gguf","label":"Wan2.1 FLF2V 14B 720p (GGUF)","family":"Wan2.1","kind":"checkpoint","docRef":"wan.md"},
{"repoId":"calcuis/wan-1.3b-gguf","label":"Wan 1.3B (GGUF)","family":"Wan2.1","kind":"checkpoint","docRef":"wan.md"},
{"repoId":"QuantStack/Wan2.1_14B_VACE-GGUF","label":"Wan2.1 VACE 14B (GGUF)","family":"Wan2.1","kind":"checkpoint","docRef":"wan.md"},
{"repoId":"Comfy-Org/Wan_2.2_ComfyUI_Repackaged","label":"Wan 2.2 (Comfy repackaged)","family":"Wan2.2","kind":"checkpoint","docRef":"wan.md"},
{"repoId":"QuantStack/Wan2.2-TI2V-5B-GGUF","label":"Wan2.2 TI2V 5B (GGUF)","family":"Wan2.2","kind":"checkpoint","docRef":"wan.md"},
{"repoId":"QuantStack/Wan2.2-T2V-A14B-GGUF","label":"Wan2.2 T2V A14B (GGUF)","family":"Wan2.2","kind":"checkpoint","docRef":"wan.md"},
{"repoId":"QuantStack/Wan2.2-I2V-A14B-GGUF","label":"Wan2.2 I2V A14B (GGUF)","family":"Wan2.2","kind":"checkpoint","docRef":"wan.md"},
{"repoId":"city96/umt5-xxl-encoder-gguf","label":"UMT5-XXL encoder (GGUF, Wan text encoder)","family":"Wan2.1","kind":"aux","docRef":"wan.md"}
]}
"""
