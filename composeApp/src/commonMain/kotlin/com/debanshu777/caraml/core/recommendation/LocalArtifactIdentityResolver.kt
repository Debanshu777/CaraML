package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.data.inference.NATIVE_DIFFUSERS_CONSUMED_PATHS
import com.debanshu777.caraml.core.data.inference.VerifiedDiffusionLoadTarget
import com.debanshu777.caraml.core.data.inference.isCompleteDiffusionInstallation
import com.debanshu777.caraml.core.data.inference.verifiedDiffusionLoadTarget
import com.debanshu777.caraml.core.storage.component.DownloadedComponentEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.huggingfacemanager.download.ArtifactManifest
import com.debanshu777.huggingfacemanager.download.ArtifactManifestEntry
import com.debanshu777.huggingfacemanager.download.StoredArtifactKind
import com.debanshu777.huggingfacemanager.download.StoredArtifactSnapshot
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.huggingfacemanager.sdcpp.getModelSetup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.FileSystem
import okio.HashingSource
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

data class RepositoryCommit(val repositoryId: String, val revision: String)

sealed interface RevisionIdentity {
    data class HubCommit(val commits: List<RepositoryCommit>) : RevisionIdentity
    data class LocalContent(val digest: String) : RevisionIdentity
}

data class ResolvedArtifactComponent(
    val logicalRole: String,
    val repositoryId: String,
    val repositoryRelativePath: String,
    val localPath: String,
    val byteCount: Long,
    val contentSha256: String,
    val identity: ModelFileIdentity,
    val localRelativePath: String = repositoryRelativePath,
)

sealed interface VerifiedArtifactLoadTarget {
    val path: String

    @ConsistentCopyVisibility
    data class File internal constructor(
        override val path: String,
        val componentRole: String,
        val repositoryId: String,
        val localRelativePath: String,
    ) : VerifiedArtifactLoadTarget

    @ConsistentCopyVisibility
    data class Directory internal constructor(
        override val path: String,
        val storageOwner: String,
        val nativeConsumedRelativePaths: List<String>,
    ) : VerifiedArtifactLoadTarget
}

@ConsistentCopyVisibility
data class ResolvedLocalArtifact internal constructor(
    val identity: ModelFileIdentity,
    val revisionIdentity: RevisionIdentity,
    val components: List<ResolvedArtifactComponent>,
    val loadTarget: VerifiedArtifactLoadTarget,
)

enum class ArtifactIdentityRejection {
    INVALID_INPUT,
    TOO_MANY_COMPONENTS,
    DUPLICATE_COMPONENT,
    UNREADABLE_OR_ESCAPING_PATH,
    STALE_MANIFEST,
    CONTENT_CHANGED_DURING_HASH,
    INCOMPLETE_DIRECTORY,
    ARTIFACT_TOO_LARGE,
    PERSISTENCE_FAILED,
}

sealed interface ArtifactIdentityResolution {
    data class Verified(val artifact: ResolvedLocalArtifact) : ArtifactIdentityResolution
    data class Rejected(val reason: ArtifactIdentityRejection) : ArtifactIdentityResolution
}

sealed interface LoadRequestResolution {
    data class Ready(val request: LoadRequest) : LoadRequestResolution
    data class Rejected(val reason: ArtifactIdentityRejection) : LoadRequestResolution
}

class LocalArtifactIdentityResolver(
    private val storagePathProvider: StoragePathProvider,
    private val manifestSource: suspend (String) -> ArtifactManifest?,
    private val hashingDispatcher: CoroutineDispatcher,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val hashFile: suspend (String, Long) -> String = { path, maxBytes ->
        hashRegularFile(fileSystem, path.toPath(), maxBytes)
    },
) {
    suspend fun resolve(
        model: LocalModelEntity,
        components: List<DownloadedComponentEntity>,
    ): ArtifactIdentityResolution {
        val inputs = validatedInputs(model, components)
            ?: return ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.INVALID_INPUT)
        if (inputs.size > MAX_COMPONENTS) {
            return ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.TOO_MANY_COMPONENTS)
        }
        if (hasDuplicateInputs(inputs)) {
            return ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.DUPLICATE_COMPONENT)
        }
        val manifest = try {
            manifestSource(model.modelId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.STALE_MANIFEST)
        }
        return manifest?.let { resolveHubManifest(model, inputs, it) } ?: resolveLegacy(model, inputs)
    }

    suspend fun revalidate(artifact: ResolvedLocalArtifact): Boolean {
        if (artifact.components.isEmpty() || artifact.components.size > MAX_COMPONENTS) return false
        if (artifact.components.distinctBy { Triple(it.logicalRole, it.repositoryId, it.localRelativePath) }.size !=
            artifact.components.size
        ) return false
        if (checkedResolvedArtifactBytes(artifact.components.map(ResolvedArtifactComponent::byteCount)) == null) {
            return false
        }
        val verified = ArrayList<VerifiedComponent>(artifact.components.size)
        for (component in artifact.components) {
            if (!component.identity.hasValidExactIdentity() ||
                component.identity.repositoryId != component.repositoryId ||
                component.identity.path != component.repositoryRelativePath ||
                component.identity.sizeBytes != component.byteCount ||
                !component.contentSha256.isSha256() ||
                !isValidRole(component.logicalRole) ||
                !isValidRelativePath(component.localRelativePath)
            ) return false
            val snapshot = verifyFile(
                component.repositoryId,
                component.localPath,
                component.byteCount,
                component.contentSha256,
            ) ?: return false
            verified += VerifiedComponent(
                logicalRole = component.logicalRole,
                repositoryId = component.repositoryId,
                repositoryRelativePath = component.repositoryRelativePath,
                localRelativePath = component.localRelativePath,
                localPath = component.localPath,
                byteCount = component.byteCount,
                contentSha256 = component.contentSha256,
                changeStamp = snapshot.changeStamp,
                immutableRevision = when (artifact.revisionIdentity) {
                    is RevisionIdentity.HubCommit -> component.identity.revision
                    is RevisionIdentity.LocalContent -> null
                },
                remoteObjectId = component.identity.gitOid ?: component.identity.lfsOid ?: component.identity.xetHash,
            )
        }
        val totalBytes = checkedResolvedArtifactBytes(verified.map(VerifiedComponent::byteCount)) ?: return false
        val aggregateDigest = when (val revision = artifact.revisionIdentity) {
            is RevisionIdentity.LocalContent -> {
                val digest = digestComponents(verified, includeRevision = false)
                if (revision.digest != digest) return false
                digest
            }
            is RevisionIdentity.HubCommit -> {
                val expectedCommits = verified.map {
                    RepositoryCommit(it.repositoryId, it.immutableRevision ?: return false)
                }.distinct().sortedWith(compareBy(RepositoryCommit::repositoryId, RepositoryCommit::revision))
                if (revision.commits != expectedCommits) return false
                digestComponents(verified, includeRevision = true)
            }
        }
        if (artifact.identity.sizeBytes != totalBytes ||
            artifact.identity.revision != aggregateDigest ||
            artifact.identity.lfsOid != "sha256:$aggregateDigest" ||
            !artifact.identity.hasValidExactIdentity()
        ) return false
        return artifact.hasValidLoadTarget()
    }

    suspend fun createLoadRequest(
        model: LocalModelEntity,
        components: List<DownloadedComponentEntity>,
        descriptor: ModelDescriptor,
        assessment: ModelAssessment,
        recommendation: PersonalizedRecommendation,
    ): LoadRequestResolution {
        val observationIdentity = ObservationModelIdentity.fromDescriptor(descriptor)
        if (descriptor.repositoryId != model.modelId || observationIdentity == null ||
            assessment.assessmentKey.isBlank() || assessment.assessmentKey != recommendation.assessmentKey ||
            assessment.planAssessments.assessmentKey != assessment.assessmentKey
        ) {
            return LoadRequestResolution.Rejected(ArtifactIdentityRejection.INVALID_INPUT)
        }
        val selected = recommendation.selectedPlan as? RunPlan
            ?: return LoadRequestResolution.Rejected(ArtifactIdentityRejection.INVALID_INPUT)
        val selectedAssessment = assessment.planAssessments.values
            .filter { it.plan.stableKey == selected.stableKey }
            .singleOrNull()
        if (selectedAssessment == null || recommendation.selectedPlanAssessment != selectedAssessment) {
            return LoadRequestResolution.Rejected(ArtifactIdentityRejection.INVALID_INPUT)
        }
        return when (val resolved = resolve(model, components)) {
            is ArtifactIdentityResolution.Rejected -> LoadRequestResolution.Rejected(resolved.reason)
            is ArtifactIdentityResolution.Verified -> {
                if (!descriptorMatchesResolvedArtifact(descriptor, resolved.artifact)) {
                    LoadRequestResolution.Rejected(ArtifactIdentityRejection.STALE_MANIFEST)
                } else {
                    LoadRequestResolution.Ready(
                        LoadRequest(
                            model = model,
                            identity = resolved.artifact.identity,
                            observationIdentity = observationIdentity,
                            plan = selected,
                            assessmentKey = assessment.assessmentKey,
                            artifact = resolved.artifact,
                            assessedPlans = assessment.planAssessments,
                            profile = recommendation.profile,
                        ),
                    )
                }
            }
        }
    }

    private fun descriptorMatchesResolvedArtifact(
        descriptor: ModelDescriptor,
        artifact: ResolvedLocalArtifact,
    ): Boolean {
        val expected = when (descriptor) {
            is LlmModelDescriptor -> descriptor.files
            is DiffusionModelDescriptor -> descriptor.components.map(DiffusionComponentDescriptor::file)
        }
        if (expected.isEmpty() || expected.size != artifact.components.size) return false
        val unmatched = artifact.components.map(ResolvedArtifactComponent::identity).toMutableList()
        return expected.all { identity ->
            val index = unmatched.indexOfFirst { resolved -> identity.matchesExactResolvedIdentity(resolved) }
            if (index < 0) false else {
                unmatched.removeAt(index)
                true
            }
        } && unmatched.isEmpty()
    }

    private fun ModelFileIdentity.matchesExactResolvedIdentity(other: ModelFileIdentity): Boolean =
        repositoryId == other.repositoryId &&
            revision.equals(other.revision, ignoreCase = true) &&
            path == other.path &&
            sizeBytes == other.sizeBytes &&
            canonicalObjectIds().intersect(other.canonicalObjectIds()).isNotEmpty()

    private fun ModelFileIdentity.canonicalObjectIds(): Set<String> = buildSet(3) {
        gitOid?.let { add("git:${it.lowercase()}") }
        lfsOid?.let {
            val normalized = it.lowercase().removePrefix("sha256:")
            add("lfs:sha256:$normalized")
        }
        xetHash?.let { add("xet:${it.lowercase()}") }
    }

    private suspend fun resolveHubManifest(
        model: LocalModelEntity,
        inputs: List<InputComponent>,
        manifest: ArtifactManifest,
    ): ArtifactIdentityResolution {
        if (manifest.entries.isEmpty() || manifest.entries.size > MAX_COMPONENTS) {
            return ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.STALE_MANIFEST)
        }
        if (checkedResolvedArtifactBytes(manifest.entries.map(ArtifactManifestEntry::byteCount)) == null) {
            return ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.ARTIFACT_TOO_LARGE)
        }
        val directoryRoot = when (manifest.verifiedDiffusionLoadTarget(model.modelId)) {
            VerifiedDiffusionLoadTarget.Directory -> verifiedDirectoryRoot(model, manifest)
                ?: return ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.INCOMPLETE_DIRECTORY)
            else -> null
        }
        val byRemoteIdentity = inputs.associateBy { it.repositoryId to it.repositoryRelativePath }
        val resolved = ArrayList<VerifiedComponent>(manifest.entries.size)
        for (entry in manifest.entries) {
            val supplied = byRemoteIdentity[entry.identity.repositoryId to entry.identity.relativePath]
            val nativePath = directoryRoot
                ?.takeIf {
                    entry.identity.repositoryId == model.modelId &&
                        entry.localRelativePath in NATIVE_DIFFUSERS_CONSUMED_PATHS
                }
                ?.let { root -> localPathUnder(root, entry.localRelativePath) }
            if (nativePath != null && supplied != null && !sameNormalizedPath(supplied.localPath, nativePath)) {
                return ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.INCOMPLETE_DIRECTORY)
            }
            val localPath = nativePath ?: supplied?.localPath ?: localPathFor(entry)
            val owner = if (nativePath != null) model.modelId else supplied?.storageOwner ?: entry.identity.repositoryId
            val verified = verifyFile(owner, localPath, entry.byteCount, entry.contentSha256)
                ?: return ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.STALE_MANIFEST)
            resolved += VerifiedComponent(
                logicalRole = entry.logicalRole,
                repositoryId = entry.identity.repositoryId,
                repositoryRelativePath = entry.identity.relativePath,
                localRelativePath = entry.localRelativePath,
                localPath = localPath,
                byteCount = verified.byteCount,
                contentSha256 = entry.contentSha256.lowercase(),
                changeStamp = verified.changeStamp,
                immutableRevision = entry.identity.immutableRevision.lowercase(),
                remoteObjectId = entry.identity.remoteObjectId,
            )
        }
        if (resolved.none { it.repositoryId == model.modelId } || !allSuppliedInputsCovered(inputs, resolved)) {
            return ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.STALE_MANIFEST)
        }
        val commits = resolved.map { RepositoryCommit(it.repositoryId, checkNotNull(it.immutableRevision)) }
            .distinct()
            .sortedWith(compareBy(RepositoryCommit::repositoryId, RepositoryCommit::revision))
        return buildArtifact(model, resolved, RevisionIdentity.HubCommit(commits), manifest)
    }

    private suspend fun resolveLegacy(
        model: LocalModelEntity,
        inputs: List<InputComponent>,
    ): ArtifactIdentityResolution {
        if (storagePathProvider.inspectDownloadedArtifact(model.modelId, model.localPath)?.kind == StoredArtifactKind.DIRECTORY) {
            return ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.INCOMPLETE_DIRECTORY)
        }
        if (inputs.isEmpty()) return ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.INVALID_INPUT)
        val sidecarPath = model.sidecarPath()
        val cached = readSidecar(sidecarPath)
        val cachedComponents = cached?.components?.takeIf { entries ->
            entries.size == inputs.size && entries.map(SidecarComponent::key).toSet() == inputs.map(InputComponent::key).toSet()
        }
        if (cached != null && cachedComponents != null) {
            reuseUnchanged(inputs, cachedComponents)?.let { reused ->
                val digest = digestComponents(reused, includeRevision = false)
                if (digest == cached.aggregateDigest) {
                    return buildArtifact(model, reused, RevisionIdentity.LocalContent(digest), manifest = null)
                }
            }
        }

        val inspected = ArrayList<Pair<InputComponent, StoredArtifactSnapshot>>(inputs.size)
        for (input in inputs) {
            val snapshot = storagePathProvider.inspectDownloadedArtifact(input.storageOwner, input.localPath)
                ?: return ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.UNREADABLE_OR_ESCAPING_PATH)
            if (snapshot.kind != StoredArtifactKind.REGULAR_FILE) {
                return ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.UNREADABLE_OR_ESCAPING_PATH)
            }
            if (snapshot.byteCount !in 1..DescriptorLimits.MAX_FILE_BYTES) {
                return ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.ARTIFACT_TOO_LARGE)
            }
            inspected += input to snapshot
        }
        if (checkedResolvedArtifactBytes(inspected.map { it.second.byteCount }) == null) {
            return ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.ARTIFACT_TOO_LARGE)
        }

        val verified = ArrayList<VerifiedComponent>(inputs.size)
        try {
            for ((input, before) in inspected) {
                val digest = hashOnTrustedDispatcher(input.localPath, before.byteCount)
                    .lowercase()
                    .takeIf { it.isSha256() }
                    ?: return ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.INVALID_INPUT)
                val after = storagePathProvider.inspectDownloadedArtifact(input.storageOwner, input.localPath)
                if (after != before) {
                    return ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.CONTENT_CHANGED_DURING_HASH)
                }
                verified += input.verified(before, digest)
            }
            val digest = digestComponents(verified, includeRevision = false)
            writeSidecar(
                sidecarPath,
                LegacyIdentitySidecar(LEGACY_MANIFEST_VERSION, digest, verified.map(VerifiedComponent::toSidecar)),
            )
            return buildArtifact(model, verified, RevisionIdentity.LocalContent(digest), manifest = null)
        } catch (cancelled: CancellationException) {
            deletePart(sidecarPath)
            throw cancelled
        } catch (_: Throwable) {
            deletePart(sidecarPath)
            return ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.PERSISTENCE_FAILED)
        }
    }

    private fun validatedInputs(
        model: LocalModelEntity,
        components: List<DownloadedComponentEntity>,
    ): List<InputComponent>? {
        if (!isValidRepositoryId(model.modelId) || components.size > MAX_COMPONENTS) return null
        val result = ArrayList<InputComponent>(components.size + 1)
        when (storagePathProvider.inspectDownloadedArtifact(model.modelId, model.localPath)?.kind) {
            StoredArtifactKind.REGULAR_FILE -> {
                if (!isValidRelativePath(model.filename)) return null
                result += InputComponent("model", model.modelId, model.filename, model.localPath, model.modelId)
            }
            StoredArtifactKind.DIRECTORY -> Unit
            null -> return null
        }
        for (component in components.asSequence().take(MAX_COMPONENTS + 1)) {
            if (!isValidRole(component.role) || !isValidRepositoryId(component.repoId) || !isValidRelativePath(component.filePath)) {
                return null
            }
            result += InputComponent(component.role, component.repoId, component.filePath, component.localPath, component.repoId)
        }
        return result
    }

    private fun hasDuplicateInputs(inputs: List<InputComponent>): Boolean {
        val roles = HashSet<String>(inputs.size)
        val remotePaths = HashSet<Pair<String, String>>(inputs.size)
        val localPaths = HashSet<String>(inputs.size)
        return inputs.any {
            !roles.add(it.logicalRole) || !remotePaths.add(it.repositoryId to it.repositoryRelativePath) || !localPaths.add(it.localPath)
        }
    }

    private fun allSuppliedInputsCovered(inputs: List<InputComponent>, verified: List<VerifiedComponent>): Boolean {
        val verifiedKeys = verified.mapTo(HashSet()) { it.repositoryId to it.repositoryRelativePath }
        return inputs.all { (it.repositoryId to it.repositoryRelativePath) in verifiedKeys }
    }

    private suspend fun verifyFile(
        storageOwner: String,
        localPath: String,
        expectedBytes: Long,
        expectedSha256: String,
    ): StoredArtifactSnapshot? {
        val before = storagePathProvider.inspectDownloadedArtifact(storageOwner, localPath)
            ?.takeIf { it.kind == StoredArtifactKind.REGULAR_FILE && it.byteCount == expectedBytes }
            ?: return null
        val actual = try {
            hashOnTrustedDispatcher(localPath, expectedBytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return null
        }
        val after = storagePathProvider.inspectDownloadedArtifact(storageOwner, localPath)
        return before.takeIf { after == before && actual.equals(expectedSha256, ignoreCase = true) }
    }

    private fun reuseUnchanged(inputs: List<InputComponent>, cached: List<SidecarComponent>): List<VerifiedComponent>? {
        val byKey = cached.associateBy(SidecarComponent::key)
        val result = ArrayList<VerifiedComponent>(inputs.size)
        for (input in inputs) {
            val prior = byKey[input.key()] ?: return null
            val snapshot = storagePathProvider.inspectDownloadedArtifact(input.storageOwner, input.localPath)
                ?.takeIf {
                    it.kind == StoredArtifactKind.REGULAR_FILE &&
                        it.byteCount == prior.byteCount && it.changeStamp == prior.changeStamp
                } ?: return null
            result += input.verified(snapshot, prior.contentSha256)
        }
        return result
    }

    private suspend fun hashOnTrustedDispatcher(path: String, expectedBytes: Long): String {
        var originalCancellation: CancellationException? = null
        return try {
            withContext(hashingDispatcher) {
                try {
                    hashFile(path, expectedBytes)
                } catch (cancelled: CancellationException) {
                    originalCancellation = cancelled
                    throw cancelled
                }
            }
        } catch (cancelled: CancellationException) {
            throw originalCancellation ?: cancelled
        }
    }

    private fun buildArtifact(
        model: LocalModelEntity,
        components: List<VerifiedComponent>,
        revisionIdentity: RevisionIdentity,
        manifest: ArtifactManifest?,
    ): ArtifactIdentityResolution {
        val ordered = components.sortedWith(componentComparator)
        val totalBytes = checkedResolvedArtifactBytes(ordered.map(VerifiedComponent::byteCount))
            ?: return ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.ARTIFACT_TOO_LARGE)
        val loadTarget = resolvedLoadTarget(model, ordered, manifest)
            ?: return ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.INCOMPLETE_DIRECTORY)
        val artifact = ordered.toArtifact(model, revisionIdentity, totalBytes, loadTarget)
            ?: return ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.INVALID_INPUT)
        return ArtifactIdentityResolution.Verified(artifact)
    }

    private fun List<VerifiedComponent>.toArtifact(
        model: LocalModelEntity,
        revisionIdentity: RevisionIdentity,
        totalBytes: Long,
        loadTarget: VerifiedArtifactLoadTarget,
    ): ResolvedLocalArtifact? {
        val ordered = this
        val aggregateDigest = when (revisionIdentity) {
            is RevisionIdentity.LocalContent -> revisionIdentity.digest
            is RevisionIdentity.HubCommit -> digestComponents(ordered, includeRevision = true)
        }
        val aggregateIdentity = ModelFileIdentity(
            repositoryId = model.modelId,
            revision = aggregateDigest,
            path = model.filename.takeIf(::isValidRelativePath) ?: ordered.first().repositoryRelativePath,
            sizeBytes = totalBytes,
            gitOid = null,
            lfsOid = "sha256:$aggregateDigest",
            xetHash = null,
            evidence = emptyList(),
        )
        if (!aggregateIdentity.hasValidExactIdentity()) return null
        return ResolvedLocalArtifact(
            identity = aggregateIdentity,
            revisionIdentity = revisionIdentity,
            components = ordered.map { component ->
                val remote = component.remoteObjectId
                val identity = ModelFileIdentity(
                    repositoryId = component.repositoryId,
                    revision = component.immutableRevision ?: aggregateDigest,
                    path = component.repositoryRelativePath,
                    sizeBytes = component.byteCount,
                    gitOid = remote?.takeIf { it.length == 40 && !it.startsWith("sha256:") },
                    lfsOid = remote?.takeIf { it.startsWith("sha256:") } ?: "sha256:${component.contentSha256}",
                    xetHash = remote?.takeIf { it.length != 40 && !it.startsWith("sha256:") },
                    evidence = emptyList(),
                )
                ResolvedArtifactComponent(
                    component.logicalRole,
                    component.repositoryId,
                    component.repositoryRelativePath,
                    component.localPath,
                    component.byteCount,
                    component.contentSha256,
                    identity,
                    component.localRelativePath,
                )
            },
            loadTarget = loadTarget,
        )
    }

    private fun resolvedLoadTarget(
        model: LocalModelEntity,
        components: List<VerifiedComponent>,
        manifest: ArtifactManifest?,
    ): VerifiedArtifactLoadTarget? {
        if (manifest?.verifiedDiffusionLoadTarget(model.modelId) is VerifiedDiffusionLoadTarget.Directory) {
            val root = verifiedDirectoryRoot(model, manifest) ?: return null
            val requiredPaths = NATIVE_DIFFUSERS_CONSUMED_PATHS.sorted()
            if (!requiredPaths.all { relativePath ->
                    val expectedPath = localPathUnder(root, relativePath) ?: return@all false
                    components.singleOrNull {
                        it.repositoryId == model.modelId &&
                            it.localRelativePath == relativePath &&
                            sameNormalizedPath(it.localPath, expectedPath)
                    } != null
                }
            ) return null
            return VerifiedArtifactLoadTarget.Directory(
                path = root,
                storageOwner = model.modelId,
                nativeConsumedRelativePaths = requiredPaths,
            )
        }
        val modelSnapshot = storagePathProvider.inspectDownloadedArtifact(model.modelId, model.localPath) ?: return null
        if (modelSnapshot.kind == StoredArtifactKind.REGULAR_FILE) {
            if (manifest != null &&
                !manifest.isCompleteDiffusionInstallation(model.modelId, getModelSetup(model.modelId))
            ) return null
            val primary = components.singleOrNull {
                it.logicalRole == "model" && it.repositoryId == model.modelId && it.localPath == model.localPath
            } ?: return null
            return VerifiedArtifactLoadTarget.File(
                path = primary.localPath,
                componentRole = primary.logicalRole,
                repositoryId = primary.repositoryId,
                localRelativePath = primary.localRelativePath,
            )
        }
        return null
    }

    private fun ResolvedLocalArtifact.hasValidLoadTarget(): Boolean = when (val target = loadTarget) {
        is VerifiedArtifactLoadTarget.File -> components.singleOrNull {
            it.logicalRole == target.componentRole &&
                it.repositoryId == target.repositoryId &&
                it.localRelativePath == target.localRelativePath &&
                it.localPath == target.path
        } != null
        is VerifiedArtifactLoadTarget.Directory -> {
            val trustedRoot = normalizedModelRoot(target.storageOwner)
            trustedRoot != null && sameNormalizedPath(target.path, trustedRoot) &&
                storagePathProvider.inspectDownloadedArtifact(target.storageOwner, trustedRoot)?.kind ==
                StoredArtifactKind.DIRECTORY &&
                target.nativeConsumedRelativePaths == NATIVE_DIFFUSERS_CONSUMED_PATHS.sorted() &&
                target.nativeConsumedRelativePaths.all { relativePath ->
                    val expectedPath = localPathUnder(trustedRoot, relativePath) ?: return@all false
                    components.singleOrNull {
                        it.repositoryId == target.storageOwner &&
                            it.localRelativePath == relativePath &&
                            sameNormalizedPath(it.localPath, expectedPath)
                    } != null
                }
        }
    }

    private fun verifiedDirectoryRoot(model: LocalModelEntity, manifest: ArtifactManifest): String? {
        if (!manifest.isCompleteDiffusionInstallation(model.modelId, getModelSetup(model.modelId))) return null
        val root = normalizedModelRoot(model.modelId) ?: return null
        if (!sameNormalizedPath(model.localPath, root)) return null
        return root.takeIf {
            storagePathProvider.inspectDownloadedArtifact(model.modelId, it)?.kind == StoredArtifactKind.DIRECTORY
        }
    }

    private fun normalizedModelRoot(modelId: String): String? = try {
        storagePathProvider.getModelsStorageDirectory(modelId)
            .takeIf { it.isNotBlank() && '\u0000' !in it }
            ?.toPath(normalize = true)
            ?.toString()
    } catch (_: Exception) {
        null
    }

    private fun localPathUnder(root: String, relativePath: String): String? = try {
        if (!isValidRelativePath(relativePath)) return null
        (root.toPath(normalize = true) / relativePath).normalized().toString()
    } catch (_: Exception) {
        null
    }

    private fun sameNormalizedPath(left: String, right: String): Boolean = try {
        left.toPath(normalize = true) == right.toPath(normalize = true)
    } catch (_: Exception) {
        false
    }

    private fun digestComponents(components: List<VerifiedComponent>, includeRevision: Boolean): String {
        val buffer = Buffer()
        components.sortedWith(componentComparator).forEach { component ->
            buffer.writeLengthPrefixed(component.logicalRole)
            buffer.writeLengthPrefixed(component.repositoryId)
            if (includeRevision) buffer.writeLengthPrefixed(component.immutableRevision.orEmpty())
            buffer.writeLengthPrefixed(component.repositoryRelativePath)
            buffer.writeLong(component.byteCount)
            buffer.writeLengthPrefixed(component.contentSha256)
        }
        return buffer.snapshot().sha256().hex()
    }

    private fun localPathFor(entry: ArtifactManifestEntry): String =
        "${storagePathProvider.getModelsStorageDirectory(entry.identity.repositoryId).trimEnd('/', '\\')}/${entry.localRelativePath}"

    private fun LocalModelEntity.sidecarPath(): Path =
        storagePathProvider.getModelsStorageDirectory(modelId).toPath() / MANIFEST_FILE_NAME

    private suspend fun readSidecar(path: Path): LegacyIdentitySidecar? = withContext(hashingDispatcher) {
        val metadata = fileSystem.metadataOrNull(path) ?: return@withContext null
        if (!metadata.isRegularFile || metadata.symlinkTarget != null || (metadata.size ?: Long.MAX_VALUE) > MAX_MANIFEST_BYTES) {
            return@withContext null
        }
        val bytes = readBounded(path, MAX_MANIFEST_BYTES) ?: return@withContext null
        runCatching { json.decodeFromString<LegacyIdentitySidecar>(bytes.decodeToString()) }.getOrNull()
            ?.takeIf(LegacyIdentitySidecar::isValid)
    }

    private suspend fun writeSidecar(path: Path, sidecar: LegacyIdentitySidecar) = withContext(hashingDispatcher) {
        val encoded = json.encodeToString(sidecar).encodeToByteArray()
        require(encoded.size in 1..MAX_MANIFEST_BYTES)
        val part = "$path.part".toPath()
        fileSystem.createDirectories(path.parent!!)
        fileSystem.delete(part, mustExist = false)
        try {
            fileSystem.write(part, mustCreate = true) { write(encoded) }
            fileSystem.atomicMove(part, path)
        } catch (cancelled: CancellationException) {
            fileSystem.delete(part, mustExist = false)
            throw cancelled
        } catch (failure: Throwable) {
            fileSystem.delete(part, mustExist = false)
            throw failure
        }
    }

    private suspend fun deletePart(path: Path) = withContext(NonCancellable + hashingDispatcher) {
        runCatching { fileSystem.delete("$path.part".toPath(), mustExist = false) }
    }

    private fun readBounded(path: Path, maxBytes: Int): ByteArray? = runCatching {
        val source = fileSystem.source(path)
        try {
            val sink = Buffer()
            val limit = maxBytes.toLong() + 1L
            while (sink.size < limit) {
                val read = source.read(sink, minOf(8_192L, limit - sink.size))
                if (read == -1L) break
            }
            sink.readByteArray().takeIf { it.size in 1..maxBytes }
        } finally {
            source.close()
        }
    }.getOrNull()

    private data class InputComponent(
        val logicalRole: String,
        val repositoryId: String,
        val repositoryRelativePath: String,
        val localPath: String,
        val storageOwner: String,
    ) {
        fun key(): String = "$logicalRole\u0000$repositoryId\u0000$repositoryRelativePath"
        fun verified(snapshot: StoredArtifactSnapshot, digest: String) = VerifiedComponent(
            logicalRole, repositoryId, repositoryRelativePath, repositoryRelativePath, localPath,
            snapshot.byteCount, digest, snapshot.changeStamp, null, null,
        )
    }

    private data class VerifiedComponent(
        val logicalRole: String,
        val repositoryId: String,
        val repositoryRelativePath: String,
        val localRelativePath: String,
        val localPath: String,
        val byteCount: Long,
        val contentSha256: String,
        val changeStamp: String,
        val immutableRevision: String?,
        val remoteObjectId: String?,
    ) {
        fun toSidecar() = SidecarComponent(
            logicalRole, repositoryId, repositoryRelativePath, byteCount, contentSha256, changeStamp,
        )
    }

    @Serializable
    private data class LegacyIdentitySidecar(
        val version: Int,
        val aggregateDigest: String,
        val components: List<SidecarComponent>,
    ) {
        fun isValid(): Boolean = version == LEGACY_MANIFEST_VERSION && aggregateDigest.isSha256() &&
            components.size in 1..MAX_COMPONENTS && components.all(SidecarComponent::isValid) &&
            components.map(SidecarComponent::key).distinct().size == components.size
    }

    @Serializable
    private data class SidecarComponent(
        val logicalRole: String,
        val repositoryId: String,
        val repositoryRelativePath: String,
        val byteCount: Long,
        val contentSha256: String,
        val changeStamp: String,
    ) {
        fun key(): String = "$logicalRole\u0000$repositoryId\u0000$repositoryRelativePath"
        fun isValid(): Boolean = isValidRole(logicalRole) && isValidRepositoryId(repositoryId) &&
            isValidRelativePath(repositoryRelativePath) && byteCount in 1..DescriptorLimits.MAX_FILE_BYTES &&
            contentSha256.isSha256() && changeStamp.isNotBlank() &&
            changeStamp.length <= MAX_CHANGE_STAMP_LENGTH && changeStamp.none(Char::isISOControl)
    }

    companion object {
        const val MANIFEST_FILE_NAME = ".caraml-local-identity-v1.json"
        private const val LEGACY_MANIFEST_VERSION = 1
        private const val MAX_COMPONENTS = 64
        private const val MAX_MANIFEST_BYTES = 256 * 1024
        private const val MAX_CHANGE_STAMP_LENGTH = 128
        /** Resolved identity is a ModelFileIdentity, whose stable public size contract is one PiB. */
        internal const val MAX_RESOLVED_ARTIFACT_BYTES: Long = DescriptorLimits.MAX_FILE_BYTES
        private val componentComparator = compareBy<VerifiedComponent>(
            VerifiedComponent::logicalRole, VerifiedComponent::repositoryId, VerifiedComponent::repositoryRelativePath,
        )
        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = false
            isLenient = false
            explicitNulls = true
        }

        private fun String.isSha256(): Boolean = length == 64 && all { it in '0'..'9' || it in 'a'..'f' }
        private fun isValidRole(value: String): Boolean =
            value.isNotBlank() && value.length <= 64 && value.all { it.isLetterOrDigit() || it in "._-" }
        private fun isValidRepositoryId(value: String): Boolean =
            ModelFileIdentity(value, "a".repeat(40), "model", 1, "a".repeat(40), null, null, emptyList())
                .hasValidExactIdentity()
        private fun isValidRelativePath(value: String): Boolean =
            ModelFileIdentity("local", "a".repeat(40), value, 1, "a".repeat(40), null, null, emptyList())
                .hasValidExactIdentity()

        private suspend fun hashRegularFile(fileSystem: FileSystem, path: Path, expectedBytes: Long): String {
            if (expectedBytes !in 1..DescriptorLimits.MAX_FILE_BYTES) {
                throw IllegalArgumentException("Invalid artifact size")
            }
            val hashing = HashingSource.sha256(fileSystem.source(path))
            try {
                val source = hashing.buffer()
                val scratch = Buffer()
                var total = 0L
                while (true) {
                    val remaining = expectedBytes + 1L - total
                    val read = source.read(scratch, minOf(64L * 1_024L, remaining))
                    if (read == -1L) break
                    total += read
                    scratch.clear()
                    if (total > expectedBytes) throw IllegalStateException("Artifact changed while reading")
                }
                if (total != expectedBytes) throw IllegalStateException("Artifact changed while reading")
                return hashing.hash.hex()
            } finally {
                hashing.close()
            }
        }

        private fun Buffer.writeLengthPrefixed(value: String) {
            val bytes = value.encodeToByteArray()
            writeInt(bytes.size)
            write(bytes)
        }
    }
}

internal fun checkedResolvedArtifactBytes(byteCounts: Iterable<Long>): Long? {
    var total = 0L
    for (byteCount in byteCounts) {
        if (byteCount !in 1..LocalArtifactIdentityResolver.MAX_RESOLVED_ARTIFACT_BYTES ||
            total > LocalArtifactIdentityResolver.MAX_RESOLVED_ARTIFACT_BYTES - byteCount
        ) return null
        total += byteCount
    }
    return total.takeIf { it > 0L }
}
