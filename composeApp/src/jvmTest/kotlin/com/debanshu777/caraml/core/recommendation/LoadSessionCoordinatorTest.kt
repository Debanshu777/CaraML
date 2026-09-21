package com.debanshu777.caraml.core.recommendation

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.debanshu777.huggingfacemanager.download.ArtifactManifest
import com.debanshu777.huggingfacemanager.download.ArtifactManifestEntry
import com.debanshu777.huggingfacemanager.download.ArtifactRootLifetime
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.artifactBundleId
import com.debanshu777.huggingfacemanager.download.immutableArtifactStorageLocation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LoadSessionCoordinatorTest {
    @Test
    fun mutationAfterPreflightStopsBeforeMarkerAndNativeEntry() = runTest {
        val repository = repository()
        val lifetime = testArtifactLifetime(request)
        val coordinator = LoadSessionCoordinator(repository, lifetime)
        var artifactCurrent = true
        var nativeCalls = 0

        val result = coordinator.execute(
            request = request,
            evaluateAdmission = {
                artifactCurrent = false
                LoadAdmission.Ready(request)
            },
            artifactValidator = { artifactCurrent },
            releasePartialState = {},
            nativeLoad = {
                nativeCalls++
                NativeLoadOutcome.Succeeded("loaded")
            },
        )

        assertIs<CoordinatedLoadResult.ArtifactChanged>(result)
        assertEquals(0, nativeCalls)
        assertEquals(0, repository.recoveryRecordCount())
        assertEquals(null, repository.recoverPendingLoad())
    }

    @Test
    fun concurrentCrossEngineLoadsCannotOverlapOrReplaceALiveMarker() = runTest {
        val repository = repository()
        val coordinator = LoadSessionCoordinator(repository, testArtifactLifetime(request))
        val firstEnteredNative = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var secondAdmissionStarted = false

        val first = backgroundScope.launch {
            coordinator.execute(
                request = request,
                evaluateAdmission = { LoadAdmission.Ready(request) },
                artifactValidator = { true },
                releasePartialState = {},
                nativeLoad = {
                    firstEnteredNative.complete(Unit)
                    releaseFirst.await()
                    NativeLoadOutcome.Succeeded("llama")
                },
            )
        }
        firstEnteredNative.await()

        val secondRequest = request.copy(plan = RecoveryFixtures.plan(contextTokens = 2_048))
        val second = backgroundScope.launch {
            coordinator.execute(
                request = secondRequest,
                evaluateAdmission = {
                    secondAdmissionStarted = true
                    LoadAdmission.Ready(secondRequest)
                },
                artifactValidator = { true },
                releasePartialState = {},
                nativeLoad = { NativeLoadOutcome.Succeeded("diffusion") },
            )
        }
        runCurrent()
        assertFalse(secondAdmissionStarted)

        releaseFirst.complete(Unit)
        first.join()
        second.join()

        assertEquals(0, repository.recoveryRecordCount())
        assertEquals(null, repository.recoverPendingLoad())
    }

    @Test
    fun abandonedMarkerIsConvertedOnlyByOneStartupRecovery() = runTest {
        val repository = repository()
        val coordinator = LoadSessionCoordinator(repository, testArtifactLifetime(request))
        repository.beginLoad(RecoveryFixtures.identity, RecoveryFixtures.plan)

        val first = coordinator.recoverAbandonedLoadAtStartup()
        val repeated = coordinator.recoverAbandonedLoadAtStartup()

        assertIs<SuspectedLoadFailure>(first)
        assertEquals(null, repeated)
        assertEquals(1, repository.recoveryRecordCount())
    }

    @Test
    fun overlappingArtifactMutationWaitsThroughFinalValidationMarkerAndNativeLoad() = runTest {
        val repository = repository()
        val lifetime = testArtifactLifetime(request)
        val coordinator = LoadSessionCoordinator(repository, lifetime)
        val validationEntered = CompletableDeferred<Unit>()
        val releaseValidation = CompletableDeferred<Unit>()
        val nativeEntered = CompletableDeferred<Unit>()
        val releaseNative = CompletableDeferred<Unit>()
        val mutationEntered = CompletableDeferred<Unit>()
        val exactRequest = scopedRequest()
        val storageRoot = exactRequest.artifact!!.components.single().storageRoot

        val load = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.execute<String>(
                request = exactRequest,
                evaluateAdmission = { LoadAdmission.Ready(exactRequest) },
                artifactValidator = {
                    validationEntered.complete(Unit)
                    releaseValidation.await()
                    true
                },
                releasePartialState = {},
                nativeLoad = {
                    nativeEntered.complete(Unit)
                    releaseNative.await()
                    NativeLoadOutcome.Succeeded("loaded")
                },
            )
        }
        validationEntered.await()
        val mutation = async(start = CoroutineStart.UNDISPATCHED) {
            lifetime.withRoots(listOf(storageRoot)) {
                mutationEntered.complete(Unit)
            }
        }

        runCurrent()
        assertFalse(mutationEntered.isCompleted)
        releaseValidation.complete(Unit)
        nativeEntered.await()
        assertFalse(mutationEntered.isCompleted)

        releaseNative.complete(Unit)
        assertIs<CoordinatedLoadResult.Completed<String>>(load.await())
        mutation.await()
        assertTrue(mutationEntered.isCompleted)
        assertEquals(0, repository.recoveryRecordCount())
    }

    @Test
    fun everyDiffusionComponentIsLockedBeforeFinalValidationStarts() = runTest {
        val repository = repository()
        val exactRequest = scopedRequest(
            componentCount = 2,
            directoryTarget = true,
            separateComponentRoots = true,
        )
        val lifetime = testArtifactLifetime(exactRequest)
        val coordinator = LoadSessionCoordinator(repository, lifetime)
        val secondRoot = exactRequest.artifact!!.components.last().storageRoot
        val blockerEntered = CompletableDeferred<Unit>()
        val releaseBlocker = CompletableDeferred<Unit>()
        val validationEntered = CompletableDeferred<Unit>()
        val blocker = async(start = CoroutineStart.UNDISPATCHED) {
            lifetime.withRoots(listOf(secondRoot)) {
                blockerEntered.complete(Unit)
                releaseBlocker.await()
            }
        }
        blockerEntered.await()

        val load = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.execute(
                request = exactRequest,
                evaluateAdmission = { LoadAdmission.Ready(exactRequest) },
                artifactValidator = {
                    validationEntered.complete(Unit)
                    true
                },
                releasePartialState = {},
                nativeLoad = { NativeLoadOutcome.Succeeded("loaded") },
            )
        }

        runCurrent()
        assertFalse(validationEntered.isCompleted)
        releaseBlocker.complete(Unit)
        blocker.await()
        assertIs<CoordinatedLoadResult.Completed<String>>(load.await())
        assertTrue(validationEntered.isCompleted)
    }

    @Test
    fun newerCurrentBundleRejectsStaleRequestBeforeHashingOrNativeEntry() = runTest {
        val repository = repository()
        val stale = scopedRequest(remoteKind = RemoteKind.LFS)
        val newer = scopedRequest(remoteKind = RemoteKind.XET)
        val coordinator = LoadSessionCoordinator(repository, testArtifactLifetime(newer))
        var validationCalls = 0
        var nativeCalls = 0

        val result = coordinator.execute(
            request = stale,
            evaluateAdmission = { LoadAdmission.Ready(stale) },
            artifactValidator = {
                validationCalls += 1
                true
            },
            releasePartialState = {},
            nativeLoad = {
                nativeCalls += 1
                NativeLoadOutcome.Succeeded("unexpected")
            },
        )

        assertIs<CoordinatedLoadResult.ArtifactChanged>(result)
        assertEquals(0, validationCalls)
        assertEquals(0, nativeCalls)
        assertEquals(0, repository.recoveryRecordCount())
    }

    @Test
    fun malformedScopedBindingFailsClosedBeforeValidationMarkerOrNativeEntry() = runTest {
        val repository = repository()
        val coordinator = LoadSessionCoordinator(repository, testArtifactLifetime(request))
        val valid = scopedRequest()
        val component = valid.artifact!!.components.single()
        val malformed = valid.copy(
            artifact = valid.artifact.copy(
                components = listOf(component.copy(localRelativePath = component.layoutRelativePath)),
            ),
        )
        var validationCalls = 0
        var nativeCalls = 0

        val result = coordinator.execute(
            request = malformed,
            evaluateAdmission = { LoadAdmission.Ready(malformed) },
            artifactValidator = { validationCalls += 1; true },
            releasePartialState = {},
            nativeLoad = {
                nativeCalls += 1
                NativeLoadOutcome.Succeeded("unexpected")
            },
        )

        assertIs<CoordinatedLoadResult.ArtifactChanged>(result)
        assertEquals(0, validationCalls)
        assertEquals(0, nativeCalls)
        assertEquals(0, repository.recoveryRecordCount())
    }

    @Test
    fun forgedGenerationBundleFailsClosedBeforeTakingAStorageLifetime() = runTest {
        val repository = repository()
        val coordinator = LoadSessionCoordinator(repository, testArtifactLifetime(request))
        val valid = scopedRequest()
        val original = valid.artifact!!.components.single()
        val forgedBundle = "f".repeat(64)
        val forgedLocation = immutableArtifactStorageLocation(original.downloadIdentity(), forgedBundle)
        val forgedPath = "/private/${original.repositoryId}/${forgedLocation.localRelativePath}"
        val forgedComponent = original.copy(
            localPath = forgedPath,
            localRelativePath = forgedLocation.localRelativePath,
            layoutRelativePath = forgedLocation.layoutRelativePath,
            bundleId = forgedBundle,
        )
        val forgedArtifact = valid.artifact.copy(
            components = listOf(forgedComponent),
            loadTarget = VerifiedArtifactLoadTarget.File(
                path = forgedPath,
                componentRole = forgedComponent.logicalRole,
                repositoryId = forgedComponent.repositoryId,
                localRelativePath = forgedComponent.localRelativePath,
            ),
        )
        val forged = valid.copy(
            model = valid.model.copy(localPath = forgedPath),
            artifact = forgedArtifact,
        )
        var validationCalls = 0

        val result = coordinator.execute(
            request = forged,
            evaluateAdmission = { LoadAdmission.Ready(forged) },
            artifactValidator = { validationCalls += 1; true },
            releasePartialState = {},
            nativeLoad = { NativeLoadOutcome.Succeeded("unexpected") },
        )

        assertIs<CoordinatedLoadResult.ArtifactChanged>(result)
        assertEquals(0, validationCalls)
        assertEquals(0, repository.recoveryRecordCount())
    }

    @Test
    fun missingGenerationBundleFailsClosedBeforeFinalValidation() = runTest {
        val valid = scopedRequest()
        val component = valid.artifact!!.components.single()
        val malformed = valid.copy(
            artifact = valid.artifact.copy(components = listOf(component.copy(bundleId = null))),
        )

        assertRejectedBeforeFinalValidation(malformed)
    }

    @Test
    fun mutatedStorageRootFailsClosedBeforeFinalValidation() = runTest {
        val valid = scopedRequest()
        val component = valid.artifact!!.components.single()
        val malformed = valid.copy(
            artifact = valid.artifact.copy(
                components = listOf(component.copy(storageRoot = "/private/other/root")),
            ),
        )

        assertRejectedBeforeFinalValidation(malformed)
    }

    @Test
    fun xetRemoteIdentityUsesExactManifestIdAndRejectsMutation() = runTest {
        val repository = repository()
        val exact = scopedRequest(remoteKind = RemoteKind.XET)
        val coordinator = LoadSessionCoordinator(repository, testArtifactLifetime(exact))
        var validationCalls = 0

        val completed = coordinator.execute(
            request = exact,
            evaluateAdmission = { LoadAdmission.Ready(exact) },
            artifactValidator = { validationCalls += 1; true },
            releasePartialState = {},
            nativeLoad = { NativeLoadOutcome.Succeeded("loaded") },
        )

        assertIs<CoordinatedLoadResult.Completed<String>>(completed)
        assertEquals(1, validationCalls)
        val component = exact.artifact!!.components.single()
        val mutated = exact.copy(
            artifact = exact.artifact.copy(
                components = listOf(component.copy(remoteObjectId = "e".repeat(64))),
            ),
        )
        assertRejectedBeforeFinalValidation(mutated)
    }

    @Test
    fun duplicateGenerationKeyFailsClosedBeforeFinalValidation() = runTest {
        val valid = scopedRequest()
        val component = valid.artifact!!.components.single()
        val malformed = valid.copy(
            artifact = valid.artifact.copy(
                components = listOf(component, component.copy(logicalRole = "duplicate")),
            ),
        )

        assertRejectedBeforeFinalValidation(malformed)
    }

    @Test
    fun oversizedGenerationFailsClosedBeforeFinalValidation() = runTest {
        val valid = scopedRequest(componentCount = 64, directoryTarget = true)
        val extra = valid.artifact!!.components.first().copy(logicalRole = "overflow")
        val malformed = valid.copy(
            artifact = valid.artifact.copy(components = valid.artifact.components + extra),
        )

        assertRejectedBeforeFinalValidation(malformed)
    }

    @Test
    fun inconsistentNativeTargetFailsClosedBeforeFinalValidation() = runTest {
        val valid = scopedRequest()
        val target = valid.artifact!!.loadTarget as VerifiedArtifactLoadTarget.File
        val malformed = valid.copy(
            artifact = valid.artifact.copy(loadTarget = target.copy(path = "/private/other/model.gguf")),
        )

        assertRejectedBeforeFinalValidation(malformed)
    }

    @Test
    fun cancellationReleasesArtifactLifetimeAfterTerminalMarkerCleanup() = runTest {
        val repository = repository()
        val exactRequest = scopedRequest()
        val lifetime = testArtifactLifetime(exactRequest)
        val coordinator = LoadSessionCoordinator(repository, lifetime)
        val storageRoot = exactRequest.artifact!!.components.single().storageRoot
        val nativeEntered = CompletableDeferred<Unit>()
        val load = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.execute<String>(
                request = exactRequest,
                evaluateAdmission = { LoadAdmission.Ready(exactRequest) },
                artifactValidator = { true },
                releasePartialState = {},
                nativeLoad = {
                    nativeEntered.complete(Unit)
                    awaitCancellation()
                },
            )
        }
        nativeEntered.await()

        load.cancelAndJoin()
        var mutationEntered = false
        lifetime.withRoots(listOf(storageRoot)) {
            mutationEntered = true
        }

        assertTrue(mutationEntered)
        assertEquals(0, repository.recoveryRecordCount())
    }

    @Test
    fun nativeThrowReleasesArtifactLifetimeAfterFailureMarkerCleanup() = runTest {
        val repository = repository()
        val exactRequest = scopedRequest()
        val lifetime = testArtifactLifetime(exactRequest)
        val coordinator = LoadSessionCoordinator(repository, lifetime)
        val storageRoot = exactRequest.artifact!!.components.single().storageRoot
        val failure = IllegalStateException("native open failed")

        val observed = runCatching {
            coordinator.execute<String>(
                request = exactRequest,
                evaluateAdmission = { LoadAdmission.Ready(exactRequest) },
                artifactValidator = { true },
                releasePartialState = {},
                nativeLoad = { throw failure },
            )
        }.exceptionOrNull()
        var mutationEntered = false
        lifetime.withRoots(listOf(storageRoot)) {
            mutationEntered = true
        }

        assertTrue(observed === failure)
        assertTrue(mutationEntered)
        assertEquals(0, repository.recoveryRecordCount())
    }

    @Test
    fun disjointArtifactGenerationDoesNotWaitForActiveNativeLoad() = runTest {
        val repository = repository()
        val exactRequest = scopedRequest()
        val disjoint = scopedRequest(
            repositoryId = "different/model",
        )
        val lifetime = testArtifactLifetime(exactRequest, disjoint)
        val coordinator = LoadSessionCoordinator(repository, lifetime)
        val disjointRoot = disjoint.artifact!!.components.single().storageRoot
        val nativeEntered = CompletableDeferred<Unit>()
        val releaseNative = CompletableDeferred<Unit>()
        val load = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.execute(
                request = exactRequest,
                evaluateAdmission = { LoadAdmission.Ready(exactRequest) },
                artifactValidator = { true },
                releasePartialState = {},
                nativeLoad = {
                    nativeEntered.complete(Unit)
                    releaseNative.await()
                    NativeLoadOutcome.Succeeded("loaded")
                },
            )
        }
        nativeEntered.await()
        var disjointEntered = false

        lifetime.withRoots(listOf(disjointRoot)) {
            disjointEntered = true
        }

        assertTrue(disjointEntered)
        releaseNative.complete(Unit)
        load.await()
    }

    private fun TestScope.repository(): LoadRecoveryRepository {
        val path = Files.createTempDirectory("caraml-load-coordinator-").resolve("state.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = backgroundScope) { path.toString().toPath() }
        return LoadRecoveryRepository(dataStore, ENGINE_VERSION) { 2_000L }
    }

    private suspend fun TestScope.assertRejectedBeforeFinalValidation(malformed: LoadRequest) {
        val repository = repository()
        val coordinator = LoadSessionCoordinator(repository, testArtifactLifetime(request))
        var validationCalls = 0
        var nativeCalls = 0

        val result = coordinator.execute(
            request = malformed,
            evaluateAdmission = { LoadAdmission.Ready(malformed) },
            artifactValidator = { validationCalls += 1; true },
            releasePartialState = {},
            nativeLoad = {
                nativeCalls += 1
                NativeLoadOutcome.Succeeded("unexpected")
            },
        )

        assertIs<CoordinatedLoadResult.ArtifactChanged>(result)
        assertEquals(0, validationCalls)
        assertEquals(0, nativeCalls)
        assertEquals(0, repository.recoveryRecordCount())
    }

    private fun testArtifactLifetime(vararg requests: LoadRequest): TestArtifactRootLifetime {
        val manifests = requests.associate { candidate ->
            candidate.model.modelId to requireNotNull(candidate.artifact).toManifest()
        }
        return TestArtifactRootLifetime(manifests)
    }

    private class TestArtifactRootLifetime(
        private val manifests: Map<String, ArtifactManifest>,
    ) : ArtifactRootLifetime {
        private val guard = Mutex()
        private val rootLocks = mutableMapOf<String, Mutex>()

        override suspend fun <T> withCurrentBundle(
            ownerModelId: String,
            expectedRepositoryRoots: Map<String, String>,
            block: suspend (ArtifactManifest?) -> T,
        ): T = withRoots(expectedRepositoryRoots.values) {
            block(manifests[ownerModelId])
        }

        suspend fun <T> withRoots(roots: Collection<String>, block: suspend () -> T): T {
            val locks = guard.withLock {
                roots.distinct().sorted().map { root -> rootLocks.getOrPut(root) { Mutex() } }
            }
            return lockRecursively(locks, 0, block)
        }

        private suspend fun <T> lockRecursively(
            locks: List<Mutex>,
            index: Int,
            block: suspend () -> T,
        ): T = if (index == locks.size) block() else locks[index].withLock {
            lockRecursively(locks, index + 1, block)
        }
    }

    private fun ResolvedLocalArtifact.toManifest(): ArtifactManifest = requireNotNull(
        ArtifactManifest.create(
            components.map { component ->
                requireNotNull(
                    ArtifactManifestEntry.create(
                        logicalRole = component.logicalRole,
                        identity = component.downloadIdentity(),
                        byteCount = component.byteCount,
                        contentSha256 = component.contentSha256,
                        bundleId = requireNotNull(component.bundleId),
                        localRelativePath = component.localRelativePath,
                        layoutRelativePath = component.layoutRelativePath,
                    ),
                )
            },
        ),
    )

    private companion object {
        const val ENGINE_VERSION = "engine-1"
        val request = scopedRequest()

        fun scopedRequest(
            repositoryId: String = "owner/model",
            componentCount: Int = 1,
            directoryTarget: Boolean = false,
            separateComponentRoots: Boolean = false,
            remoteKind: RemoteKind = RemoteKind.LFS,
        ): LoadRequest {
            val componentIdentities = (0 until componentCount).map { index ->
                val componentRepository = if (separateComponentRoots && index > 0) {
                    "external/component-$index"
                } else {
                    repositoryId
                }
                val path = if (componentCount == 1) "model.gguf" else "component-$index.safetensors"
                val identityDigit = (index % 16).toString(16)
                val objectDigit = ((index + 3) % 16).toString(16)
                val objectId = when (remoteKind) {
                    RemoteKind.GIT -> objectDigit.repeat(40)
                    RemoteKind.LFS -> "sha256:${objectDigit.repeat(64)}"
                    RemoteKind.XET -> objectDigit.repeat(64)
                }
                val identity = ModelFileIdentity(
                    repositoryId = componentRepository,
                    revision = identityDigit.repeat(64),
                    path = path,
                    sizeBytes = 4L,
                    gitOid = objectId.takeIf { remoteKind == RemoteKind.GIT },
                    lfsOid = objectId.takeIf { remoteKind == RemoteKind.LFS },
                    xetHash = objectId.takeIf { remoteKind == RemoteKind.XET },
                    evidence = emptyList(),
                )
                identity
            }
            val downloadIdentities = componentIdentities.map { identity ->
                requireNotNull(
                    DownloadArtifactIdentity.create(
                        repositoryId = identity.repositoryId,
                        immutableRevision = identity.revision,
                        relativePath = identity.path,
                        remoteObjectId = identity.canonicalDownloadRemoteObjectId(),
                        expectedBytes = identity.sizeBytes,
                    ),
                )
            }
            val bundleId = requireNotNull(artifactBundleId(downloadIdentities))
            val components = componentIdentities.mapIndexed { index, identity ->
                val path = identity.path
                val downloadIdentity = downloadIdentities[index]
                val location = immutableArtifactStorageLocation(downloadIdentity, bundleId)
                ResolvedArtifactComponent(
                    logicalRole = if (index == 0) "model" else "component-$index",
                    repositoryId = identity.repositoryId,
                    repositoryRelativePath = path,
                    localPath = "/private/${identity.repositoryId}/${location.localRelativePath}",
                    byteCount = identity.sizeBytes,
                    contentSha256 = ((index + 3) % 16).toString(16).repeat(64),
                    identity = identity,
                    localRelativePath = location.localRelativePath,
                    layoutRelativePath = location.layoutRelativePath,
                    bundleId = bundleId,
                    remoteObjectId = requireNotNull(downloadIdentity.remoteObjectId),
                    storageRoot = "/private/${identity.repositoryId}",
                )
            }
            val aggregate = ModelFileIdentity(
                repositoryId = repositoryId,
                revision = "a".repeat(64),
                path = if (directoryTarget) "model_index.json" else components.first().repositoryRelativePath,
                sizeBytes = components.sumOf(ResolvedArtifactComponent::byteCount),
                gitOid = null,
                lfsOid = "sha256:${"b".repeat(64)}",
                xetHash = null,
                evidence = emptyList(),
            )
            val root = "/private/$repositoryId/.caraml-artifacts/$bundleId"
            val artifact = ResolvedLocalArtifact(
                identity = aggregate,
                revisionIdentity = RevisionIdentity.HubCommit(
                    components.map { RepositoryCommit(it.repositoryId, it.identity.revision) }.distinct(),
                ),
                components = components,
                loadTarget = if (directoryTarget) {
                    VerifiedArtifactLoadTarget.Directory(
                        path = root,
                        storageOwner = repositoryId,
                        nativeConsumedRelativePaths = components
                            .filter { it.repositoryId == repositoryId }
                            .map(ResolvedArtifactComponent::layoutRelativePath),
                    )
                } else {
                    val component = components.first()
                    VerifiedArtifactLoadTarget.File(
                        path = component.localPath,
                        componentRole = component.logicalRole,
                        repositoryId = component.repositoryId,
                        localRelativePath = component.localRelativePath,
                    )
                },
            )
            return LoadRequest(
            model = com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity(
                modelId = repositoryId,
                filename = aggregate.path,
                localPath = artifact.loadTarget.path,
                sizeBytes = aggregate.sizeBytes,
                downloadedAt = 1L,
                author = null,
                libraryName = null,
                pipelineTag = "text-generation",
            ),
            identity = aggregate,
            observationIdentity = requireNotNull(ObservationModelIdentity.fromDescriptor(task6LlmDescriptor())),
            plan = RecoveryFixtures.plan,
            assessmentKey = "assessment-1",
            artifact = artifact,
            )
        }

        enum class RemoteKind {
            GIT,
            LFS,
            XET,
        }
    }
}

private fun ResolvedArtifactComponent.downloadIdentity(): DownloadArtifactIdentity = requireNotNull(
    DownloadArtifactIdentity.create(
        repositoryId = identity.repositoryId,
        immutableRevision = identity.revision,
        relativePath = identity.path,
        remoteObjectId = remoteObjectId,
        expectedBytes = identity.sizeBytes,
    ),
)
