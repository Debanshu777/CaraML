package com.debanshu777.caraml.features.modelhub.domain

import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.ModelDescriptor
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode

/** Remote metadata boundary used by recommendation orchestration. */
fun interface ModelMetadataSource {
    suspend fun describeVariants(
        repositoryId: String,
        mode: ModelHubBrowseMode,
    ): RepositoryVariantSet
}

/** One exact runnable file, complete GGUF shard set, or explicit diffusion bundle. */
data class RepositoryVariant(
    val descriptor: ModelDescriptor,
    val displayName: String,
)

sealed interface RepositoryVariantSet {
    @ConsistentCopyVisibility
    data class Ready private constructor(
        val variants: List<RepositoryVariant>,
        internal val inputLimitExceeded: Boolean,
    ) : RepositoryVariantSet {
        constructor(variants: Collection<RepositoryVariant>) : this(boundedVariants(variants))

        private constructor(snapshot: BoundedVariants) : this(snapshot.values, snapshot.limitExceeded)
    }

    @ConsistentCopyVisibility
    data class SelectVariant private constructor(
        val repositoryId: String,
        val reasons: List<AssessmentReason>,
    ) : RepositoryVariantSet {
        constructor(
            repositoryId: String,
            reasons: Collection<AssessmentReason> = listOf(AssessmentReason.SELECT_VARIANT),
        ) : this(repositoryId, reasons.asSequence().take(MAX_RETAINED_REASONS).toList())
    }

    @ConsistentCopyVisibility
    data class NeedsInformation private constructor(
        val repositoryId: String,
        val reasons: List<AssessmentReason>,
    ) : RepositoryVariantSet {
        constructor(
            repositoryId: String,
            reasons: Collection<AssessmentReason> = listOf(AssessmentReason.INVALID_METADATA),
        ) : this(repositoryId, reasons.asSequence().take(MAX_RETAINED_REASONS).toList())
    }
}

private const val MAX_RETAINED_VARIANTS = 65
private val MAX_RETAINED_REASONS = AssessmentReason.entries.size

private data class BoundedVariants(
    val values: List<RepositoryVariant>,
    val limitExceeded: Boolean,
)

private fun boundedVariants(values: Collection<RepositoryVariant>): BoundedVariants {
    val snapshot = values.asSequence().take(MAX_RETAINED_VARIANTS).toList()
    return BoundedVariants(
        values = snapshot,
        limitExceeded = values.size > MAX_RETAINED_VARIANTS || snapshot.size > values.size,
    )
}
