package com.debanshu777.caraml.sdk.plugin.onnx

import java.io.File
import java.io.EOFException
import java.io.IOException
import java.io.RandomAccessFile

internal actual fun onnxCanonicalPath(path: String): String = File(path).canonicalPath
internal actual fun onnxIsFile(path: String): Boolean = File(path).isFile
internal actual fun openOnnxReader(path: String): OnnxReader {
    val file = RandomAccessFile(path, "r")
    return try {
        BufferedOnnxReader(file)
    } catch (failure: Throwable) {
        file.close()
        throw failure
    }
}

/**
 * Small protobuf reads use a bounded cache; seek never walks skipped tensor payloads.
 * A refill can read ahead by at most one buffer, even at a metadata/payload boundary.
 * Bundle files are immutable while open. Position and length do not issue file syscalls.
 */
internal class BufferedOnnxReader(private val file: RandomAccessFile) : OnnxReader {
    private val buffer = ByteArray(8 * 1024)
    private var bufferStart = 0L
    private var bufferCount = 0
    private var cursor = 0L
    private var closed = false
    override val position: Long get() = cursor
    override val length: Long = file.length()

    override fun readByte(): Int {
        ensureBuffered()
        return (buffer[(cursor++ - bufferStart).toInt()].toInt() and 255)
    }

    override fun readFully(bytes: ByteArray) {
        checkOpen()
        var offset = 0
        while (offset < bytes.size) {
            ensureBuffered()
            val index = (cursor - bufferStart).toInt()
            val count = minOf(bytes.size - offset, bufferCount - index)
            buffer.copyInto(bytes, offset, index, index + count)
            cursor += count
            offset += count
        }
    }

    override fun seek(position: Long) {
        checkOpen()
        if (position < 0) throw IOException("Negative seek offset")
        cursor = position
    }

    private fun ensureBuffered() {
        checkOpen()
        if (cursor >= length) throw EOFException()
        if (cursor >= bufferStart && cursor - bufferStart < bufferCount) return
        file.seek(cursor)
        bufferStart = cursor
        bufferCount = file.read(buffer, 0, minOf(buffer.size.toLong(), length - cursor).toInt())
        if (bufferCount <= 0) throw EOFException()
    }

    private fun checkOpen() {
        if (closed) throw IOException("ONNX reader is closed")
    }

    override fun close() {
        if (!closed) {
            closed = true
            file.close()
        }
    }
}
