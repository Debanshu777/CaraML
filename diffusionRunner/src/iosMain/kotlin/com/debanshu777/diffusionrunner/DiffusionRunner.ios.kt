package com.debanshu777.diffusionrunner

import com.debanshu777.diffusionrunner.cpp.DiffusionModelConfigFFI
import com.debanshu777.diffusionrunner.cpp.ImageGenConfigFFI
import com.debanshu777.diffusionrunner.cpp.diffusion_runner_ios_free_png
import com.debanshu777.diffusionrunner.cpp.diffusion_runner_ios_init
import com.debanshu777.diffusionrunner.cpp.diffusion_runner_ios_load_model
import com.debanshu777.diffusionrunner.cpp.diffusion_runner_ios_release
import com.debanshu777.diffusionrunner.cpp.diffusion_runner_ios_txt2img
import com.debanshu777.diffusionrunner.cpp.diffusion_runner_ios_free_result
import kotlinx.cinterop.*

@OptIn(ExperimentalForeignApi::class)
actual class DiffusionRunner {
    private var handle: Long = 0L

    actual fun initialize(nativeLibDir: String) {
        diffusion_runner_ios_init()
    }

    actual fun loadModel(config: DiffusionModelConfig): Boolean {
        validateModelConfig(config)

        val ffiConfig = memScoped {
            cValue<DiffusionModelConfigFFI> {
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
                wtype = config.wtype
                flow_shift = config.flowShift
                flow_shift_is_set = if (config.flowShift.isFinite()) 1 else 0
                n_threads = config.nThreads
            }
        }

        handle = diffusion_runner_ios_load_model(ffiConfig)
        return handle != 0L
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
                    val bytes = resultStruct.data!!.readBytes(resultStruct.size)
                    diffusion_runner_ios_free_png(resultStruct.data)
                    diffusion_runner_ios_free_result(result)
                    bytes
                }
            }
        }
    }

    actual fun videoGen(params: VideoGenParams): List<ByteArray>? {
        if (handle == 0L) return null

        // For now, implement video generation as multiple image generations
        // This is a simplified implementation - full video generation would require
        // additional FFI functions similar to the JNI implementation

        val frames = mutableListOf<ByteArray>()
        for (frame in 0 until params.videoFrames) {
            val imageParams = ImageGenParams(
                prompt = params.prompt,
                negativePrompt = params.negativePrompt,
                width = params.width,
                height = params.height,
                steps = params.steps,
                cfgScale = params.cfgScale,
                seed = if (params.seed == -1L) -1L else params.seed + frame,
                sampleMethod = params.sampleMethod,
                loraPaths = params.loraPaths,
                loraStrengths = params.loraStrengths
            )

            val frameData = txt2Img(imageParams)
            if (frameData != null) {
                frames.add(frameData)
            } else {
                return null // Failed to generate a frame
            }
        }

        return frames
    }

    actual fun release() {
        if (handle != 0L) {
            diffusion_runner_ios_release(handle)
            handle = 0L
        }
    }
}