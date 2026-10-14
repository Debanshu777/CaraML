package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.features.modelhub.domain.RepositoryVariantSet
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode

sealed interface InstalledDescriptorLookup {
    data class Ready(val descriptor: ModelDescriptor) : InstalledDescriptorLookup
    data object RetryableUnavailable : InstalledDescriptorLookup
    data class Rejected(val reasons: List<AssessmentReason>) : InstalledDescriptorLookup
}

fun interface InstalledDescriptorMetadataSource {
    suspend fun findExact(
        repositoryId: String,
        mode: ModelHubBrowseMode,
        identities: List<ModelFileIdentity>,
    ): InstalledDescriptorLookup
}

internal fun exactInstalledDescriptorLookup(
    repositoryId: String,
    mode: ModelHubBrowseMode,
    identities: List<ModelFileIdentity>,
    variants: RepositoryVariantSet,
): InstalledDescriptorLookup {
    val requested = identities.exactInstalledIdentitySet()
        ?: return rejectedInvalidMetadata()
    return when (variants) {
        is RepositoryVariantSet.NeedsInformation -> InstalledDescriptorLookup.Rejected(
            variants.reasons.ifEmpty { listOf(AssessmentReason.INVALID_METADATA) },
        )
        is RepositoryVariantSet.SelectVariant -> InstalledDescriptorLookup.Rejected(
            variants.reasons.ifEmpty { listOf(AssessmentReason.INVALID_METADATA) },
        )
        is RepositoryVariantSet.Ready -> {
            if (variants.inputLimitExceeded) return rejectedInvalidMetadata()
            val matches = variants.variants.filter { variant ->
                variant.descriptor.repositoryId == repositoryId &&
                    variant.descriptor.matchesBrowseMode(mode) &&
                    variant.descriptor.requiredInstalledIdentities().exactInstalledIdentitySet() == requested
            }
            matches.singleOrNull()?.let { InstalledDescriptorLookup.Ready(it.descriptor) }
                ?: rejectedInvalidMetadata()
        }
    }
}

internal fun ModelDescriptor.requiredInstalledIdentities(): List<ModelFileIdentity> = when (this) {
    is LlmModelDescriptor -> files
    is DiffusionModelDescriptor -> components.map { it.file }
}

internal fun Collection<ModelFileIdentity>.hasSameExactInstalledIdentities(
    other: Collection<ModelFileIdentity>,
): Boolean {
    val left = exactInstalledIdentitySet() ?: return false
    val right = other.exactInstalledIdentitySet() ?: return false
    return left == right
}

internal fun Collection<ModelFileIdentity>.hasValidExactInstalledIdentitySet(): Boolean =
    exactInstalledIdentitySet() != null

internal fun ModelDescriptor.matchesBrowseMode(mode: ModelHubBrowseMode): Boolean = when (this) {
    is LlmModelDescriptor -> mode == ModelHubBrowseMode.LanguageModels
    is DiffusionModelDescriptor -> when (mode) {
        ModelHubBrowseMode.LanguageModels -> false
        ModelHubBrowseMode.DiffusionImage -> this.mode == DiffusionMode.IMAGE
        ModelHubBrowseMode.DiffusionVideo -> this.mode == DiffusionMode.VIDEO
    }
}

private fun Collection<ModelFileIdentity>.exactInstalledIdentitySet(): Set<ExactInstalledIdentity>? {
    if (isEmpty() || size > DescriptorLimits.MAX_COMPONENTS) return null
    val values = map { identity ->
        if (!identity.hasValidExactIdentity()) return null
        ExactInstalledIdentity(
            repositoryId = identity.repositoryId,
            revision = identity.revision.lowercase(),
            path = identity.path,
            sizeBytes = identity.sizeBytes,
            remoteObjectId = identity.canonicalDownloadRemoteObjectId()?.lowercase() ?: return null,
        )
    }.toSet()
    return values.takeIf { it.size == size }
}

private data class ExactInstalledIdentity(
    val repositoryId: String,
    val revision: String,
    val path: String,
    val sizeBytes: Long,
    val remoteObjectId: String,
)

private fun rejectedInvalidMetadata() =
    InstalledDescriptorLookup.Rejected(listOf(AssessmentReason.INVALID_METADATA))
