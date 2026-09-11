package com.debanshu777.diffusionrunner

internal const val DIFFUSION_BACKEND_MAX_DEVICES = 16
internal const val DIFFUSION_BACKEND_HEADER_FIELDS = 1
internal const val DIFFUSION_BACKEND_DEVICE_FIELDS = 4

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
)

internal fun decodeDiffusionBackendCapabilities(payload: LongArray?): List<DiffusionBackendCapability> {
    if (payload == null || payload.size < DIFFUSION_BACKEND_HEADER_FIELDS) return emptyList()
    val countLong = payload[0]
    if (countLong !in 1L..DIFFUSION_BACKEND_MAX_DEVICES.toLong()) return emptyList()
    val count = countLong.toInt()
    if (payload.size != DIFFUSION_BACKEND_HEADER_FIELDS + count * DIFFUSION_BACKEND_DEVICE_FIELDS) {
        return emptyList()
    }

    val capabilities = ArrayList<DiffusionBackendCapability>(count)
    repeat(count) { index ->
        val offset = DIFFUSION_BACKEND_HEADER_FIELDS + index * DIFFUSION_BACKEND_DEVICE_FIELDS
        val kind = payload[offset].toBoundedIndex(DiffusionBackendKind.entries.size)
            ?.let(DiffusionBackendKind.entries::get) ?: return emptyList()
        val type = payload[offset + 1].toBoundedIndex(DiffusionBackendDeviceType.entries.size)
            ?.let(DiffusionBackendDeviceType.entries::get) ?: return emptyList()
        val freeBytes = payload[offset + 2].optionalBytes() ?: if (payload[offset + 2] == -1L) null else return emptyList()
        val totalBytes = payload[offset + 3].optionalBytes() ?: if (payload[offset + 3] == -1L) null else return emptyList()
        if (freeBytes != null && totalBytes != null && freeBytes > totalBytes) return emptyList()
        capabilities += DiffusionBackendCapability(kind, type, freeBytes, totalBytes)
    }
    return capabilities
}

private fun Long.optionalBytes(): Long? = takeIf { it >= 0L }

internal fun Long.toBoundedIndex(size: Int): Int? =
    takeIf { it in 0L until size.toLong() }?.toInt()
