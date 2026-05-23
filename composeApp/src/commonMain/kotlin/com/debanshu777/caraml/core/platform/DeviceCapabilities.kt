package com.debanshu777.caraml.core.platform

data class DeviceHints(
    val performanceCoreCount: Int,
    val totalCoreCount: Int,
    val memoryBudgetMB: Long,
    val gpuBackendAvailable: Boolean,
    val perfCoreMask: String = "",  // e.g. "4-7" or "" (no pinning)
)

interface DeviceCapabilities {
    fun getDeviceHints(): DeviceHints
}
