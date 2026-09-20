package com.debanshu777.caraml.core.recommendation

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.debanshu777.caraml.core.storage.catalog.InstalledModelPublicationCoordinator
import com.debanshu777.caraml.core.storage.catalog.artifactStorageCoordinationKey
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
        val coordinator = LoadSessionCoordinator(repository, InstalledModelPublicationCoordinator())
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
        val coordinator = LoadSessionCoordinator(repository, InstalledModelPublicationCoordinator())
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
        val coordinator = LoadSessionCoordinator(repository, InstalledModelPublicationCoordinator())
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
        val publication = InstalledModelPublicationCoordinator(stripeCount = 256)
        val coordinator = LoadSessionCoordinator(repository, publication)
        val validationEntered = CompletableDeferred<Unit>()
        val releaseValidation = CompletableDeferred<Unit>()
        val nativeEntered = CompletableDeferred<Unit>()
        val releaseNative = CompletableDeferred<Unit>()
        val mutationEntered = CompletableDeferred<Unit>()
        val exactRequest = scopedRequest()
        val storageKey = exactRequest.artifact!!.components.single().coordinationKey()

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
            publication.withArtifactPublication("other/owner", listOf(storageKey)) {
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
        val publication = InstalledModelPublicationCoordinator(stripeCount = 256)
        val coordinator = LoadSessionCoordinator(repository, publication)
        val exactRequest = scopedRequest(componentCount = 2, directoryTarget = true)
        val secondKey = exactRequest.artifact!!.components.last().coordinationKey()
        val blockerEntered = CompletableDeferred<Unit>()
        val releaseBlocker = CompletableDeferred<Unit>()
        val validationEntered = CompletableDeferred<Unit>()
        val blocker = async(start = CoroutineStart.UNDISPATCHED) {
            publication.withArtifactPublication("other/owner", listOf(secondKey)) {
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
    fun malformedScopedBindingFailsClosedBeforeValidationMarkerOrNativeEntry() = runTest {
        val repository = repository()
        val coordinator = LoadSessionCoordinator(repository, InstalledModelPublicationCoordinator())
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
        val coordinator = LoadSessionCoordinator(repository, InstalledModelPublicationCoordinator())
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
        val publication = InstalledModelPublicationCoordinator()
        val coordinator = LoadSessionCoordinator(repository, publication)
        val exactRequest = scopedRequest()
        val storageKey = exactRequest.artifact!!.components.single().coordinationKey()
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
        publication.withArtifactPublication("other/owner", listOf(storageKey)) {
            mutationEntered = true
        }

        assertTrue(mutationEntered)
        assertEquals(0, repository.recoveryRecordCount())
    }

    @Test
    fun nativeThrowReleasesArtifactLifetimeAfterFailureMarkerCleanup() = runTest {
        val repository = repository()
        val publication = InstalledModelPublicationCoordinator()
        val coordinator = LoadSessionCoordinator(repository, publication)
        val exactRequest = scopedRequest()
        val storageKey = exactRequest.artifact!!.components.single().coordinationKey()
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
        publication.withArtifactPublication("other/owner", listOf(storageKey)) {
            mutationEntered = true
        }

        assertTrue(observed === failure)
        assertTrue(mutationEntered)
        assertEquals(0, repository.recoveryRecordCount())
    }

    @Test
    fun disjointArtifactGenerationDoesNotWaitForActiveNativeLoad() = runTest {
        val repository = repository()
        val publication = InstalledModelPublicationCoordinator(stripeCount = 256)
        val coordinator = LoadSessionCoordinator(repository, publication)
        val exactRequest = scopedRequest()
        val disjoint = scopedRequest(
            repositoryId = "different/model",
        )
        val disjointKey = disjoint.artifact!!.components.single().coordinationKey()
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

        publication.withArtifactPublication("different/owner", listOf(disjointKey)) {
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
        val coordinator = LoadSessionCoordinator(repository, InstalledModelPublicationCoordinator())
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

    private companion object {
        const val ENGINE_VERSION = "engine-1"
        val request = scopedRequest()

        fun scopedRequest(
            repositoryId: String = "owner/model",
            componentCount: Int = 1,
            directoryTarget: Boolean = false,
        ): LoadRequest {
            val componentIdentities = (0 until componentCount).map { index ->
                val path = if (componentCount == 1) "model.gguf" else "component-$index.safetensors"
                val identityDigit = (index % 16).toString(16)
                val objectDigit = ((index + 3) % 16).toString(16)
                val identity = ModelFileIdentity(
                    repositoryId = repositoryId,
                    revision = identityDigit.repeat(64),
                    path = path,
                    sizeBytes = 4L,
                    gitOid = null,
                    lfsOid = "sha256:${objectDigit.repeat(64)}",
                    xetHash = null,
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
                        remoteObjectId = identity.lfsOid,
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
                    repositoryId = repositoryId,
                    repositoryRelativePath = path,
                    localPath = "/private/$repositoryId/${location.localRelativePath}",
                    byteCount = identity.sizeBytes,
                    contentSha256 = ((index + 3) % 16).toString(16).repeat(64),
                    identity = identity,
                    localRelativePath = location.localRelativePath,
                    layoutRelativePath = location.layoutRelativePath,
                    bundleId = bundleId,
                )
            }
            val aggregate = ModelFileIdentity(
                repositoryId = repositoryId,
                revision = "a".repeat(64),
                path = if (directoryTarget) "model_index.json" else components.single().repositoryRelativePath,
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
                        nativeConsumedRelativePaths = components.map(ResolvedArtifactComponent::layoutRelativePath),
                    )
                } else {
                    val component = components.single()
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
    }
}

private fun ResolvedArtifactComponent.coordinationKey(): String =
    artifactStorageCoordinationKey(repositoryId, localRelativePath)

private fun ResolvedArtifactComponent.downloadIdentity(): DownloadArtifactIdentity = requireNotNull(
    DownloadArtifactIdentity.create(
        repositoryId = identity.repositoryId,
        immutableRevision = identity.revision,
        relativePath = identity.path,
        remoteObjectId = identity.gitOid ?: identity.lfsOid ?: identity.xetHash,
        expectedBytes = identity.sizeBytes,
    ),
)
