package com.debanshu777.runner

internal const val NATIVE_BACKEND_MAX_DEVICES = 16
internal const val NATIVE_BACKEND_HEADER_FIELDS = 1
internal const val NATIVE_BACKEND_DEVICE_FIELDS = 13
private const val NATIVE_BACKEND_LEGACY_DEVICE_FIELDS = 4
private const val NATIVE_BACKEND_IDENTITY_WORDS = 8
private const val NATIVE_BACKEND_IDENTITY_MAX_BYTES = 64

enum class NativeBackendKind(val stableName: String) {
    CPU("cpu"),
    CUDA("cuda"),
    METAL("metal"),
    VULKAN("vulkan"),
    OPENCL("opencl"),
    SYCL("sycl"),
    OTHER("other"),
}

enum class NativeBackendDeviceType {
    CPU,
    DISCRETE_GPU,
    INTEGRATED_GPU,
    ACCELERATOR,
    META,
}

data class NativeBackendCapability(
    val kind: NativeBackendKind,
    val deviceType: NativeBackendDeviceType,
    val freeBytes: Long?,
    val totalBytes: Long?,
    val deviceIdentity: String? = null,
)

internal fun decodeNativeBackendCapabilities(payload: LongArray?): List<NativeBackendCapability> {
    if (payload == null || payload.size < NATIVE_BACKEND_HEADER_FIELDS) return emptyList()
    val countLong = payload[0]
    if (countLong !in 1L..NATIVE_BACKEND_MAX_DEVICES.toLong()) return emptyList()
    val count = countLong.toInt()
    val recordFields = when (payload.size) {
        NATIVE_BACKEND_HEADER_FIELDS + count * NATIVE_BACKEND_DEVICE_FIELDS -> NATIVE_BACKEND_DEVICE_FIELDS
        NATIVE_BACKEND_HEADER_FIELDS + count * NATIVE_BACKEND_LEGACY_DEVICE_FIELDS ->
            NATIVE_BACKEND_LEGACY_DEVICE_FIELDS
        else -> return emptyList()
    }

    val capabilities = ArrayList<NativeBackendCapability>(count)
    repeat(count) { index ->
        val offset = NATIVE_BACKEND_HEADER_FIELDS + index * recordFields
        val kind = payload[offset].toNativeBackendIndex(NativeBackendKind.entries.size)
            ?.let(NativeBackendKind.entries::get) ?: return emptyList()
        val deviceType = payload[offset + 1].toNativeBackendIndex(NativeBackendDeviceType.entries.size)
            ?.let(NativeBackendDeviceType.entries::get) ?: return emptyList()
        val freeBytes = payload[offset + 2].optionalNativeBytes() ?: if (payload[offset + 2] == -1L) null else return emptyList()
        val totalBytes = payload[offset + 3].optionalNativeBytes() ?: if (payload[offset + 3] == -1L) null else return emptyList()
        if (freeBytes != null && totalBytes != null && freeBytes > totalBytes) return emptyList()
        val identity = if (recordFields == NATIVE_BACKEND_DEVICE_FIELDS) {
            payload.decodeBackendIdentity(offset + 4) ?: return emptyList()
        } else {
            null
        }
        capabilities += NativeBackendCapability(kind, deviceType, freeBytes, totalBytes, identity)
    }
    return capabilities
}

private fun Long.optionalNativeBytes(): Long? = takeIf { it >= 0L }

private fun Long.toNativeBackendIndex(size: Int): Int? =
    takeIf { it in 0L until size.toLong() }?.toInt()

private fun LongArray.decodeBackendIdentity(offset: Int): String? {
    val length = this[offset]
    if (length !in 1L..NATIVE_BACKEND_IDENTITY_MAX_BYTES.toLong()) return null
    val bytes = ByteArray(NATIVE_BACKEND_IDENTITY_MAX_BYTES)
    repeat(NATIVE_BACKEND_IDENTITY_WORDS) { wordIndex ->
        val word = this[offset + 1 + wordIndex]
        repeat(Long.SIZE_BYTES) { byteIndex ->
            bytes[wordIndex * Long.SIZE_BYTES + byteIndex] =
                ((word ushr (byteIndex * Byte.SIZE_BITS)) and 0xffL).toByte()
        }
    }
    val boundedLength = length.toInt()
    if (bytes.drop(boundedLength).any { it != 0.toByte() }) return null
    val identity = buildString(boundedLength) {
        repeat(boundedLength) { index ->
            val value = bytes[index].toInt() and 0xff
            if (value !in 0x20..0x7e) return null
            append(value.toChar())
        }
    }
    val canonical = identity.trim().map { character ->
        if (character in 'A'..'Z') character.lowercaseChar() else character
    }.joinToString("")
    return identity.takeIf { it.isNotEmpty() && it == canonical }
}
