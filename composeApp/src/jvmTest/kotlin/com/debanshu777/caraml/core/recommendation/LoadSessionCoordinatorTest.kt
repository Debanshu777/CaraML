package com.debanshu777.caraml.core.recommendation

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
class LoadSessionCoordinatorTest {
    @Test
    fun mutationAfterPreflightStopsBeforeMarkerAndNativeEntry() = runTest {
        val repository = repository()
        val coordinator = LoadSessionCoordinator(repository)
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
        val coordinator = LoadSessionCoordinator(repository)
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
        val coordinator = LoadSessionCoordinator(repository)
        repository.beginLoad(RecoveryFixtures.identity, RecoveryFixtures.plan)

        val first = coordinator.recoverAbandonedLoadAtStartup()
        val repeated = coordinator.recoverAbandonedLoadAtStartup()

        assertIs<SuspectedLoadFailure>(first)
        assertEquals(null, repeated)
        assertEquals(1, repository.recoveryRecordCount())
    }

    private fun TestScope.repository(): LoadRecoveryRepository {
        val path = Files.createTempDirectory("caraml-load-coordinator-").resolve("state.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = backgroundScope) { path.toString().toPath() }
        return LoadRecoveryRepository(dataStore, ENGINE_VERSION) { 2_000L }
    }

    private companion object {
        const val ENGINE_VERSION = "engine-1"
        val request = LoadRequest(
            model = com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity(
                modelId = "owner/model",
                filename = "model.gguf",
                localPath = "/private/model.gguf",
                sizeBytes = 4L,
                downloadedAt = 1L,
                author = null,
                libraryName = null,
                pipelineTag = "text-generation",
            ),
            identity = RecoveryFixtures.identity,
            observationIdentity = requireNotNull(ObservationModelIdentity.fromDescriptor(task6LlmDescriptor())),
            plan = RecoveryFixtures.plan,
            assessmentKey = "assessment-1",
        )
    }
}
