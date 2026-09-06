package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.rating.SdArchitecture
import com.debanshu777.huggingfacemanager.sdcpp.ComponentRole

object DescriptorLimits {
    const val MAX_FILE_BYTES: Long = 1L shl 50
    const val MAX_BUNDLE_BYTES: Long = 2L shl 50
    const val MAX_COMPONENTS: Int = 4_096
    const val MAX_PARAMETERS: Long = 1_000_000_000_000_000L
    const val MAX_CONTEXT_TOKENS: Int = 16_777_216
    const val MAX_IMAGE_DIMENSION: Int = 65_536
    const val MAX_MODEL_ID_LENGTH: Int = 193
    const val MAX_REPOSITORY_SEGMENT_LENGTH: Int = 96
    const val MAX_RELATIVE_PATH_LENGTH: Int = 1_024
    const val MAX_PATH_SEGMENT_LENGTH: Int = 255
    const val MAX_METADATA_STRING_LENGTH: Int = 256
    const val MAX_METADATA_COLLECTION_SIZE: Int = 256
}

enum class ModelFormat {
    GGUF,
    SAFETENSORS,
    CHECKPOINT,
}

enum class DiffusionMode {
    IMAGE,
    VIDEO,
}

@ConsistentCopyVisibility
data class ModelFileIdentity private constructor(
    val repositoryId: String,
    val revision: String,
    val path: String,
    val sizeBytes: Long,
    val gitOid: String?,
    val lfsOid: String?,
    val xetHash: String?,
    val evidence: List<Evidence>,
) {
    constructor(
        repositoryId: String,
        revision: String,
        path: String,
        sizeBytes: Long,
        gitOid: String?,
        lfsOid: String?,
        xetHash: String?,
        evidence: Collection<Evidence>,
    ) : this(
        repositoryId = repositoryId,
        revision = revision,
        path = path,
        sizeBytes = sizeBytes,
        gitOid = gitOid,
        lfsOid = lfsOid,
        xetHash = xetHash,
        evidence = evidence.toList(),
    )
}

internal fun ModelFileIdentity.hasValidExactIdentity(): Boolean {
    val objectIdentities = listOf(gitOid, lfsOid, xetHash)
    if (!isValidDescriptorRepositoryId(repositoryId) ||
        revision.length !in 40..64 || !revision.all(::isAsciiHexDigit) ||
        !isValidDescriptorRelativePath(path) ||
        sizeBytes !in 1..DescriptorLimits.MAX_FILE_BYTES ||
        objectIdentities.none { it != null } ||
        objectIdentities.any { value ->
            value != null && (value.isEmpty() || value.length > DescriptorLimits.MAX_METADATA_STRING_LENGTH ||
                value.any { it.code < 32 || it.code == 127 })
        } || evidence.size > DescriptorLimits.MAX_METADATA_COLLECTION_SIZE ||
        evidence.any { it.detail?.let { detail ->
            detail.length > DescriptorLimits.MAX_METADATA_STRING_LENGTH || detail.any { it.isISOControl() }
        } == true }
    ) {
        return false
    }
    return true
}

private fun isValidDescriptorRepositoryId(value: String): Boolean {
    if (value.isEmpty() || value != value.trim() || value.length > DescriptorLimits.MAX_MODEL_ID_LENGTH || '\\' in value) {
        return false
    }
    val segments = value.split('/')
    return segments.size in 1..2 && segments.all { segment ->
        segment.isNotEmpty() && segment.length <= DescriptorLimits.MAX_REPOSITORY_SEGMENT_LENGTH &&
            segment != "." && segment != ".." && ".." !in segment && "--" !in segment &&
            segment.first() !in ".-" && segment.last() !in ".-" &&
            segment.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
    }
}

private fun isValidDescriptorRelativePath(value: String): Boolean {
    if (value.isEmpty() || value != value.trim() || value.length > DescriptorLimits.MAX_RELATIVE_PATH_LENGTH ||
        value.startsWith('/') || value.startsWith('\\') || '\\' in value
    ) {
        return false
    }
    return value.split('/').all { segment ->
        segment.isNotEmpty() && segment != "." && segment != ".." &&
            segment.length <= DescriptorLimits.MAX_PATH_SEGMENT_LENGTH &&
            segment.none { it.code < 32 || it.code == 127 || it in ":*?\"<>|" }
    }
}

private fun isAsciiHexDigit(value: Char): Boolean =
    value in '0'..'9' || value in 'a'..'f' || value in 'A'..'F'

data class TransformerShape(
    val layerCount: Int?,
    val kvHeadCount: Int?,
    val attentionHeadCount: Int?,
    val hiddenSize: Int?,
    val headDim: Int?,
)

sealed interface ModelDescriptor {
    val repositoryId: String
    val revision: String
    val format: ModelFormat
    val requiredEngineFeatures: Set<String>
    val evidence: List<Evidence>
}

@ConsistentCopyVisibility
data class LlmModelDescriptor private constructor(
    override val repositoryId: String,
    override val revision: String,
    val file: ModelFileIdentity,
    val files: List<ModelFileIdentity>,
    val architecture: String?,
    val quantization: QuantizationEvidence,
    val parameterCount: Long?,
    val contextLimit: Int?,
    val transformerShape: TransformerShape?,
    val ggufVersion: Int?,
    override val requiredEngineFeatures: Set<String>,
    override val evidence: List<Evidence>,
) : ModelDescriptor {
    override val format: ModelFormat = ModelFormat.GGUF

    constructor(
        repositoryId: String,
        revision: String,
        file: ModelFileIdentity,
        architecture: String?,
        quantization: QuantizationEvidence,
        parameterCount: Long?,
        contextLimit: Int?,
        transformerShape: TransformerShape?,
        ggufVersion: Int?,
        requiredEngineFeatures: Collection<String>,
        evidence: Collection<Evidence>,
    ) : this(
        repositoryId = repositoryId,
        revision = revision,
        file = file,
        files = listOf(file),
        architecture = architecture,
        quantization = quantization,
        parameterCount = parameterCount,
        contextLimit = contextLimit,
        transformerShape = transformerShape,
        ggufVersion = ggufVersion,
        requiredEngineFeatures = requiredEngineFeatures.toSet(),
        evidence = evidence.toList(),
    )

    internal constructor(
        repositoryId: String,
        revision: String,
        files: Collection<ModelFileIdentity>,
        architecture: String?,
        quantization: QuantizationEvidence,
        parameterCount: Long?,
        contextLimit: Int?,
        transformerShape: TransformerShape?,
        ggufVersion: Int?,
        requiredEngineFeatures: Collection<String>,
        evidence: Collection<Evidence>,
    ) : this(
        repositoryId = repositoryId,
        revision = revision,
        file = requireNotNull(files.firstOrNull()) { "LLM descriptor requires at least one exact file" },
        files = files.toList(),
        architecture = architecture,
        quantization = quantization,
        parameterCount = parameterCount,
        contextLimit = contextLimit,
        transformerShape = transformerShape,
        ggufVersion = ggufVersion,
        requiredEngineFeatures = requiredEngineFeatures.toSet(),
        evidence = evidence.toList(),
    )
}

internal fun LlmModelDescriptor.checkedTotalFileBytes(): CheckedLong {
    if (files.isEmpty() || files.size > DescriptorLimits.MAX_COMPONENTS) {
        return CheckedLong.Invalid(AssessmentReason.INVALID_METADATA)
    }
    val seen = HashSet<String>(files.size)
    var total = 0L
    for (identity in files) {
        if (identity.repositoryId != repositoryId || identity.revision != revision ||
            identity.sizeBytes !in 1..DescriptorLimits.MAX_FILE_BYTES || !seen.add(identity.path)
        ) {
            return CheckedLong.Invalid(AssessmentReason.INVALID_METADATA)
        }
        total = when (val sum = checkedAdd(total, identity.sizeBytes)) {
            is CheckedLong.Invalid -> return sum
            is CheckedLong.Value -> sum.value
        }
        if (total > DescriptorLimits.MAX_BUNDLE_BYTES) {
            return CheckedLong.Invalid(AssessmentReason.BUNDLE_SIZE_LIMIT_EXCEEDED)
        }
    }
    return CheckedLong.Value(total)
}

data class DiffusionComponentDescriptor(
    val file: ModelFileIdentity,
    val role: ComponentRole?,
    val required: Boolean,
    val isPrimary: Boolean,
    val quantization: QuantizationEvidence = QuantizationEvidence.Unknown,
)

@ConsistentCopyVisibility
data class DiffusionModelDescriptor private constructor(
    override val repositoryId: String,
    override val revision: String,
    val components: List<DiffusionComponentDescriptor>,
    val mode: DiffusionMode,
    val family: String,
    val architecture: SdArchitecture?,
    val width: Int?,
    val height: Int?,
    val quantizationDistribution: Set<String>,
    val requiredComponentsPresent: Boolean,
    override val requiredEngineFeatures: Set<String>,
    override val evidence: List<Evidence>,
) : ModelDescriptor {
    override val format: ModelFormat = components.firstOrNull { it.isPrimary }
        ?.file
        ?.path
        ?.let(::modelFormatForPath)
        ?: ModelFormat.SAFETENSORS

    constructor(
        repositoryId: String,
        revision: String,
        components: Collection<DiffusionComponentDescriptor>,
        mode: DiffusionMode,
        family: String,
        architecture: SdArchitecture? = null,
        width: Int? = null,
        height: Int? = null,
        quantizationDistribution: Collection<String>,
        requiredComponentsPresent: Boolean,
        requiredEngineFeatures: Collection<String>,
        evidence: Collection<Evidence>,
    ) : this(
        repositoryId = repositoryId,
        revision = revision,
        components = components.toList(),
        mode = mode,
        family = family,
        architecture = architecture,
        width = width,
        height = height,
        quantizationDistribution = quantizationDistribution.toSet(),
        requiredComponentsPresent = requiredComponentsPresent,
        requiredEngineFeatures = requiredEngineFeatures.toSet(),
        evidence = evidence.toList(),
    )
}

internal fun modelFormatForPath(path: String): ModelFormat? = when {
    path.endsWith(".gguf", ignoreCase = true) -> ModelFormat.GGUF
    path.endsWith(".safetensors", ignoreCase = true) -> ModelFormat.SAFETENSORS
    path.endsWith(".ckpt", ignoreCase = true) || path.endsWith(".pth", ignoreCase = true) ->
        ModelFormat.CHECKPOINT
    else -> null
}

sealed interface DescriptorBuildResult {
    data class Ready(val descriptor: ModelDescriptor) : DescriptorBuildResult

    data class NeedsVariant(
        val repositoryId: String,
        val reasons: List<AssessmentReason>,
        val assumedQuantization: String? = null,
    ) : DescriptorBuildResult

    data class Invalid(val reasons: List<AssessmentReason>) : DescriptorBuildResult
}
