package com.debanshu777.diffusionrunner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

suspend fun DiffusionRunner.generateImage(params: ImageGenParams): Result<ByteArray> =
    withContext(Dispatchers.Default) {
        try {
            validateImageGenParams(params)
            Result.success(
                txt2Img(params) ?: throw IllegalStateException("Image generation failed")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

suspend fun DiffusionRunner.generateVideo(params: VideoGenParams): Result<VideoGenResult> =
    withContext(Dispatchers.Default) {
        try {
            validateVideoGenParams(params)
            Result.success(
                videoGen(params) ?: throw IllegalStateException("Video generation failed")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
