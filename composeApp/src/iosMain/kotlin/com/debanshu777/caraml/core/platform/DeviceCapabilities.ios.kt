package com.debanshu777.caraml.core.platform

import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.Confidence
import com.debanshu777.caraml.core.recommendation.Evidence
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.Foundation.NSDate
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSProcessInfoThermalState
import platform.Foundation.lowPowerModeEnabled
import platform.Foundation.thermalState
import platform.Foundation.timeIntervalSince1970
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.darwin.HOST_VM_INFO64
import platform.darwin.HOST_VM_INFO64_COUNT
import platform.darwin.KERN_SUCCESS
import platform.darwin.MACH_TASK_BASIC_INFO
import platform.darwin.MACH_TASK_BASIC_INFO_COUNT
import platform.darwin.host_statistics64
import platform.darwin.mach_msg_type_number_tVar
import platform.darwin.mach_host_self
import platform.darwin.mach_port_deallocate
import platform.darwin.mach_task_basic_info_data_t
import platform.darwin.mach_task_self_
import platform.darwin.sysctlbyname
import platform.darwin.task_info
import platform.darwin.vm_page_size
import platform.darwin.vm_statistics64_data_t
import platform.posix.uint64_tVar

private const val TAG = "DeviceCapabilities"

@OptIn(ExperimentalForeignApi::class)
actual class DeviceCapabilities actual constructor() {

    private val cachedHints: DeviceHints by lazy { computeHints() }

    actual fun getDeviceHints(): DeviceHints = cachedHints

    actual fun getHardwareProfile(): HardwareProfile {
        val logicalResult = validatedUnsignedCoreCount(
            NSProcessInfo.processInfo.activeProcessorCount,
            "active-processor-count",
        )
        val logicalReading = logicalResult.value
        val performanceReading = sysctlPerflevel0PhysicalCpuOrNull()
            ?: fallbackPerformanceCoreCount(logicalReading)
        val simulator = isRunningOnSimulator()
        val metalStatus = if (simulator) BackendStatus.UNAVAILABLE else BackendStatus.UNKNOWN
        val metalReason = if (simulator) {
            AssessmentReason.REQUIRED_BACKEND_UNAVAILABLE
        } else {
            AssessmentReason.BACKEND_CAPABILITY_UNKNOWN
        }
        return validatedHardwareProfile(
            cpuArchitecture = (NSProcessInfo.processInfo.environment["SIMULATOR_ARCHS"] as? String)
                ?: "arm64",
            logicalCoreCountReading = logicalReading,
            performanceCoreCountReading = performanceReading,
            instructionSets = emptySet(),
            backends = listOf(
                cpuBackendCapability(),
                BackendCapability(
                    kind = BackendKind.METAL,
                    status = metalStatus,
                    additionalAllocatableBytes = null,
                    evidence = listOf(
                        Evidence(metalReason, if (simulator) Confidence.HIGH else Confidence.LOW, "ios-metal-runner-registry-unchecked"),
                    ),
                ),
            ),
            memoryTopology = MemoryTopology.UNIFIED,
            evidence = logicalResult.evidence,
        )
    }

    actual fun getResourceSnapshot(): ResourceSnapshot {
        val evidence = mutableListOf<Evidence>()
        val available = readAvailableMemoryOrNull(evidence)
        val gpuHeadroom = readMetalHeadroomOrNull(evidence)
        val processBytes = readResidentSizeOrNull(evidence)
        return ResourceSnapshot(
            additionalAllocatableHostBytes = available,
            additionalAllocatableGpuBytes = gpuHeadroom,
            currentProcessBytes = processBytes,
            freeStorageBytes = null,
            osPressureReserveHostBytes = null,
            observedAppFootprintNoiseP95Bytes = null,
            platformMinimumReserveHostBytes = 384L * MIB_BYTES,
            lowMemory = null,
            thermalState = NSProcessInfo.processInfo.thermalState.toDomainState(),
            powerPolicyState = if (NSProcessInfo.processInfo.lowPowerModeEnabled) {
                PowerPolicyState.POWER_SAVER
            } else {
                PowerPolicyState.NORMAL
            },
            capturedAtEpochMs = (NSDate().timeIntervalSince1970 * 1_000.0).toLong(),
            evidence = evidence,
        )
    }

    private fun computeHints(): DeviceHints {
        val totalCores = validatedUnsignedCoreCount(
            NSProcessInfo.processInfo.activeProcessorCount,
            "active-processor-count",
        ).value
        val perfCores = sysctlPerflevel0PhysicalCpuOrNull()
            ?.takeIf { it in 1..totalCores }
            ?: fallbackPerformanceCoreCount(totalCores)!!
        val isSimulator = isRunningOnSimulator()

        return DeviceHints(
            performanceCoreCount = perfCores,
            totalCoreCount = totalCores,
            memoryBudgetMB = getMemoryBudgetMB(),
            gpuBackendAvailable = !isSimulator,
        )
    }

    private fun isRunningOnSimulator(): Boolean {
        return NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != null
    }

    private fun getMemoryBudgetMB(): Long {
        val physicalMemory = NSProcessInfo.processInfo.physicalMemory
        if (physicalMemory in 1uL..Long.MAX_VALUE.toULong()) {
            return physicalMemory.toLong().div(2L).div(1024L * 1024L).coerceAtLeast(1L)
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
                val totalMemBytes = value.value
                if (totalMemBytes <= Long.MAX_VALUE.toULong()) {
                    totalMemBytes.toLong().div(2L).div(1024L * 1024L).coerceAtLeast(1L)
                } else {
                    AppLogger.w(TAG, "hw.memsize exceeded signed range, using fallback 4096MB")
                    4096L
                }
            } else {
                AppLogger.w(TAG, "hw.memsize sysctl failed, using fallback 4096MB")
                4096L
            }
        }
    }

    private fun sysctlPerflevel0PhysicalCpuOrNull(): Int? {
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
                value.value.takeIf { it in 1..MAX_LOGICAL_CORE_COUNT }
            } else {
                AppLogger.w(TAG, "perflevel0 sysctl failed")
                null
            }
        }
    }

    private fun readMetalHeadroomOrNull(evidence: MutableList<Evidence>): Long? {
        if (isRunningOnSimulator()) {
            evidence += unavailableEvidence("metal-headroom-simulator")
            return null
        }
        val device = MTLCreateSystemDefaultDevice() ?: run {
            evidence += unavailableEvidence("metal-device-unavailable")
            return null
        }
        val recommended = device.recommendedMaxWorkingSetSize
        val allocated = device.currentAllocatedSize
        if (recommended < allocated) {
            evidence += invalidMemoryEvidence("metal-working-set-smaller-than-allocation")
            return null
        }
        val difference = recommended - allocated
        return checkedUnsignedBytes(difference, "metal-working-set-headroom", evidence)?.also {
            evidence += Evidence(
                AssessmentReason.RESOURCE_READING_VALIDATED,
                Confidence.HIGH,
                "metal-working-set-headroom",
            )
        }
    }

    private fun readResidentSizeOrNull(evidence: MutableList<Evidence>): Long? = memScoped {
        val info = alloc<mach_task_basic_info_data_t>()
        val count = alloc<mach_msg_type_number_tVar>()
        count.value = MACH_TASK_BASIC_INFO_COUNT.toUInt()
        val result = task_info(
            mach_task_self_,
            MACH_TASK_BASIC_INFO.toUInt(),
            info.ptr.reinterpret(),
            count.ptr,
        )
        if (result != KERN_SUCCESS) {
            evidence += unavailableEvidence("mach-task-basic-info")
            return@memScoped null
        }
        checkedUnsignedBytes(info.resident_size, "resident-size", evidence)?.also {
            evidence += Evidence(
                AssessmentReason.RESOURCE_READING_VALIDATED,
                Confidence.HIGH,
                "mach-task-resident-size",
            )
        }
    }

    private fun readAvailableMemoryOrNull(evidence: MutableList<Evidence>): Long? {
        val hostPort = mach_host_self()
        return withOwnedMachPort(
            port = hostPort,
            deallocate = { mach_port_deallocate(mach_task_self_, it) },
        ) { ownedHostPort ->
            memScoped {
                val stats = alloc<vm_statistics64_data_t>()
                val count = alloc<mach_msg_type_number_tVar>()
                count.value = HOST_VM_INFO64_COUNT
                val result = host_statistics64(
                    ownedHostPort,
                    HOST_VM_INFO64,
                    stats.ptr.reinterpret(),
                    count.ptr,
                )
                if (result != KERN_SUCCESS) {
                    evidence += unavailableEvidence("host_statistics64")
                    return@memScoped null
                }
                val free = stats.free_count.toULong()
                val inactive = stats.inactive_count.toULong()
                val speculative = stats.speculative_count.toULong()
                if (ULong.MAX_VALUE - free < inactive || ULong.MAX_VALUE - free - inactive < speculative) {
                    evidence += invalidMemoryEvidence("host_statistics64-page-count")
                    return@memScoped null
                }
                val pages = free + inactive + speculative
                val pageSize = vm_page_size
                if (pageSize == 0uL || pages > ULong.MAX_VALUE / pageSize) {
                    evidence += invalidMemoryEvidence("host_statistics64-byte-count")
                    return@memScoped null
                }
                val bytes = checkedUnsignedBytes(pages * pageSize, "host_statistics64", evidence)
                if (bytes == null || bytes <= 0L) {
                    evidence += unavailableEvidence("host_statistics64-empty")
                    null
                } else {
                    evidence += Evidence(
                        AssessmentReason.RESOURCE_READING_VALIDATED,
                        Confidence.HIGH,
                        "host_statistics64",
                    )
                    bytes
                }
            }
        }
    }

    private fun checkedUnsignedBytes(
        value: ULong,
        detail: String,
        evidence: MutableList<Evidence>,
    ): Long? {
        if (value > Long.MAX_VALUE.toULong()) {
            evidence += invalidMemoryEvidence(detail)
            return null
        }
        return value.toLong()
    }

    private fun NSProcessInfoThermalState.toDomainState(): ThermalState = when (this) {
        NSProcessInfoThermalState.NSProcessInfoThermalStateNominal -> ThermalState.NOMINAL
        NSProcessInfoThermalState.NSProcessInfoThermalStateFair -> ThermalState.FAIR
        NSProcessInfoThermalState.NSProcessInfoThermalStateSerious -> ThermalState.SERIOUS
        NSProcessInfoThermalState.NSProcessInfoThermalStateCritical -> ThermalState.CRITICAL
    }

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

    private fun cpuBackendCapability() = BackendCapability(
        kind = BackendKind.CPU,
        status = BackendStatus.AVAILABLE,
        additionalAllocatableBytes = null,
        evidence = listOf(
            Evidence(AssessmentReason.BACKEND_CAPABILITY_VERIFIED, Confidence.HIGH, "ios-cpu-runtime"),
        ),
    )
}
