package com.debanshu777.caraml.sdk.plugin.onnx

internal interface OnnxReader {
    val position: Long
    val length: Long
    fun readByte(): Int
    fun readFully(bytes: ByteArray)
    fun seek(position: Long)
    fun close()
}
internal expect fun openOnnxReader(path: String): OnnxReader
internal expect fun onnxCanonicalPath(path: String): String
internal expect fun onnxIsFile(path: String): Boolean
internal fun readOnnxMetadata(path: String): String {
    val reader = openOnnxReader(path)
    try {
        require(reader.length <= 32L * 1024 * 1024) { "ONNX metadata exceeds the size limit" }
        return ByteArray(reader.length.toInt()).also(reader::readFully).decodeToString()
    } finally { reader.close() }
}
