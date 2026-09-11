package com.debanshu777.caraml.core.platform

import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.Confidence
import com.debanshu777.caraml.core.recommendation.Evidence
import com.debanshu777.runner.LlamaRunner
import com.debanshu777.runner.NativeBackendCapability
import com.debanshu777.runner.NativeBackendDeviceType
import com.debanshu777.runner.NativeBackendKind
import kotlinx.coroutines.CancellationException

class RunnerBackendCapabilitySource(
    private val runner: LlamaRunner,
) : BackendCapabilitySource {
    override fun capabilities(): List<BackendCapability> = try {
        val nativeCapabilities = discoverWithInitializedRunner(
            trustedNativeLibraryDirectory = PlatformPaths::getNativeLibDir,
            initialize = runner::initialize,
            discover = runner::backendCapabilities,
        ).orEmpty()
        if (nativeCapabilities.isEmpty()) {
            unknownRegistryCapabilities()
        } else {
            mapRunnerBackendCapabilities(nativeCapabilities)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        unknownRegistryCapabilities()
    }

    private fun unknownRegistryCapabilities(): List<BackendCapability> = buildList {
        add(availableCpuCapability())
        BackendKind.entries.filterNot { it == BackendKind.CPU }.forEach { kind ->
            add(
                BackendCapability(
                    kind = kind,
                    status = BackendStatus.UNKNOWN,
                    additionalAllocatableBytes = null,
                    availabilityConfidence = Confidence.LOW,
                    headroomConfidence = null,
                    evidence = listOf(
                        Evidence(
                            reason = AssessmentReason.BACKEND_CAPABILITY_UNKNOWN,
                            confidence = Confidence.LOW,
                            detail = "llama-native-registry",
                        ),
                    ),
                ),
            )
        }
    }
}

internal fun mapRunnerBackendCapabilities(
    nativeCapabilities: List<NativeBackendCapability>,
): List<BackendCapability> {
    val grouped = nativeCapabilities.groupBy { it.kind.toBackendKind() }
    return BackendKind.entries.map { kind ->
        val devices = grouped[kind].orEmpty().filter { capability ->
            when (kind) {
                BackendKind.CPU -> capability.deviceType == NativeBackendDeviceType.CPU
                else -> capability.deviceType == NativeBackendDeviceType.DISCRETE_GPU ||
                    capability.deviceType == NativeBackendDeviceType.INTEGRATED_GPU
            }
        }
        when {
            kind == BackendKind.CPU && devices.isEmpty() -> availableCpuCapability()
            devices.isNotEmpty() -> {
                val freeBytes = devices.checkedFreeBytesSum()
                BackendCapability(
                    kind = kind,
                    status = BackendStatus.AVAILABLE,
                    additionalAllocatableBytes = freeBytes,
                    availabilityConfidence = Confidence.HIGH,
                    headroomConfidence = freeBytes?.let { Confidence.HIGH },
                    evidence = listOf(verifiedBackendEvidence("llama-native-${kind.name.lowercase()}")),
                )
            }
            else -> BackendCapability(
                kind = kind,
                status = BackendStatus.UNAVAILABLE,
                additionalAllocatableBytes = null,
                availabilityConfidence = Confidence.HIGH,
                headroomConfidence = null,
                evidence = listOf(verifiedBackendEvidence("llama-native-not-registered")),
            )
        }
    }
}

private fun availableCpuCapability() = BackendCapability(
    kind = BackendKind.CPU,
    status = BackendStatus.AVAILABLE,
    additionalAllocatableBytes = null,
    availabilityConfidence = Confidence.HIGH,
    headroomConfidence = null,
    evidence = listOf(verifiedBackendEvidence("llama-native-cpu")),
)

private fun verifiedBackendEvidence(detail: String) = Evidence(
    reason = AssessmentReason.BACKEND_CAPABILITY_VERIFIED,
    confidence = Confidence.HIGH,
    detail = detail,
)

private fun NativeBackendKind.toBackendKind(): BackendKind = when (this) {
    NativeBackendKind.CPU -> BackendKind.CPU
    NativeBackendKind.CUDA -> BackendKind.CUDA
    NativeBackendKind.METAL -> BackendKind.METAL
    NativeBackendKind.VULKAN -> BackendKind.VULKAN
    NativeBackendKind.OPENCL,
    NativeBackendKind.SYCL,
    NativeBackendKind.OTHER,
    -> BackendKind.OTHER
}

private fun List<NativeBackendCapability>.checkedFreeBytesSum(): Long? {
    var sum = 0L
    for (device in this) {
        val value = device.freeBytes ?: return null
        if (value < 0L || sum > Long.MAX_VALUE - value) return null
        sum += value
    }
    return sum
}
