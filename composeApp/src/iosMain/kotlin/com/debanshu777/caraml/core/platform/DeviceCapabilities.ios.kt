package com.debanshu777.caraml.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.Foundation.NSProcessInfo
import platform.darwin.sysctlbyname
import platform.posix.uint64_tVar

private const val TAG = "DeviceCapabilities"

@OptIn(ExperimentalForeignApi::class)
actual class DeviceCapabilities actual constructor() {

    private val cachedHints: DeviceHints by lazy { computeHints() }

    actual fun getDeviceHints(): DeviceHints = cachedHints

    private fun computeHints(): DeviceHints {
        val perfCores = sysctlPerflevel0PhysicalCpu()
        val totalCores = NSProcessInfo.processInfo.activeProcessorCount.toInt()
        val isSimulator = isRunningOnSimulator()

        return DeviceHints(
            performanceCoreCount = perfCores.coerceAtLeast(2),
            totalCoreCount = totalCores,
            memoryBudgetMB = getMemoryBudgetMB(),
            gpuBackendAvailable = !isSimulator,
        )
    }

    private fun isRunningOnSimulator(): Boolean {
        return NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != null
    }

    private fun getMemoryBudgetMB(): Long {
        val totalBytes = NSProcessInfo.processInfo.physicalMemory.toLong()
        if (totalBytes > 0) {
            return (totalBytes * 0.50 / (1024 * 1024)).toLong()
        }

        AppLogger.w(TAG, "physicalMemory unavailable, falling back to hw.memsize sysctl")
        return getHwMemsizeFallbackMB()
    }

    private fun getHwMemsizeFallbackMB(): Long {
        return memScoped {
            val value = alloc<uint64_tVar>()
            val size = alloc<ULongVar>()
            size.value = sizeOf<uint64_tVar>().toULong()
            val result = sysctlbyname(
                "hw.memsize",
                value.ptr,
                size.ptr,
                null,
                0u
            )
            if (result == 0) {
                val totalMemBytes = value.value.toLong()
                (totalMemBytes * 0.50 / (1024 * 1024)).toLong()
            } else {
                AppLogger.w(TAG, "hw.memsize sysctl failed, using fallback 4096MB")
                4096L
            }
        }
    }

    private fun sysctlPerflevel0PhysicalCpu(): Int {
        return memScoped {
            val value = alloc<IntVar>()
            val size = alloc<ULongVar>()
            size.value = sizeOf<IntVar>().toULong()
            val result = sysctlbyname(
                "hw.perflevel0.physicalcpu",
                value.ptr,
                size.ptr,
                null,
                0u
            )
            if (result == 0) {
                value.value
            } else {
                val totalCores = NSProcessInfo.processInfo.activeProcessorCount.toInt()
                val fallback = (totalCores / 2).coerceAtLeast(2)
                AppLogger.w(TAG, "perflevel0 sysctl failed, using fallback=$fallback")
                fallback
            }
        }
    }
}
