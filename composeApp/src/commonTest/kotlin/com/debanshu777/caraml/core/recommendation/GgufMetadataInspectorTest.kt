package com.debanshu777.caraml.core.recommendation

import okio.Buffer
import okio.BufferedSink
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ForwardingSource
import okio.Path
import okio.Source
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GgufMetadataInspectorTest {
    @Test
    fun containerProbeSeparatesWrongFormatFromUnreadableOrIncompleteData() {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "caraml-container-${Random.nextLong()}.gguf"
        val inspector = GgufMetadataInspector(FileSystem.SYSTEM)
        try {
            assertEquals(GgufContainerRecognition.UNKNOWN, inspector.recognizeContainer(path.toString()))
            for (bytes in listOf(byteArrayOf(), "O".encodeToByteArray(), "GGU".encodeToByteArray())) {
                FileSystem.SYSTEM.write(path) { write(bytes) }
                assertEquals(GgufContainerRecognition.UNKNOWN, inspector.recognizeContainer(path.toString()))
            }
            for (prefix in listOf("ONNX", "gguf", "NOPE")) {
                FileSystem.SYSTEM.write(path) { writeUtf8(prefix) }
                assertEquals(GgufContainerRecognition.NON_GGUF, inspector.recognizeContainer(path.toString()))
            }
            FileSystem.SYSTEM.write(path) { writeUtf8("GGUF") }
            assertEquals(GgufContainerRecognition.GGUF, inspector.recognizeContainer(path.toString()))
            assertNull(inspector.inspect(path.toString()), "Signature alone must not establish valid GGUF metadata")
        } finally {
            FileSystem.SYSTEM.delete(path, mustExist = false)
        }
    }

    @Test
    fun containerProbeReadsOnlyFourBytesEvenWithShortReadsAndLargeTrailingData() {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "caraml-container-${Random.nextLong()}.onnx"
        var bytesRead = 0L
        val tracking = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun source(file: Path): Source = object : ForwardingSource(super.source(file)) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    assertTrue(byteCount in 1L..4L, "Probe must bound every underlying read")
                    return super.read(sink, minOf(1L, byteCount)).also { if (it > 0) bytesRead += it }
                }
            }
        }
        try {
            FileSystem.SYSTEM.write(path) { writeUtf8("GGUF"); write(ByteArray(1_048_576)) }
            assertEquals(GgufContainerRecognition.GGUF, GgufMetadataInspector(tracking).recognizeContainer(path.toString()))
            assertEquals(4L, bytesRead)
        } finally {
            FileSystem.SYSTEM.delete(path, mustExist = false)
        }
    }

    @Test
    fun readsVersionAndArchitectureFromBoundedLocalHeader() {
        val path = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "caraml-gguf-${Random.nextLong()}.gguf")
        try {
            FileSystem.SYSTEM.write(path) {
                write(minimalGguf(version = 3, architecture = "llama"))
            }

            assertEquals(
                GgufLocalMetadata(
                    version = 3,
                    architecture = "llama",
                    contextLimit = null,
                    transformerShape = null,
                ),
                GgufMetadataInspector(FileSystem.SYSTEM).inspect(path.toString()),
            )
        } finally {
            FileSystem.SYSTEM.delete(path, mustExist = false)
        }
    }

    @Test
    fun readsArchitectureScopedContextAndTransformerShapeFromLocalHeader() {
        val path = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "caraml-gguf-${Random.nextLong()}.gguf")
        try {
            FileSystem.SYSTEM.write(path) {
                write(
                    ggufWithUnsignedMetadata(
                        architecture = "llama",
                        values = linkedMapOf(
                            "llama.context_length" to 32_768L,
                            "llama.embedding_length" to 3_072L,
                            "llama.block_count" to 40L,
                            "llama.attention.head_count" to 24L,
                            "llama.attention.head_count_kv" to 8L,
                        ),
                    ),
                )
            }

            assertEquals(
                GgufLocalMetadata(
                    version = 3,
                    architecture = "llama",
                    contextLimit = 32_768,
                    transformerShape = TransformerShape(
                        layerCount = 40,
                        kvHeadCount = 8,
                        attentionHeadCount = 24,
                        hiddenSize = 3_072,
                        headDim = 128,
                    ),
                ),
                GgufMetadataInspector(FileSystem.SYSTEM).inspect(path.toString()),
            )
        } finally {
            FileSystem.SYSTEM.delete(path, mustExist = false)
        }
    }

    @Test
    fun readsCompleteShapeWhenTokenizerArraysPushHeaderPastFourMebibytes() {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "caraml-gguf-${Random.nextLong()}.gguf"
        try {
            FileSystem.SYSTEM.write(path) {
                write(completeCoreMetadata(declaredExtraEntries = 2))
                val token = "t".repeat(2_000)
                listOf("tokenizer.ggml.tokens", "tokenizer.ggml.merges").forEach { key ->
                    writeGgufString(key)
                    writeIntLe(9) // GGUF array of strings
                    writeIntLe(8)
                    writeLongLe(1_300)
                    repeat(1_300) { writeGgufString(token) }
                }
            }
            assertEquals(true, FileSystem.SYSTEM.metadata(path).size!! > 4L * 1024L * 1024L)

            assertEquals(
                GgufLocalMetadata(
                    version = 3,
                    architecture = "llama",
                    contextLimit = 32_768,
                    transformerShape = TransformerShape(
                        layerCount = 40,
                        kvHeadCount = 8,
                        attentionHeadCount = 24,
                        hiddenSize = 3_072,
                        headDim = 128,
                    ),
                ),
                GgufMetadataInspector(FileSystem.SYSTEM).inspect(path.toString()),
            )
        } finally {
            FileSystem.SYSTEM.delete(path, mustExist = false)
        }
    }

    @Test
    fun readsRelevantNumericMetadataDeclaredBeforeArchitecture() {
        val path = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "caraml-gguf-${Random.nextLong()}.gguf")
        try {
            FileSystem.SYSTEM.write(path) {
                write(
                    ggufWithEntries(
                        GgufTestEntry.Unsigned("llama.context_length", 32_768L),
                        GgufTestEntry.Unsigned("llama.embedding_length", 3_072L),
                        GgufTestEntry.Unsigned("llama.block_count", 40L),
                        GgufTestEntry.Unsigned("llama.attention.head_count", 24L),
                        GgufTestEntry.Unsigned("llama.attention.head_count_kv", 8L),
                        GgufTestEntry.Text("general.architecture", "llama"),
                    ),
                )
            }

            assertEquals(
                GgufLocalMetadata(
                    version = 3,
                    architecture = "llama",
                    contextLimit = 32_768,
                    transformerShape = TransformerShape(
                        layerCount = 40,
                        kvHeadCount = 8,
                        attentionHeadCount = 24,
                        hiddenSize = 3_072,
                        headDim = 128,
                    ),
                ),
                GgufMetadataInspector(FileSystem.SYSTEM).inspect(path.toString()),
            )
        } finally {
            FileSystem.SYSTEM.delete(path, mustExist = false)
        }
    }

    @Test
    fun rejectsDuplicateRelevantKeyEvenWhenValuesMatch() {
        val path = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "caraml-gguf-${Random.nextLong()}.gguf")
        try {
            FileSystem.SYSTEM.write(path) {
                write(
                    ggufWithEntries(
                        GgufTestEntry.Text("general.architecture", "llama"),
                        GgufTestEntry.Unsigned("llama.context_length", 32_768L),
                        GgufTestEntry.Unsigned("llama.context_length", 32_768L),
                    ),
                )
            }

            assertNull(GgufMetadataInspector(FileSystem.SYSTEM).inspect(path.toString()))
        } finally {
            FileSystem.SYSTEM.delete(path, mustExist = false)
        }
    }

    @Test
    fun explicitHeadDimensionOverridesEmbeddingHeadRatioInGemma3AndQwen3Headers() {
        // Scalar metadata observed from the immutable validation matrix artifacts.
        listOf(
            "gemma3" to TransformerShape(18, 1, 4, 640, 256),
            "qwen3" to TransformerShape(28, 8, 16, 1024, 128),
        ).forEach { (architecture, expected) ->
            val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "caraml-gguf-${Random.nextLong()}.gguf"
            try {
                FileSystem.SYSTEM.write(path) {
                    write(ggufWithUnsignedMetadata(architecture, linkedMapOf(
                        "$architecture.block_count" to expected.layerCount!!.toLong(),
                        "$architecture.embedding_length" to expected.hiddenSize!!.toLong(),
                        "$architecture.attention.head_count" to expected.attentionHeadCount!!.toLong(),
                        "$architecture.attention.head_count_kv" to expected.kvHeadCount!!.toLong(),
                        "$architecture.attention.key_length" to expected.headDim!!.toLong(),
                    )))
                }
                assertEquals(expected, GgufMetadataInspector(FileSystem.SYSTEM).inspect(path.toString())?.transformerShape)
            } finally {
                FileSystem.SYSTEM.delete(path, mustExist = false)
            }
        }
    }

    @Test
    fun omittedKvHeadCountDefaultsToAttentionHeadCount() {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "caraml-gguf-${Random.nextLong()}.gguf"
        try {
            FileSystem.SYSTEM.write(path) {
                write(ggufWithEntries(
                    GgufTestEntry.Text("general.architecture", "llama"),
                    GgufTestEntry.Unsigned("llama.context_length", 2048L),
                    GgufTestEntry.Unsigned("llama.block_count", 12L),
                    GgufTestEntry.Unsigned("llama.embedding_length", 768L),
                    GgufTestEntry.Unsigned("llama.attention.head_count", 12L),
                ))
            }
            assertEquals(TransformerShape(12, 12, 12, 768, 64),
                GgufMetadataInspector(FileSystem.SYSTEM).inspect(path.toString())?.transformerShape)
        } finally {
            FileSystem.SYSTEM.delete(path, mustExist = false)
        }
    }

    @Test
    fun rejectsMalformedEntryDeclaredAfterCompleteCoreMetadata() {
        val path = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "caraml-gguf-${Random.nextLong()}.gguf")
        try {
            FileSystem.SYSTEM.write(path) {
                write(completeCoreMetadata(declaredExtraEntries = 1))
                writeLongLe(7L)
                writeUtf8("trunc")
            }

            assertNull(GgufMetadataInspector(FileSystem.SYSTEM).inspect(path.toString()))
        } finally {
            FileSystem.SYSTEM.delete(path, mustExist = false)
        }
    }

    @Test
    fun rejectsLateDuplicateAfterCompleteCoreMetadata() {
        val path = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "caraml-gguf-${Random.nextLong()}.gguf")
        try {
            FileSystem.SYSTEM.write(path) {
                write(
                    ggufWithEntries(
                        GgufTestEntry.Text("general.architecture", "llama"),
                        GgufTestEntry.Unsigned("llama.context_length", 32_768L),
                        GgufTestEntry.Unsigned("llama.embedding_length", 3_072L),
                        GgufTestEntry.Unsigned("llama.block_count", 40L),
                        GgufTestEntry.Unsigned("llama.attention.head_count", 24L),
                        GgufTestEntry.Unsigned("llama.attention.head_count_kv", 8L),
                        GgufTestEntry.Unsigned("llama.context_length", 32_768L),
                    ),
                )
            }

            assertNull(GgufMetadataInspector(FileSystem.SYSTEM).inspect(path.toString()))
        } finally {
            FileSystem.SYSTEM.delete(path, mustExist = false)
        }
    }

    @Test
    fun rejectsUnsupportedVersionMalformedArchitectureAndUnboundedHeader() {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "caraml-gguf-${Random.nextLong()}"
        FileSystem.SYSTEM.createDirectories(root)
        try {
            val unsupported = root / "unsupported.gguf"
            val malformed = root / "malformed.gguf"
            val oversized = root / "oversized.gguf"
            FileSystem.SYSTEM.write(unsupported) { write(minimalGguf(version = 1, architecture = "llama")) }
            FileSystem.SYSTEM.write(malformed) { write(minimalGguf(version = 3, architecture = "bad architecture")) }
            FileSystem.SYSTEM.write(oversized) {
                writeUtf8("GGUF")
                writeIntLe(3)
                writeLongLe(0)
                writeLongLe(Long.MAX_VALUE)
            }
            val inspector = GgufMetadataInspector(FileSystem.SYSTEM)

            assertNull(inspector.inspect(unsupported.toString()))
            assertNull(inspector.inspect(malformed.toString()))
            assertNull(inspector.inspect(oversized.toString()))
            assertNull(inspector.inspect((root / "missing.gguf").toString()))
        } finally {
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }
}

internal fun minimalGguf(version: Int, architecture: String): ByteArray = Buffer().apply {
    writeUtf8("GGUF")
    writeIntLe(version)
    writeLongLe(0)
    writeLongLe(1)
    writeLongLe("general.architecture".encodeToByteArray().size.toLong())
    writeUtf8("general.architecture")
    writeIntLe(8)
    writeLongLe(architecture.encodeToByteArray().size.toLong())
    writeUtf8(architecture)
}.readByteArray()

private fun ggufWithUnsignedMetadata(
    architecture: String,
    values: Map<String, Long>,
): ByteArray = Buffer().apply {
    writeUtf8("GGUF")
    writeIntLe(3)
    writeLongLe(0)
    writeLongLe((values.size + 1).toLong())
    writeGgufString("general.architecture")
    writeIntLe(8)
    writeGgufString(architecture)
    values.forEach { (key, value) ->
        writeGgufString(key)
        writeIntLe(4)
        writeIntLe(value.toInt())
    }
}.readByteArray()

private sealed interface GgufTestEntry {
    val key: String

    data class Text(override val key: String, val value: String) : GgufTestEntry
    data class Unsigned(override val key: String, val value: Long) : GgufTestEntry
}

private fun ggufWithEntries(vararg entries: GgufTestEntry): ByteArray = Buffer().apply {
    writeUtf8("GGUF")
    writeIntLe(3)
    writeLongLe(0)
    writeLongLe(entries.size.toLong())
    entries.forEach { entry ->
        writeGgufString(entry.key)
        when (entry) {
            is GgufTestEntry.Text -> {
                writeIntLe(8)
                writeGgufString(entry.value)
            }
            is GgufTestEntry.Unsigned -> {
                writeIntLe(4)
                writeIntLe(entry.value.toInt())
            }
        }
    }
}.readByteArray()

private fun completeCoreMetadata(declaredExtraEntries: Int): ByteArray {
    val entries = listOf(
        GgufTestEntry.Text("general.architecture", "llama"),
        GgufTestEntry.Unsigned("llama.context_length", 32_768L),
        GgufTestEntry.Unsigned("llama.embedding_length", 3_072L),
        GgufTestEntry.Unsigned("llama.block_count", 40L),
        GgufTestEntry.Unsigned("llama.attention.head_count", 24L),
        GgufTestEntry.Unsigned("llama.attention.head_count_kv", 8L),
    )
    return Buffer().apply {
        writeUtf8("GGUF")
        writeIntLe(3)
        writeLongLe(0)
        writeLongLe((entries.size + declaredExtraEntries).toLong())
        entries.forEach { entry ->
            writeGgufString(entry.key)
            when (entry) {
                is GgufTestEntry.Text -> {
                    writeIntLe(8)
                    writeGgufString(entry.value)
                }
                is GgufTestEntry.Unsigned -> {
                    writeIntLe(4)
                    writeIntLe(entry.value.toInt())
                }
            }
        }
    }.readByteArray()
}

private fun BufferedSink.writeGgufString(value: String) {
    writeLongLe(value.encodeToByteArray().size.toLong())
    writeUtf8(value)
}
