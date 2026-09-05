package com.debanshu777.caraml.core.platform

import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.Confidence
import com.debanshu777.caraml.core.recommendation.Evidence
import com.sun.management.OperatingSystemMXBean
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "DeviceCapabilities"
private const val MAX_PROBE_OUTPUT_BYTES = 64 * 1024
private const val PROBE_TIMEOUT_SECONDS = 2L
private val VM_STAT_COMMAND = listOf("vm_stat")
private val TOTAL_MEMORY_COMMAND = listOf("sysctl", "-n", "hw.memsize")
private val PERFORMANCE_CORE_COMMAND = listOf("sysctl", "-n", "hw.perflevel0.physicalcpu")
private val LDCONFIG_COMMAND = listOf("ldconfig", "-p")

actual class DeviceCapabilities actual constructor() {

    private val cachedHints: DeviceHints by lazy { computeHints() }

    actual fun getDeviceHints(): DeviceHints {
        val base = cachedHints
        val availableBudgetMb = readAvailablePhysicalMemory().bytes?.let { available ->
            conservativeMemoryBudget(totalBytes = 0L, currentlyAvailableBytes = available) / MIB_BYTES
        } ?: base.memoryBudgetMB
        return base.copy(
            memoryBudgetMB = minOf(base.memoryBudgetMB, availableBudgetMb).coerceAtLeast(1L),
        )
    }

    actual fun getHardwareProfile(): HardwareProfile {
        val logicalReading = Runtime.getRuntime().availableProcessors()
        val logical = logicalReading.takeIf { it in 1..MAX_LOGICAL_CORE_COUNT }
        val isMac = osName().let { it.contains("mac") || it.contains("darwin") }
        val performanceReading = if (isMac) {
            readBoundedCommand(PERFORMANCE_CORE_COMMAND)?.trim()?.toIntOrNull()
                ?: logical?.let(::fallbackPerformanceCoreCount)
        } else {
            logical?.let(::fallbackPerformanceCoreCount)
        }
        val gpuKind = if (isMac) BackendKind.METAL else BackendKind.VULKAN
        return validatedHardwareProfile(
            cpuArchitecture = System.getProperty("os.arch").orEmpty(),
            logicalCoreCountReading = logicalReading,
            performanceCoreCountReading = performanceReading,
            instructionSets = setOfNotNull(System.getProperty("os.arch")),
            backends = listOf(
                cpuBackendCapability(),
                BackendCapability(
                    kind = gpuKind,
                    status = BackendStatus.UNKNOWN,
                    additionalAllocatableBytes = null,
                    evidence = listOf(
                        Evidence(
                            AssessmentReason.BACKEND_CAPABILITY_UNKNOWN,
                            Confidence.LOW,
                            "desktop-gpu-runner-registry-unchecked",
                        ),
                    ),
                ),
            ),
            memoryTopology = if (isMac) MemoryTopology.UNIFIED else MemoryTopology.UNKNOWN,
            evidence = emptyList(),
        )
    }

    actual fun getResourceSnapshot(): ResourceSnapshot {
        val reading = readAvailablePhysicalMemory()
        val evidence = reading.evidence.toMutableList()
        val available = reading.bytes ?: readTotalPhysicalMemoryBytes()?.let { total ->
            evidence += Evidence(
                AssessmentReason.RESOURCE_READING_UNAVAILABLE,
                Confidence.LOW,
                "available-memory-unavailable;conservative-total-fallback",
            )
            conservativeMemoryBudget(totalBytes = total, currentlyAvailableBytes = null)
                .takeIf { it > 0L }
        }
        if (available == null) {
            evidence += Evidence(
                AssessmentReason.RESOURCE_READING_UNAVAILABLE,
                Confidence.LOW,
                "desktop-memory-unavailable",
            )
        }
        val runtime = Runtime.getRuntime()
        val total = runtime.totalMemory()
        val free = runtime.freeMemory()
        val processBytes = if (total >= 0L && free >= 0L && total >= free) {
            total - free
        } else {
            evidence += Evidence(
                AssessmentReason.INVALID_OS_MEMORY_READING,
                Confidence.LOW,
                "jvm-process-memory",
            )
            null
        }
        return ResourceSnapshot(
            additionalAllocatableHostBytes = available,
            additionalAllocatableGpuBytes = null,
            currentProcessBytes = processBytes,
            freeStorageBytes = null,
            osPressureReserveHostBytes = null,
            observedAppFootprintNoiseP95Bytes = null,
            platformMinimumReserveHostBytes = 512L * MIB_BYTES,
            lowMemory = null,
            thermalState = ThermalState.UNKNOWN,
            powerPolicyState = PowerPolicyState.UNKNOWN,
            capturedAtEpochMs = System.currentTimeMillis(),
            evidence = evidence,
        )
    }

    private fun computeHints(): DeviceHints {
        val totalCores = Runtime.getRuntime().availableProcessors()
            .takeIf { it in 1..MAX_LOGICAL_CORE_COUNT }
            ?: 1
        val isMac = osName().let { it.contains("mac") || it.contains("darwin") }
        val perfCores = if (isMac) {
            detectMacPerformanceCores() ?: fallbackPerformanceCoreCount(totalCores)!!
        } else {
            fallbackPerformanceCoreCount(totalCores)!!
        }
        return DeviceHints(
            performanceCoreCount = perfCores.coerceIn(1, totalCores),
            totalCoreCount = totalCores,
            memoryBudgetMB = getPhysicalMemoryMB(),
            gpuBackendAvailable = isMac || detectDesktopVulkan(),
        )
    }

    private fun readAvailablePhysicalMemory(): MemoryReading {
        val bean = ManagementFactory.getOperatingSystemMXBean()
        if (bean is OperatingSystemMXBean) {
            val value = runCatching { bean.freeMemorySize }.getOrNull()
            if (value != null && value >= 0L) {
                return MemoryReading(
                    value,
                    listOf(validatedEvidence("OperatingSystemMXBean.freeMemorySize")),
                )
            }
        }

        return when {
            osName().contains("linux") -> readLinuxMemoryAvailable()
            osName().contains("mac") || osName().contains("darwin") -> readMacMemoryAvailable()
            else -> MemoryReading(null, listOf(unavailableEvidence("desktop-available-memory-api")))
        }
    }

    private fun readLinuxMemoryAvailable(): MemoryReading {
        val output = readBoundedFile(File("/proc/meminfo"))
            ?: return MemoryReading(null, listOf(unavailableEvidence("/proc/meminfo")))
        val kib = output.lineSequence()
            .firstOrNull { it.startsWith("MemAvailable:") }
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toLongOrNull()
        val bytes = kib?.takeIf { it >= 0L && it <= Long.MAX_VALUE / 1024L }?.times(1024L)
        return if (bytes != null) {
            MemoryReading(bytes, listOf(validatedEvidence("/proc/meminfo:MemAvailable")))
        } else {
            MemoryReading(null, listOf(invalidMemoryEvidence("/proc/meminfo:MemAvailable")))
        }
    }

    private fun readMacMemoryAvailable(): MemoryReading {
        val output = readBoundedCommand(VM_STAT_COMMAND)
            ?: return MemoryReading(null, listOf(unavailableEvidence("vm_stat")))
        val pageSize = Regex("page size of (\\d+) bytes")
            .find(output)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: return MemoryReading(null, listOf(invalidMemoryEvidence("vm_stat-page-size")))
        var pages = 0L
        for (line in output.lineSequence()) {
            if (
                !line.startsWith("Pages free:") &&
                !line.startsWith("Pages inactive:") &&
                !line.startsWith("Pages speculative:")
            ) continue
            val value = line.substringAfter(':').trim().trimEnd('.').toLongOrNull()
                ?: return MemoryReading(null, listOf(invalidMemoryEvidence("vm_stat-pages")))
            if (value < 0L || pages > Long.MAX_VALUE - value) {
                return MemoryReading(null, listOf(invalidMemoryEvidence("vm_stat-pages")))
            }
            pages += value
        }
        if (pages <= 0L || pages > Long.MAX_VALUE / pageSize) {
            return MemoryReading(null, listOf(invalidMemoryEvidence("vm_stat-available")))
        }
        return MemoryReading(
            pages * pageSize,
            listOf(validatedEvidence("vm_stat")),
        )
    }

    private fun readTotalPhysicalMemoryBytes(): Long? {
        val bean = ManagementFactory.getOperatingSystemMXBean()
        if (bean is OperatingSystemMXBean) {
            runCatching { bean.totalMemorySize }.getOrNull()?.takeIf { it > 0L }?.let { return it }
        }
        return when {
            osName().contains("mac") || osName().contains("darwin") ->
                readBoundedCommand(TOTAL_MEMORY_COMMAND)?.trim()?.toLongOrNull()?.takeIf { it > 0L }
            osName().contains("linux") -> {
                val output = readBoundedFile(File("/proc/meminfo")) ?: return null
                val kib = output.lineSequence().firstOrNull { it.startsWith("MemTotal:") }
                    ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: return null
                if (kib <= 0L || kib > Long.MAX_VALUE / 1024L) null else kib * 1024L
            }
            else -> null
        }
    }

    private fun getPhysicalMemoryMB(): Long {
        val budget = readTotalPhysicalMemoryBytes()?.let { conservativeMemoryBudget(it, null) }
        if (budget == null || budget <= 0L) {
            AppLogger.w(TAG, "Physical memory detection failed, using fallback 4096MB")
            return 4096L
        }
        return (budget / MIB_BYTES).coerceAtLeast(1L)
    }

    private fun detectMacPerformanceCores(): Int? =
        readBoundedCommand(PERFORMANCE_CORE_COMMAND)?.trim()?.toIntOrNull()
            ?.takeIf { it in 1..MAX_LOGICAL_CORE_COUNT }

    private fun readBoundedFile(file: File): String? = try {
        FileInputStream(file).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4_096)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (total > MAX_PROBE_OUTPUT_BYTES - count) return null
                output.write(buffer, 0, count)
                total += count
            }
            output.toString(StandardCharsets.UTF_8.name())
        }
    } catch (_: Exception) {
        null
    }

    private fun readBoundedCommand(command: List<String>): String? {
        if (command !in FIXED_PROBE_COMMANDS) return null
        var process: Process? = null
        return try {
            process = ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val output = ByteArrayOutputStream()
            val tooLarge = AtomicBoolean(false)
            val reader = Thread {
                try {
                    process.inputStream.use { input ->
                        val buffer = ByteArray(4_096)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (total > MAX_PROBE_OUTPUT_BYTES - count) {
                                tooLarge.set(true)
                                break
                            }
                            output.write(buffer, 0, count)
                            total += count
                        }
                    }
                } catch (_: Exception) {
                    // The owner closes the stream on timeout or oversized output.
                }
            }.apply {
                isDaemon = true
                name = "caraml-device-probe-reader"
                start()
            }
            val completed = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed || tooLarge.get()) {
                process.destroy()
                process.inputStream.close()
                if (process.isAlive) process.destroyForcibly()
                reader.join(100L)
                null
            } else {
                reader.join(250L)
                if (reader.isAlive || process.exitValue() != 0 || tooLarge.get()) null
                else output.toString(StandardCharsets.UTF_8.name()).trim()
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { process?.inputStream?.close() }
            runCatching { process?.errorStream?.close() }
            runCatching { process?.outputStream?.close() }
            if (process?.isAlive == true) {
                process.destroy()
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }

    private fun detectDesktopVulkan(): Boolean = when {
        osName().contains("linux") -> checkLinuxVulkan()
        osName().contains("win") -> checkWindowsVulkan()
        else -> false
    }

    private fun checkLinuxVulkan(): Boolean {
        val output = readBoundedCommand(LDCONFIG_COMMAND).orEmpty()
        if (output.contains("libvulkan.so")) return true
        return listOf(
            "/usr/lib/x86_64-linux-gnu/libvulkan.so.1",
            "/usr/lib/libvulkan.so.1",
            "/usr/local/lib/libvulkan.so.1",
        ).any { File(it).exists() }
    }

    private fun checkWindowsVulkan(): Boolean = try {
        val systemRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
        File("$systemRoot\\System32\\vulkan-1.dll").exists()
    } catch (_: Exception) {
        false
    }

    private fun osName(): String = System.getProperty("os.name").orEmpty().lowercase()

    private fun cpuBackendCapability() = BackendCapability(
        kind = BackendKind.CPU,
        status = BackendStatus.AVAILABLE,
        additionalAllocatableBytes = null,
        evidence = listOf(
            Evidence(
                AssessmentReason.BACKEND_CAPABILITY_VERIFIED,
                Confidence.HIGH,
                "jvm-cpu-runtime",
            ),
        ),
    )

    private fun validatedEvidence(detail: String) = Evidence(
        AssessmentReason.RESOURCE_READING_VALIDATED,
        Confidence.HIGH,
        detail,
    )

    private fun unavailableEvidence(detail: String) = Evidence(
        AssessmentReason.RESOURCE_READING_UNAVAILABLE,
        Confidence.LOW,
        detail,
    )

    private fun invalidMemoryEvidence(detail: String) = Evidence(
        AssessmentReason.INVALID_OS_MEMORY_READING,
        Confidence.LOW,
        detail,
    )

    private data class MemoryReading(
        val bytes: Long?,
        val evidence: List<Evidence>,
    )

    private companion object {
        val FIXED_PROBE_COMMANDS = setOf(
            VM_STAT_COMMAND,
            TOTAL_MEMORY_COMMAND,
            PERFORMANCE_CORE_COMMAND,
            LDCONFIG_COMMAND,
        )
    }
}
