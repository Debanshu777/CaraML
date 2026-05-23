package com.debanshu777.caraml.core.platform

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import java.io.File

private const val TAG = "DeviceCapabilities"

class AndroidDeviceCapabilities(
    private val context: Context,
) : DeviceCapabilities {

    private val cachedHints: DeviceHints by lazy { computeHints() }

    override fun getDeviceHints(): DeviceHints = cachedHints

    private fun computeHints(): DeviceHints {
        val totalCores = Runtime.getRuntime().availableProcessors()
        val capacityResult = detectViaCapacity(totalCores)
        val perfCores = capacityResult?.first
            ?: detectViaFrequency(totalCores)
            ?: (totalCores / 2).coerceIn(2, totalCores - 1).also {
                AppLogger.w(TAG, "Perf core detection: all strategies failed, using fallback=$it")
            }
        val perfMask = capacityResult?.second ?: ""
        AppLogger.i(TAG, { "perfCores=$perfCores, perfCoreMask=$perfMask" })
        return DeviceHints(
            performanceCoreCount = perfCores,
            totalCoreCount = totalCores,
            memoryBudgetMB = getDeviceMemoryMB(),
            gpuBackendAvailable = hasVulkanSupport(),
            perfCoreMask = perfMask,
        )
    }

    private fun getDeviceMemoryMB(): Long {
        return try {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            val totalMB = memInfo.totalMem / (1024 * 1024)
            (totalMB * 0.70).toLong()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Memory detection failed, using fallback 2048MB", e)
            2048L
        }
    }

    private fun detectPerformanceCores(totalCores: Int): Int {
        val capacityResult = detectViaCapacity(totalCores)
        if (capacityResult != null) return capacityResult.first

        val freqResult = detectViaFrequency(totalCores)
        if (freqResult != null) return freqResult

        val fallback = (totalCores / 2).coerceIn(2, totalCores - 1)
        AppLogger.w(TAG, "Perf core detection: all strategies failed, using fallback=$fallback")
        return fallback
    }

    private fun detectViaCapacity(totalCores: Int): Pair<Int, String>? {
        return try {
            val capacities = (0 until totalCores).mapNotNull { i ->
                try {
                    File("/sys/devices/system/cpu/cpu$i/cpu_capacity")
                        .readText().trim().toIntOrNull()?.let { cap -> i to cap }
                } catch (_: Exception) { null }
            }
            if (capacities.isEmpty()) return null

            val maxCap = capacities.maxOf { it.second }
            val threshold = (maxCap * 0.70).toInt()
            val perfIndices = capacities.filter { it.second >= threshold }.map { it.first }.sorted()
            val perfCount = perfIndices.size.coerceIn(2, totalCores - 1)

            val mask = buildString {
                if (perfIndices.isNotEmpty()) {
                    val min = perfIndices.first()
                    val max = perfIndices.last()
                    if (max - min == perfIndices.size - 1) {
                        append("$min-$max")
                    } else {
                        append(perfIndices.joinToString(","))
                    }
                }
            }

            perfCount to mask
        } catch (e: Exception) {
            AppLogger.w(TAG, "cpu_capacity detection failed", e)
            null
        }
    }

    private fun detectViaFrequency(totalCores: Int): Int? {
        return try {
            val frequencies = (0 until totalCores).mapNotNull { i ->
                try {
                    File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                        .readText().trim().toLongOrNull()
                } catch (_: Exception) { null }
            }
            if (frequencies.isEmpty()) return null

            val maxFreq = frequencies.max()
            val threshold = (maxFreq * 0.8).toLong()
            val perfCoreCount = frequencies.count { it >= threshold }
            perfCoreCount.coerceIn(2, totalCores - 1)
        } catch (e: Exception) {
            AppLogger.w(TAG, "cpufreq detection failed", e)
            null
        }
    }

    private fun hasVulkanSupport(): Boolean {
        return try {
            val packageManager = context.packageManager

            val hasVulkanHardware = packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
            if (!hasVulkanHardware) {
                AppLogger.i(TAG, { "Vulkan hardware feature not available" })
                return false
            }

            val vulkanLevel = try {
                packageManager.systemAvailableFeatures
                    .find { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL }
                    ?.version ?: 0
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to get Vulkan hardware level", e)
                0
            }

            if (vulkanLevel < 1) {
                AppLogger.i(TAG, { "Vulkan hardware level insufficient: $vulkanLevel (need >= 1)" })
                return false
            }

            // Probe whether ggml-vulkan backend .so is actually loadable.
            // Without this, gpuActive=true is sent to native even when GGML_VULKAN was
            // not compiled, causing misleading "GPU offload requested but unavailable" logs.
            val libLoadable = probeVulkanLib()
            AppLogger.i(TAG, { "Vulkan: level=$vulkanLevel, libLoadable=$libLoadable" })
            libLoadable

        } catch (e: Exception) {
            AppLogger.w(TAG, "Vulkan capability check failed", e)
            false
        }
    }

    companion object {
        @Volatile private var vulkanLibResult: Boolean? = null

        private fun probeVulkanLib(): Boolean {
            return vulkanLibResult ?: run {
                val result = try {
                    System.loadLibrary("ggml-vulkan")
                    true
                } catch (_: UnsatisfiedLinkError) {
                    false
                }
                vulkanLibResult = result
                result
            }
        }
    }
}
