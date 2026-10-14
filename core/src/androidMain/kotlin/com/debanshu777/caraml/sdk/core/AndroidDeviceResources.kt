@file:OptIn(com.debanshu777.caraml.sdk.api.ExperimentalCaraMLApi::class)

package com.debanshu777.caraml.sdk.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import com.debanshu777.caraml.sdk.api.DeviceResourceSource
import com.debanshu777.caraml.sdk.api.ResourceSnapshot
import com.debanshu777.caraml.sdk.api.ThermalStatus
import java.io.File

/** Retains application context only; polling works without trim callbacks or app DI. */
fun androidDeviceResources(context: Context, outputDirectory: File? = null): DeviceResourceSource {
    val application = context.applicationContext
    return DeviceResourceSource {
        val memory = runCatching {
            val manager = application.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            manager?.let { ActivityManager.MemoryInfo().also(it::getMemoryInfo) }
        }.getOrNull()
        val thermal = if (Build.VERSION.SDK_INT >= 29) runCatching {
            val manager = application.getSystemService(Context.POWER_SERVICE) as? PowerManager
            when (manager?.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.NOMINAL
                PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.FAIR
                PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.SERIOUS
                PowerManager.THERMAL_STATUS_SEVERE,
                PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.CRITICAL
                else -> ThermalStatus.UNKNOWN
            }
        }.getOrDefault(ThermalStatus.UNKNOWN) else ThermalStatus.UNKNOWN
        ResourceSnapshot(
            availableMemoryBytes = memory?.availMem?.takeIf { it >= 0 },
            availableStorageBytes = outputDirectory?.let {
                runCatching { StatFs(it.absolutePath).availableBytes }.getOrNull()
            },
            lowMemory = memory?.lowMemory,
            thermalStatus = thermal,
            capturedAtEpochMillis = System.currentTimeMillis(),
        )
    }
}
