package com.debanshu777.caraml.features.modelhub.domain

import com.debanshu777.caraml.core.recommendation.DescriptorLimits

internal enum class ModelArtifactRole {
    PRIMARY_MODEL,
    PROJECTOR,
    ADAPTER,
    UNVERIFIED,
}

internal enum class ModelArtifactRoleEvidence {
    EXPLICIT_FILENAME,
    MODEL_FORMAT_ONLY,
    INSUFFICIENT,
}

internal data class ModelArtifactClassification(
    val role: ModelArtifactRole,
    val evidence: ModelArtifactRoleEvidence,
) {
    val recommendationEligible: Boolean get() = role == ModelArtifactRole.PRIMARY_MODEL
}

/** LLM GGUF browsing only. Repo-wide tags never prove a specific file's role. */
internal object ModelArtifactClassifier {
    @Suppress("UNUSED_PARAMETER")
    fun classify(
        path: String?,
        repositoryTags: Collection<String?> = emptyList(),
    ): ModelArtifactClassification {
        if (path.isNullOrBlank() || path.length > DescriptorLimits.MAX_RELATIVE_PATH_LENGTH ||
            path.any(Char::isISOControl)
        ) {
            return unverified()
        }

        val name = path.substringAfterLast('/').lowercase()
        val nameTokens = tokens(name)
        if (nameTokens.any { it in PROJECTOR_TOKENS }) {
            return ModelArtifactClassification(ModelArtifactRole.PROJECTOR, ModelArtifactRoleEvidence.EXPLICIT_FILENAME)
        }
        if (nameTokens.any { it in ADAPTER_TOKENS }) {
            return ModelArtifactClassification(ModelArtifactRole.ADAPTER, ModelArtifactRoleEvidence.EXPLICIT_FILENAME)
        }

        if (nameTokens.any { it in DRAFT_TOKENS }) return unverified()

        if (!name.endsWith(".gguf")) return unverified()
        return ModelArtifactClassification(
            ModelArtifactRole.PRIMARY_MODEL,
            ModelArtifactRoleEvidence.MODEL_FORMAT_ONLY,
        )
    }

    private fun tokens(value: String): List<String> = value
        .split(TOKEN_SEPARATOR)
        .filter(String::isNotEmpty)

    private fun unverified() =
        ModelArtifactClassification(ModelArtifactRole.UNVERIFIED, ModelArtifactRoleEvidence.INSUFFICIENT)

    private val TOKEN_SEPARATOR = Regex("[^a-z0-9]+")
    private val PROJECTOR_TOKENS = setOf("mmproj", "projector")
    private val ADAPTER_TOKENS = setOf("adapter", "lora")
    private val DRAFT_TOKENS = setOf("draft", "mtp", "fastmtp", "speculative")
}
