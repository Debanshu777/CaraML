package com.debanshu777.diffusionrunner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun DiffusionRunner.generateImage(params: ImageGenParams): Result<ByteArray> =
    withContext(Dispatchers.Default) {
        runCatching {
            validateImageGenParams(params)
            txt2Img(params) ?: throw IllegalStateException("Image generation failed")
        }
    }

suspend fun DiffusionRunner.generateVideo(params: VideoGenParams): Result<List<ByteArray>> =
    withContext(Dispatchers.Default) {
        runCatching {
            videoGen(params) ?: throw IllegalStateException("Video generation failed")
        }
    }