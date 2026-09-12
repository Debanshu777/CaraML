package com.debanshu777.diffusionrunner

import com.debanshu777.diffusionrunner.cpp.DiffusionMetadataResultFFI
import com.debanshu777.diffusionrunner.cpp.DiffusionModelConfigFFI
import com.debanshu777.diffusionrunner.cpp.ImageGenConfigFFI
import com.debanshu777.diffusionrunner.cpp.diffusion_runner_ios_free_png
import com.debanshu777.diffusionrunner.cpp.diffusion_runner_ios_cancel_generation
import com.debanshu777.diffusionrunner.cpp.diffusion_runner_ios_get_metadata
import com.debanshu777.diffusionrunner.cpp.diffusion_runner_ios_backend_capabilities
import com.debanshu777.diffusionrunner.cpp.diffusion_runner_ios_engine_version
import com.debanshu777.diffusionrunner.cpp.diffusion_runner_ios_init
import com.debanshu777.diffusionrunner.cpp.diffusion_runner_ios_load_model
import com.debanshu777.diffusionrunner.cpp.diffusion_runner_ios_preflight
import com.debanshu777.diffusionrunner.cpp.diffusion_runner_ios_probe_model_features
import com.debanshu777.diffusionrunner.cpp.diffusion_runner_ios_release
import com.debanshu777.diffusionrunner.cpp.diffusion_runner_ios_txt2img
import com.debanshu777.diffusionrunner.cpp.diffusion_runner_ios_free_result
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cValue
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value

@OptIn(ExperimentalForeignApi::class)
actual class DiffusionRunner {
    private var handle: Long = 0L

    actual fun initialize(nativeLibDir: String) {
        diffusion_runner_ios_init()
    }

    actual fun loadModel(config: DiffusionModelConfig): Boolean {
        validateModelConfig(config)

        handle = memScoped {
            val ffiConfig = cValue<DiffusionModelConfigFFI> {
                model_path = config.modelPath.cstr.ptr
                vae_path = config.vaePath.cstr.ptr
                llm_path = config.llmPath.cstr.ptr
                clip_l_path = config.clipLPath.cstr.ptr
                clip_g_path = config.clipGPath.cstr.ptr
                t5xxl_path = config.t5xxlPath.cstr.ptr
                offload_to_cpu = if (config.offloadToCpu) 1 else 0
                keep_clip_on_cpu = if (config.keepClipOnCpu) 1 else 0
                keep_vae_on_cpu = if (config.keepVaeOnCpu) 1 else 0
                diffusion_flash_attn = if (config.diffusionFlashAttn) 1 else 0
                enable_mmap = if (config.enableMmap) 1 else 0
                diffusion_conv_direct = if (config.diffusionConvDirect) 1 else 0
                free_params_immediately = if (config.freeParamsImmediately) 1 else 0
                wtype = config.wtype
                flow_shift = config.flowShift
                flow_shift_is_set = if (config.flowShift.isFinite()) 1 else 0
                n_threads = config.nThreads
                prediction = config.prediction
                taesd_path = config.taesdPath.cstr.ptr
                vae_tiling = if (config.vaeTiling) 1 else 0
                max_vram = config.maxVram.cstr.ptr
                stream_layers = if (config.streamLayers) 1 else 0
                auto_fit = if (config.autoFit) 1 else 0
            }
            diffusion_runner_ios_load_model(ffiConfig)
        }
        return handle != 0L
    }

    actual fun preflightModel(config: DiffusionModelConfig): DiffusionPreflightResult =
        runDiffusionPreflight(config) { safeConfig ->
            memScoped {
                val capacity = DIFFUSION_PREFLIGHT_HEADER_FIELDS +
                    DIFFUSION_PREFLIGHT_MAX_COMPONENTS * DIFFUSION_PREFLIGHT_COMPONENT_FIELDS +
                    DIFFUSION_PREFLIGHT_MAX_BACKENDS * DIFFUSION_PREFLIGHT_BACKEND_FIELDS
                val output = allocArray<LongVar>(capacity)
                val ffiConfig = toFfiConfig(safeConfig)
                val count = diffusion_runner_ios_preflight(ffiConfig, output, capacity)
                if (count !in DIFFUSION_PREFLIGHT_HEADER_FIELDS..capacity) null
                else LongArray(count) { output[it] }
            }
        }

    actual fun backendCapabilities(): List<DiffusionBackendCapability> = memScoped {
        val capacity = DIFFUSION_BACKEND_HEADER_FIELDS +
            DIFFUSION_BACKEND_MAX_DEVICES * DIFFUSION_BACKEND_DEVICE_FIELDS
        val output = allocArray<LongVar>(capacity)
        val count = diffusion_runner_ios_backend_capabilities(output, capacity)
        decodeDiffusionBackendCapabilities(
            if (count in DIFFUSION_BACKEND_HEADER_FIELDS..capacity) LongArray(count) { output[it] }
            else null,
        )
    }

    actual fun probeModelFeatures(
        architecture: String,
        quantization: String?,
        mode: DiffusionGenerationMode,
    ): DiffusionModelFeatureSupport {
        val support = probeDiffusionModelFeatures(
            architecture = architecture,
            quantization = quantization,
            mode = mode,
            nativeProbe = { nativeArchitecture, nativeQuantization, nativeMode ->
                memScoped {
                    val output = allocArray<LongVar>(3)
                    val count = diffusion_runner_ios_probe_model_features(
                        nativeArchitecture,
                        nativeQuantization,
                        nativeMode,
                        output,
                        3,
                    )
                    if (count == 3) LongArray(3) { output[it] } else null
                }
            },
            nativeVersion = { diffusion_runner_ios_engine_version()?.toKString() },
        )
        return if (mode == DiffusionGenerationMode.VIDEO &&
            support.mode == DiffusionFeatureState.SUPPORTED
        ) {
            support.copy(mode = DiffusionFeatureState.UNSUPPORTED)
        } else {
            support
        }
    }

    actual fun txt2Img(params: ImageGenParams): ByteArray? {
        if (handle == 0L) return null
        validateImageGenParams(params)

        return memScoped {
            val ffiConfig = cValue<ImageGenConfigFFI> {
                prompt = params.prompt.cstr.ptr
                negative_prompt = params.negativePrompt.cstr.ptr
                width = params.width
                height = params.height
                steps = params.steps
                cfg_scale = params.cfgScale
                seed = params.seed
                sample_method = params.sampleMethod.value
            }

            // Handle LoRA paths and strengths
            val loraPaths: CPointer<CPointerVar<ByteVar>>? = if (params.loraPaths.isNotEmpty()) {
                allocArray<CPointerVar<ByteVar>>(params.loraPaths.size) { index ->
                    value = params.loraPaths[index].cstr.ptr
                }
            } else null

            val loraStrengths: CPointer<FloatVar>? = if (params.loraStrengths.isNotEmpty()) {
                allocArray<FloatVar>(params.loraStrengths.size) { index ->
                    value = params.loraStrengths[index]
                }
            } else null

            val result = diffusion_runner_ios_txt2img(
                handle, ffiConfig, loraPaths, loraStrengths, params.loraPaths.size
            )

            if (result == null) {
                null
            } else {
                val resultStruct = result.pointed
                if (resultStruct.data == null || resultStruct.size <= 0) {
                    diffusion_runner_ios_free_result(result)
                    null
                } else {
                    try {
                        resultStruct.data!!.readBytes(resultStruct.size)
                    } finally {
                        diffusion_runner_ios_free_png(resultStruct.data)
                        diffusion_runner_ios_free_result(result)
                    }
                }
            }
        }
    }

    actual fun videoGen(params: VideoGenParams): List<ByteArray>? {
        if (handle == 0L) return null
        validateVideoGenParams(params)
        return null
    }

    actual fun cancelGeneration(): Boolean =
        handle != 0L && diffusion_runner_ios_cancel_generation(handle) != 0

    actual fun supportsVideoGeneration(): Boolean = false

    actual fun release() {
        if (handle != 0L) {
            diffusion_runner_ios_release(handle)
            handle = 0L
        }
    }

    // iOS doesn't poll native progress (generation returns all at once via the same thread)
    actual fun getStepProgress(): IntArray = intArrayOf(0, 0)

    actual fun getDiffusionModelMetadata(modelPath: String): DiffusionModelMetadata? {
        val result = diffusion_runner_ios_get_metadata(modelPath)
        return result.useContents {
            if (success == 0) return@useContents null
            val arch = architecture.toKString().takeIf { it.isNotEmpty() }
                ?: return@useContents null
            val quant = dominant_quant.toKString().takeIf { it.isNotEmpty() }
            DiffusionModelMetadata(
                architecture = arch,
                dominantQuantType = quant,
                estimatedRamBytes = estimated_ram,
            )
        }
    }

    private fun MemScope.toFfiConfig(config: DiffusionModelConfig): CValue<DiffusionModelConfigFFI> =
        cValue {
            model_path = config.modelPath.cstr.ptr
            vae_path = config.vaePath.cstr.ptr
            llm_path = config.llmPath.cstr.ptr
            clip_l_path = config.clipLPath.cstr.ptr
            clip_g_path = config.clipGPath.cstr.ptr
            t5xxl_path = config.t5xxlPath.cstr.ptr
            offload_to_cpu = if (config.offloadToCpu) 1 else 0
            keep_clip_on_cpu = if (config.keepClipOnCpu) 1 else 0
            keep_vae_on_cpu = if (config.keepVaeOnCpu) 1 else 0
            diffusion_flash_attn = if (config.diffusionFlashAttn) 1 else 0
            enable_mmap = if (config.enableMmap) 1 else 0
            diffusion_conv_direct = if (config.diffusionConvDirect) 1 else 0
            free_params_immediately = if (config.freeParamsImmediately) 1 else 0
            wtype = config.wtype
            flow_shift = config.flowShift
            flow_shift_is_set = if (config.flowShift.isFinite()) 1 else 0
            n_threads = config.nThreads
            prediction = config.prediction
            taesd_path = config.taesdPath.cstr.ptr
            vae_tiling = if (config.vaeTiling) 1 else 0
            max_vram = config.maxVram.cstr.ptr
            stream_layers = if (config.streamLayers) 1 else 0
            auto_fit = if (config.autoFit) 1 else 0
        }
}
