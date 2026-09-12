package com.debanshu777.caraml.core.data.inference

import com.debanshu777.caraml.core.recommendation.DiffusionExecutionConfig
import com.debanshu777.caraml.core.recommendation.DiffusionMode
import com.debanshu777.diffusionrunner.ImageGenParams
import com.debanshu777.diffusionrunner.VideoGenParams

internal class AdmittedDiffusionExecutionState {
    private var current: DiffusionExecutionConfig? = null

    fun publish(config: DiffusionExecutionConfig) {
        current = config
    }

    fun clear() {
        current = null
    }

    fun applyTo(params: ImageGenParams): ImageGenParams? = current?.let { config ->
        if (config.mode != DiffusionMode.IMAGE) null
        else params.copy(width = config.width, height = config.height, steps = config.steps)
    } ?: params.takeIf { current == null }

    fun applyTo(params: VideoGenParams): VideoGenParams? = current?.let { config ->
        if (config.mode != DiffusionMode.VIDEO) null
        else params.copy(
            width = config.width,
            height = config.height,
            videoFrames = config.frameCount,
            steps = config.steps,
        )
    } ?: params.takeIf { current == null }
}
