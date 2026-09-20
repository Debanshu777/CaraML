package com.debanshu777.caraml.core.recommendation.storage

import com.debanshu777.caraml.core.rating.SdArchitecture
import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.Confidence
import com.debanshu777.caraml.core.recommendation.DescriptorLimits
import com.debanshu777.caraml.core.recommendation.DiffusionComponentDescriptor
import com.debanshu777.caraml.core.recommendation.DiffusionMode
import com.debanshu777.caraml.core.recommendation.DiffusionModelDescriptor
import com.debanshu777.caraml.core.recommendation.Evidence
import com.debanshu777.caraml.core.recommendation.LlmModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.ModelFormat
import com.debanshu777.caraml.core.recommendation.QuantizationEvidence
import com.debanshu777.caraml.core.recommendation.TransformerShape
import com.debanshu777.caraml.core.recommendation.hasValidExactIdentity
import com.debanshu777.caraml.core.recommendation.modelFormatForPath
import com.debanshu777.huggingfacemanager.sdcpp.ComponentRole
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okio.Buffer

private const val EVIDENCE_SCHEMA_VERSION = 1
private const val MAX_EVIDENCE_PAYLOAD_BYTES = 262_144
private const val MAX_ARCHITECTURE_LENGTH = 64
private const val MAX_TRANSFORMER_FIELD = 1_048_576

internal const val UTF8_BYTE_COUNT_LIMIT_EXCEEDED = -1
internal const val UTF8_BYTE_COUNT_MALFORMED = -2

private val evidenceJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    classDiscriminator = "descriptor_kind"
}

private fun digest(payload: String): String =
    Buffer().writeUtf8(payload).snapshot().sha256().hex()

/** Returns a non-negative byte count, or one of the rejection constants above. */
internal fun cappedUtf8ByteCount(value: String, limit: Int): Int {
    require(limit >= 0) { "UTF-8 byte limit must be non-negative" }
    var byteCount = 0
    var index = 0
    while (index < value.length) {
        val codeUnit = value[index].code
        val width = when {
            codeUnit <= 0x7f -> 1
            codeUnit <= 0x7ff -> 2
            codeUnit in 0xd800..0xdbff -> {
                if (index + 1 >= value.length || value[index + 1].code !in 0xdc00..0xdfff) {
                    return UTF8_BYTE_COUNT_MALFORMED
                }
                index += 1
                4
            }
            codeUnit in 0xdc00..0xdfff -> return UTF8_BYTE_COUNT_MALFORMED
            else -> 3
        }
        if (byteCount > limit - width) return UTF8_BYTE_COUNT_LIMIT_EXCEEDED
        byteCount += width
        index += 1
    }
    return byteCount
}

private fun requireBoundedEvidencePayload(payload: String) {
    when (cappedUtf8ByteCount(payload, MAX_EVIDENCE_PAYLOAD_BYTES)) {
        UTF8_BYTE_COUNT_LIMIT_EXCEEDED -> throw IllegalArgumentException("Evidence payload is too large")
        UTF8_BYTE_COUNT_MALFORMED -> throw IllegalArgumentException("Invalid evidence payload")
    }
}

enum class InstalledEvidenceState { COMPLETE, REQUIRES_ENRICHMENT }

data class EncodedModelEvidence(
    val state: InstalledEvidenceState,
    val schemaVersion: Int,
    val payload: String,
    val sha256: String,
)

data class DecodedModelEvidence(
    val state: InstalledEvidenceState,
    val artifactIdentities: List<ModelFileIdentity>,
    val descriptor: ModelDescriptor?,
)

class PersistedModelEvidenceCodec {
    fun encode(
        artifactIdentities: Collection<ModelFileIdentity>,
        descriptor: ModelDescriptor?,
    ): EncodedModelEvidence {
        val identities = canonicalIdentities(artifactIdentities)
        validateIdentities(identities)
        val state = if (descriptor == null) {
            InstalledEvidenceState.REQUIRES_ENRICHMENT
        } else {
            validateDescriptor(descriptor)
            require(requiredIdentities(descriptor) == identities) {
                "Descriptor identities must exactly match persisted artifacts"
            }
            InstalledEvidenceState.COMPLETE
        }
        val payload = evidenceJson.encodeToString(
            EvidenceEnvelopeDto(
                schemaVersion = EVIDENCE_SCHEMA_VERSION,
                state = state,
                artifactIdentities = identities.map(::IdentityDto),
                descriptor = descriptor?.let(::DescriptorDto),
            ),
        )
        requireBoundedEvidencePayload(payload)
        return EncodedModelEvidence(
            state = state,
            schemaVersion = EVIDENCE_SCHEMA_VERSION,
            payload = payload,
            sha256 = digest(payload),
        )
    }

    fun decode(encoded: EncodedModelEvidence): DecodedModelEvidence {
        require(encoded.schemaVersion == EVIDENCE_SCHEMA_VERSION) { "Unsupported evidence schema" }
        requireBoundedEvidencePayload(encoded.payload)
        require(LOWERCASE_SHA256.matches(encoded.sha256)) { "Invalid evidence digest" }
        require(digest(encoded.payload) == encoded.sha256) { "Evidence digest mismatch" }

        val envelope = try {
            evidenceJson.decodeFromString<EvidenceEnvelopeDto>(encoded.payload)
        } catch (_: SerializationException) {
            throw IllegalArgumentException("Invalid evidence payload")
        }
        require(envelope.schemaVersion == EVIDENCE_SCHEMA_VERSION) { "Unsupported evidence schema" }
        require(envelope.schemaVersion == encoded.schemaVersion && envelope.state == encoded.state) {
            "Evidence transport does not match payload"
        }
        validateEnvelopeDto(envelope)

        val identities = envelope.artifactIdentities.map(IdentityDto::toModelFileIdentity)
        validateIdentities(identities)
        val descriptor = envelope.descriptor?.let(::ModelDescriptor)
        when (envelope.state) {
            InstalledEvidenceState.COMPLETE -> requireNotNull(descriptor) { "Complete evidence requires a descriptor" }
            InstalledEvidenceState.REQUIRES_ENRICHMENT -> require(descriptor == null) {
                "Pending evidence must not include a descriptor"
            }
        }
        if (descriptor != null) {
            validateDescriptor(descriptor)
            require(requiredIdentities(descriptor) == canonicalIdentities(identities)) {
                "Descriptor identities must exactly match persisted artifacts"
            }
        }
        return DecodedModelEvidence(envelope.state, identities, descriptor)
    }
}

@Serializable
private data class EvidenceEnvelopeDto(
    val schemaVersion: Int,
    val state: InstalledEvidenceState,
    val artifactIdentities: List<IdentityDto>,
    val descriptor: DescriptorDto?,
)

@Serializable
private data class IdentityDto(
    val repositoryId: String,
    val revision: String,
    val path: String,
    val sizeBytes: Long,
    val gitOid: String?,
    val lfsOid: String?,
    val xetHash: String?,
    val evidence: List<EvidenceDto>,
) {
    constructor(value: ModelFileIdentity) : this(
        repositoryId = value.repositoryId,
        revision = value.revision,
        path = value.path,
        sizeBytes = value.sizeBytes,
        gitOid = value.gitOid,
        lfsOid = value.lfsOid,
        xetHash = value.xetHash,
        evidence = value.evidence.map(::EvidenceDto),
    )

    fun toModelFileIdentity() = ModelFileIdentity(
        repositoryId = repositoryId,
        revision = revision,
        path = path,
        sizeBytes = sizeBytes,
        gitOid = gitOid,
        lfsOid = lfsOid,
        xetHash = xetHash,
        evidence = evidence.map(EvidenceDto::toEvidence),
    )
}

@Serializable
private data class EvidenceDto(
    val reason: AssessmentReason,
    val confidence: Confidence,
    val detail: String?,
) {
    constructor(value: Evidence) : this(value.reason, value.confidence, value.detail)

    fun toEvidence() = Evidence(reason, confidence, detail)
}

@Serializable
private sealed interface DescriptorDto {
    fun toModelDescriptor(): ModelDescriptor
}

@Serializable
@SerialName("llm")
private data class LlmDescriptorDto(
    val repositoryId: String,
    val revision: String,
    val files: List<IdentityDto>,
    val architecture: String?,
    val quantization: QuantizationDto,
    val parameterCount: Long?,
    val contextLimit: Int?,
    val transformerShape: TransformerShapeDto?,
    val ggufVersion: Int?,
    val requiredEngineFeatures: List<String>,
    val evidence: List<EvidenceDto>,
) : DescriptorDto {
    override fun toModelDescriptor() = LlmModelDescriptor(
        repositoryId = repositoryId,
        revision = revision,
        files = files.map(IdentityDto::toModelFileIdentity),
        architecture = architecture,
        quantization = quantization.toQuantizationEvidence(),
        parameterCount = parameterCount,
        contextLimit = contextLimit,
        transformerShape = transformerShape?.toTransformerShape(),
        ggufVersion = ggufVersion,
        requiredEngineFeatures = requiredEngineFeatures,
        evidence = evidence.map(EvidenceDto::toEvidence),
    )
}

@Serializable
@SerialName("diffusion")
private data class DiffusionDescriptorDto(
    val repositoryId: String,
    val revision: String,
    val components: List<DiffusionComponentDto>,
    val mode: DiffusionMode,
    val family: String,
    val architecture: SdArchitecture?,
    val width: Int?,
    val height: Int?,
    val quantizationDistribution: List<String>,
    val requiredComponentsPresent: Boolean,
    val requiredEngineFeatures: List<String>,
    val evidence: List<EvidenceDto>,
) : DescriptorDto {
    override fun toModelDescriptor() = DiffusionModelDescriptor(
        repositoryId = repositoryId,
        revision = revision,
        components = components.map(DiffusionComponentDto::toDiffusionComponentDescriptor),
        mode = mode,
        family = family,
        architecture = architecture,
        width = width,
        height = height,
        quantizationDistribution = quantizationDistribution,
        requiredComponentsPresent = requiredComponentsPresent,
        requiredEngineFeatures = requiredEngineFeatures,
        evidence = evidence.map(EvidenceDto::toEvidence),
    )
}

private fun DescriptorDto(value: ModelDescriptor): DescriptorDto = when (value) {
    is LlmModelDescriptor -> LlmDescriptorDto(
        repositoryId = value.repositoryId,
        revision = value.revision,
        files = value.files.map(::IdentityDto),
        architecture = value.architecture,
        quantization = QuantizationDto(value.quantization),
        parameterCount = value.parameterCount,
        contextLimit = value.contextLimit,
        transformerShape = value.transformerShape?.let(::TransformerShapeDto),
        ggufVersion = value.ggufVersion,
        requiredEngineFeatures = value.requiredEngineFeatures.sorted(),
        evidence = value.evidence.map(::EvidenceDto),
    )
    is DiffusionModelDescriptor -> DiffusionDescriptorDto(
        repositoryId = value.repositoryId,
        revision = value.revision,
        components = value.components.map(::DiffusionComponentDto),
        mode = value.mode,
        family = value.family,
        architecture = value.architecture,
        width = value.width,
        height = value.height,
        quantizationDistribution = value.quantizationDistribution.sorted(),
        requiredComponentsPresent = value.requiredComponentsPresent,
        requiredEngineFeatures = value.requiredEngineFeatures.sorted(),
        evidence = value.evidence.map(::EvidenceDto),
    )
}

private fun ModelDescriptor(value: DescriptorDto): ModelDescriptor = value.toModelDescriptor()

@Serializable
private data class DiffusionComponentDto(
    val file: IdentityDto,
    val role: ComponentRole?,
    val required: Boolean,
    val isPrimary: Boolean,
    val quantization: QuantizationDto,
) {
    constructor(value: DiffusionComponentDescriptor) : this(
        file = IdentityDto(value.file),
        role = value.role,
        required = value.required,
        isPrimary = value.isPrimary,
        quantization = QuantizationDto(value.quantization),
    )

    fun toDiffusionComponentDescriptor() = DiffusionComponentDescriptor(
        file = file.toModelFileIdentity(),
        role = role,
        required = required,
        isPrimary = isPrimary,
        quantization = quantization.toQuantizationEvidence(),
    )
}

@Serializable
private data class TransformerShapeDto(
    val layerCount: Int?,
    val kvHeadCount: Int?,
    val attentionHeadCount: Int?,
    val hiddenSize: Int?,
    val headDim: Int?,
) {
    constructor(value: TransformerShape) : this(
        layerCount = value.layerCount,
        kvHeadCount = value.kvHeadCount,
        attentionHeadCount = value.attentionHeadCount,
        hiddenSize = value.hiddenSize,
        headDim = value.headDim,
    )

    fun toTransformerShape() = TransformerShape(layerCount, kvHeadCount, attentionHeadCount, hiddenSize, headDim)
}

@Serializable
private sealed interface QuantizationDto {
    fun toQuantizationEvidence(): QuantizationEvidence
}

@Serializable
@SerialName("known")
private data class KnownQuantizationDto(val quantization: String) : QuantizationDto {
    override fun toQuantizationEvidence() = QuantizationEvidence.Known(quantization)
}

@Serializable
@SerialName("mixed")
private data class MixedQuantizationDto(val quantizations: List<String>) : QuantizationDto {
    override fun toQuantizationEvidence() = QuantizationEvidence.Mixed(quantizations)
}

@Serializable
@SerialName("unknown")
private data object UnknownQuantizationDto : QuantizationDto {
    override fun toQuantizationEvidence() = QuantizationEvidence.Unknown
}

private fun QuantizationDto(value: QuantizationEvidence): QuantizationDto = when (value) {
    is QuantizationEvidence.Known -> KnownQuantizationDto(value.quantization)
    is QuantizationEvidence.Mixed -> MixedQuantizationDto(value.quantizations.sorted())
    QuantizationEvidence.Unknown -> UnknownQuantizationDto
}

private fun canonicalIdentities(identities: Collection<ModelFileIdentity>): List<ModelFileIdentity> =
    identities.sortedWith(compareBy<ModelFileIdentity> { it.repositoryId }.thenBy { it.revision }.thenBy { it.path })

private fun requiredIdentities(descriptor: ModelDescriptor): List<ModelFileIdentity> = canonicalIdentities(
    when (descriptor) {
        is LlmModelDescriptor -> descriptor.files
        is DiffusionModelDescriptor -> descriptor.components.map { it.file }
    },
)

private fun validateEnvelopeDto(envelope: EvidenceEnvelopeDto) {
    validateIdentityDtos(envelope.artifactIdentities)
    envelope.descriptor?.let(::validateDescriptorDto)
}

private fun validateDescriptorDto(descriptor: DescriptorDto) = when (descriptor) {
    is LlmDescriptorDto -> {
        validateIdentityDtos(descriptor.files)
        validateUniqueMetadataStrings(descriptor.requiredEngineFeatures, allowEmpty = false)
        validateEvidenceDtos(descriptor.evidence)
        validateQuantizationDto(descriptor.quantization)
    }
    is DiffusionDescriptorDto -> {
        require(descriptor.components.isNotEmpty() && descriptor.components.size <= DescriptorLimits.MAX_COMPONENTS) {
            "Invalid diffusion component count"
        }
        validateUniqueMetadataStrings(descriptor.quantizationDistribution, allowEmpty = false)
        validateUniqueMetadataStrings(descriptor.requiredEngineFeatures, allowEmpty = false)
        validateEvidenceDtos(descriptor.evidence)
        descriptor.components.forEach { component ->
            validateIdentityDto(component.file)
            validateQuantizationDto(component.quantization)
        }
    }
}

private fun validateQuantizationDto(quantization: QuantizationDto) = when (quantization) {
    is KnownQuantizationDto -> require(isSafeMetadataString(quantization.quantization, allowEmpty = false)) {
        "Invalid quantization"
    }
    is MixedQuantizationDto -> {
        validateUniqueMetadataStrings(quantization.quantizations, allowEmpty = false)
        require(quantization.quantizations.size >= 2) { "Mixed quantization requires multiple values" }
    }
    UnknownQuantizationDto -> Unit
}

private fun validateIdentityDtos(identities: List<IdentityDto>) {
    require(identities.isNotEmpty() && identities.size <= DescriptorLimits.MAX_COMPONENTS) { "Invalid artifact count" }
    identities.forEach(::validateIdentityDto)
    require(identities.map(::identityKey).distinct().size == identities.size) { "Artifact identities must be unique" }
}

private fun validateIdentityDto(identity: IdentityDto) {
    require(isValidRepositoryId(identity.repositoryId) && isValidRevision(identity.revision) &&
        isValidRelativePath(identity.path) && identity.sizeBytes in 1..DescriptorLimits.MAX_FILE_BYTES
    ) { "Invalid exact artifact identity" }
    val objectIdentities = listOf(identity.gitOid, identity.lfsOid, identity.xetHash)
    require(objectIdentities.any { it != null } && objectIdentities.all(::isSafeObjectIdentity)) {
        "Invalid exact artifact identity"
    }
    validateEvidenceDtos(identity.evidence)
}

private fun validateEvidenceDtos(evidence: List<EvidenceDto>) {
    require(evidence.size <= DescriptorLimits.MAX_METADATA_COLLECTION_SIZE && evidence.all {
        it.detail == null || isSafeMetadataString(it.detail, allowEmpty = true)
    }) { "Invalid evidence" }
}

private fun validateIdentities(identities: List<ModelFileIdentity>) {
    require(identities.isNotEmpty() && identities.size <= DescriptorLimits.MAX_COMPONENTS) { "Invalid artifact count" }
    require(identities.all(ModelFileIdentity::hasValidExactIdentity)) { "Invalid exact artifact identity" }
    require(identities.all { identity ->
        listOf(identity.gitOid, identity.lfsOid, identity.xetHash).all(::isSafeObjectIdentity) &&
            isValidRelativePath(identity.path) &&
            identity.evidence.all { evidence -> evidence.detail == null || isSafeMetadataString(evidence.detail, allowEmpty = true) }
    }) { "Invalid exact artifact identity" }
    require(identities.map(::identityKey).distinct().size == identities.size) { "Artifact identities must be unique" }
}

private fun validateDescriptor(descriptor: ModelDescriptor) {
    require(isValidRepositoryId(descriptor.repositoryId) && isValidRevision(descriptor.revision)) {
        "Invalid descriptor identity"
    }
    validateMetadataStrings(descriptor.requiredEngineFeatures, allowEmpty = false)
    validateEvidence(descriptor.evidence)
    when (descriptor) {
        is LlmModelDescriptor -> validateLlmDescriptor(descriptor)
        is DiffusionModelDescriptor -> validateDiffusionDescriptor(descriptor)
    }
}

private fun validateLlmDescriptor(descriptor: LlmModelDescriptor) {
    require(descriptor.files.isNotEmpty() && descriptor.files.size <= DescriptorLimits.MAX_COMPONENTS) {
        "Invalid LLM artifact count"
    }
    validateIdentities(descriptor.files)
    require(descriptor.files.all {
        it.repositoryId == descriptor.repositoryId && it.revision == descriptor.revision && modelFormatForPath(it.path) == ModelFormat.GGUF
    }) { "Invalid LLM artifacts" }
    require(descriptor.files.map { it.path }.distinct().size == descriptor.files.size) { "Duplicate LLM artifact path" }
    require(descriptor.files.sumOf { it.sizeBytes } <= DescriptorLimits.MAX_BUNDLE_BYTES) { "LLM bundle is too large" }
    require(descriptor.architecture == null || isSafeArchitecture(descriptor.architecture)) { "Invalid LLM architecture" }
    validateQuantization(descriptor.quantization)
    require(descriptor.parameterCount == null || descriptor.parameterCount in 1..DescriptorLimits.MAX_PARAMETERS) {
        "Invalid parameter count"
    }
    require(descriptor.contextLimit == null || descriptor.contextLimit in 1..DescriptorLimits.MAX_CONTEXT_TOKENS) {
        "Invalid context limit"
    }
    validateTransformerShape(descriptor.transformerShape)
    require(descriptor.ggufVersion == null || descriptor.ggufVersion >= 0) { "Invalid GGUF version" }
}

private fun validateDiffusionDescriptor(descriptor: DiffusionModelDescriptor) {
    require(descriptor.components.isNotEmpty() && descriptor.components.size <= DescriptorLimits.MAX_COMPONENTS) {
        "Invalid diffusion component count"
    }
    require(descriptor.requiredComponentsPresent) { "Incomplete diffusion descriptor" }
    require(isSafeMetadataString(descriptor.family, allowEmpty = false)) { "Invalid diffusion family" }
    require(descriptor.width == null || descriptor.width in 1..DescriptorLimits.MAX_IMAGE_DIMENSION) {
        "Invalid diffusion width"
    }
    require(descriptor.height == null || descriptor.height in 1..DescriptorLimits.MAX_IMAGE_DIMENSION) {
        "Invalid diffusion height"
    }
    validateMetadataStrings(descriptor.quantizationDistribution, allowEmpty = false)
    require(descriptor.components.count { it.isPrimary } == 1) { "Diffusion descriptor requires one primary component" }
    val primary = descriptor.components.single { it.isPrimary }.file
    require(primary.repositoryId == descriptor.repositoryId && primary.revision == descriptor.revision) {
        "Diffusion primary component must match descriptor identity"
    }
    val files = descriptor.components.map { it.file }
    validateIdentities(files)
    require(files.map(::repositoryPathKey).distinct().size == files.size) { "Duplicate diffusion artifact path" }
    require(files.all { modelFormatForPath(it.path) != null }) { "Unsupported diffusion artifact format" }
    require(files.sumOf { it.sizeBytes } <= DescriptorLimits.MAX_BUNDLE_BYTES) { "Diffusion bundle is too large" }
    require(descriptor.components.mapNotNull { it.role }.distinct().size == descriptor.components.count { it.role != null }) {
        "Duplicate diffusion component role"
    }
    descriptor.components.forEach { validateQuantization(it.quantization) }
    val distribution = descriptor.components.flatMapTo(linkedSetOf()) { it.quantization.quantizations }
    require(distribution == descriptor.quantizationDistribution) { "Invalid diffusion quantization distribution" }
}

private fun validateTransformerShape(shape: TransformerShape?) {
    if (shape == null) return
    val values = listOf(shape.layerCount, shape.kvHeadCount, shape.attentionHeadCount, shape.hiddenSize, shape.headDim)
    require(values.any { it != null } && values.all { it == null || it in 1..MAX_TRANSFORMER_FIELD }) {
        "Invalid transformer shape"
    }
}

private fun validateQuantization(value: QuantizationEvidence) = when (value) {
    is QuantizationEvidence.Known -> require(isSafeMetadataString(value.quantization, allowEmpty = false)) {
        "Invalid quantization"
    }
    is QuantizationEvidence.Mixed -> {
        validateMetadataStrings(value.quantizations, allowEmpty = false)
        require(value.quantizations.size >= 2) { "Mixed quantization requires multiple values" }
    }
    QuantizationEvidence.Unknown -> Unit
}

private fun validateEvidence(evidence: List<Evidence>) {
    require(evidence.size <= DescriptorLimits.MAX_METADATA_COLLECTION_SIZE) { "Too much evidence" }
    require(evidence.all { it.detail == null || isSafeMetadataString(it.detail, allowEmpty = true) }) {
        "Invalid evidence detail"
    }
}

private fun validateMetadataStrings(values: Collection<String>, allowEmpty: Boolean) {
    require(values.size <= DescriptorLimits.MAX_METADATA_COLLECTION_SIZE && values.all {
        isSafeMetadataString(it, allowEmpty)
    }) { "Invalid metadata collection" }
}

private fun validateUniqueMetadataStrings(values: List<String>, allowEmpty: Boolean) {
    require(values.size <= DescriptorLimits.MAX_METADATA_COLLECTION_SIZE && values.distinct().size == values.size &&
        values.all { isSafeMetadataString(it, allowEmpty) }
    ) { "Invalid metadata collection" }
}

private fun isSafeMetadataString(value: String, allowEmpty: Boolean): Boolean =
    (allowEmpty || value.isNotBlank()) && value.length <= DescriptorLimits.MAX_METADATA_STRING_LENGTH &&
        value.none(::isControlCharacter) && "://" !in value

private fun isSafeObjectIdentity(value: String?): Boolean = value == null ||
    value.isNotEmpty() && value.length <= DescriptorLimits.MAX_METADATA_STRING_LENGTH &&
        value.all { it.isAsciiLetterOrDigit() || it in "._+-:" } &&
        !hasUnsupportedUriScheme(value)

private fun hasUnsupportedUriScheme(value: String): Boolean {
    val separator = value.indexOf(':')
    if (separator <= 0 || !value.substring(0, separator).all { it.isAsciiLetterOrDigit() || it in "+-." }) {
        return false
    }
    return !value.startsWith("sha256:")
}

private fun isControlCharacter(value: Char): Boolean = value.code in 0..31 || value.code in 127..159

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private fun isSafeArchitecture(value: String): Boolean =
    value.length in 1..MAX_ARCHITECTURE_LENGTH && value.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }

private fun isValidRepositoryId(value: String): Boolean {
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

private fun isValidRevision(value: String): Boolean =
    value.length in 40..64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

private fun isValidRelativePath(value: String): Boolean {
    if (value.isEmpty() || value != value.trim() || value.length > DescriptorLimits.MAX_RELATIVE_PATH_LENGTH ||
        value.startsWith('/') || value.startsWith('\\') || '\\' in value
    ) return false
    return value.split('/').all { segment ->
        segment.isNotEmpty() && segment != "." && segment != ".." &&
            segment.length <= DescriptorLimits.MAX_PATH_SEGMENT_LENGTH &&
            segment.none { isControlCharacter(it) || it in ":*?\"<>|" }
    }
}

private fun identityKey(value: ModelFileIdentity): String =
    "${value.repositoryId}\u0000${value.revision}\u0000${value.path}"

private fun repositoryPathKey(value: ModelFileIdentity): String = "${value.repositoryId}\u0000${value.path}"

private fun identityKey(value: IdentityDto): String = "${value.repositoryId}\u0000${value.revision}\u0000${value.path}"

private val LOWERCASE_SHA256 = Regex("^[0-9a-f]{64}$")
