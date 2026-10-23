package com.debanshu777.caraml.sdk.plugin.onnx

import java.io.File
import java.io.RandomAccessFile

internal actual fun onnxCanonicalPath(path: String): String = File(path).canonicalPath
internal actual fun onnxIsFile(path: String): Boolean = File(path).isFile
internal actual fun openOnnxReader(path: String): OnnxReader = object : OnnxReader {
    private val file = RandomAccessFile(path, "r")
    override val position get() = file.filePointer
    override val length get() = file.length()
    override fun readByte() = file.readUnsignedByte()
    override fun readFully(bytes: ByteArray) = file.readFully(bytes)
    override fun seek(position: Long) = file.seek(position)
    override fun close() = file.close()
}
