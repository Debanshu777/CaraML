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
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import okio.HashingSource
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

data class RepositoryCommit(val repositoryId: String, val revision: String)

sealed interface RevisionIdentity {
    data class HubCommit(val commits: List<RepositoryCommit>) : RevisionIdentity
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
    val revisionIdentity: RevisionIdentity.HubCommit,
    val components: List<ResolvedArtifactComponent>,
    val loadTarget: VerifiedArtifactLoadTarget,
)

enum class ArtifactIdentityRejection {
    INVALID_INPUT,
    TOO_MANY_COMPONENTS,
    DUPLICATE_COMPONENT,
    STALE_MANIFEST,
    INCOMPLETE_DIRECTORY,
    ARTIFACT_TOO_LARGE,
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
    fileSystem: FileSystem = FileSystem.SYSTEM,
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
        return manifest?.let { resolveHubManifest(model, inputs, it) }
            ?: ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.STALE_MANIFEST)
    }

    internal suspend fun resolve(
        model: LocalModelEntity,
        components: List<DownloadedComponentEntity>,
        manifest: ArtifactManifest,
    ): ArtifactIdentityResolution {
        val inputs = validatedInputs(model, components)
            ?: return ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.INVALID_INPUT)
        if (inputs.size > MAX_COMPONENTS) {
            return ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.TOO_MANY_COMPONENTS)
        }
        if (hasDuplicateInputs(inputs)) {
            return ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.DUPLICATE_COMPONENT)
        }
        return resolveHubManifest(model, inputs, manifest)
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
                immutableRevision = component.identity.revision,
                remoteObjectId = component.identity.gitOid ?: component.identity.lfsOid ?: component.identity.xetHash,
            )
        }
        val totalBytes = checkedResolvedArtifactBytes(verified.map(VerifiedComponent::byteCount)) ?: return false
        val expectedCommits = verified.map {
            RepositoryCommit(it.repositoryId, it.immutableRevision)
        }.distinct().sortedWith(compareBy(RepositoryCommit::repositoryId, RepositoryCommit::revision))
        if (artifact.revisionIdentity.commits != expectedCommits) return false
        val aggregateDigest = digestComponents(verified)
        if (artifact.identity.sizeBytes != totalBytes ||
            artifact.identity.revision != aggregateDigest ||
            artifact.identity.lfsOid != "sha256:$aggregateDigest" ||
            !artifact.identity.hasValidExactIdentity()
        ) return false
        return artifact.hasValidLoadTarget()
    }

    suspend fun createLoadRequestFromVerifiedArtifact(
        model: LocalModelEntity,
        descriptor: ModelDescriptor,
        artifact: ResolvedLocalArtifact,
        assessment: ModelAssessment,
        recommendation: PersonalizedRecommendation,
    ): LoadRequestResolution {
        if (!hasValidRequestBindings(model, descriptor, assessment, recommendation)) {
            return LoadRequestResolution.Rejected(ArtifactIdentityRejection.INVALID_INPUT)
        }
        if (artifact.identity.repositoryId != model.modelId || !artifact.hasOwner(model.modelId) ||
            !descriptorMatchesResolvedArtifact(descriptor, artifact) || !revalidate(artifact)
        ) {
            return LoadRequestResolution.Rejected(ArtifactIdentityRejection.STALE_MANIFEST)
        }
        return buildLoadRequest(model, descriptor, artifact, assessment, recommendation)
    }

    private fun buildLoadRequest(
        model: LocalModelEntity,
        descriptor: ModelDescriptor,
        artifact: ResolvedLocalArtifact,
        assessment: ModelAssessment,
        recommendation: PersonalizedRecommendation,
    ): LoadRequestResolution {
        val observationIdentity = ObservationModelIdentity.fromDescriptor(descriptor)
        if (!hasValidRequestBindings(model, descriptor, assessment, recommendation) || observationIdentity == null ||
            artifact.identity.repositoryId != model.modelId || !artifact.hasOwner(model.modelId)
        ) {
            return LoadRequestResolution.Rejected(ArtifactIdentityRejection.INVALID_INPUT)
        }
        if (!descriptorMatchesResolvedArtifact(descriptor, artifact)) {
            return LoadRequestResolution.Rejected(ArtifactIdentityRejection.STALE_MANIFEST)
        }
        val selected = recommendation.selectedPlan as RunPlan
        return LoadRequestResolution.Ready(
            LoadRequest(
                model = model,
                identity = artifact.identity,
                observationIdentity = observationIdentity,
                plan = selected,
                assessmentKey = assessment.assessmentKey,
                artifact = artifact,
                assessedPlans = assessment.planAssessments,
                profile = recommendation.profile,
            ),
        )
    }

    private fun hasValidRequestBindings(
        model: LocalModelEntity,
        descriptor: ModelDescriptor,
        assessment: ModelAssessment,
        recommendation: PersonalizedRecommendation,
    ): Boolean {
        if (descriptor.repositoryId != model.modelId || ObservationModelIdentity.fromDescriptor(descriptor) == null ||
            assessment.assessmentKey.isBlank() || assessment.assessmentKey != recommendation.assessmentKey ||
            assessment.planAssessments.assessmentKey != assessment.assessmentKey ||
            assessment.compatibility != assessment.planAssessments.compatibility
        ) return false
        val selected = recommendation.selectedPlan as? RunPlan ?: return false
        val selectedAssessment = assessment.planAssessments.values
            .filter { it.plan.stableKey == selected.stableKey }
            .singleOrNull()
        return selectedAssessment != null && recommendation.selectedPlanAssessment == selectedAssessment
    }

    private fun ResolvedLocalArtifact.hasOwner(modelId: String): Boolean = when (val target = loadTarget) {
        is VerifiedArtifactLoadTarget.File -> target.repositoryId == modelId
        is VerifiedArtifactLoadTarget.Directory -> target.storageOwner == modelId
    }

    private fun descriptorMatchesResolvedArtifact(
        descriptor: ModelDescriptor,
        artifact: ResolvedLocalArtifact,
    ): Boolean = when (descriptor) {
        is LlmModelDescriptor -> {
            if (descriptor.files.isEmpty() || descriptor.files.size != artifact.components.size) return false
            val unmatched = artifact.components.map(ResolvedArtifactComponent::identity).toMutableList()
            descriptor.files.all { identity ->
                val index = unmatched.indexOfFirst { resolved -> identity.matchesExactResolvedIdentity(resolved) }
                if (index < 0) false else {
                    unmatched.removeAt(index)
                    true
                }
            } && unmatched.isEmpty()
        }
        is DiffusionModelDescriptor -> {
            if (descriptor.components.isEmpty() || descriptor.components.size != artifact.components.size ||
                descriptor.components.count(DiffusionComponentDescriptor::isPrimary) != 1
            ) return false
            val unmatched = artifact.components.toMutableList()
            descriptor.components.all { expected ->
                val expectedRole = when {
                    expected.isPrimary -> "model"
                    expected.role != null -> expected.role.name.lowercase()
                    else -> return false
                }
                val index = unmatched.indexOfFirst { resolved ->
                    resolved.logicalRole == expectedRole &&
                        expected.file.matchesExactResolvedIdentity(resolved.identity)
                }
                if (index < 0) false else {
                    unmatched.removeAt(index)
                    true
                }
            } && unmatched.isEmpty()
        }
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
                immutableRevision = entry.identity.immutableRevision.lowercase(),
                remoteObjectId = entry.identity.remoteObjectId,
            )
        }
        if (resolved.none { it.repositoryId == model.modelId } || !allSuppliedInputsCovered(inputs, resolved)) {
            return ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.STALE_MANIFEST)
        }
        val commits = resolved.map { RepositoryCommit(it.repositoryId, it.immutableRevision) }
            .distinct()
            .sortedWith(compareBy(RepositoryCommit::repositoryId, RepositoryCommit::revision))
        return buildArtifact(model, resolved, RevisionIdentity.HubCommit(commits), manifest)
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
        revisionIdentity: RevisionIdentity.HubCommit,
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
        revisionIdentity: RevisionIdentity.HubCommit,
        totalBytes: Long,
        loadTarget: VerifiedArtifactLoadTarget,
    ): ResolvedLocalArtifact? {
        val ordered = this
        val aggregateDigest = digestComponents(ordered)
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
                    revision = component.immutableRevision,
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

    private fun digestComponents(components: List<VerifiedComponent>): String {
        val buffer = Buffer()
        components.sortedWith(componentComparator).forEach { component ->
            buffer.writeLengthPrefixed(component.logicalRole)
            buffer.writeLengthPrefixed(component.repositoryId)
            buffer.writeLengthPrefixed(component.immutableRevision)
            buffer.writeLengthPrefixed(component.repositoryRelativePath)
            buffer.writeLong(component.byteCount)
            buffer.writeLengthPrefixed(component.contentSha256)
        }
        return buffer.snapshot().sha256().hex()
    }

    private fun localPathFor(entry: ArtifactManifestEntry): String =
        "${storagePathProvider.getModelsStorageDirectory(entry.identity.repositoryId).trimEnd('/', '\\')}/${entry.localRelativePath}"

    private data class InputComponent(
        val logicalRole: String,
        val repositoryId: String,
        val repositoryRelativePath: String,
        val localPath: String,
        val storageOwner: String,
    )

    private data class VerifiedComponent(
        val logicalRole: String,
        val repositoryId: String,
        val repositoryRelativePath: String,
        val localRelativePath: String,
        val localPath: String,
        val byteCount: Long,
        val contentSha256: String,
        val immutableRevision: String,
        val remoteObjectId: String?,
    )

    companion object {
        private const val MAX_COMPONENTS = 64
        /** Resolved identity is a ModelFileIdentity, whose stable public size contract is one PiB. */
        internal const val MAX_RESOLVED_ARTIFACT_BYTES: Long = DescriptorLimits.MAX_FILE_BYTES
        private val componentComparator = compareBy<VerifiedComponent>(
            VerifiedComponent::logicalRole, VerifiedComponent::repositoryId, VerifiedComponent::repositoryRelativePath,
        )
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
