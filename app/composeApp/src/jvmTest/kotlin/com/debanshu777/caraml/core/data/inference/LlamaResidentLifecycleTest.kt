package com.debanshu777.caraml.core.data.inference

import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.platform.DeviceCapabilities
import com.debanshu777.caraml.core.recommendation.LoadRequest
import com.debanshu777.caraml.core.recommendation.ObservationModelIdentity
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.recommendation.ResolvedArtifactComponent
import com.debanshu777.caraml.core.recommendation.ResolvedLocalArtifact
import com.debanshu777.caraml.core.recommendation.RevisionIdentity
import com.debanshu777.caraml.core.recommendation.VerifiedArtifactLoadTarget
import com.debanshu777.caraml.core.recommendation.canonicalDownloadRemoteObjectId
import com.debanshu777.caraml.core.recommendation.task6LlmDescriptor
import com.debanshu777.caraml.core.recommendation.task6LlmPlan
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.sdk.plugin.llama.bridge.LlamaRunner
import com.debanshu777.caraml.sdk.plugin.llama.bridge.NativeRunnerConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Exercises the real repository entry points, not a fake InferenceRepository. Reflection seeds
 * the post-load cache without requiring a multi-GB model or native library in JVM tests. JNI is
 * disabled on this runner instance, so no global native session can be touched by these tests.
 */
class LlamaResidentLifecycleTest {
    @Test
    fun clearingContextInvalidatesTheSuccessfulLoadBeforeReturning() = runTest {
        val repository = readyRepository()
        repository.resetContext()
        assertIneligible(repository)
    }

    @Test
    fun summarizationInvalidatesBeforeReadingTheTemporaryPromptAndStaysInvalidOnCancellation() = runTest {
        val entered = CompletableDeferred<Unit>()
        val repository = readyRepository(SuspendedSettings(entered))
        val job = launch { repository.summarizeConversation("synthetic transcript").toList() }
        entered.await()
        assertIneligible(repository)
        job.cancelAndJoin()
        assertIneligible(repository)
    }

    @Test
    fun summaryRestorationCannotRetainAnOldSettingsKeyDuringCancellation() = runTest {
        val entered = CompletableDeferred<Unit>()
        val repository = readyRepository(SuspendedSettings(entered))
        val job = launch { repository.resetContextWithSummary("synthetic summary") }
        entered.await()
        assertIneligible(repository)
        job.cancelAndJoin()
        assertIneligible(repository)
    }

    @Test
    fun failedReplacementAndExplicitUnloadClearEligibility() = runTest {
        val rejected = readyRepository()
        assertIs<ModelLoadResult.Error>(rejected.loadModel(request.copy(artifact = null)))
        assertIneligible(rejected)
        val unloaded = readyRepository()
        unloaded.unloadModel()
        assertIneligible(unloaded)
    }

    @Test
    fun generationFailureClearsEligibilityEvenWhenTheAllocationWasPreviouslyReady() = runTest {
        val repository = readyRepository()
        val result = runCatching { repository.generateResponse("synthetic prompt").toList() }
        check(result.isFailure)
        assertIneligible(repository)
    }

    @Test
    fun failedUnloadKeepsNativeOwnershipUntilTeardownCanBeRetried() = runTest {
        var failUnload = true
        var unloadCalls = 0
        val repository = readyRepository(unloadNativeModel = {
            unloadCalls++
            check(!failUnload) { "native unload failed" }
        })

        assertFailsWith<IllegalStateException> { repository.unloadModel() }
        assertEquals(true, field(repository, "nativeLoaded"))
        assertIneligible(repository)

        failUnload = false
        repository.unloadModel()
        repository.unloadModel()

        assertEquals(false, field(repository, "nativeLoaded"))
        assertEquals(2, unloadCalls)
    }

    private fun readyRepository(
        settings: SettingsRepository = FixedSettings(),
        unloadNativeModel: () -> Unit = {},
    ): LlamaInferenceRepository {
        val runner = LlamaRunner()
        runner.javaClass.getDeclaredField("nativeAvailable").apply { isAccessible = true }.setBoolean(runner, false)
        return LlamaInferenceRepository(runner, DeviceCapabilities(), settings, unloadNativeModel = unloadNativeModel).also { repository ->
            setField(repository, "nativeLoaded", true)
            setField(repository, "contextLimitSnapshot", 4_096)
            setField(repository, "residentRequest", request)
            setField(repository, "residentReuseKey", requireNotNull(ResidentLlamaReuseKey.from(
                request, AppSettings(), NativeRunnerConfig(nCtx = 4_096), "lifecycle-test",
            )))
            assertNotNull(field(repository, "residentReuseKey"))
        }
    }

    private fun assertIneligible(repository: LlamaInferenceRepository) {
        assertNull(field(repository, "residentRequest"))
        assertNull(field(repository, "residentReuseKey"))
    }

    private fun field(repository: LlamaInferenceRepository, name: String): Any? =
        repository.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(repository)

    private fun setField(repository: LlamaInferenceRepository, name: String, value: Any) =
        repository.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(repository, value)

    private open class FixedSettings : SettingsRepository {
        override fun getSettings(): Flow<AppSettings> = flowOf(AppSettings())
        override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) = Unit
        override suspend fun updateRecommendationProfile(profile: RecommendationProfile) = Unit
        override suspend fun completeModelProfileOnboarding(profile: RecommendationProfile) = Unit
    }

    private class SuspendedSettings(private val entered: CompletableDeferred<Unit>) : FixedSettings() {
        override fun getSettings(): Flow<AppSettings> = flow {
            entered.complete(Unit)
            awaitCancellation()
        }
    }

    private val descriptor = task6LlmDescriptor()
    private val model = LocalModelEntity(
        modelId = descriptor.file.repositoryId, filename = descriptor.file.path,
        localPath = "/test/model.gguf", sizeBytes = descriptor.file.sizeBytes,
        downloadedAt = 1, author = null, libraryName = null, pipelineTag = "text-generation",
    )
    private val artifact = ResolvedLocalArtifact(
        descriptor.file, RevisionIdentity.HubCommit(emptyList()),
        listOf(ResolvedArtifactComponent(
            logicalRole = "model", repositoryId = model.modelId,
            repositoryRelativePath = model.filename, localPath = model.localPath,
            byteCount = descriptor.file.sizeBytes, contentSha256 = "a".repeat(64),
            identity = descriptor.file,
            remoteObjectId = requireNotNull(descriptor.file.canonicalDownloadRemoteObjectId()),
            storageRoot = "/test",
        )),
        VerifiedArtifactLoadTarget.File(model.localPath, "model", model.modelId, model.filename),
    )
    private val request = LoadRequest(
        model, descriptor.file, requireNotNull(ObservationModelIdentity.fromDescriptor(descriptor)),
        task6LlmPlan(), "lifecycle-test", artifact = artifact,
    )
}
