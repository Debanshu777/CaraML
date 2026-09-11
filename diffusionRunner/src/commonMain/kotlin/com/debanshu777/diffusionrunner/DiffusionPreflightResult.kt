package com.debanshu777.diffusionrunner

import kotlinx.coroutines.CancellationException

internal const val DIFFUSION_PREFLIGHT_MAX_COMPONENTS = 8
internal const val DIFFUSION_PREFLIGHT_MAX_BACKENDS = 16
internal const val DIFFUSION_PREFLIGHT_HEADER_FIELDS = 8
internal const val DIFFUSION_PREFLIGHT_COMPONENT_FIELDS = 6
internal const val DIFFUSION_PREFLIGHT_BACKEND_FIELDS = 6

enum class DiffusionPreflightReason {
    INVALID_ARGUMENT,
    MODEL_DOES_NOT_FIT,
    INVALID_MODEL,
    INCOMPLETE_COMPONENT_EVIDENCE,
    NATIVE_RUNTIME_UNAVAILABLE,
    MALFORMED_NATIVE_PAYLOAD,
    INVALID_NATIVE_SIZE,
}

enum class DiffusionArchitecture {
    UNKNOWN,
    SD1,
    SD2,
    SDXL,
    SD3,
    FLUX,
    WAN,
    OTHER_IMAGE,
    OTHER_VIDEO,
}

enum class DiffusionQuantization {
    UNKNOWN,
    MIXED,
    F32,
    F16,
    BF16,
    Q4_0,
    Q4_1,
    Q5_0,
    Q5_1,
    Q8_0,
    Q2_K,
    Q3_K,
    Q4_K,
    Q5_K,
    Q6_K,
    OTHER,
}

enum class DiffusionMemoryConfidence {
    LOW,
    MEDIUM,
    HIGH,
}

enum class DiffusionComponentRole {
    MODEL_BUNDLE,
    DIFFUSION_MODEL,
    VAE,
    LLM,
    CLIP_L,
    CLIP_G,
    T5XXL,
    TAESD,
}

enum class DiffusionRuntimePlacement {
    DEFAULT,
    CPU,
    GPU,
    SPLIT_GPU,
}

enum class DiffusionParameterPlacement {
    DEFAULT,
    CPU,
    DISK,
}

data class DiffusionPreflightComponent(
    val role: DiffusionComponentRole,
    val ordinal: Int,
    val parameterBytes: Long,
    val runtimePlacement: DiffusionRuntimePlacement,
    val runtimeBackendMask: Long,
    val parameterPlacement: DiffusionParameterPlacement,
)

data class DiffusionPreflightBackend(
    val kind: DiffusionBackendKind,
    val deviceType: DiffusionBackendDeviceType,
    val ordinal: Int,
    val budgetBytes: Long,
    val freeBytes: Long,
    val totalBytes: Long,
)

data class DiffusionFitReport(
    val architecture: DiffusionArchitecture,
    val quantization: DiffusionQuantization,
    val memoryConfidence: DiffusionMemoryConfidence,
    val streamLayers: Boolean,
    val components: List<DiffusionPreflightComponent>,
    val backends: List<DiffusionPreflightBackend>,
)

sealed interface DiffusionPreflightResult {
    data class Fit(val report: DiffusionFitReport) : DiffusionPreflightResult
    data class NoFit(
        val reason: DiffusionPreflightReason = DiffusionPreflightReason.MODEL_DOES_NOT_FIT,
    ) : DiffusionPreflightResult
    data class InvalidModel(val reason: DiffusionPreflightReason) : DiffusionPreflightResult
    data class Unavailable(val reason: DiffusionPreflightReason) : DiffusionPreflightResult
}

internal fun runDiffusionPreflight(
    config: DiffusionModelConfig,
    nativePreflight: (DiffusionModelConfig) -> LongArray?,
): DiffusionPreflightResult = try {
    validateModelConfig(config)
    decodeDiffusionPreflight(nativePreflight(config))
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: IllegalArgumentException) {
    DiffusionPreflightResult.InvalidModel(DiffusionPreflightReason.INVALID_ARGUMENT)
} catch (_: Throwable) {
    DiffusionPreflightResult.Unavailable(DiffusionPreflightReason.NATIVE_RUNTIME_UNAVAILABLE)
}

internal fun decodeDiffusionPreflight(payload: LongArray?): DiffusionPreflightResult {
    if (payload == null || payload.size < DIFFUSION_PREFLIGHT_HEADER_FIELDS ||
        payload.size > maxDiffusionPreflightFields()
    ) {
        return malformedDiffusionPreflight()
    }
    if (payload.any { it < 0L }) {
        return DiffusionPreflightResult.InvalidModel(DiffusionPreflightReason.INVALID_NATIVE_SIZE)
    }

    val status = payload[0]
    val componentCountLong = payload[6]
    val backendCountLong = payload[7]
    if (componentCountLong !in 0L..DIFFUSION_PREFLIGHT_MAX_COMPONENTS.toLong() ||
        backendCountLong !in 0L..DIFFUSION_PREFLIGHT_MAX_BACKENDS.toLong()
    ) {
        return malformedDiffusionPreflight()
    }
    val componentCount = componentCountLong.toInt()
    val backendCount = backendCountLong.toInt()
    val expectedSize = DIFFUSION_PREFLIGHT_HEADER_FIELDS +
        componentCount * DIFFUSION_PREFLIGHT_COMPONENT_FIELDS +
        backendCount * DIFFUSION_PREFLIGHT_BACKEND_FIELDS
    if (payload.size != expectedSize) return malformedDiffusionPreflight()

    if (status != 0L) {
        if (payload.drop(1).any { it != 0L }) return malformedDiffusionPreflight()
        return when (status) {
            1L -> DiffusionPreflightResult.NoFit()
            2L -> DiffusionPreflightResult.InvalidModel(DiffusionPreflightReason.INVALID_MODEL)
            3L -> DiffusionPreflightResult.Unavailable(DiffusionPreflightReason.NATIVE_RUNTIME_UNAVAILABLE)
            else -> malformedDiffusionPreflight()
        }
    }
    if (componentCount == 0 || backendCount == 0) return malformedDiffusionPreflight()

    val architecture = payload[1].toBoundedIndex(DiffusionArchitecture.entries.size)
        ?.let(DiffusionArchitecture.entries::get) ?: return malformedDiffusionPreflight()
    val quantization = payload[2].toBoundedIndex(DiffusionQuantization.entries.size)
        ?.let(DiffusionQuantization.entries::get) ?: return malformedDiffusionPreflight()
    val confidence = payload[3].toBoundedIndex(DiffusionMemoryConfidence.entries.size)
        ?.let(DiffusionMemoryConfidence.entries::get) ?: return malformedDiffusionPreflight()
    val streamLayers = when (payload[4]) {
        0L -> false
        1L -> true
        else -> return malformedDiffusionPreflight()
    }
    val declaredMask = payload[5]
    val allowedComponentMask = (1L shl DiffusionComponentRole.entries.size) - 1L
    if (declaredMask == 0L || declaredMask and allowedComponentMask.inv() != 0L ||
        declaredMask.countOneBits() != componentCount
    ) {
        return DiffusionPreflightResult.InvalidModel(
            DiffusionPreflightReason.INCOMPLETE_COMPONENT_EVIDENCE,
        )
    }

    val components = ArrayList<DiffusionPreflightComponent>(componentCount)
    var observedMask = 0L
    repeat(componentCount) { index ->
        val offset = DIFFUSION_PREFLIGHT_HEADER_FIELDS + index * DIFFUSION_PREFLIGHT_COMPONENT_FIELDS
        val role = payload[offset].toBoundedIndex(DiffusionComponentRole.entries.size)
            ?.let(DiffusionComponentRole.entries::get) ?: return malformedDiffusionPreflight()
        if (payload[offset + 1] != index.toLong()) return malformedDiffusionPreflight()
        val roleBit = 1L shl role.ordinal
        if (observedMask and roleBit != 0L) return malformedDiffusionPreflight()
        observedMask = observedMask or roleBit
        val runtimePlacement = payload[offset + 3].toBoundedIndex(DiffusionRuntimePlacement.entries.size)
            ?.let(DiffusionRuntimePlacement.entries::get) ?: return malformedDiffusionPreflight()
        val backendMask = payload[offset + 4]
        val allowedBackendMask = (1L shl backendCount) - 1L
        if (backendMask and allowedBackendMask.inv() != 0L ||
            (runtimePlacement == DiffusionRuntimePlacement.GPU && backendMask.countOneBits() != 1) ||
            (runtimePlacement == DiffusionRuntimePlacement.SPLIT_GPU && backendMask.countOneBits() < 2) ||
            (runtimePlacement != DiffusionRuntimePlacement.GPU &&
                runtimePlacement != DiffusionRuntimePlacement.SPLIT_GPU && backendMask != 0L)
        ) {
            return malformedDiffusionPreflight()
        }
        val parameterPlacement = payload[offset + 5].toBoundedIndex(DiffusionParameterPlacement.entries.size)
            ?.let(DiffusionParameterPlacement.entries::get) ?: return malformedDiffusionPreflight()
        components += DiffusionPreflightComponent(
            role = role,
            ordinal = index,
            parameterBytes = payload[offset + 2],
            runtimePlacement = runtimePlacement,
            runtimeBackendMask = backendMask,
            parameterPlacement = parameterPlacement,
        )
    }
    if (observedMask != declaredMask) {
        return DiffusionPreflightResult.InvalidModel(
            DiffusionPreflightReason.INCOMPLETE_COMPONENT_EVIDENCE,
        )
    }

    val backends = ArrayList<DiffusionPreflightBackend>(backendCount)
    val backendOffset = DIFFUSION_PREFLIGHT_HEADER_FIELDS +
        componentCount * DIFFUSION_PREFLIGHT_COMPONENT_FIELDS
    repeat(backendCount) { index ->
        val offset = backendOffset + index * DIFFUSION_PREFLIGHT_BACKEND_FIELDS
        val kind = payload[offset].toBoundedIndex(DiffusionBackendKind.entries.size)
            ?.let(DiffusionBackendKind.entries::get) ?: return malformedDiffusionPreflight()
        val type = payload[offset + 1].toBoundedIndex(DiffusionBackendDeviceType.entries.size)
            ?.let(DiffusionBackendDeviceType.entries::get) ?: return malformedDiffusionPreflight()
        if (payload[offset + 2] != index.toLong()) return malformedDiffusionPreflight()
        val budgetBytes = payload[offset + 3]
        val freeBytes = payload[offset + 4]
        val totalBytes = payload[offset + 5]
        if (budgetBytes > freeBytes || freeBytes > totalBytes) return malformedDiffusionPreflight()
        backends += DiffusionPreflightBackend(kind, type, index, budgetBytes, freeBytes, totalBytes)
    }

    return DiffusionPreflightResult.Fit(
        DiffusionFitReport(
            architecture = architecture,
            quantization = quantization,
            memoryConfidence = confidence,
            streamLayers = streamLayers,
            components = components.toList(),
            backends = backends.toList(),
        ),
    )
}

private fun maxDiffusionPreflightFields() = DIFFUSION_PREFLIGHT_HEADER_FIELDS +
    DIFFUSION_PREFLIGHT_MAX_COMPONENTS * DIFFUSION_PREFLIGHT_COMPONENT_FIELDS +
    DIFFUSION_PREFLIGHT_MAX_BACKENDS * DIFFUSION_PREFLIGHT_BACKEND_FIELDS

private fun malformedDiffusionPreflight() =
    DiffusionPreflightResult.Unavailable(DiffusionPreflightReason.MALFORMED_NATIVE_PAYLOAD)
