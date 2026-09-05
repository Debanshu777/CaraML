package com.debanshu777.caraml.core.platform

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.Confidence
import com.debanshu777.caraml.core.recommendation.Evidence
import org.koin.mp.KoinPlatform
import java.io.File

private const val TAG = "DeviceCapabilities"

actual class DeviceCapabilities actual constructor() {

    private val cachedHints: DeviceHints by lazy { computeHints() }

    actual fun getDeviceHints(): DeviceHints {
        val base = cachedHints
        val currentBudget = try {
            val context = KoinPlatform.getKoin().get<Context>()
            getDeviceMemoryMB(context)
        } catch (_: Exception) {
            base.memoryBudgetMB
        }
        return base.copy(memoryBudgetMB = currentBudget)
    }

    actual fun getHardwareProfile(): HardwareProfile {
        val context = runCatching { KoinPlatform.getKoin().get<Context>() }.getOrNull()
        val logicalReading = Runtime.getRuntime().availableProcessors()
        val logical = logicalReading.takeIf { it in 1..MAX_LOGICAL_CORE_COUNT }
        val capacityResult = logical?.let(::detectViaCapacity)
        val performanceReading = capacityResult?.first
            ?: logical?.let(::detectViaFrequency)
            ?: logical?.let(::fallbackPerformanceCoreCount)
        val vulkanHint = context?.let(::hasVulkanHardwareHint)
        val vulkanEvidence = Evidence(
            reason = AssessmentReason.BACKEND_CAPABILITY_UNKNOWN,
            confidence = Confidence.LOW,
            detail = "android-vulkan-hardware-hint=$vulkanHint;runner-registry-unchecked",
        )
        return validatedHardwareProfile(
            cpuArchitecture = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            logicalCoreCountReading = logicalReading,
            performanceCoreCountReading = performanceReading,
            instructionSets = Build.SUPPORTED_ABIS.toSet(),
            backends = listOf(
                cpuBackendCapability(),
                BackendCapability(
                    kind = BackendKind.VULKAN,
                    status = BackendStatus.UNKNOWN,
                    additionalAllocatableBytes = null,
                    availabilityConfidence = Confidence.LOW,
                    headroomConfidence = null,
                    evidence = listOf(vulkanEvidence),
                ),
            ),
            memoryTopology = MemoryTopology.UNIFIED,
            evidence = emptyList(),
        )
    }

    actual fun getResourceSnapshot(): ResourceSnapshot {
        val capturedAt = System.currentTimeMillis()
        return try {
            val context = KoinPlatform.getKoin().get<Context>()
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return unavailableResourceSnapshot(capturedAt, "activity-manager-unavailable")
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val info = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
            val evidence = mutableListOf(
                Evidence(
                    AssessmentReason.RESOURCE_READING_VALIDATED,
                    Confidence.HIGH,
                    "ActivityManager.MemoryInfo",
                ),
            )
            val available = validatedMemoryReading(info.availMem, "availMem", evidence)
            val threshold = validatedMemoryReading(info.threshold, "threshold", evidence)
            val processBytes = Debug.getPss().let { kib ->
                if (kib < 0L || kib > Long.MAX_VALUE / 1024L) {
                    evidence += invalidMemoryEvidence("Debug.getPss")
                    null
                } else {
                    kib * 1024L
                }
            }
            ResourceSnapshot(
                additionalAllocatableHostBytes = available,
                additionalAllocatableGpuBytes = null,
                currentProcessBytes = processBytes,
                freeStorageBytes = null,
                osPressureReserveHostBytes = threshold,
                observedAppFootprintNoiseP95Bytes = null,
                platformMinimumReserveHostBytes = 384L * MIB_BYTES,
                lowMemory = info.lowMemory,
                thermalState = powerManager?.readThermalState() ?: ThermalState.UNKNOWN,
                powerPolicyState = powerManager?.let {
                    if (it.isPowerSaveMode) PowerPolicyState.POWER_SAVER else PowerPolicyState.NORMAL
                } ?: PowerPolicyState.UNKNOWN,
                capturedAtEpochMs = capturedAt,
                evidence = evidence,
                confidence = ResourcePoolConfidence(
                    host = available?.let { Confidence.HIGH },
                ),
            )
        } catch (exception: Exception) {
            AppLogger.w(TAG, "Resource snapshot collection failed", exception)
            unavailableResourceSnapshot(capturedAt, "android-resource-collection-failed")
        }
    }

    private fun computeHints(): DeviceHints {
        val context = try {
            KoinPlatform.getKoin().get<Context>()
        } catch (_: Exception) {
            AppLogger.w(TAG, "Koin context unavailable, using fallback DeviceHints")
            val logicalCoreCount = Runtime.getRuntime().availableProcessors()
                .takeIf { it in 1..MAX_LOGICAL_CORE_COUNT }
                ?: 1
            return DeviceHints(
                performanceCoreCount = fallbackPerformanceCoreCount(logicalCoreCount)!!,
                totalCoreCount = logicalCoreCount,
                memoryBudgetMB = 2048L,
                gpuBackendAvailable = false,
            )
        }

        val totalCores = Runtime.getRuntime().availableProcessors()
            .takeIf { it in 1..MAX_LOGICAL_CORE_COUNT }
            ?: 1
        val capacityResult = detectViaCapacity(totalCores)
        val perfCores = capacityResult?.first
            ?: detectViaFrequency(totalCores)
            ?: fallbackPerformanceCoreCount(totalCores)!!.also {
                AppLogger.w(TAG, "Perf core detection: all strategies failed, using fallback=$it")
            }
        val perfMask = capacityResult?.second ?: ""
        AppLogger.i(TAG, { "perfCores=$perfCores, perfCoreMask=$perfMask" })
        return DeviceHints(
            performanceCoreCount = perfCores,
            totalCoreCount = totalCores,
            memoryBudgetMB = getDeviceMemoryMB(context),
            gpuBackendAvailable = hasVulkanSupport(context),
            perfCoreMask = perfMask,
        )
    }

    private fun getDeviceMemoryMB(context: Context): Long {
        return try {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            conservativeMemoryBudget(
                totalBytes = memInfo.totalMem,
                currentlyAvailableBytes = memInfo.availMem,
            ).div(1024 * 1024).coerceAtLeast(1L)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Memory detection failed, using fallback 2048MB", e)
            2048L
        }
    }

    private fun detectViaCapacity(totalCores: Int): Pair<Int, String>? {
        return try {
            val capacities = (0 until totalCores).mapNotNull { i ->
                try {
                    File("/sys/devices/system/cpu/cpu$i/cpu_capacity")
                        .readText().trim().toIntOrNull()?.takeIf { it > 0 }?.let { cap -> i to cap }
                } catch (_: Exception) { null }
            }
            if (capacities.isEmpty()) return null

            val maxCap = capacities.maxOf { it.second }
            val threshold = (maxCap * 0.70).toInt()
            val perfIndices = capacities.filter { it.second >= threshold }.map { it.first }.sorted()
            val perfCount = perfIndices.size.coerceIn(1, totalCores)

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
                        .readText().trim().toLongOrNull()?.takeIf { it > 0L }
                } catch (_: Exception) { null }
            }
            if (frequencies.isEmpty()) return null

            val maxFreq = frequencies.max()
            val threshold = (maxFreq * 0.8).toLong()
            val perfCoreCount = frequencies.count { it >= threshold }
            perfCoreCount.coerceIn(1, totalCores)
        } catch (e: Exception) {
            AppLogger.w(TAG, "cpufreq detection failed", e)
            null
        }
    }

    private fun hasVulkanSupport(context: Context): Boolean {
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

            val libLoadable = probeVulkanLib()
            AppLogger.i(TAG, { "Vulkan: level=$vulkanLevel, libLoadable=$libLoadable" })
            libLoadable

        } catch (e: Exception) {
            AppLogger.w(TAG, "Vulkan capability check failed", e)
            false
        }
    }

    private fun hasVulkanHardwareHint(context: Context): Boolean = try {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
    } catch (_: Exception) {
        false
    }

    private fun PowerManager.readThermalState(): ThermalState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalState.UNKNOWN
        return when (currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalState.NOMINAL
            PowerManager.THERMAL_STATUS_LIGHT,
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.FAIR
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SERIOUS
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.CRITICAL
            else -> ThermalState.UNKNOWN
        }
    }

    private fun validatedMemoryReading(
        value: Long,
        name: String,
        evidence: MutableList<Evidence>,
    ): Long? = value.takeIf { it >= 0L } ?: run {
        evidence += invalidMemoryEvidence(name)
        null
    }

    private fun unavailableResourceSnapshot(capturedAt: Long, detail: String) = ResourceSnapshot(
        additionalAllocatableHostBytes = null,
        additionalAllocatableGpuBytes = null,
        currentProcessBytes = null,
        freeStorageBytes = null,
        osPressureReserveHostBytes = null,
        observedAppFootprintNoiseP95Bytes = null,
        platformMinimumReserveHostBytes = 384L * MIB_BYTES,
        lowMemory = null,
        thermalState = ThermalState.UNKNOWN,
        powerPolicyState = PowerPolicyState.UNKNOWN,
        capturedAtEpochMs = capturedAt,
        evidence = listOf(
            Evidence(AssessmentReason.RESOURCE_READING_UNAVAILABLE, Confidence.LOW, detail),
        ),
    )

    private fun invalidMemoryEvidence(detail: String) = Evidence(
        AssessmentReason.INVALID_OS_MEMORY_READING,
        Confidence.LOW,
        detail,
    )

    private fun cpuBackendCapability() = BackendCapability(
        kind = BackendKind.CPU,
        status = BackendStatus.AVAILABLE,
        additionalAllocatableBytes = null,
        availabilityConfidence = Confidence.HIGH,
        headroomConfidence = null,
        evidence = listOf(
            Evidence(AssessmentReason.BACKEND_CAPABILITY_VERIFIED, Confidence.HIGH, "android-cpu-runtime"),
        ),
    )

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
