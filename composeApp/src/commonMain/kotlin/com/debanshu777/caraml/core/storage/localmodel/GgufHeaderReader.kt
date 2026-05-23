package com.debanshu777.caraml.core.storage.localmodel

/**
 * Pure-Kotlin GGUF file header parser. Reads only the first portion of the file
 * to extract the `general.architecture` metadata key. No native dependency.
 *
 * GGUF format (v2+):
 *   magic (4 bytes LE) = 0x46554747 ("GGUF")
 *   version (4 bytes LE)
 *   tensor_count (8 bytes LE)
 *   kv_count (8 bytes LE)
 *   then kv_count key-value pairs:
 *     key: uint64 length + bytes
 *     value_type: uint32
 *     value: depends on type
 */
object GgufHeaderReader {
    private const val GGUF_MAGIC = 0x46554747L  // "GGUF" in little-endian
    private const val GGUF_VERSION_MIN = 2

    // gguf value types
    private const val TYPE_UINT8   = 0
    private const val TYPE_INT8    = 1
    private const val TYPE_UINT16  = 2
    private const val TYPE_INT16   = 3
    private const val TYPE_UINT32  = 4
    private const val TYPE_INT32   = 5
    private const val TYPE_FLOAT32 = 6
    private const val TYPE_BOOL    = 7
    private const val TYPE_STRING  = 8
    private const val TYPE_ARRAY   = 9
    private const val TYPE_UINT64  = 10
    private const val TYPE_INT64   = 11
    private const val TYPE_FLOAT64 = 12

    data class GgufMetadata(val architecture: String?)

    fun read(filePath: String): GgufMetadata {
        return try {
            readInternal(filePath)
        } catch (_: Exception) {
            GgufMetadata(architecture = null)
        }
    }

    private fun readInternal(filePath: String): GgufMetadata {
        return java.io.RandomAccessFile(filePath, "r").use { f ->
            val magic = readU32LE(f)
            if (magic != GGUF_MAGIC) return GgufMetadata(null)
            val version = readU32LE(f).toInt()
            if (version < GGUF_VERSION_MIN) return GgufMetadata(null)
            f.readLong()  // tensor_count (skip)
            val kvCount = readU64LE(f)
            for (i in 0 until kvCount.coerceAtMost(200)) {
                val key = readGgufString(f) ?: break
                val valueType = readU32LE(f).toInt()
                if (key == "general.architecture" && valueType == TYPE_STRING) {
                    return GgufMetadata(architecture = readGgufString(f))
                }
                skipGgufValue(f, valueType)
            }
            GgufMetadata(null)
        }
    }

    private fun readU32LE(f: java.io.RandomAccessFile): Long {
        val b = ByteArray(4)
        f.readFully(b)
        return ((b[3].toLong() and 0xFF) shl 24) or
               ((b[2].toLong() and 0xFF) shl 16) or
               ((b[1].toLong() and 0xFF) shl 8) or
               (b[0].toLong() and 0xFF)
    }

    private fun readU64LE(f: java.io.RandomAccessFile): Long {
        val b = ByteArray(8)
        f.readFully(b)
        var result = 0L
        for (i in 0..7) result = result or ((b[i].toLong() and 0xFF) shl (8 * i))
        return result
    }

    private fun readGgufString(f: java.io.RandomAccessFile): String? {
        val len = readU64LE(f)
        if (len <= 0L || len > 4096L) return null
        val bytes = ByteArray(len.toInt())
        f.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun skipGgufValue(f: java.io.RandomAccessFile, valueType: Int) {
        when (valueType) {
            TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> f.skipBytes(1)
            TYPE_UINT16, TYPE_INT16 -> f.skipBytes(2)
            TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> f.skipBytes(4)
            TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> f.skipBytes(8)
            TYPE_STRING -> readGgufString(f)  // skip by reading
            TYPE_ARRAY -> {
                val elemType = readU32LE(f).toInt()
                val count = readU64LE(f)
                for (j in 0 until count.coerceAtMost(10000)) {
                    skipGgufValue(f, elemType)
                }
            }
            else -> throw java.io.IOException("Unknown GGUF value type: $valueType")
        }
    }
}
