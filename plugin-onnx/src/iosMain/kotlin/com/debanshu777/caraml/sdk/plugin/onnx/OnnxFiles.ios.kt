@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.debanshu777.caraml.sdk.plugin.onnx

import kotlinx.cinterop.*
import platform.posix.*

internal actual fun onnxCanonicalPath(path: String): String {
    val result = realpath(path, null) ?: error("ONNX file is missing")
    return try { result.toKString() } finally { free(result) }
}
internal actual fun onnxIsFile(path: String): Boolean = memScoped {
    val status = alloc<stat>()
    stat(path, status.ptr) == 0 && (status.st_mode.toInt() and S_IFMT) == S_IFREG
}
internal actual fun openOnnxReader(path: String): OnnxReader = object : OnnxReader {
    private val file = fopen(path, "rb") ?: error("Cannot read ONNX file")
    override val position get() = ftell(file).also { check(it >= 0) }
    override val length: Long = run {
        check(fseek(file, 0, SEEK_END) == 0)
        val size = ftell(file)
        check(size >= 0 && fseek(file, 0, SEEK_SET) == 0)
        size
    }
    override fun readByte() = fgetc(file).also { check(it >= 0) { "Truncated ONNX file" } }
    override fun readFully(bytes: ByteArray) {
        if (bytes.isNotEmpty()) bytes.usePinned {
            check(fread(it.addressOf(0), 1u, bytes.size.toULong(), file).toInt() == bytes.size) { "Truncated ONNX file" }
        }
    }
    override fun seek(position: Long) { check(fseek(file, position, SEEK_SET) == 0) }
    override fun close() { fclose(file) }
}
