package com.debanshu777.caraml.core.platform

data class DeviceHints(
    val performanceCoreCount: Int,
    val totalCoreCount: Int,
    val memoryBudgetMB: Long,
    val gpuBackendAvailable: Boolean,
    /** "4-7" or comma list on Android; empty on iOS/JVM. */
    val perfCoreMask: String = "",
)

expect class DeviceCapabilities() {
    fun getDeviceHints(): DeviceHints
}
