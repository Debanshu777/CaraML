package com.debanshu777.runner

internal const val NATIVE_BACKEND_MAX_DEVICES = 16
internal const val NATIVE_BACKEND_HEADER_FIELDS = 1
internal const val NATIVE_BACKEND_DEVICE_FIELDS = 4

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
)

internal fun decodeNativeBackendCapabilities(payload: LongArray?): List<NativeBackendCapability> {
    if (payload == null || payload.size < NATIVE_BACKEND_HEADER_FIELDS) return emptyList()
    val countLong = payload[0]
    if (countLong !in 1L..NATIVE_BACKEND_MAX_DEVICES.toLong()) return emptyList()
    val count = countLong.toInt()
    if (payload.size != NATIVE_BACKEND_HEADER_FIELDS + count * NATIVE_BACKEND_DEVICE_FIELDS) return emptyList()

    val capabilities = ArrayList<NativeBackendCapability>(count)
    repeat(count) { index ->
        val offset = NATIVE_BACKEND_HEADER_FIELDS + index * NATIVE_BACKEND_DEVICE_FIELDS
        val kind = NativeBackendKind.entries.getOrNull(payload[offset].toInt()) ?: return emptyList()
        val deviceType = NativeBackendDeviceType.entries.getOrNull(payload[offset + 1].toInt())
            ?: return emptyList()
        val freeBytes = payload[offset + 2].optionalNativeBytes() ?: if (payload[offset + 2] == -1L) null else return emptyList()
        val totalBytes = payload[offset + 3].optionalNativeBytes() ?: if (payload[offset + 3] == -1L) null else return emptyList()
        if (freeBytes != null && totalBytes != null && freeBytes > totalBytes) return emptyList()
        capabilities += NativeBackendCapability(kind, deviceType, freeBytes, totalBytes)
    }
    return capabilities
}

private fun Long.optionalNativeBytes(): Long? = takeIf { it >= 0L }
