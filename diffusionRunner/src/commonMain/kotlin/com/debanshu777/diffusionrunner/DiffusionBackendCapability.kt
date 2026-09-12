package com.debanshu777.diffusionrunner

internal const val DIFFUSION_BACKEND_MAX_DEVICES = 16
internal const val DIFFUSION_BACKEND_HEADER_FIELDS = 1
internal const val DIFFUSION_BACKEND_DEVICE_FIELDS = 13
private const val DIFFUSION_BACKEND_LEGACY_DEVICE_FIELDS = 4
private const val DIFFUSION_BACKEND_IDENTITY_WORDS = 8
private const val DIFFUSION_BACKEND_IDENTITY_MAX_BYTES = 64

enum class DiffusionBackendKind(val stableName: String) {
    CPU("cpu"),
    CUDA("cuda"),
    METAL("metal"),
    VULKAN("vulkan"),
    OPENCL("opencl"),
    SYCL("sycl"),
    OTHER("other"),
}

enum class DiffusionBackendDeviceType {
    CPU,
    DISCRETE_GPU,
    INTEGRATED_GPU,
    ACCELERATOR,
    META,
}

data class DiffusionBackendCapability(
    val kind: DiffusionBackendKind,
    val deviceType: DiffusionBackendDeviceType,
    val freeBytes: Long?,
    val totalBytes: Long?,
    val deviceIdentity: String? = null,
)

internal fun decodeDiffusionBackendCapabilities(payload: LongArray?): List<DiffusionBackendCapability> {
    if (payload == null || payload.size < DIFFUSION_BACKEND_HEADER_FIELDS) return emptyList()
    val countLong = payload[0]
    if (countLong !in 1L..DIFFUSION_BACKEND_MAX_DEVICES.toLong()) return emptyList()
    val count = countLong.toInt()
    val recordFields = when (payload.size) {
        DIFFUSION_BACKEND_HEADER_FIELDS + count * DIFFUSION_BACKEND_DEVICE_FIELDS ->
            DIFFUSION_BACKEND_DEVICE_FIELDS
        DIFFUSION_BACKEND_HEADER_FIELDS + count * DIFFUSION_BACKEND_LEGACY_DEVICE_FIELDS ->
            DIFFUSION_BACKEND_LEGACY_DEVICE_FIELDS
        else -> return emptyList()
    }

    val capabilities = ArrayList<DiffusionBackendCapability>(count)
    repeat(count) { index ->
        val offset = DIFFUSION_BACKEND_HEADER_FIELDS + index * recordFields
        val kind = payload[offset].toBoundedIndex(DiffusionBackendKind.entries.size)
            ?.let(DiffusionBackendKind.entries::get) ?: return emptyList()
        val type = payload[offset + 1].toBoundedIndex(DiffusionBackendDeviceType.entries.size)
            ?.let(DiffusionBackendDeviceType.entries::get) ?: return emptyList()
        val freeBytes = payload[offset + 2].optionalBytes() ?: if (payload[offset + 2] == -1L) null else return emptyList()
        val totalBytes = payload[offset + 3].optionalBytes() ?: if (payload[offset + 3] == -1L) null else return emptyList()
        if (freeBytes != null && totalBytes != null && freeBytes > totalBytes) return emptyList()
        val identity = if (recordFields == DIFFUSION_BACKEND_DEVICE_FIELDS) {
            payload.decodeBackendIdentity(offset + 4) ?: return emptyList()
        } else {
            null
        }
        capabilities += DiffusionBackendCapability(kind, type, freeBytes, totalBytes, identity)
    }
    return capabilities
}

private fun Long.optionalBytes(): Long? = takeIf { it >= 0L }

private fun LongArray.decodeBackendIdentity(offset: Int): String? {
    val length = this[offset]
    if (length !in 1L..DIFFUSION_BACKEND_IDENTITY_MAX_BYTES.toLong()) return null
    val bytes = ByteArray(DIFFUSION_BACKEND_IDENTITY_MAX_BYTES)
    repeat(DIFFUSION_BACKEND_IDENTITY_WORDS) { wordIndex ->
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

internal fun Long.toBoundedIndex(size: Int): Int? =
    takeIf { it in 0L until size.toLong() }?.toInt()
