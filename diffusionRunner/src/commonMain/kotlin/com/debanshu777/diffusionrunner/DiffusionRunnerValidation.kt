package com.debanshu777.diffusionrunner

private const val MAX_PROMPT_LENGTH = 16_384
private const val MAX_IMAGE_DIMENSION = 4_096
private const val MAX_IMAGE_PIXELS = 16_777_216L
private const val MAX_VIDEO_FRAME_PIXELS = 16_777_216L
private const val MAX_GENERATION_STEPS = 150
private const val MAX_VIDEO_FRAMES = 256
private const val MAX_LORA_COUNT = 32
private const val MAX_LORA_PATH_LENGTH = 4_096
private const val MAX_CFG_SCALE = 50f
private const val MAX_ABSOLUTE_LORA_STRENGTH = 10f
private const val MAX_MODEL_PATH_BYTES = 4_096
private const val MAX_MAX_VRAM_SPEC_BYTES = 256
private const val MAX_NATIVE_THREADS = 1_024
private const val MAX_MAX_VRAM_GIB = 1_024.0
private val validWeightTypes = setOf(
    0, 1, 2, 3, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
    20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 34, 35, 39, 40, 41,
)
private val backendBudgetKey = Regex(
    "(?:\\*|default|all|gpu|cpu[0-9]{0,3}|cuda[0-9]{0,3}|rocm[0-9]{0,3}|" +
        "hip[0-9]{0,3}|metal[0-9]{0,3}|vulkan[0-9]{0,3}|opencl[0-9]{0,3}|sycl[0-9]{0,3})",
)
private val decimalGib = Regex("(?:0|[1-9][0-9]{0,3})(?:\\.[0-9]{1,3})?")

internal fun validateModelConfig(config: DiffusionModelConfig) {
    require(config.modelPath.isSafeModelPath(required = true)) { "modelPath is invalid" }
    listOf(
        config.vaePath,
        config.llmPath,
        config.clipLPath,
        config.clipGPath,
        config.t5xxlPath,
        config.taesdPath,
    ).forEach { path ->
        require(path.isSafeModelPath(required = false)) { "component path is invalid" }
    }
    require(config.nThreads == -1 || config.nThreads in 1..MAX_NATIVE_THREADS) {
        "nThreads is outside the supported range"
    }
    require(config.wtype == -1 || config.wtype in validWeightTypes) {
        "wtype is outside the supported range"
    }
    require(config.prediction in -1..5) { "prediction is outside the supported range" }
    require(config.flowShift == Float.POSITIVE_INFINITY ||
        config.flowShift.isFinite() && config.flowShift in -1_000f..1_000f
    ) {
        "flowShift is outside the supported range"
    }
    require(config.maxVram.isValidMaxVramSpec()) { "maxVram is invalid" }
    require(!config.streamLayers || config.maxVram.isActiveMaxVramBudget()) {
        "streamLayers requires an active maxVram budget"
    }
}

private fun String.isSafeModelPath(required: Boolean): Boolean {
    if (isEmpty()) return !required
    return isNotBlank() && '\u0000' !in this && encodeToByteArray().size <= MAX_MODEL_PATH_BYTES
}

private fun String.isValidMaxVramSpec(): Boolean {
    if (isEmpty()) return true
    val bytes = encodeToByteArray()
    if (bytes.size > MAX_MAX_VRAM_SPEC_BYTES ||
        bytes.any { it.toInt() !in 0x21..0x7e }
    ) {
        return false
    }
    if ('=' !in this) return isValidMaxVramValue()

    val seen = HashSet<String>()
    val assignments = split(',')
    if (assignments.isEmpty()) return false
    return assignments.all { assignment ->
        if (assignment.count { it == '=' } != 1) return@all false
        val key = assignment.substringBefore('=')
        val value = assignment.substringAfter('=')
        val normalizedKey = key.lowercase()
        normalizedKey.matches(backendBudgetKey) && seen.add(normalizedKey) && value.isValidMaxVramValue()
    }
}

private fun String.isValidMaxVramValue(): Boolean {
    if (this == "-1") return true
    if (!matches(decimalGib)) return false
    return toDoubleOrNull()?.let { it in 0.0..MAX_MAX_VRAM_GIB } == true
}

private fun String.isActiveMaxVramBudget(): Boolean {
    if (isEmpty()) return false
    val values = if ('=' in this) split(',').map { it.substringAfter('=') } else listOf(this)
    return values.any { value -> value == "-1" || value.toDoubleOrNull()?.let { it > 0.0 } == true }
}

internal fun validateImageGenParams(params: ImageGenParams) {
    validateGenerationParams(
        prompt = params.prompt,
        negativePrompt = params.negativePrompt,
        width = params.width,
        height = params.height,
        steps = params.steps,
        cfgScale = params.cfgScale,
        loraPaths = params.loraPaths,
        loraStrengths = params.loraStrengths,
    )
}

internal fun validateVideoGenParams(params: VideoGenParams) {
    validateGenerationParams(
        prompt = params.prompt,
        negativePrompt = params.negativePrompt,
        width = params.width,
        height = params.height,
        steps = params.steps,
        cfgScale = params.cfgScale,
        loraPaths = params.loraPaths,
        loraStrengths = params.loraStrengths,
    )
    require(params.videoFrames in 1..MAX_VIDEO_FRAMES) {
        "videoFrames is outside the supported range"
    }
    require(
        params.width.toLong() * params.height.toLong() * params.videoFrames.toLong() <=
            MAX_VIDEO_FRAME_PIXELS
    ) {
        "video output is outside the supported range"
    }
}

private fun validateGenerationParams(
    prompt: String,
    negativePrompt: String,
    width: Int,
    height: Int,
    steps: Int,
    cfgScale: Float,
    loraPaths: List<String>,
    loraStrengths: List<Float>,
) {
    require(prompt.isNotBlank() && prompt.length <= MAX_PROMPT_LENGTH) {
        "prompt is outside the supported range"
    }
    require(negativePrompt.length <= MAX_PROMPT_LENGTH) {
        "negativePrompt is outside the supported range"
    }
    require(width in 8..MAX_IMAGE_DIMENSION && width % 8 == 0) {
        "width is outside the supported range"
    }
    require(height in 8..MAX_IMAGE_DIMENSION && height % 8 == 0) {
        "height is outside the supported range"
    }
    require(width.toLong() * height.toLong() <= MAX_IMAGE_PIXELS) {
        "image area is outside the supported range"
    }
    require(steps in 1..MAX_GENERATION_STEPS) {
        "steps is outside the supported range"
    }
    require(cfgScale.isFinite() && cfgScale in 0f..MAX_CFG_SCALE) {
        "cfgScale is outside the supported range"
    }
    require(loraPaths.size == loraStrengths.size && loraPaths.size <= MAX_LORA_COUNT) {
        "LoRA configuration is outside the supported range"
    }
    require(loraPaths.all { it.isNotBlank() && it.length <= MAX_LORA_PATH_LENGTH }) {
        "LoRA path is outside the supported range"
    }
    require(loraStrengths.all { it.isFinite() && kotlin.math.abs(it) <= MAX_ABSOLUTE_LORA_STRENGTH }) {
        "LoRA strength is outside the supported range"
    }
}
