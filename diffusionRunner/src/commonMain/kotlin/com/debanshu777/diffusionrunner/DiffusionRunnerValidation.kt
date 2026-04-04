package com.debanshu777.diffusionrunner

internal fun validateModelConfig(config: DiffusionModelConfig) {
    require(config.modelPath.isNotBlank()) { "modelPath must not be blank" }
}

internal fun validateImageGenParams(params: ImageGenParams) {
    require(params.prompt.isNotBlank()) { "prompt must not be blank" }
    require(params.width > 0 && params.width % 8 == 0) { "width must be positive and divisible by 8" }
    require(params.height > 0 && params.height % 8 == 0) { "height must be positive and divisible by 8" }
    require(params.steps > 0) { "steps must be positive" }
    require(params.loraPaths.size == params.loraStrengths.size) { "loraPaths and loraStrengths must have same size" }
}