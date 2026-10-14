package com.debanshu777.huggingfacemanager.download

import kotlinx.coroutines.CancellationException
import okio.IOException

/** Fixed values only: callbacks never receive exception messages, identifiers or paths. */
enum class BundlePublicationStage {
    VALIDATE_INPUT, READ_ROOTS, RECOVER_ARTIFACTS, ROOTS_CHANGED, RECOVER_BUNDLE, ENTRY_LOOKUP,
    VALIDATE_ENTRIES, REPLACEMENT_PREPARE, CURRENT_DIGEST, WRITE_MANIFEST, WRITE_JOURNAL,
    PRESERVE_OLD, PUBLISH_MANIFEST, VERIFY_PUBLISHED, FINISH, COMPLETE,
}
enum class BundlePublicationReason {
    STARTED, COMPLETED, SAME_VERIFIED_BUNDLE, INVALID_INPUT, INTEGRITY, ROOTS_CHANGED, ENTRY_MISSING,
    REPLACEMENT_CONFLICT, REPLACEMENT_PLAN_INVALID, REPLACEMENT_PLAN_TOO_LARGE,
    DURABILITY, FILE_ACCESS, IO, CANCELLED, OTHER,
}
typealias BundlePublicationDiagnostics = (BundlePublicationStage, BundlePublicationReason) -> Unit

internal fun BundlePublicationDiagnostics?.emit(stage: BundlePublicationStage, reason: BundlePublicationReason) {
    // Cancellation remains a control signal; ordinary logging failures never alter publication.
    try { this?.invoke(stage, reason) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { /* Logging failure must not alter publication. */ }
}
internal fun bundlePublicationFailure(error: Exception): BundlePublicationReason = when (error) {
    is CancellationException -> BundlePublicationReason.CANCELLED
    is ArtifactVerificationException -> BundlePublicationReason.INTEGRITY
    is ArtifactDurabilityException -> BundlePublicationReason.DURABILITY
    is ArtifactFileAccessException -> BundlePublicationReason.FILE_ACCESS
    is IOException -> BundlePublicationReason.IO
    else -> BundlePublicationReason.OTHER
}
