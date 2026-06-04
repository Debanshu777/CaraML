package com.debanshu777.caraml.core.platform

import java.io.File

private const val TAG = "DeviceCapabilities"

actual class DeviceCapabilities actual constructor() {

    private val cachedHints: DeviceHints by lazy { computeHints() }

    actual fun getDeviceHints(): DeviceHints = cachedHints

    private fun computeHints(): DeviceHints {
        val totalCores = Runtime.getRuntime().availableProcessors()
        val isMac = System.getProperty("os.name").lowercase().contains("mac")

        val perfCores = if (isMac) {
            detectMacPerformanceCores() ?: (totalCores / 2)
        } else {
            totalCores / 2
        }

        return DeviceHints(
            performanceCoreCount = perfCores.coerceAtLeast(2),
            totalCoreCount = totalCores,
            memoryBudgetMB = getPhysicalMemoryMB(),
            gpuBackendAvailable = isMac || detectDesktopVulkan(),
        )
    }

    private fun getPhysicalMemoryMB(): Long {
        return try {
            val osName = System.getProperty("os.name").lowercase()
            val rawBytes: Long? = when {
                osName.contains("mac") || osName.contains("darwin") -> {
                    readCommandOutput("sysctl", "-n", "hw.memsize")?.toLongOrNull()
                }
                osName.contains("linux") -> {
                    File("/proc/meminfo").useLines { lines ->
                        lines.firstOrNull()
                            ?.split("\\s+".toRegex())
                            ?.getOrNull(1)
                            ?.toLongOrNull()
                            ?.let { it * 1024 }
                    }
                }
                osName.contains("windows") -> {
                    try {
                        val osBean = java.lang.management.ManagementFactory
                            .getOperatingSystemMXBean()
                        val method = osBean.javaClass
                            .getMethod("getTotalPhysicalMemorySize")
                        method.isAccessible = true
                        method.invoke(osBean) as? Long
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Windows memory detection via MXBean failed", e)
                        null
                    }
                }
                else -> null
            }
            if (rawBytes == null) {
                AppLogger.w(TAG, "Physical memory detection failed for OS=$osName, using fallback")
                return 4096L
            }
            val totalMB = rawBytes / (1024 * 1024)
            (totalMB * 0.70).toLong()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Physical memory detection failed", e)
            4096L
        }
    }

    private fun detectMacPerformanceCores(): Int? {
        return try {
            val output = readCommandOutput("sysctl", "-n", "hw.perflevel0.physicalcpu")
            output?.toIntOrNull()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Mac perf core detection failed", e)
            null
        }
    }

    private fun readCommandOutput(vararg command: String): String? {
        return try {
            val process = ProcessBuilder(*command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            process.waitFor()
            if (process.exitValue() == 0) output else null
        } catch (_: Exception) { null }
    }

    private fun detectDesktopVulkan(): Boolean {
        val osName = System.getProperty("os.name").lowercase()
        return when {
            osName.contains("linux") -> checkLinuxVulkan()
            osName.contains("win") -> checkWindowsVulkan()
            else -> false
        }
    }

    private fun checkLinuxVulkan(): Boolean {
        return try {
            val output = readCommandOutput("ldconfig", "-p") ?: ""
            val found = output.contains("libvulkan.so")
            if (!found) {
                // Fallback: probe common library paths
                return listOf(
                    "/usr/lib/x86_64-linux-gnu/libvulkan.so.1",
                    "/usr/lib/libvulkan.so.1",
                    "/usr/local/lib/libvulkan.so.1",
                ).any { java.io.File(it).exists() }
            }
            found
        } catch (_: Exception) { false }
    }

    private fun checkWindowsVulkan(): Boolean {
        return try {
            val systemRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
            java.io.File("$systemRoot\\System32\\vulkan-1.dll").exists()
        } catch (_: Exception) { false }
    }
}
