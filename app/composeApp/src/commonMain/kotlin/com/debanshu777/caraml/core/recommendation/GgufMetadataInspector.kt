package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.AppLogger
import okio.Buffer
import okio.BufferedSource
import okio.EOFException
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.buffer
import okio.use

internal enum class GgufContainerRecognition { GGUF, NON_GGUF, UNKNOWN }

internal data class GgufLocalMetadata(
    val version: Int,
    val architecture: String,
    val contextLimit: Int?,
    val transformerShape: TransformerShape?,
)

/** Reads only bounded, compatibility-critical metadata from an already verified local GGUF. */
class GgufMetadataInspector(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    /** Negative format evidence only; a GGUF signature never establishes load compatibility. */
    internal fun recognizeContainer(path: String): GgufContainerRecognition {
        val result = try {
            fileSystem.source(path.toPath()).use { source ->
                val prefix = Buffer()
                while (prefix.size < 4L) {
                    if (source.read(prefix, 4L - prefix.size) <= 0L) {
                        return@use GgufContainerRecognition.UNKNOWN
                    }
                }
                if (prefix.readUtf8(4L) == GGUF_MAGIC) GgufContainerRecognition.GGUF
                else GgufContainerRecognition.NON_GGUF
            }
        } catch (_: Exception) {
            GgufContainerRecognition.UNKNOWN
        }
        AppLogger.i("ModelLoad") { "stage=gguf-container outcome=$result" }
        return result
    }

    internal fun inspect(path: String): GgufLocalMetadata? {
        val result = inspectHeader(path)
        val shapeFields = result?.transformerShape?.let {
            listOf(it.layerCount, it.kvHeadCount, it.attentionHeadCount, it.hiddenSize, it.headDim).count { field -> field != null }
        } ?: 0
        AppLogger.i("ModelLoad") {
            "stage=gguf-header outcome=${if (result == null) "HEADER_REJECTED" else "HEADER_READ"} " +
                "version=${result?.version ?: -1} hasContext=${result?.contextLimit != null} shapeFields=$shapeFields"
        }
        return result
    }

    private fun inspectHeader(path: String): GgufLocalMetadata? = try {
        fileSystem.source(path.toPath()).buffer().use { source ->
            val reader = BoundedGgufReader(source, MAX_HEADER_SCAN_BYTES)
            if (reader.readUtf8(4L) != GGUF_MAGIC) return rejectHeader(reader.rejection ?: GgufHeaderRejection.MALFORMED_HEADER)
            val version = reader.readUnsignedIntLe()?.toInt()
                ?.takeIf { it in SUPPORTED_GGUF_VERSIONS }
                ?: return rejectHeader(reader.rejection ?: GgufHeaderRejection.UNSUPPORTED_VERSION)
            val tensorCount = reader.readNonNegativeLongLe() ?: return rejectHeader(reader.rejection ?: GgufHeaderRejection.NUMERIC_BOUNDS)
            if (tensorCount > MAX_TENSOR_COUNT) return rejectHeader(GgufHeaderRejection.NUMERIC_BOUNDS)
            val metadataCount = reader.readNonNegativeLongLe() ?: return rejectHeader(reader.rejection ?: GgufHeaderRejection.NUMERIC_BOUNDS)
            if (metadataCount > MAX_METADATA_ENTRIES) return rejectHeader(GgufHeaderRejection.NUMERIC_BOUNDS)

            var architecture: String? = null
            var architectureSeen = false
            val numericCandidates = mutableMapOf<String, Long?>()
            val duplicateNumericCandidates = mutableSetOf<String>()
            val unsupportedShapeCandidates = mutableSetOf<String>()
            repeat(metadataCount.toInt()) {
                val key = reader.readString(MAX_KEY_BYTES) ?: return rejectHeader(reader.rejection ?: GgufHeaderRejection.MALFORMED_HEADER)
                val type = reader.readUnsignedIntLe()?.toInt() ?: return rejectHeader(reader.rejection ?: GgufHeaderRejection.MALFORMED_HEADER)
                if (key == ARCHITECTURE_KEY) {
                    if (architectureSeen) return rejectHeader(GgufHeaderRejection.DUPLICATE_KEY)
                    architectureSeen = true
                    if (type != GGUF_TYPE_STRING) return rejectHeader(GgufHeaderRejection.ARCHITECTURE_INVALID)
                    architecture = reader.readString(MAX_ARCHITECTURE_BYTES)
                        ?.takeIf(::isSafeArchitecture)
                        ?: return rejectHeader(reader.rejection ?: GgufHeaderRejection.ARCHITECTURE_INVALID)
                } else if (NUMERIC_SUFFIXES.any(key::endsWith)) {
                    if (numericCandidates.containsKey(key)) duplicateNumericCandidates += key
                    numericCandidates[key] = if (type == GGUF_TYPE_UINT32 || type == GGUF_TYPE_UINT64) {
                        reader.readUnsignedMetadata(type) ?: return rejectHeader(reader.rejection ?: GgufHeaderRejection.NUMERIC_BOUNDS)
                    } else {
                        if (!reader.skipValue(type)) return rejectHeader(reader.rejection ?: GgufHeaderRejection.MALFORMED_HEADER)
                        unsupportedShapeCandidates += key
                        null
                    }
                } else if (!reader.skipValue(type)) {
                    return rejectHeader(reader.rejection ?: GgufHeaderRejection.MALFORMED_HEADER)
                }
            }
            val exactArchitecture = architecture ?: return rejectHeader(GgufHeaderRejection.ARCHITECTURE_MISSING)
            val numericMetadata = mutableMapOf<String, Int>()
            NUMERIC_SUFFIXES.forEach { suffix ->
                val key = "$exactArchitecture$suffix"
                if (key in duplicateNumericCandidates) return rejectHeader(GgufHeaderRejection.DUPLICATE_KEY)
                if (key in unsupportedShapeCandidates) return rejectHeader(GgufHeaderRejection.UNSUPPORTED_SHAPE_ENCODING)
                if (numericCandidates.containsKey(key)) {
                    val value = numericCandidates[key]
                        ?.takeIf { it in 1..MAX_TRANSFORMER_FIELD }
                        ?.toInt()
                        ?: return rejectHeader(GgufHeaderRejection.NUMERIC_BOUNDS)
                    numericMetadata[key] = value
                }
            }
            metadata(version, exactArchitecture, numericMetadata)
        }
    } catch (_: Exception) {
        rejectHeader(GgufHeaderRejection.READ_UNAVAILABLE)
    }

    private fun rejectHeader(reason: GgufHeaderRejection): GgufLocalMetadata? {
        AppLogger.i("ModelLoad") { "stage=gguf-header outcome=HEADER_REJECTED reason=$reason" }
        return null
    }

    internal fun enrich(
        descriptor: ModelDescriptor,
        artifact: ResolvedLocalArtifact,
    ): ModelDescriptor? {
        if (descriptor !is LlmModelDescriptor) return descriptor
        if (descriptor.hasCompleteLocalCompatibilityMetadata()) return descriptor
        val target = artifact.loadTarget as? VerifiedArtifactLoadTarget.File ?: return null
        val local = inspect(target.path) ?: return null
        if (descriptor.architecture != null && descriptor.architecture != local.architecture) return null
        if (descriptor.ggufVersion != null && descriptor.ggufVersion != local.version) return null
        if (descriptor.contextLimit != null && local.contextLimit != null && descriptor.contextLimit != local.contextLimit) {
            return null
        }
        if (descriptor.transformerShape.conflictsWith(local.transformerShape)) return null
        val shape = descriptor.transformerShape.mergeMissing(local.transformerShape)
        val enrichedEvidence = buildList {
            addAll(descriptor.evidence)
            if (descriptor.architecture == null) {
                add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "architecture:local-gguf-header"))
            }
            if (descriptor.ggufVersion == null) {
                add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "gguf-version:local-header"))
            }
            if (descriptor.contextLimit == null && local.contextLimit != null) {
                add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "context:local-gguf-header"))
            }
            if (descriptor.transformerShape != shape && shape != null) {
                add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "shape:local-gguf-header"))
            }
        }.distinct()
        if (enrichedEvidence.size > DescriptorLimits.MAX_METADATA_COLLECTION_SIZE) return null
        return LlmModelDescriptor(
            repositoryId = descriptor.repositoryId,
            revision = descriptor.revision,
            files = descriptor.files,
            architecture = descriptor.architecture ?: local.architecture,
            quantization = descriptor.quantization,
            parameterCount = descriptor.parameterCount,
            contextLimit = descriptor.contextLimit ?: local.contextLimit,
            transformerShape = shape,
            ggufVersion = descriptor.ggufVersion ?: local.version,
            requiredEngineFeatures = descriptor.requiredEngineFeatures,
            evidence = enrichedEvidence,
        )
    }

    private fun isSafeArchitecture(value: String): Boolean =
        value.isNotEmpty() && value.length <= MAX_ARCHITECTURE_BYTES &&
            value.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }

    private fun metadata(
        version: Int,
        architecture: String,
        numericMetadata: Map<String, Int>,
    ): GgufLocalMetadata? {
        fun value(suffix: String): Int? = numericMetadata["$architecture$suffix"]
        val contextLimit = value(CONTEXT_LENGTH_SUFFIX)
            ?.takeIf { it <= DescriptorLimits.MAX_CONTEXT_TOKENS }
            ?: if (numericMetadata.containsKey("$architecture$CONTEXT_LENGTH_SUFFIX")) return rejectHeader(GgufHeaderRejection.NUMERIC_BOUNDS) else null
        val hiddenSize = value(EMBEDDING_LENGTH_SUFFIX)
        val attentionHeads = value(ATTENTION_HEAD_COUNT_SUFFIX)
        val explicitHeadDim = value(ATTENTION_KEY_LENGTH_SUFFIX)
        val derivedHeadDim = if (hiddenSize != null && attentionHeads != null && hiddenSize % attentionHeads == 0) {
            hiddenSize / attentionHeads
        } else {
            null
        }
        AppLogger.i("ModelLoad") {
            "stage=gguf-shape explicitKeyLength=${explicitHeadDim ?: -1} derivedKeyLength=${derivedHeadDim ?: -1} " +
                "kvHeadsDefaulted=${value(ATTENTION_HEAD_COUNT_KV_SUFFIX) == null && attentionHeads != null}"
        }
        // llama.cpp gives explicit key length authority; projection width need not match hidden size / heads.
        if (explicitHeadDim == null && hiddenSize != null && attentionHeads != null && derivedHeadDim == null) {
            return rejectHeader(GgufHeaderRejection.INCONSISTENT_SHAPE)
        }
        val headDim = explicitHeadDim ?: derivedHeadDim
        val shapeValues = listOf(
            value(BLOCK_COUNT_SUFFIX),
            value(ATTENTION_HEAD_COUNT_KV_SUFFIX) ?: attentionHeads,
            attentionHeads,
            hiddenSize,
            headDim,
        )
        return GgufLocalMetadata(
            version = version,
            architecture = architecture,
            contextLimit = contextLimit,
            transformerShape = if (shapeValues.any { it != null }) {
                TransformerShape(
                    layerCount = shapeValues[0],
                    kvHeadCount = shapeValues[1],
                    attentionHeadCount = shapeValues[2],
                    hiddenSize = shapeValues[3],
                    headDim = shapeValues[4],
                )
            } else {
                null
            },
        )
    }

    private companion object {
        const val GGUF_MAGIC = "GGUF"
        const val ARCHITECTURE_KEY = "general.architecture"
        const val GGUF_TYPE_STRING = 8
        const val GGUF_TYPE_UINT32 = 4
        const val GGUF_TYPE_UINT64 = 10
        const val MAX_ARCHITECTURE_BYTES = 64
        const val MAX_TRANSFORMER_FIELD = 1_048_576L
        const val MAX_KEY_BYTES = 256
        // Valid tokenizer token/merge arrays can push the metadata block past 4 MiB
        // (MiniCPM5 Q4_K_M ends at 5,122,873 bytes). Keep the full scan bounded.
        const val MAX_HEADER_SCAN_BYTES = 8L * 1024L * 1024L
        const val MAX_METADATA_ENTRIES = 16_384L
        const val MAX_TENSOR_COUNT = 1_000_000L
        const val CONTEXT_LENGTH_SUFFIX = ".context_length"
        const val EMBEDDING_LENGTH_SUFFIX = ".embedding_length"
        const val BLOCK_COUNT_SUFFIX = ".block_count"
        const val ATTENTION_HEAD_COUNT_SUFFIX = ".attention.head_count"
        const val ATTENTION_HEAD_COUNT_KV_SUFFIX = ".attention.head_count_kv"
        const val ATTENTION_KEY_LENGTH_SUFFIX = ".attention.key_length"
        val NUMERIC_SUFFIXES = listOf(
            CONTEXT_LENGTH_SUFFIX,
            EMBEDDING_LENGTH_SUFFIX,
            BLOCK_COUNT_SUFFIX,
            ATTENTION_HEAD_COUNT_SUFFIX,
            ATTENTION_HEAD_COUNT_KV_SUFFIX,
            ATTENTION_KEY_LENGTH_SUFFIX,
        )
        val SUPPORTED_GGUF_VERSIONS = 2..3
    }
}

internal fun LlmModelDescriptor.hasCompleteLocalCompatibilityMetadata(): Boolean =
    architecture != null && ggufVersion != null && contextLimit != null && transformerShape?.let {
        it.layerCount != null && it.kvHeadCount != null && it.attentionHeadCount != null &&
            it.hiddenSize != null && it.headDim != null
    } == true

private fun TransformerShape?.mergeMissing(local: TransformerShape?): TransformerShape? {
    if (this == null) return local
    if (local == null) return this
    return TransformerShape(
        layerCount = layerCount ?: local.layerCount,
        kvHeadCount = kvHeadCount ?: local.kvHeadCount,
        attentionHeadCount = attentionHeadCount ?: local.attentionHeadCount,
        hiddenSize = hiddenSize ?: local.hiddenSize,
        headDim = headDim ?: local.headDim,
    )
}

private fun TransformerShape?.conflictsWith(local: TransformerShape?): Boolean {
    if (this == null || local == null) return false
    fun conflicts(existing: Int?, candidate: Int?): Boolean =
        existing != null && candidate != null && existing != candidate
    return conflicts(layerCount, local.layerCount) ||
        conflicts(kvHeadCount, local.kvHeadCount) ||
        conflicts(attentionHeadCount, local.attentionHeadCount) ||
        conflicts(hiddenSize, local.hiddenSize) ||
        conflicts(headDim, local.headDim)
}

private enum class GgufHeaderRejection {
    SCAN_LIMIT, UNSUPPORTED_VERSION, ARCHITECTURE_MISSING, ARCHITECTURE_INVALID,
    DUPLICATE_KEY, UNSUPPORTED_SHAPE_ENCODING, NUMERIC_BOUNDS, INCONSISTENT_SHAPE,
    MALFORMED_HEADER, TRUNCATED_HEADER, READ_UNAVAILABLE,
}

private class BoundedGgufReader(
    private val source: BufferedSource,
    private var remaining: Long,
) {
    var rejection: GgufHeaderRejection? = null
        private set

    fun readUtf8(byteCount: Long): String? = take(byteCount) { source.readUtf8(byteCount) }

    fun readUnsignedIntLe(): Long? = take(Int.SIZE_BYTES.toLong()) {
        source.readIntLe().toLong() and 0xffff_ffffL
    }

    fun readNonNegativeLongLe(): Long? = take(Long.SIZE_BYTES.toLong()) {
        source.readLongLe().takeIf { it >= 0L }
    }

    fun readUnsignedMetadata(type: Int): Long? = when (type) {
        4 -> readUnsignedIntLe()
        10 -> readNonNegativeLongLe()
        else -> null
    }

    fun readString(maxBytes: Int): String? {
        val byteCount = readNonNegativeLongLe() ?: return null
        if (byteCount > maxBytes.toLong()) return null
        return readUtf8(byteCount)
    }

    fun skipValue(type: Int): Boolean = when (type) {
        0, 1, 7 -> skip(1)
        2, 3 -> skip(2)
        4, 5, 6 -> skip(4)
        8 -> skipString()
        9 -> skipArray()
        10, 11, 12 -> skip(8)
        else -> false
    }

    private fun skipString(): Boolean {
        val byteCount = readNonNegativeLongLe() ?: return false
        return skip(byteCount)
    }

    private fun skipArray(): Boolean {
        val elementType = readUnsignedIntLe()?.toInt() ?: return false
        if (elementType == 9) return false
        val count = readNonNegativeLongLe() ?: return false
        if (count > MAX_ARRAY_ELEMENTS) return false
        return when (val width = fixedWidth(elementType)) {
            null -> if (elementType == 8) repeatSafely(count) { skipString() } else false
            else -> count <= remaining / width && skip(count * width)
        }
    }

    private fun fixedWidth(type: Int): Long? = when (type) {
        0, 1, 7 -> 1
        2, 3 -> 2
        4, 5, 6 -> 4
        10, 11, 12 -> 8
        else -> null
    }

    private fun repeatSafely(count: Long, action: () -> Boolean): Boolean {
        var index = 0L
        while (index < count) {
            if (!action()) return false
            index += 1
        }
        return true
    }

    private fun skip(byteCount: Long): Boolean = take(byteCount) {
        source.skip(byteCount)
        true
    } ?: false

    private inline fun <T> take(byteCount: Long, block: () -> T): T? {
        if (byteCount < 0L || byteCount > remaining) {
            rejection = if (byteCount > remaining) GgufHeaderRejection.SCAN_LIMIT else GgufHeaderRejection.NUMERIC_BOUNDS
            return null
        }
        return try {
            block().also { remaining -= byteCount }
        } catch (error: Exception) {
            rejection = if (error is EOFException) GgufHeaderRejection.TRUNCATED_HEADER else GgufHeaderRejection.READ_UNAVAILABLE
            null
        }
    }

    private companion object {
        const val MAX_ARRAY_ELEMENTS = 1_000_000L
    }
}
