package com.debanshu777.caraml.core.platform

import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.Confidence
import com.debanshu777.caraml.core.recommendation.Evidence
import com.debanshu777.diffusionrunner.DiffusionBackendCapability
import com.debanshu777.diffusionrunner.DiffusionBackendDeviceType
import com.debanshu777.diffusionrunner.DiffusionBackendKind
import com.debanshu777.diffusionrunner.DiffusionRunner
import com.debanshu777.runner.LlamaRunner
import com.debanshu777.runner.NativeBackendCapability
import com.debanshu777.runner.NativeBackendDeviceType
import com.debanshu777.runner.NativeBackendKind
import kotlinx.coroutines.CancellationException

class RunnerBackendCapabilitySource(
    private val llamaRunner: LlamaRunner,
    private val diffusionRunner: DiffusionRunner,
) : BackendCapabilitySource {
    override fun capabilities(): List<BackendCapability> = try {
        val llamaCapabilities = discoverWithInitializedRunner(
            trustedNativeLibraryDirectory = PlatformPaths::getNativeLibDir,
            initialize = llamaRunner::initialize,
            discover = llamaRunner::backendCapabilities,
        ).orEmpty()
        val diffusionCapabilities = discoverWithInitializedRunner(
            trustedNativeLibraryDirectory = PlatformPaths::getNativeLibDir,
            initialize = diffusionRunner::initialize,
            discover = diffusionRunner::backendCapabilities,
        ).orEmpty()
        if (llamaCapabilities.isEmpty() || diffusionCapabilities.isEmpty()) {
            unknownRegistryCapabilities()
        } else {
            mapRunnerBackendCapabilities(llamaCapabilities, diffusionCapabilities)
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
                            detail = "runner-native-registry",
                        ),
                    ),
                ),
            )
        }
    }
}

internal fun mapRunnerBackendCapabilities(
    llamaCapabilities: List<NativeBackendCapability>,
    diffusionCapabilities: List<DiffusionBackendCapability>,
): List<BackendCapability> {
    val llamaGrouped = llamaCapabilities
        .filter { it.kind != NativeBackendKind.OTHER && it.isComputeDeviceForKind() }
        .groupBy { it.kind.stableName }
    val diffusionGrouped = diffusionCapabilities
        .filter { it.kind != DiffusionBackendKind.OTHER && it.isComputeDeviceForKind() }
        .groupBy { it.kind.stableName }
    return BackendKind.entries.map { kind ->
        val commonStableKinds = llamaGrouped.keys.intersect(diffusionGrouped.keys).filter { stableName ->
            llamaGrouped.getValue(stableName).first().kind.toBackendKind() == kind &&
                diffusionGrouped.getValue(stableName).first().kind.toBackendKind() == kind
        }
        when {
            kind == BackendKind.CPU -> availableCpuCapability()
            commonStableKinds.isNotEmpty() -> {
                val freeBytes = commonStableKinds.fold(0L as Long?) { accumulated, stableName ->
                    val llamaFree = llamaGrouped.getValue(stableName).checkedLlamaFreeBytesSum()
                    val diffusionFree = diffusionGrouped.getValue(stableName).checkedDiffusionFreeBytesSum()
                    val commonFree = if (llamaFree != null && diffusionFree != null) {
                        minOf(llamaFree, diffusionFree)
                    } else {
                        null
                    }
                    if (accumulated == null || commonFree == null ||
                        accumulated > Long.MAX_VALUE - commonFree
                    ) null else accumulated + commonFree
                }
                BackendCapability(
                    kind = kind,
                    status = BackendStatus.AVAILABLE,
                    additionalAllocatableBytes = freeBytes,
                    availabilityConfidence = Confidence.HIGH,
                    headroomConfidence = freeBytes?.let { Confidence.HIGH },
                    evidence = listOf(verifiedBackendEvidence("runner-native-${kind.name.lowercase()}")),
                )
            }
            else -> BackendCapability(
                kind = kind,
                status = BackendStatus.UNAVAILABLE,
                additionalAllocatableBytes = null,
                availabilityConfidence = Confidence.HIGH,
                headroomConfidence = null,
                evidence = listOf(verifiedBackendEvidence("runner-native-not-common")),
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

private fun DiffusionBackendKind.toBackendKind(): BackendKind = when (this) {
    DiffusionBackendKind.CPU -> BackendKind.CPU
    DiffusionBackendKind.CUDA -> BackendKind.CUDA
    DiffusionBackendKind.METAL -> BackendKind.METAL
    DiffusionBackendKind.VULKAN -> BackendKind.VULKAN
    DiffusionBackendKind.OPENCL,
    DiffusionBackendKind.SYCL,
    DiffusionBackendKind.OTHER,
    -> BackendKind.OTHER
}

private fun NativeBackendCapability.isComputeDeviceForKind(): Boolean = when (kind) {
    NativeBackendKind.CPU -> deviceType == NativeBackendDeviceType.CPU
    else -> deviceType == NativeBackendDeviceType.DISCRETE_GPU ||
        deviceType == NativeBackendDeviceType.INTEGRATED_GPU
}

private fun DiffusionBackendCapability.isComputeDeviceForKind(): Boolean = when (kind) {
    DiffusionBackendKind.CPU -> deviceType == DiffusionBackendDeviceType.CPU
    else -> deviceType == DiffusionBackendDeviceType.DISCRETE_GPU ||
        deviceType == DiffusionBackendDeviceType.INTEGRATED_GPU
}

private fun List<NativeBackendCapability>.checkedLlamaFreeBytesSum(): Long? {
    var sum = 0L
    for (device in this) {
        val value = device.freeBytes ?: return null
        if (value < 0L || sum > Long.MAX_VALUE - value) return null
        sum += value
    }
    return sum
}

private fun List<DiffusionBackendCapability>.checkedDiffusionFreeBytesSum(): Long? {
    var sum = 0L
    for (device in this) {
        val value = device.freeBytes ?: return null
        if (value < 0L || sum > Long.MAX_VALUE - value) return null
        sum += value
    }
    return sum
}
