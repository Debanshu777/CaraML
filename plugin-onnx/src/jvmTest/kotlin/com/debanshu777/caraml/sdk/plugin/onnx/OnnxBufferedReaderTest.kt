package com.debanshu777.caraml.sdk.plugin.onnx

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.random.Random
import kotlin.test.*

class OnnxBufferedReaderTest {
    @Test fun metadataByteReadsAmortizeIoAndCachePositionAndLength() = inDirectory { root ->
        val bytes = ByteArray(20_000) { (it * 37).toByte() }
        val file = File(root, "metadata").apply { writeBytes(bytes) }
        val backing = CountingFile(file)
        val reader = BufferedOnnxReader(backing)
        try {
            bytes.forEachIndexed { index, byte ->
                assertEquals(index.toLong(), reader.position)
                assertEquals(bytes.size.toLong(), reader.length)
                assertEquals(byte.toInt() and 255, reader.readByte())
            }
            assertEquals(bytes.size.toLong(), reader.position)
            assertFailsWith<EOFException> { reader.readByte() }
            assertEquals(3, backing.readCalls)
            assertEquals(3, backing.seekCalls)
            assertEquals(1, backing.lengthCalls)
            assertEquals(0, backing.positionCalls)
            assertEquals(bytes.size.toLong(), backing.bytesRead)
        } finally { reader.close() }
    }

    @Test fun mixedReadsAndSeeksMatchRandomAccessFileAcrossBufferBoundaries() = inDirectory { root ->
        val file = File(root, "metadata").apply { writeBytes(ByteArray(32_777) { (it * 13).toByte() }) }
        val reader = BufferedOnnxReader(RandomAccessFile(file, "r"))
        try {
            RandomAccessFile(file, "r").use { reference ->
                val random = Random(8172)
                repeat(500) {
                    val position = random.nextLong(file.length() + 20)
                    reader.seek(position)
                    reference.seek(position)
                    if (it % 3 == 0) {
                        val expected = runCatching { reference.readUnsignedByte() }
                        val actual = runCatching { reader.readByte() }
                        assertEquals(expected.getOrNull(), actual.getOrNull())
                        assertEquals(expected.exceptionOrNull()?.javaClass, actual.exceptionOrNull()?.javaClass)
                    } else {
                        val expected = ByteArray(random.nextInt(0, 12_000))
                        val actual = ByteArray(expected.size)
                        val expectedFailure = runCatching { reference.readFully(expected) }.exceptionOrNull()
                        val actualFailure = runCatching { reader.readFully(actual) }.exceptionOrNull()
                        assertEquals(expectedFailure?.javaClass, actualFailure?.javaClass)
                        assertContentEquals(expected, actual)
                    }
                    assertEquals(reference.filePointer, reader.position)
                }
                reader.seek(8191)
                reference.seek(8191)
                val actual = ByteArray(8195).also(reader::readFully)
                val expected = ByteArray(8195).also(reference::readFully)
                assertContentEquals(expected, actual)
                reader.seek(8192) // Backward into an already-read range.
                reference.seek(8192)
                assertEquals(reference.readUnsignedByte(), reader.readByte())
            }
        } finally { reader.close() }
    }

    @Test fun shortReadsEofNegativeSeeksAndCloseAreHandled() = inDirectory { root ->
        val file = File(root, "metadata").apply { writeBytes(byteArrayOf(0, 127, -128, -1, 9)) }
        val reader = BufferedOnnxReader(CountingFile(file, maxRead = 2))
        val actual = ByteArray(7)
        try {
            assertFailsWith<EOFException> { reader.readFully(actual) }
            assertContentEquals(byteArrayOf(0, 127, -128, -1, 9, 0, 0), actual)
            assertEquals(5L, reader.position)
            assertFailsWith<IOException> { reader.seek(-1) }
            assertEquals(5L, reader.position)
            reader.seek(Long.MAX_VALUE)
            assertFailsWith<EOFException> { reader.readByte() }
            assertEquals(Long.MAX_VALUE, reader.position)
            reader.readFully(byteArrayOf())
            reader.seek(0)
            assertEquals(0, reader.readByte())
        } finally { reader.close() }
        reader.close()
        assertFailsWith<IOException> { reader.readByte() }
        assertFailsWith<IOException> { reader.readFully(ByteArray(1)) }
        assertFailsWith<IOException> { reader.seek(0) }

        // A truncated immutable bundle must fail instead of returning stale or invented bytes.
        val truncated = BufferedOnnxReader(RandomAccessFile(file, "r"))
        try {
            RandomAccessFile(file, "rw").use { it.setLength(2) }
            assertFailsWith<EOFException> { truncated.readFully(ByteArray(5)) }
            assertEquals(2L, truncated.position)
        } finally { truncated.close() }
    }

    @Test fun sparseSeekPastTwoGiBReadsOnlyBoundaryBuffers() = inDirectory { root ->
        val offset = 3L * 1024 * 1024 * 1024
        val file = File(root, "sparse")
        RandomAccessFile(file, "rw").use {
            it.write(17)
            it.seek(offset)
            it.write(253)
        }
        val backing = CountingFile(file)
        val reader = BufferedOnnxReader(backing)
        try {
            assertEquals(17, reader.readByte())
            val initialReads = backing.readCalls
            reader.seek(offset)
            assertEquals(initialReads, backing.readCalls)
            assertEquals(253, reader.readByte())
            assertEquals(offset + 1, reader.position)
            assertEquals(2, backing.readCalls)
            assertEquals(2, backing.seekCalls)
            assertTrue(backing.bytesRead <= 8193, "Sparse skip read ${backing.bytesRead} bytes")
            assertEquals(1, backing.lengthCalls)
            assertEquals(0, backing.positionCalls)
        } finally { reader.close() }
    }

    @Test fun validatorChecksReferencesAfterSparsePayloadAndRejectsMalformedMetadata() = inDirectory { root ->
        val graph = File(root, "model.onnx")
        File(root, "model.onnx_data").writeBytes(byteArrayOf(1))
        val payloadSize = 3L * 1024 * 1024 * 1024
        fun sparseGraph(location: String) {
            val external = field(13, field(1, "location".toByteArray()) + field(2, location.toByteArray()))
            val rawHeader = header(9, payloadSize)
            val tensorSize = rawHeader.size + payloadSize + external.size
            val tensorHeader = header(5, tensorSize)
            RandomAccessFile(graph, "rw").use {
                it.setLength(0)
                it.write(header(7, tensorHeader.size + tensorSize))
                it.write(tensorHeader)
                it.write(rawHeader)
                it.seek(it.filePointer + payloadSize)
                it.write(external)
            }
        }
        sparseGraph("model.onnx_data")
        validateOnnxGraph(graph.path)
        for (location in listOf("../secret", "/absolute", "missing.data")) {
            sparseGraph(location)
            assertFails { validateOnnxGraph(graph.path) }
        }
        for (malformed in listOf(
            byteArrayOf(0x3a, 0x80.toByte()), // Truncated field length.
            byteArrayOf(0x3a, 0x7f), // Length extends past EOF.
            byteArrayOf(0x38) + ByteArray(10) { 0xff.toByte() }, // Overflowing varint.
            field(7, field(5, field(13, field(1, "location".toByteArray())))), // Missing value.
            field(7, field(5, field(13, field(1, "basepath".toByteArray()) + field(2, "/".toByteArray())))),
        )) {
            graph.writeBytes(malformed)
            assertFails { validateOnnxGraph(graph.path) }
        }
    }

    private class CountingFile(file: File, private val maxRead: Int = Int.MAX_VALUE) : RandomAccessFile(file, "r") {
        var readCalls = 0
        var seekCalls = 0
        var lengthCalls = 0
        var positionCalls = 0
        var bytesRead = 0L
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            readCalls++
            return super.read(bytes, offset, minOf(length, maxRead)).also { if (it > 0) bytesRead += it }
        }
        override fun seek(position: Long) { seekCalls++; super.seek(position) }
        override fun length(): Long { lengthCalls++; return super.length() }
        override fun getFilePointer(): Long { positionCalls++; return super.getFilePointer() }
    }

    private fun header(field: Int, size: Long) = varint((field * 8 + 2).toLong()) + varint(size)
    private fun field(field: Int, bytes: ByteArray) = header(field, bytes.size.toLong()) + bytes
    private fun varint(value: Long): ByteArray {
        var remaining = value
        val bytes = ArrayList<Byte>()
        do {
            val next = (remaining and 127).toInt()
            remaining = remaining ushr 7
            bytes += (next or if (remaining != 0L) 128 else 0).toByte()
        } while (remaining != 0L)
        return bytes.toByteArray()
    }

    private inline fun inDirectory(block: (File) -> Unit) {
        val root = kotlin.io.path.createTempDirectory("onnx-buffered-reader").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
