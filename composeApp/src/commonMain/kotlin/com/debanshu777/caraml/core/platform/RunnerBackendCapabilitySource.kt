package com.debanshu777.caraml.core.platform

import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.Confidence
import com.debanshu777.caraml.core.recommendation.Evidence
import com.debanshu777.runner.LlamaRunner
import com.debanshu777.runner.NativeBackendCapability
import com.debanshu777.runner.NativeBackendKind
import kotlinx.coroutines.CancellationException

class RunnerBackendCapabilitySource(
    private val runner: LlamaRunner,
) : BackendCapabilitySource {
    override fun capabilities(): List<BackendCapability> = try {
        val nativeCapabilities = runner.backendCapabilities()
        if (nativeCapabilities.isEmpty()) {
            unknownRegistryCapabilities()
        } else {
            verifiedRegistryCapabilities(nativeCapabilities)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        unknownRegistryCapabilities()
    }

    private fun verifiedRegistryCapabilities(
        nativeCapabilities: List<NativeBackendCapability>,
    ): List<BackendCapability> {
        val grouped = nativeCapabilities.groupBy { it.kind.toBackendKind() }
        return BackendKind.entries.map { kind ->
            val devices = grouped[kind].orEmpty()
            when {
                kind == BackendKind.CPU && devices.isEmpty() -> availableCpu()
                devices.isNotEmpty() -> {
                    val freeBytes = devices.checkedFreeBytesSum()
                    BackendCapability(
                        kind = kind,
                        status = BackendStatus.AVAILABLE,
                        additionalAllocatableBytes = freeBytes,
                        availabilityConfidence = Confidence.HIGH,
                        headroomConfidence = freeBytes?.let { Confidence.HIGH },
                        evidence = listOf(verifiedEvidence("llama-native-${kind.name.lowercase()}")),
                    )
                }
                else -> BackendCapability(
                    kind = kind,
                    status = BackendStatus.UNAVAILABLE,
                    additionalAllocatableBytes = null,
                    availabilityConfidence = Confidence.HIGH,
                    headroomConfidence = null,
                    evidence = listOf(verifiedEvidence("llama-native-not-registered")),
                )
            }
        }
    }

    private fun unknownRegistryCapabilities(): List<BackendCapability> = buildList {
        add(availableCpu())
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

    private fun availableCpu() = BackendCapability(
        kind = BackendKind.CPU,
        status = BackendStatus.AVAILABLE,
        additionalAllocatableBytes = null,
        availabilityConfidence = Confidence.HIGH,
        headroomConfidence = null,
        evidence = listOf(verifiedEvidence("llama-native-cpu")),
    )

    private fun verifiedEvidence(detail: String) = Evidence(
        reason = AssessmentReason.BACKEND_CAPABILITY_VERIFIED,
        confidence = Confidence.HIGH,
        detail = detail,
    )
}

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
