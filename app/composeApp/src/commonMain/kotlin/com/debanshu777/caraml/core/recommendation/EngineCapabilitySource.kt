package com.debanshu777.caraml.core.recommendation

fun interface EngineCapabilitySource {
    fun supportFor(descriptor: ModelDescriptor): SupportEvidence
}

sealed interface SupportEvidence {
    data object Supported : SupportEvidence

    data class Unsupported(
        val reasons: List<AssessmentReason>,
        val evidence: List<Evidence> = emptyList(),
    ) : SupportEvidence

    data class Unknown(
        val reasons: List<AssessmentReason> = listOf(AssessmentReason.ENGINE_SUPPORT_UNKNOWN),
        val evidence: List<Evidence> = listOf(
            Evidence(
                reason = AssessmentReason.ENGINE_SUPPORT_UNKNOWN,
                confidence = Confidence.LOW,
                detail = "native-capability-probe",
            ),
        ),
    ) : SupportEvidence
}

data object UnknownEngineCapabilitySource : EngineCapabilitySource {
    override fun supportFor(descriptor: ModelDescriptor): SupportEvidence = SupportEvidence.Unknown()
}
