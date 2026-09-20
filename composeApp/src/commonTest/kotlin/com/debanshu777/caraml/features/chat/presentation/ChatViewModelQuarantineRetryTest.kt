package com.debanshu777.caraml.features.chat.presentation

import androidx.lifecycle.ViewModelStore
import com.debanshu777.caraml.core.data.inference.DiffusionInferenceRepository
import com.debanshu777.caraml.core.data.inference.InferenceRepository
import com.debanshu777.caraml.core.data.inference.ModelLoadResult
import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.media.GeneratedMediaStore
import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.DeviceCapabilities
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.recommendation.AssessedPlans
import com.debanshu777.caraml.core.recommendation.AssessmentConfidence
import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.Confidence
import com.debanshu777.caraml.core.recommendation.DiffusionMode
import com.debanshu777.caraml.core.recommendation.DiffusionPlanCompromise
import com.debanshu777.caraml.core.recommendation.DiffusionRunPlan
import com.debanshu777.caraml.core.recommendation.InferenceObservationPlan
import com.debanshu777.caraml.core.recommendation.InstalledModelLoadResolution
import com.debanshu777.caraml.core.recommendation.InstalledModelLoadPreparation
import com.debanshu777.caraml.core.recommendation.InstalledModelLoadResolver
import com.debanshu777.caraml.core.recommendation.KvCacheType
import com.debanshu777.caraml.core.recommendation.LlmRunPlan
import com.debanshu777.caraml.core.recommendation.LoadAdmission
import com.debanshu777.caraml.core.recommendation.LoadAdmissionReason
import com.debanshu777.caraml.core.recommendation.LoadRequest
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.ObservationModelIdentity
import com.debanshu777.caraml.core.recommendation.PlanAssessment
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.recommendation.ResolvedArtifactComponent
import com.debanshu777.caraml.core.recommendation.ResolvedLocalArtifact
import com.debanshu777.caraml.core.recommendation.RevisionIdentity
import com.debanshu777.caraml.core.recommendation.RiskAcknowledgement
import com.debanshu777.caraml.core.recommendation.RepositoryCommit
import com.debanshu777.caraml.core.recommendation.VerifiedArtifactLoadTarget
import com.debanshu777.caraml.core.recommendation.task6LlmDescriptor
import com.debanshu777.caraml.core.recommendation.task6DiffusionDescriptor
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.storage.localmodel.LocalModelDao
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelRepository
import com.debanshu777.caraml.features.chat.domain.ChatConfig
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.chat.domain.usecase.GenerateResponseUseCase
import com.debanshu777.caraml.features.chat.domain.usecase.GetAvailableModelsUseCase
import com.debanshu777.caraml.features.chat.domain.usecase.ManageContextUseCase
import com.debanshu777.caraml.features.chat.domain.usecase.TrackModelUsageUseCase
import com.debanshu777.diffusionrunner.DiffusionRunner
import com.debanshu777.runner.InferenceChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelQuarantineRetryTest {
    @Test
    fun stalePendingActionCallbacksCannotAffectReplacementLoad() = runTest {
        val cases = listOf(
            StalePendingActionCase(InitialPendingAction.CONFIRM, StalePendingCallback.CONFIRM),
            StalePendingActionCase(InitialPendingAction.ALTERNATIVE, StalePendingCallback.ACCEPT),
            StalePendingActionCase(InitialPendingAction.SAFE_ALTERNATIVE, StalePendingCallback.ACCEPT),
            StalePendingActionCase(InitialPendingAction.RETRY, StalePendingCallback.RETRY),
            StalePendingActionCase(InitialPendingAction.CONFIRM, StalePendingCallback.CANCEL),
        )

        cases.forEach { case ->
            val scenario = scenario(initialPendingAction = case.action) { _ -> }

            withScenario(scenario) {
                advanceUntilIdle()
                val staleAction = assertIs<ChatUiState.LoadActionRequired>(
                    scenario.viewModel.uiState.value,
                    "Expected pending action for $case",
                ).action
                val loadsBeforeSwitch = scenario.inference.loadRequests.toList()
                val permissionsBeforeSwitch = scenario.inference.permissionRequests.toList()

                scenario.viewModel.selectModel(scenario.modelB)
                case.callback.invoke(scenario.viewModel, staleAction)

                advanceUntilIdle()

                scenario.assertReadyFor(scenario.modelB, MODEL_B_CONTEXT)
                assertEquals(loadsBeforeSwitch + scenario.requestB, scenario.inference.loadRequests)
                assertEquals(permissionsBeforeSwitch, scenario.inference.permissionRequests)
            }
        }
    }

    @Test
    fun pendingActionAcceptConfirmAndCancelUseRealRunnerReleaseOwnership() = runTest {
        val cases = listOf(
            StalePendingActionCase(InitialPendingAction.CONFIRM, StalePendingCallback.CONFIRM),
            StalePendingActionCase(InitialPendingAction.ALTERNATIVE, StalePendingCallback.ACCEPT),
            StalePendingActionCase(InitialPendingAction.SAFE_ALTERNATIVE, StalePendingCallback.ACCEPT),
            StalePendingActionCase(InitialPendingAction.ALTERNATIVE, StalePendingCallback.CANCEL),
        )

        cases.forEach { case ->
            val events = mutableListOf<String>()
            var diffusionReleaseCalls = 0
            val scenario = scenario(
                initialPendingAction = case.action,
                onUnload = { call -> events += "unload-text:$call" },
                releaseDiffusionModel = {
                    diffusionReleaseCalls += 1
                    events += "release-diffusion:$diffusionReleaseCalls"
                },
                loadOverride = { request ->
                    events += "load:${request.model.filename}"
                    null
                },
            ) { _ -> }

            withScenario(scenario) {
                advanceUntilIdle()
                val action = assertIs<ChatUiState.LoadActionRequired>(
                    scenario.viewModel.uiState.value,
                    "Expected pending action for $case",
                ).action
                assertEquals(1, scenario.inference.unloadCalls)
                assertEquals(1, diffusionReleaseCalls)
                events.clear()

                case.callback.invoke(scenario.viewModel, action)
                advanceUntilIdle()

                when (case.callback) {
                    StalePendingCallback.CANCEL -> {
                        assertIs<ChatUiState.ModelError>(scenario.viewModel.uiState.value)
                        assertEquals(emptyList(), events)
                        assertEquals(1, scenario.inference.unloadCalls)
                        assertEquals(1, diffusionReleaseCalls)
                    }
                    else -> {
                        scenario.assertReadyFor(scenario.modelA, MODEL_A_CONTEXT)
                        val expectedEvents = if (case.action == InitialPendingAction.SAFE_ALTERNATIVE) {
                            listOf("load:a.gguf")
                        } else {
                            listOf("unload-text:2", "release-diffusion:2", "load:a.gguf")
                        }
                        assertEquals(expectedEvents, events)
                        assertEquals(
                            if (case.action == InitialPendingAction.SAFE_ALTERNATIVE) 1 else 2,
                            scenario.inference.unloadCalls,
                        )
                        assertEquals(
                            if (case.action == InitialPendingAction.SAFE_ALTERNATIVE) 1 else 2,
                            diffusionReleaseCalls,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun residentModelIsReleasedOnceBeforeReplacementAssessmentAndExactLoad() = runTest {
        val events = mutableListOf<String>()
        var residentModelId: Long? = null
        var diffusionReleaseCalls = 0
        val scenario = scenario(
            releaseDiffusionModel = {
                diffusionReleaseCalls += 1
                events += "release-diffusion"
            },
            onUnload = {
                events += "unload-text"
                residentModelId = null
            },
            loadOverride = { request ->
                events += "load:${request.model.filename}"
                residentModelId = request.model.id
                ModelLoadResult.Success(
                    if (request.model.id == 7L) MODEL_A_CONTEXT else MODEL_B_CONTEXT,
                )
            },
            onPrepare = { model -> events += "prepare:${model.filename}" },
            resolveOverride = { preparation, request ->
                events += "assess:${preparation.model.filename}:resident=$residentModelId"
                if (residentModelId == null) {
                    InstalledModelLoadResolution.Ready(request)
                } else {
                    InstalledModelLoadResolution.NotAdmissible(AssessmentReason.MEMORY_NO_FIT)
                }
            },
        ) { _ -> }

        withScenario(scenario) {
            advanceUntilIdle()
            scenario.assertRetryActionFor(scenario.modelA.id)
            scenario.viewModel.retryPendingLoad()
            advanceUntilIdle()
            scenario.assertReadyFor(scenario.modelA, MODEL_A_CONTEXT)
            assertEquals(scenario.modelA.id, residentModelId)

            events.clear()
            val unloadCallsBeforeReplacement = scenario.inference.unloadCalls
            val diffusionReleasesBeforeReplacement = diffusionReleaseCalls

            scenario.viewModel.selectModel(scenario.modelB)
            advanceUntilIdle()

            scenario.assertReadyFor(scenario.modelB, MODEL_B_CONTEXT)
            assertEquals(scenario.modelB.id, residentModelId)
            assertEquals(unloadCallsBeforeReplacement + 1, scenario.inference.unloadCalls)
            assertEquals(diffusionReleasesBeforeReplacement + 1, diffusionReleaseCalls)
            assertEquals(
                listOf(
                    "prepare:b.gguf",
                    "unload-text",
                    "release-diffusion",
                    "assess:b.gguf:resident=null",
                    "load:b.gguf",
                ),
                events,
            )
            assertEquals(scenario.requestB, scenario.inference.loadRequests.last())
        }
    }

    @Test
    fun textAndDiffusionSwitchesReleaseBothRunnersOnceBeforeExactAssessmentAndLoad() = runTest {
        val events = mutableListOf<String>()
        var diffusionReleaseCalls = 0
        val scenario = scenario(
            includeDiffusionModel = true,
            releaseDiffusionModel = {
                diffusionReleaseCalls += 1
                events += "release-diffusion:$diffusionReleaseCalls"
            },
            onUnload = { call -> events += "unload-text:$call" },
            onPrepare = { model -> events += "prepare:${model.filename}" },
            resolveOverride = { preparation, request ->
                events += "assess:${preparation.model.filename}"
                InstalledModelLoadResolution.Ready(request)
            },
            loadOverride = { request ->
                events += "load-text:${request.model.filename}"
                null
            },
            onDiffusionLoad = { request ->
                events += "load-diffusion:${request.model.filename}"
                ModelLoadResult.Success(0)
            },
        ) { _ -> }

        withScenario(scenario) {
            advanceUntilIdle()
            scenario.assertRetryActionFor(scenario.modelA.id)
            scenario.viewModel.retryPendingLoad()
            advanceUntilIdle()
            scenario.assertReadyFor(scenario.modelA, MODEL_A_CONTEXT)

            events.clear()
            val unloadsBeforeDiffusion = scenario.inference.unloadCalls
            val diffusionReleasesBeforeDiffusion = diffusionReleaseCalls

            scenario.viewModel.setGenerationMode(GenerationMode.Image)
            advanceUntilIdle()

            scenario.assertReadyFor(scenario.diffusionModel, 0)
            assertEquals(unloadsBeforeDiffusion + 1, scenario.inference.unloadCalls)
            assertEquals(diffusionReleasesBeforeDiffusion + 1, diffusionReleaseCalls)
            assertEquals(
                listOf(
                    "prepare:model.safetensors",
                    "unload-text:${unloadsBeforeDiffusion + 1}",
                    "release-diffusion:${diffusionReleasesBeforeDiffusion + 1}",
                    "assess:model.safetensors",
                    "load-diffusion:model.safetensors",
                ),
                events,
            )

            events.clear()
            val unloadsBeforeText = scenario.inference.unloadCalls
            val diffusionReleasesBeforeText = diffusionReleaseCalls

            scenario.viewModel.setGenerationMode(GenerationMode.Text)
            advanceUntilIdle()

            scenario.assertReadyFor(scenario.modelA, MODEL_A_CONTEXT)
            assertEquals(unloadsBeforeText + 1, scenario.inference.unloadCalls)
            assertEquals(diffusionReleasesBeforeText + 1, diffusionReleaseCalls)
            assertEquals(
                listOf(
                    "prepare:a.gguf",
                    "unload-text:${unloadsBeforeText + 1}",
                    "release-diffusion:${diffusionReleasesBeforeText + 1}",
                    "assess:a.gguf",
                    "load-text:a.gguf",
                ),
                events,
            )
        }
    }

    @Test
    fun modelSwitchDuringReplacementReleaseCancelsStaleAssessmentAndLoad() = runTest {
        val releaseEntered = CompletableDeferred<Unit>()
        val allowReleaseToFinish = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var diffusionReleaseCalls = 0
        val scenario = scenario(
            releaseDiffusionModel = {
                diffusionReleaseCalls += 1
                events += "release-diffusion:$diffusionReleaseCalls"
                if (diffusionReleaseCalls == 3) {
                    releaseEntered.complete(Unit)
                    withContext(NonCancellable) { allowReleaseToFinish.await() }
                    events += "release-finished:3"
                }
            },
            onUnload = { call -> events += "unload-text:$call" },
            loadOverride = { request ->
                events += "load:${request.model.filename}"
                ModelLoadResult.Success(
                    if (request.model.id == 7L) MODEL_A_CONTEXT else MODEL_B_CONTEXT,
                )
            },
            onPrepare = { model -> events += "prepare:${model.filename}" },
            resolveOverride = { preparation, request ->
                events += "assess:${preparation.model.filename}"
                InstalledModelLoadResolution.Ready(request)
            },
        ) { _ -> }

        withScenario(scenario) {
            advanceUntilIdle()
            scenario.assertRetryActionFor(scenario.modelA.id)
            scenario.viewModel.retryPendingLoad()
            advanceUntilIdle()
            scenario.assertReadyFor(scenario.modelA, MODEL_A_CONTEXT)

            events.clear()
            val unloadCallsBeforeReplacement = scenario.inference.unloadCalls
            val loadsBeforeReplacement = scenario.inference.loadRequests.size

            scenario.viewModel.selectModel(scenario.modelB)
            testScheduler.runCurrent()
            assertTrue(releaseEntered.isCompleted)
            assertEquals(
                listOf("prepare:b.gguf", "unload-text:3", "release-diffusion:3"),
                events,
            )

            scenario.viewModel.selectModel(scenario.modelA)
            testScheduler.runCurrent()
            assertIs<ChatUiState.ModelLoading>(scenario.viewModel.uiState.value)

            allowReleaseToFinish.complete(Unit)
            advanceUntilIdle()

            scenario.assertReadyFor(scenario.modelA, MODEL_A_CONTEXT)
            assertEquals(unloadCallsBeforeReplacement + 1, scenario.inference.unloadCalls)
            assertEquals(3, diffusionReleaseCalls)
            assertEquals(
                listOf(
                    "prepare:b.gguf",
                    "unload-text:3",
                    "release-diffusion:3",
                    "release-finished:3",
                    "prepare:a.gguf",
                    "assess:a.gguf",
                    "load:a.gguf",
                ),
                events,
            )
            assertEquals(
                listOf(scenario.requestA),
                scenario.inference.loadRequests.drop(loadsBeforeReplacement),
            )
            assertFalse(events.any { it == "assess:b.gguf" || it == "load:b.gguf" })
        }
    }

    @Test
    fun modeWithoutModelsWaitsForAdmittedNativeLoadBeforeSingleTeardownAndCanSelectAgain() = runTest {
        val staleLoadEntered = CompletableDeferred<Unit>()
        val allowStaleLoadToReturn = CompletableDeferred<Unit>()
        val teardownUnloadEntered = CompletableDeferred<Unit>()
        val runnerEvents = mutableListOf<String>()
        var diffusionReleaseCalls = 0
        val scenario = scenario(
            releaseDiffusionModel = {
                diffusionReleaseCalls += 1
                runnerEvents += "release-diffusion:$diffusionReleaseCalls"
            },
            onUnload = { call ->
                runnerEvents += "unload-text:$call"
                if (call == 3) teardownUnloadEntered.complete(Unit)
            },
            loadOverride = { request ->
                if (request.model.id != 7L || staleLoadEntered.isCompleted) {
                    null
                } else {
                    runnerEvents += "load-a-start"
                    staleLoadEntered.complete(Unit)
                    withContext(NonCancellable) { allowStaleLoadToReturn.await() }
                    runnerEvents += "load-a-end"
                    ModelLoadResult.Success(MODEL_A_CONTEXT)
                }
            },
        ) { _ -> }

        withScenario(scenario) {
            advanceUntilIdle()
            scenario.assertRetryActionFor(scenario.modelA.id)

            scenario.viewModel.retryPendingLoad()
            testScheduler.runCurrent()
            assertTrue(staleLoadEntered.isCompleted)
            assertEquals(2, scenario.inference.unloadCalls)
            assertEquals(2, diffusionReleaseCalls)
            assertEquals(
                listOf(
                    scenario.requestA,
                    scenario.requestA.copy(riskAcknowledgement = null),
                ),
                scenario.inference.loadRequests,
            )
            assertEquals(listOf(scenario.requestA), scenario.inference.permissionRequests)
            assertEquals(emptyList(), scenario.diffusionLoadRequests)

            try {
                scenario.viewModel.setGenerationMode(GenerationMode.Image)
                testScheduler.runCurrent()

                val teardownOverlappedNativeLoad = withContext(Dispatchers.Default) {
                    withTimeoutOrNull(250) {
                        teardownUnloadEntered.await()
                        true
                    } ?: false
                }
                assertFalse(
                    teardownOverlappedNativeLoad,
                    "Full teardown entered while model A was still inside native load",
                )
                assertIs<ChatUiState.NoModelsForMode>(scenario.viewModel.uiState.value)
                assertEquals(2, scenario.inference.unloadCalls)
                assertEquals(2, diffusionReleaseCalls)
            } finally {
                allowStaleLoadToReturn.complete(Unit)
                advanceUntilIdle()
            }

            assertTrue(teardownUnloadEntered.isCompleted)
            assertEquals(3, scenario.inference.unloadCalls)
            assertEquals(3, diffusionReleaseCalls)
            assertTrue(
                runnerEvents.indexOf("load-a-end") < runnerEvents.indexOf("unload-text:3"),
                "Teardown must begin only after the admitted native load returns",
            )

            scenario.viewModel.setGenerationMode(GenerationMode.Text)
            advanceUntilIdle()

            scenario.assertReadyFor(scenario.modelA, MODEL_A_CONTEXT)
            assertEquals(3, scenario.inference.unloadCalls)
            assertEquals(3, diffusionReleaseCalls)
            assertEquals(
                listOf(
                    scenario.requestA,
                    scenario.requestA.copy(riskAcknowledgement = null),
                    scenario.requestA,
                ),
                scenario.inference.loadRequests,
            )
            assertEquals(emptyList(), scenario.diffusionLoadRequests)
            assertEquals(
                listOf(
                    "unload-text:1",
                    "release-diffusion:1",
                    "unload-text:2",
                    "release-diffusion:2",
                    "load-a-start",
                    "load-a-end",
                    "unload-text:3",
                    "release-diffusion:3",
                ),
                runnerEvents,
            )
        }
    }

    @Test
    fun incompatibleSelectionCannotCancelPendingNoModelTeardown() = runTest {
        val staleLoadEntered = CompletableDeferred<Unit>()
        val allowStaleLoadToReturn = CompletableDeferred<Unit>()
        val runnerEvents = mutableListOf<String>()
        var diffusionReleaseCalls = 0
        val scenario = scenario(
            releaseDiffusionModel = {
                diffusionReleaseCalls += 1
                runnerEvents += "release-diffusion:$diffusionReleaseCalls"
            },
            onUnload = { call -> runnerEvents += "unload-text:$call" },
            loadOverride = { request ->
                if (request.model.id != 7L || staleLoadEntered.isCompleted) {
                    null
                } else {
                    runnerEvents += "load-a-start"
                    staleLoadEntered.complete(Unit)
                    withContext(NonCancellable) { allowStaleLoadToReturn.await() }
                    runnerEvents += "load-a-end"
                    ModelLoadResult.Success(MODEL_A_CONTEXT)
                }
            },
        ) { _ -> }

        withScenario(scenario) {
            advanceUntilIdle()
            scenario.assertRetryActionFor(scenario.modelA.id)

            scenario.viewModel.retryPendingLoad()
            testScheduler.runCurrent()
            assertTrue(staleLoadEntered.isCompleted)

            try {
                scenario.viewModel.setGenerationMode(GenerationMode.Image)
                testScheduler.runCurrent()
                assertIs<ChatUiState.NoModelsForMode>(scenario.viewModel.uiState.value)

                scenario.viewModel.selectModel(scenario.modelA)
                testScheduler.runCurrent()

                assertIs<ChatUiState.NoModelsForMode>(scenario.viewModel.uiState.value)
                assertEquals(
                    listOf(
                        scenario.requestA,
                        scenario.requestA.copy(riskAcknowledgement = null),
                    ),
                    scenario.inference.loadRequests,
                )
                assertEquals(2, scenario.inference.unloadCalls)
                assertEquals(2, diffusionReleaseCalls)
            } finally {
                allowStaleLoadToReturn.complete(Unit)
                advanceUntilIdle()
            }

            assertIs<ChatUiState.NoModelsForMode>(scenario.viewModel.uiState.value)
            assertEquals(3, scenario.inference.unloadCalls)
            assertEquals(3, diffusionReleaseCalls)
            assertEquals(listOf(scenario.requestA), scenario.inference.permissionRequests)
            assertEquals(emptyList(), scenario.diffusionLoadRequests)
            assertEquals(
                listOf(
                    "unload-text:1",
                    "release-diffusion:1",
                    "unload-text:2",
                    "release-diffusion:2",
                    "load-a-start",
                    "load-a-end",
                    "unload-text:3",
                    "release-diffusion:3",
                ),
                runnerEvents,
            )
        }
    }

    @Test
    fun clearWaitsForAdmittedNativeLoadThenTearsDownOnceWithoutUiMutation() = runTest {
        val staleLoadEntered = CompletableDeferred<Unit>()
        val allowStaleLoadToReturn = CompletableDeferred<Unit>()
        val teardownUnloadEntered = CompletableDeferred<Unit>()
        val runnerEvents = mutableListOf<String>()
        var diffusionReleaseCalls = 0
        val scenario = scenario(
            releaseDiffusionModel = {
                diffusionReleaseCalls += 1
                runnerEvents += "release-diffusion:$diffusionReleaseCalls"
            },
            onUnload = { call ->
                runnerEvents += "unload-text:$call"
                if (call == 3) teardownUnloadEntered.complete(Unit)
            },
            loadOverride = { request ->
                if (request.model.id != 7L || staleLoadEntered.isCompleted) {
                    null
                } else {
                    runnerEvents += "load-a-start"
                    staleLoadEntered.complete(Unit)
                    withContext(NonCancellable) { allowStaleLoadToReturn.await() }
                    runnerEvents += "load-a-end"
                    ModelLoadResult.Success(MODEL_A_CONTEXT)
                }
            },
        ) { _ -> }
        val store = ViewModelStore().also { it.put("chat", scenario.viewModel) }

        withScenario(scenario) {
            advanceUntilIdle()
            scenario.assertRetryActionFor(scenario.modelA.id)

            scenario.viewModel.retryPendingLoad()
            testScheduler.runCurrent()
            assertTrue(staleLoadEntered.isCompleted)
            assertEquals(2, scenario.inference.unloadCalls)
            assertEquals(2, diffusionReleaseCalls)
            assertEquals(
                listOf(
                    scenario.requestA,
                    scenario.requestA.copy(riskAcknowledgement = null),
                ),
                scenario.inference.loadRequests,
            )
            assertEquals(listOf(scenario.requestA), scenario.inference.permissionRequests)
            assertEquals(emptyList(), scenario.diffusionLoadRequests)
            val statesBeforeClear = scenario.states.toList()

            try {
                store.clear()
                testScheduler.runCurrent()

                val teardownOverlappedNativeLoad = withContext(Dispatchers.Default) {
                    withTimeoutOrNull(250) {
                        teardownUnloadEntered.await()
                        true
                    } ?: false
                }
                assertFalse(
                    teardownOverlappedNativeLoad,
                    "ViewModel clear teardown entered while model A was still inside native load",
                )
                assertEquals(2, scenario.inference.unloadCalls)
                assertEquals(2, diffusionReleaseCalls)
                assertEquals(statesBeforeClear, scenario.states)
            } finally {
                allowStaleLoadToReturn.complete(Unit)
                advanceUntilIdle()
            }

            assertTrue(teardownUnloadEntered.isCompleted)
            assertEquals(3, scenario.inference.unloadCalls)
            assertEquals(3, diffusionReleaseCalls)
            assertEquals(statesBeforeClear, scenario.states)
            assertTrue(
                runnerEvents.indexOf("load-a-end") < runnerEvents.indexOf("unload-text:3"),
                "Clear teardown must begin only after the admitted native load returns",
            )
            assertEquals(
                listOf(
                    "unload-text:1",
                    "release-diffusion:1",
                    "unload-text:2",
                    "release-diffusion:2",
                    "load-a-start",
                    "load-a-end",
                    "unload-text:3",
                    "release-diffusion:3",
                ),
                runnerEvents,
            )
        }
    }

    @Test
    fun newerSelectionAtNativeBoundaryStopsStaleRetryBeforeTextLoad() = runTest {
        val staleReleaseEntered = CompletableDeferred<Unit>()
        val allowStaleReleaseToReturn = CompletableDeferred<Unit>()
        var diffusionReleaseCalls = 0
        val scenario = scenario(
            releaseDiffusionModel = {
                diffusionReleaseCalls += 1
                if (diffusionReleaseCalls == 2) {
                    staleReleaseEntered.complete(Unit)
                    withContext(NonCancellable) { allowStaleReleaseToReturn.await() }
                }
            },
        ) { _ -> }

        withScenario(scenario) {
            advanceUntilIdle()
            scenario.assertRetryActionFor(scenario.modelA.id)
            assertEquals(1, scenario.inference.unloadCalls)

            scenario.viewModel.retryPendingLoad()
            testScheduler.runCurrent()
            assertTrue(staleReleaseEntered.isCompleted)

            scenario.viewModel.selectModel(scenario.modelB)
            testScheduler.runCurrent()

            assertIs<ChatUiState.ModelLoading>(scenario.viewModel.uiState.value)
            assertEquals(listOf(scenario.requestA), scenario.inference.loadRequests)
            assertEquals(listOf(scenario.requestA), scenario.inference.permissionRequests)
            assertEquals(2, diffusionReleaseCalls)
            assertEquals(2, scenario.inference.unloadCalls)
            assertEquals(emptyList(), scenario.diffusionLoadRequests)

            allowStaleReleaseToReturn.complete(Unit)
            advanceUntilIdle()

            scenario.assertReadyFor(scenario.modelB, MODEL_B_CONTEXT)
            assertEquals(
                listOf(scenario.requestA, scenario.requestB),
                scenario.inference.loadRequests,
            )
            assertEquals(2, diffusionReleaseCalls)
            assertEquals(2, scenario.inference.unloadCalls)
            assertEquals(emptyList(), scenario.diffusionLoadRequests)
            assertEquals(listOf(scenario.requestA), scenario.inference.permissionRequests)
        }
    }

    @Test
    fun staleNativeResultsCannotCommitSuccessFailureOrAdmissionOverNewerSelection() = runTest {
        StaleRetryOutcome.entries.forEach { outcome ->
            val staleLoadEntered = CompletableDeferred<Unit>()
            val allowStaleLoadToReturn = CompletableDeferred<Unit>()
            var diffusionReleaseCalls = 0
            val scenario = scenario(
                releaseDiffusionModel = { diffusionReleaseCalls += 1 },
                loadOverride = { request ->
                    if (request.model.id != 7L) {
                        null
                    } else {
                        staleLoadEntered.complete(Unit)
                        withContext(NonCancellable) { allowStaleLoadToReturn.await() }
                        outcome.result(request)
                    }
                },
            ) { _ -> }

            withScenario(scenario) {
                advanceUntilIdle()
                scenario.assertRetryActionFor(scenario.modelA.id)

                scenario.viewModel.retryPendingLoad()
                testScheduler.runCurrent()
                assertTrue(staleLoadEntered.isCompleted)

                scenario.viewModel.selectModel(scenario.modelB)
                testScheduler.runCurrent()
                val statesAfterNewerSelection = scenario.states.size

                assertIs<ChatUiState.ModelLoading>(scenario.viewModel.uiState.value)
                assertEquals(
                    listOf(
                        scenario.requestA,
                        scenario.requestA.copy(riskAcknowledgement = null),
                    ),
                    scenario.inference.loadRequests,
                )
                assertEquals(2, diffusionReleaseCalls)
                assertEquals(2, scenario.inference.unloadCalls)
                assertEquals(emptyList(), scenario.diffusionLoadRequests)
                assertEquals(listOf(scenario.requestA), scenario.inference.permissionRequests)

                allowStaleLoadToReturn.complete(Unit)
                advanceUntilIdle()

                scenario.assertReadyFor(scenario.modelB, MODEL_B_CONTEXT)
                assertEquals(
                    listOf(
                        scenario.requestA,
                        scenario.requestA.copy(riskAcknowledgement = null),
                        scenario.requestB,
                    ),
                    scenario.inference.loadRequests,
                )
                assertEquals(3, diffusionReleaseCalls)
                assertEquals(3, scenario.inference.unloadCalls)
                assertEquals(emptyList(), scenario.diffusionLoadRequests)
                assertEquals(listOf(scenario.requestA), scenario.inference.permissionRequests)
                assertFalse(
                    scenario.states.drop(statesAfterNewerSelection).any { state ->
                        state.isTerminalStateFor(scenario.modelA)
                    },
                    "Stale $outcome result committed after model B became current",
                )
            }
        }
    }

    @Test
    fun stalePermissionCompletionCannotLoadOverNewerSelection() = runTest {
        val permissionStarted = CompletableDeferred<Unit>()
        val releasePermission = CompletableDeferred<Unit>()
        val scenario = scenario { _ ->
            permissionStarted.complete(Unit)
            withContext(NonCancellable) { releasePermission.await() }
        }

        withScenario(scenario) {
            advanceUntilIdle()
            scenario.assertRetryActionFor(scenario.modelA.id)

            scenario.viewModel.retryPendingLoad()
            testScheduler.runCurrent()
            assertTrue(permissionStarted.isCompleted)

            scenario.viewModel.selectModel(scenario.modelB)
            testScheduler.runCurrent()
            releasePermission.complete(Unit)
            advanceUntilIdle()

            scenario.assertReadyFor(scenario.modelB, MODEL_B_CONTEXT)
            assertEquals(
                listOf(scenario.requestA, scenario.requestB),
                scenario.inference.loadRequests,
            )
        }
    }

    @Test
    fun stalePermissionFailureCannotOverwriteNewerSelection() = runTest {
        val permissionStarted = CompletableDeferred<Unit>()
        val releasePermission = CompletableDeferred<Unit>()
        val scenario = scenario { _ ->
            permissionStarted.complete(Unit)
            withContext(NonCancellable) { releasePermission.await() }
            error("permission store unavailable")
        }

        withScenario(scenario) {
            advanceUntilIdle()
            scenario.assertRetryActionFor(scenario.modelA.id)

            scenario.viewModel.retryPendingLoad()
            testScheduler.runCurrent()
            assertTrue(permissionStarted.isCompleted)

            scenario.viewModel.selectModel(scenario.modelB)
            testScheduler.runCurrent()
            releasePermission.complete(Unit)
            advanceUntilIdle()

            scenario.assertReadyFor(scenario.modelB, MODEL_B_CONTEXT)
            assertEquals(
                listOf(scenario.requestA, scenario.requestB),
                scenario.inference.loadRequests,
            )
        }
    }

    @Test
    fun cancellationDuringPermissionPropagatesWithoutNativeLoadOrTerminalMutation() = runTest {
        val permissionStarted = CompletableDeferred<Unit>()
        val permissionCancelled = CompletableDeferred<Unit>()
        val neverRelease = CompletableDeferred<Unit>()
        val scenario = scenario { _ ->
            permissionStarted.complete(Unit)
            try {
                neverRelease.await()
            } catch (cancelled: CancellationException) {
                permissionCancelled.complete(Unit)
                throw cancelled
            }
        }

        withScenario(scenario) {
            advanceUntilIdle()
            scenario.assertRetryActionFor(scenario.modelA.id)

            scenario.viewModel.retryPendingLoad()
            testScheduler.runCurrent()
            assertTrue(permissionStarted.isCompleted)

            scenario.viewModel.selectModel(scenario.modelB)
            advanceUntilIdle()

            assertTrue(permissionCancelled.isCompleted)
            scenario.assertReadyFor(scenario.modelB, MODEL_B_CONTEXT)
            assertEquals(
                listOf(scenario.requestA, scenario.requestB),
                scenario.inference.loadRequests,
            )
        }
    }

    @Test
    fun successfulExplicitRetryGrantsOnceAndLoadsTheOriginalExactRequestOnce() = runTest {
        val scenario = scenario { _ -> }

        withScenario(scenario) {
            advanceUntilIdle()
            val action = scenario.assertRetryActionFor(scenario.modelA.id)

            scenario.viewModel.retryPendingLoad()
            advanceUntilIdle()

            scenario.assertReadyFor(scenario.modelA, MODEL_A_CONTEXT)
            assertEquals(listOf(action.request), scenario.inference.permissionRequests)
            assertEquals(2, scenario.inference.loadRequests.size)
            assertEquals(action.request, scenario.inference.loadRequests.first())
            assertEquals(
                action.request.copy(riskAcknowledgement = null),
                scenario.inference.loadRequests.last(),
            )
            assertEquals(
                listOf("load:a", "permission:a", "load:a"),
                scenario.inference.events,
            )
        }
    }

    @Test
    fun doubleTapConsumesQuarantineRetryOnlyOnce() = runTest {
        val releasePermission = CompletableDeferred<Unit>()
        val scenario = scenario { _ -> releasePermission.await() }

        withScenario(scenario) {
            advanceUntilIdle()
            scenario.assertRetryActionFor(scenario.modelA.id)

            scenario.viewModel.retryPendingLoad()
            scenario.viewModel.retryPendingLoad()
            testScheduler.runCurrent()

            assertEquals(1, scenario.inference.permissionRequests.size)
            releasePermission.complete(Unit)
            advanceUntilIdle()

            scenario.assertReadyFor(scenario.modelA, MODEL_A_CONTEXT)
            assertEquals(2, scenario.inference.loadRequests.size)
        }
    }

    private fun TestScope.scenario(
        releaseDiffusionModel: suspend () -> Unit = {},
        onUnload: suspend (Int) -> Unit = {},
        loadOverride: suspend (LoadRequest) -> ModelLoadResult? = { null },
        onPrepare: suspend (LocalModelEntity) -> Unit = {},
        resolveOverride: suspend (
            InstalledModelLoadPreparation.Ready,
            LoadRequest,
        ) -> InstalledModelLoadResolution? = { _, _ -> null },
        initialPendingAction: InitialPendingAction = InitialPendingAction.RETRY,
        includeDiffusionModel: Boolean = false,
        onDiffusionLoad: suspend (LoadRequest) -> ModelLoadResult = {
            ModelLoadResult.Success(0)
        },
        allowExplicitRetry: suspend (LoadRequest) -> Unit,
    ): QuarantineRetryScenario {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val modelA = retryModel(id = 7L, suffix = "a")
        val modelB = retryModel(id = 8L, suffix = "b")
        val diffusionModel = retryDiffusionModel()
        val requestA = retryRequest(modelA, assessmentKey = "assessment-a", acknowledged = true)
        val requestB = retryRequest(modelB, assessmentKey = "assessment-b", acknowledged = false)
        val diffusionRequest = retryDiffusionRequest(diffusionModel)
        val inference = QuarantineRetryInferenceRepository(
            quarantinedRequest = requestA,
            initialPendingAction = initialPendingAction,
            allowRetry = allowExplicitRetry,
            loadOverride = loadOverride,
            onUnload = onUnload,
        )
        val resolver = StaticRetryResolver(
            requests = buildMap {
                put(modelA.id, requestA)
                put(modelB.id, requestB)
                if (includeDiffusionModel) put(diffusionModel.id, diffusionRequest)
            },
            onPrepare = onPrepare,
            resolveOverride = { preparation, request ->
                if (initialPendingAction == InitialPendingAction.SAFE_ALTERNATIVE &&
                    preparation.model == modelA
                ) {
                    InstalledModelLoadResolution.SafeAlternative(
                        primaryReason = AssessmentReason.MEMORY_NO_FIT,
                        saferRequest = request.copy(
                            riskAcknowledgement = null,
                            backendAlternative = null,
                        ),
                    )
                } else {
                    resolveOverride(preparation, request)
                }
            },
        )
        val availableModels = buildList {
            add(modelA)
            add(modelB)
            if (includeDiffusionModel) add(diffusionModel)
        }
        val models = LocalModelRepository(RetryLocalModelDao(availableModels))
        val settings = RetrySettingsRepository()
        val diffusionLoadRequests = mutableListOf<LoadRequest>()
        return QuarantineRetryScenario(
            modelA = modelA,
            modelB = modelB,
            diffusionModel = diffusionModel,
            requestA = requestA,
            requestB = requestB,
            diffusionRequest = diffusionRequest,
            inference = inference,
            diffusionLoadRequests = diffusionLoadRequests,
            states = mutableListOf(),
            viewModel = ChatViewModel(
                getAvailableModels = GetAvailableModelsUseCase(models, ChatConfig()),
                generateResponse = GenerateResponseUseCase(inference),
                manageContext = ManageContextUseCase(inference, ChatConfig()),
                trackModelUsage = TrackModelUsageUseCase(models),
                inferenceRepository = inference,
                diffusionRepository = DiffusionInferenceRepository(
                    runner = DiffusionRunner(),
                    deviceCapabilities = DeviceCapabilities(),
                    settingsRepository = settings,
                ),
                generatedMediaStore = GeneratedMediaStore(
                    baseDirectory = "/tmp",
                    sessionId = "quarantine-retry-${modelA.id}-${modelB.id}",
                ),
                installedModelLoadRequestResolver = resolver,
                modelLoadDispatcher = dispatcher,
                releaseDiffusionModel = releaseDiffusionModel,
                loadDiffusionModel = { request ->
                    diffusionLoadRequests += request
                    onDiffusionLoad(request)
                },
            ),
        )
    }

    private suspend fun kotlinx.coroutines.test.TestScope.withScenario(
        scenario: QuarantineRetryScenario,
        test: suspend kotlinx.coroutines.test.TestScope.() -> Unit,
    ) {
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            scenario.viewModel.uiState.collect { scenario.states += it }
        }
        try {
            test()
        } finally {
            collection.cancel()
            Dispatchers.resetMain()
        }
    }

    private fun QuarantineRetryScenario.assertRetryActionFor(modelId: Long): PendingLoadAction.RetryQuarantined {
        val state = assertIs<ChatUiState.LoadActionRequired>(viewModel.uiState.value)
        val action = assertIs<PendingLoadAction.RetryQuarantined>(state.action)
        assertEquals(modelId, action.request.model.id)
        return action
    }

    private fun QuarantineRetryScenario.assertReadyFor(model: LocalModelEntity, contextSize: Int) {
        val state = assertIs<ChatUiState.Ready>(viewModel.uiState.value)
        assertEquals(model, state.selectedModel)
        assertEquals(contextSize, state.contextLimit)
    }

    private fun ChatUiState.isTerminalStateFor(model: LocalModelEntity): Boolean = when (this) {
        is ChatUiState.Ready -> selectedModel == model || contextLimit == MODEL_A_CONTEXT
        is ChatUiState.ModelError -> message == STALE_RETRY_ERROR
        is ChatUiState.LoadActionRequired -> when (val pending = action) {
            is PendingLoadAction.ConfirmRisk -> pending.request.model == model
            is PendingLoadAction.AcceptAlternative -> pending.original.model == model
            is PendingLoadAction.AcceptSafeAlternative -> pending.saferRequest.model == model
            is PendingLoadAction.RetryQuarantined -> pending.request.model == model
        }
        else -> false
    }
}

private data class QuarantineRetryScenario(
    val modelA: LocalModelEntity,
    val modelB: LocalModelEntity,
    val diffusionModel: LocalModelEntity,
    val requestA: LoadRequest,
    val requestB: LoadRequest,
    val diffusionRequest: LoadRequest,
    val inference: QuarantineRetryInferenceRepository,
    val diffusionLoadRequests: MutableList<LoadRequest>,
    val states: MutableList<ChatUiState>,
    val viewModel: ChatViewModel,
)

private class StaticRetryResolver(
    private val requests: Map<Long, LoadRequest>,
    private val onPrepare: suspend (LocalModelEntity) -> Unit = {},
    private val resolveOverride: suspend (
        InstalledModelLoadPreparation.Ready,
        LoadRequest,
    ) -> InstalledModelLoadResolution? = { _, _ -> null },
) : InstalledModelLoadResolver {
    override suspend fun prepare(
        model: LocalModelEntity,
        expectedMode: GenerationMode,
    ): InstalledModelLoadPreparation {
        onPrepare(model)
        val request = requireNotNull(requests[model.id])
        return InstalledModelLoadPreparation.Ready(
            model = model,
            expectedMode = expectedMode,
            descriptor = when (expectedMode) {
                GenerationMode.Text -> task6LlmDescriptor()
                GenerationMode.Image,
                GenerationMode.Video,
                -> task6DiffusionDescriptor()
            },
            artifact = requireNotNull(request.artifact),
        )
    }

    override suspend fun resolve(
        preparation: InstalledModelLoadPreparation.Ready,
    ): InstalledModelLoadResolution {
        val request = requireNotNull(requests[preparation.model.id])
        return resolveOverride(preparation, request)
            ?: InstalledModelLoadResolution.Ready(request)
    }
}

private class QuarantineRetryInferenceRepository(
    private val quarantinedRequest: LoadRequest,
    private val initialPendingAction: InitialPendingAction,
    private val allowRetry: suspend (LoadRequest) -> Unit,
    private val loadOverride: suspend (LoadRequest) -> ModelLoadResult?,
    private val onUnload: suspend (Int) -> Unit,
) : InferenceRepository {
    val loadRequests = mutableListOf<LoadRequest>()
    val permissionRequests = mutableListOf<LoadRequest>()
    val events = mutableListOf<String>()
    var unloadCalls: Int = 0
        private set

    override suspend fun loadModel(request: LoadRequest): ModelLoadResult {
        loadRequests += request
        events += "load:${request.model.filename.substringBefore('.')}"
        if (initialPendingAction != InitialPendingAction.SAFE_ALTERNATIVE &&
            request.model.id == quarantinedRequest.model.id &&
            loadRequests.count { it.model.id == quarantinedRequest.model.id } == 1
        ) {
            val admission = when (initialPendingAction) {
                InitialPendingAction.CONFIRM -> LoadAdmission.ConfirmationRequired(
                    request = quarantinedRequest,
                    reason = LoadAdmissionReason.RISK_ACKNOWLEDGEMENT_REQUIRED,
                    explicitRetryRequired = false,
                )
                InitialPendingAction.ALTERNATIVE -> LoadAdmission.AlternativeAvailable(
                    original = quarantinedRequest,
                    saferPlan = quarantinedRequest.plan,
                    reason = LoadAdmissionReason.NATIVE_BACKEND_INCOMPATIBLE,
                    saferRequest = quarantinedRequest.copy(
                        riskAcknowledgement = null,
                        backendAlternative = null,
                    ),
                )
                InitialPendingAction.RETRY -> LoadAdmission.ConfirmationRequired(
                    request = quarantinedRequest,
                    reason = LoadAdmissionReason.SUSPECTED_PREVIOUS_CRASH,
                    explicitRetryRequired = true,
                )
                InitialPendingAction.SAFE_ALTERNATIVE -> error("Unreachable static alternative")
            }
            return ModelLoadResult.AdmissionRequired(admission)
        }
        loadOverride(request)?.let { return it }
        return ModelLoadResult.Success(
            contextSize = if (request.model.id == quarantinedRequest.model.id) {
                MODEL_A_CONTEXT
            } else {
                MODEL_B_CONTEXT
            },
        )
    }

    override suspend fun allowExplicitRetry(request: LoadRequest) {
        permissionRequests += request
        events += "permission:${request.model.filename.substringBefore('.')}"
        allowRetry(request)
    }

    override suspend fun unloadModel() {
        unloadCalls += 1
        onUnload(unloadCalls)
    }
    override fun generateResponse(userPrompt: String): Flow<InferenceChunk> = emptyFlow()
    override fun cancelGeneration() = Unit
    override fun getContextUsed(): Int = 0
    override fun getContextLimit(): Int = 4_096
    override fun getStopReason(): Int = 0
    override fun isContextAboveThreshold(): Boolean = false
    override fun summarizeConversation(transcript: String): Flow<String> = emptyFlow()
    override suspend fun resetContextWithSummary(summary: String, lastExchange: String): Boolean = true
    override suspend fun resetContext() = Unit
    override fun getRuntimeConfigString(): String = ""
    override fun currentGenerationObservation(): InferenceObservationPlan? = null
}

private data class StalePendingActionCase(
    val action: InitialPendingAction,
    val callback: StalePendingCallback,
)

private enum class InitialPendingAction {
    CONFIRM,
    ALTERNATIVE,
    SAFE_ALTERNATIVE,
    RETRY,
}

private enum class StalePendingCallback {
    CONFIRM,
    ACCEPT,
    RETRY,
    CANCEL;

    fun invoke(viewModel: ChatViewModel, action: PendingLoadAction) {
        when (this) {
            CONFIRM -> viewModel.confirmPendingLoad(assertIs<PendingLoadAction.ConfirmRisk>(action))
            ACCEPT -> viewModel.acceptSaferPlan(action)
            RETRY -> viewModel.retryPendingLoad(assertIs<PendingLoadAction.RetryQuarantined>(action))
            CANCEL -> viewModel.cancelPendingLoad(action)
        }
    }
}

private enum class StaleRetryOutcome {
    SUCCESS,
    FAILURE,
    ADMISSION;

    fun result(request: LoadRequest): ModelLoadResult = when (this) {
        SUCCESS -> ModelLoadResult.Success(MODEL_A_CONTEXT)
        FAILURE -> ModelLoadResult.Error(STALE_RETRY_ERROR)
        ADMISSION -> ModelLoadResult.AdmissionRequired(
            LoadAdmission.ConfirmationRequired(
                request = request,
                reason = LoadAdmissionReason.RISK_ACKNOWLEDGEMENT_REQUIRED,
                explicitRetryRequired = false,
            ),
        )
    }
}

private class RetrySettingsRepository : SettingsRepository {
    private val settings = MutableStateFlow(AppSettings())

    override fun getSettings(): Flow<AppSettings> = settings
    override suspend fun updateSettings(settings: AppSettings) {
        this.settings.value = settings
    }
    override suspend fun updateRecommendationProfile(profile: RecommendationProfile) = Unit
    override suspend fun completeModelProfileOnboarding(profile: RecommendationProfile) = Unit
}

private class RetryLocalModelDao(
    private val available: List<LocalModelEntity>,
) : LocalModelDao {
    private val models = MutableStateFlow(available)

    override suspend fun getFilenamesByModelId(modelId: String): List<String> =
        available.filter { it.modelId == modelId }.map { it.filename }
    override suspend fun deleteByModelIdAndFilename(modelId: String, filename: String) = Unit
    override suspend fun deleteAllForModelId(modelId: String) = Unit
    override suspend fun insert(entity: LocalModelEntity) = Unit
    override fun getAllDownloadedFiles(): Flow<List<LocalModelEntity>> = models
    override fun getDownloadedFilesByType(modelType: String): Flow<List<LocalModelEntity>> = models
    override suspend fun incrementUsageCount(modelId: String, filename: String) = Unit
    override fun getTotalDownloadedSizeBytes(): Flow<Long> =
        MutableStateFlow(available.sumOf { it.sizeBytes ?: 0L })
    override suspend fun updateComponentStatus(modelId: String, status: String) = Unit
    override suspend fun getMainModels(): List<LocalModelEntity> = available
    override suspend fun updateArch(modelId: String, arch: String) = Unit
    override suspend fun demoteMmprojFilesFromMain() = Unit
}

private fun retryModel(id: Long, suffix: String) = LocalModelEntity(
    id = id,
    modelId = "owner/model-$suffix",
    filename = "$suffix.gguf",
    localPath = "/private/$suffix.gguf",
    sizeBytes = 4,
    downloadedAt = id,
    author = "owner",
    libraryName = "gguf",
    pipelineTag = "text-generation",
    componentStatus = LocalModelEntity.STATUS_READY,
)

private fun retryDiffusionModel(): LocalModelEntity {
    val descriptor = task6DiffusionDescriptor()
    return LocalModelEntity(
        id = 9L,
        modelId = descriptor.repositoryId,
        filename = descriptor.components.first { it.isPrimary }.file.path,
        localPath = "/private/diffusion",
        sizeBytes = descriptor.components.sumOf { it.file.sizeBytes },
        downloadedAt = 9L,
        author = "owner",
        libraryName = "diffusers",
        pipelineTag = "text-to-image",
        componentStatus = LocalModelEntity.STATUS_READY,
    )
}

private fun retryDiffusionRequest(model: LocalModelEntity): LoadRequest {
    val descriptor = task6DiffusionDescriptor()
    val components = descriptor.components.mapIndexed { index, component ->
        ResolvedArtifactComponent(
            logicalRole = if (component.isPrimary) "model" else "vae",
            repositoryId = component.file.repositoryId,
            repositoryRelativePath = component.file.path,
            localPath = "${model.localPath}/${component.file.path}",
            byteCount = component.file.sizeBytes,
            contentSha256 = (index + 1).toString().repeat(64),
            identity = component.file,
        )
    }
    val identity = ModelFileIdentity(
        repositoryId = model.modelId,
        revision = "d".repeat(64),
        path = model.filename,
        sizeBytes = components.sumOf(ResolvedArtifactComponent::byteCount),
        gitOid = null,
        lfsOid = "sha256:${"d".repeat(64)}",
        xetHash = null,
        evidence = emptyList(),
    )
    val plan = DiffusionRunPlan(
        mode = DiffusionMode.IMAGE,
        width = 1_024,
        height = 1_024,
        frameCount = 1,
        batchSize = 1,
        steps = 20,
        vaeTiling = false,
        offloadToCpu = true,
        keepClipOnCpu = true,
        keepVaeOnCpu = true,
        maxVramBytes = null,
        layerStreaming = false,
        requiresUserAcceptance = false,
        backend = BackendKind.CPU,
        memoryTopology = MemoryTopology.UNIFIED,
        compromises = listOf(DiffusionPlanCompromise.CPU_OFFLOAD),
    )
    val assessmentKey = "assessment-diffusion"
    return LoadRequest(
        model = model,
        identity = identity,
        observationIdentity = requireNotNull(ObservationModelIdentity.fromDescriptor(descriptor)),
        plan = plan,
        assessmentKey = assessmentKey,
        artifact = ResolvedLocalArtifact(
            identity = identity,
            revisionIdentity = RevisionIdentity.HubCommit(
                listOf(RepositoryCommit(model.modelId, descriptor.revision)),
            ),
            components = components,
            loadTarget = VerifiedArtifactLoadTarget.Directory(
                path = model.localPath,
                storageOwner = model.modelId,
                nativeConsumedRelativePaths = components.map(
                    ResolvedArtifactComponent::repositoryRelativePath,
                ),
            ),
        ),
        assessedPlans = AssessedPlans(
            values = listOf(
                PlanAssessment(
                    plan = plan,
                    hostMemoryBytes = null,
                    gpuMemoryBytes = null,
                    sharedMemoryBytes = null,
                    storageBytes = null,
                    confidence = AssessmentConfidence(
                        Confidence.LOW,
                        Confidence.LOW,
                        Confidence.LOW,
                        Confidence.LOW,
                    ),
                    evidence = emptyList(),
                ),
            ),
            assessmentKey = assessmentKey,
        ),
    )
}

private fun retryRequest(
    model: LocalModelEntity,
    assessmentKey: String,
    acknowledged: Boolean,
): LoadRequest {
    val identity = ModelFileIdentity(
        repositoryId = model.modelId,
        revision = "a".repeat(40),
        path = model.filename,
        sizeBytes = requireNotNull(model.sizeBytes),
        gitOid = "b".repeat(40),
        lfsOid = null,
        xetHash = null,
        evidence = emptyList(),
    )
    val plan = LlmRunPlan(
        contextTokens = 4_096,
        batchSize = 128,
        microBatchSize = 64,
        sequenceCount = 1,
        keyCacheType = KvCacheType.F16,
        valueCacheType = KvCacheType.F16,
        backend = BackendKind.CPU,
        memoryTopology = MemoryTopology.UNIFIED,
        gpuLayerCount = 0,
        compromises = emptyList(),
    )
    return LoadRequest(
        model = model,
        identity = identity,
        observationIdentity = requireNotNull(ObservationModelIdentity.fromDescriptor(task6LlmDescriptor())),
        plan = plan,
        assessmentKey = assessmentKey,
        artifact = ResolvedLocalArtifact(
            identity = identity,
            revisionIdentity = RevisionIdentity.HubCommit(
                listOf(RepositoryCommit(model.modelId, identity.revision)),
            ),
            components = listOf(
                ResolvedArtifactComponent(
                    logicalRole = "model",
                    repositoryId = model.modelId,
                    repositoryRelativePath = model.filename,
                    localPath = model.localPath,
                    byteCount = identity.sizeBytes,
                    contentSha256 = "c".repeat(64),
                    identity = identity,
                ),
            ),
            loadTarget = VerifiedArtifactLoadTarget.File(
                path = model.localPath,
                componentRole = "model",
                repositoryId = model.modelId,
                localRelativePath = model.filename,
            ),
        ),
        assessedPlans = AssessedPlans(
            values = listOf(
                PlanAssessment(
                    plan = plan,
                    hostMemoryBytes = null,
                    gpuMemoryBytes = null,
                    sharedMemoryBytes = null,
                    storageBytes = null,
                    confidence = AssessmentConfidence(
                        Confidence.LOW,
                        Confidence.LOW,
                        Confidence.LOW,
                        Confidence.LOW,
                    ),
                    evidence = emptyList(),
                ),
            ),
            assessmentKey = assessmentKey,
        ),
        riskAcknowledgement = if (acknowledged) {
            RiskAcknowledgement(
                assessmentKey = assessmentKey,
                planKey = plan.stableKey,
                acknowledgedAtEpochMs = 1L,
            )
        } else {
            null
        },
    )
}

private const val MODEL_A_CONTEXT = 1_111
private const val MODEL_B_CONTEXT = 2_222
private const val STALE_RETRY_ERROR = "stale model A failure"
