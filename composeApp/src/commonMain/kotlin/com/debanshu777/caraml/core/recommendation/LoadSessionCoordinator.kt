package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.storage.catalog.InstalledModelPublicationCoordinator
import com.debanshu777.caraml.core.storage.catalog.artifactStorageCoordinationKey
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.artifactBundleId
import com.debanshu777.huggingfacemanager.download.persistedArtifactStorageLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath

sealed interface NativeLoadOutcome<out T> {
    data class Succeeded<T>(val value: T) : NativeLoadOutcome<T>
    data class Failed<T>(val value: T, val reason: StableLoadFailure) : NativeLoadOutcome<T>
}

sealed interface CoordinatedLoadResult<out T> {
    data class AdmissionRequired(val admission: LoadAdmission) : CoordinatedLoadResult<Nothing>
    data class ArtifactChanged(val request: LoadRequest) : CoordinatedLoadResult<Nothing>
    data class Completed<T>(val value: T) : CoordinatedLoadResult<T>
}

/**
 * Process-wide ownership for the complete exact-load transaction across both native engines.
 * The lock covers admission, both byte-identity checks, marker persistence, and native entry.
 */
class LoadSessionCoordinator(
    private val recoveryRepository: LoadRecoveryRepository,
    private val publicationCoordinator: InstalledModelPublicationCoordinator,
) {
    private val sessionMutex = Mutex()
    private var startupRecoveryCompleted = false

    suspend fun recoverAbandonedLoadAtStartup(): SuspectedLoadFailure? = sessionMutex.withLock {
        if (startupRecoveryCompleted) return@withLock null
        val recovered = recoveryRepository.recoverPendingLoad()
        startupRecoveryCompleted = true
        recovered
    }

    suspend fun <T> execute(
        request: LoadRequest,
        evaluateAdmission: suspend () -> LoadAdmission,
        artifactValidator: suspend (LoadRequest) -> Boolean,
        releasePartialState: suspend () -> Unit,
        nativeLoad: suspend () -> NativeLoadOutcome<T>,
    ): CoordinatedLoadResult<T> = sessionMutex.withLock {
        val admission = evaluateAdmission()
        val admitted = admission as? LoadAdmission.Ready
            ?: return@withLock CoordinatedLoadResult.AdmissionRequired(admission)
        if (!admitted.request.matchesExact(request)) {
            return@withLock CoordinatedLoadResult.ArtifactChanged(request)
        }
        val storageKeys = request.artifactLifetimeStorageKeys()
            ?: return@withLock CoordinatedLoadResult.ArtifactChanged(request)

        publicationCoordinator.withArtifactLifetime(storageKeys) {
            if (!artifactValidator(request)) {
                return@withArtifactLifetime CoordinatedLoadResult.ArtifactChanged(request)
            }
            val marker = recoveryRepository.beginLoad(request.identity, request.plan)
            try {
                when (val outcome = nativeLoad()) {
                    is NativeLoadOutcome.Succeeded -> {
                        recoveryRepository.markLoadSucceeded(marker)
                        CoordinatedLoadResult.Completed(outcome.value)
                    }
                    is NativeLoadOutcome.Failed -> {
                        withContext(NonCancellable) {
                            runCatching { releasePartialState() }
                            recoveryRepository.markLoadFailed(marker, outcome.reason)
                        }
                        CoordinatedLoadResult.Completed(outcome.value)
                    }
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    runCatching { releasePartialState() }
                    runCatching { recoveryRepository.markLoadCancelled(marker) }
                }
                throw cancelled
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    runCatching { releasePartialState() }
                    runCatching { recoveryRepository.markLoadFailed(marker, StableLoadFailure.UNKNOWN) }
                }
                throw failure
            }
        }
    }

    suspend fun allowExplicitRetry(request: LoadRequest, engineVersion: String) {
        if (request.artifact?.identity == request.identity) {
            recoveryRepository.allowExplicitRetry(request.identity, request.plan, engineVersion)
        }
    }

    private fun LoadRequest.matchesExact(other: LoadRequest): Boolean =
        identity == other.identity &&
            plan.stableKey == other.plan.stableKey &&
            assessmentKey == other.assessmentKey &&
            artifact == other.artifact
}

private fun LoadRequest.artifactLifetimeStorageKeys(): List<String>? {
    val resolved = artifact ?: return null
    if (resolved.identity != identity || resolved.identity.repositoryId != model.modelId ||
        resolved.components.isEmpty() || resolved.components.size > MAX_LIFETIME_COMPONENTS
    ) return null
    val keys = ArrayList<String>(resolved.components.size)
    val downloadIdentities = ArrayList<DownloadArtifactIdentity>(resolved.components.size)
    for (component in resolved.components) {
        if (component.identity.repositoryId != component.repositoryId ||
            component.identity.path != component.repositoryRelativePath ||
            component.identity.sizeBytes != component.byteCount ||
            !component.localPath.isCanonicalAbsolutePath()
        ) return null
        val bundle = component.bundleId ?: return null
        val downloadIdentity = DownloadArtifactIdentity.create(
            repositoryId = component.identity.repositoryId,
            immutableRevision = component.identity.revision,
            relativePath = component.identity.path,
            remoteObjectId = component.identity.gitOid ?: component.identity.lfsOid ?: component.identity.xetHash,
            expectedBytes = component.identity.sizeBytes,
        ) ?: return null
        downloadIdentities += downloadIdentity
        val location = persistedArtifactStorageLocation(downloadIdentity, bundle, component.localRelativePath)
            ?.takeIf { it.isScoped && it.layoutRelativePath == component.layoutRelativePath }
            ?: return null
        keys += runCatching {
            artifactStorageCoordinationKey(component.repositoryId, location.localRelativePath)
        }.getOrNull() ?: return null
    }
    val expectedBundle = artifactBundleId(downloadIdentities) ?: return null
    if (resolved.components.any { it.bundleId != expectedBundle } ||
        keys.distinct().size != keys.size || !resolved.hasBoundLoadTarget(model.localPath)
    ) return null
    return keys.sorted()
}

private fun ResolvedLocalArtifact.hasBoundLoadTarget(modelLocalPath: String): Boolean =
    when (val target = loadTarget) {
        is VerifiedArtifactLoadTarget.File ->
            target.path.isCanonicalAbsolutePath() && sameCanonicalPath(target.path, modelLocalPath) &&
                components.singleOrNull { component ->
                    component.logicalRole == target.componentRole &&
                        component.repositoryId == target.repositoryId &&
                        component.localRelativePath == target.localRelativePath &&
                        sameCanonicalPath(component.localPath, target.path)
                } != null
        is VerifiedArtifactLoadTarget.Directory -> {
            if (!target.path.isCanonicalAbsolutePath() || !sameCanonicalPath(target.path, modelLocalPath) ||
                target.storageOwner != identity.repositoryId ||
                target.nativeConsumedRelativePaths.isEmpty() ||
                target.nativeConsumedRelativePaths.distinct().size != target.nativeConsumedRelativePaths.size
            ) return false
            target.nativeConsumedRelativePaths.all { relativePath ->
                val expected = canonicalChildPath(target.path, relativePath) ?: return@all false
                components.singleOrNull { component ->
                    component.repositoryId == target.storageOwner &&
                        component.layoutRelativePath == relativePath &&
                        sameCanonicalPath(component.localPath, expected)
                } != null
            }
        }
    }

private fun String.isCanonicalAbsolutePath(): Boolean = runCatching {
    val raw = toPath(normalize = false)
    raw.isAbsolute && raw == raw.normalized()
}.getOrDefault(false)

private fun sameCanonicalPath(left: String, right: String): Boolean = runCatching {
    val leftPath = left.toPath(normalize = false)
    val rightPath = right.toPath(normalize = false)
    leftPath.isAbsolute && rightPath.isAbsolute &&
        leftPath == leftPath.normalized() && rightPath == rightPath.normalized() && leftPath == rightPath
}.getOrDefault(false)

private fun canonicalChildPath(root: String, relativePath: String): String? = runCatching {
    val rootPath = root.toPath(normalize = false)
    val relative = relativePath.toPath(normalize = false)
    if (!rootPath.isAbsolute || rootPath != rootPath.normalized()) return null
    if (relativePath.isBlank() || relative.isAbsolute || relative != relative.normalized() ||
        relativePath != relative.toString()
    ) return null
    val child = (rootPath / relative).normalized()
    if (child.segments.take(rootPath.segments.size) != rootPath.segments) return null
    child.toString()
}.getOrNull()

private const val MAX_LIFETIME_COMPONENTS = 64
