package com.debanshu777.caraml.core.data.inference

import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.recommendation.*
import com.debanshu777.caraml.sdk.plugin.llama.bridge.InferenceChunk
import com.debanshu777.caraml.sdk.plugin.llama.bridge.StopReason
import com.debanshu777.caraml.sdk.plugin.onnx.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/** ONNX shares exact-artifact validation, current-memory admission and crash recovery with GGUF. */
class OnnxInferenceRepository(
    private val runtime: OnnxTextRuntime,
    private val settings: SettingsRepository,
    private val snapshots: DeviceSnapshotProvider,
    private val suitability: SuitabilityEngine,
    private val policy: RecommendationPolicy,
    private val recovery: LoadRecoveryRepository,
    private val resolver: LocalArtifactIdentityResolver,
    private val coordinator: LoadSessionCoordinator,
) : InferenceRepository {
    private val session = Mutex()
    private val messages = mutableListOf<OnnxChatMessage>()
    private val usedContext = MutableStateFlow(0)
    private var systemPrompt = "You are a helpful assistant."

    override suspend fun loadModel(request: LoadRequest): ModelLoadResult {
        runtime.cancel()
        return session.withLock {
            val artifact = request.artifact ?: return@withLock ModelLoadResult.Error("The ONNX bundle is not verified.")
            onnxExportIssue(artifact.components.map { it.identity })?.let { return@withLock ModelLoadResult.Error(it) }
            val plan = request.plan as? LlmRunPlan ?: return@withLock ModelLoadResult.Error("Invalid ONNX configuration.")
            val path = (artifact.loadTarget as? VerifiedArtifactLoadTarget.File)?.path
                ?: return@withLock ModelLoadResult.Error("The ONNX graph is missing.")
            if (!path.endsWith(".onnx") || artifact.identity != request.identity || plan.backend != BackendKind.CPU ||
                plan.keyCacheType != KvCacheType.F32 || plan.valueCacheType != KvCacheType.F32 ||
                !com.debanshu777.caraml.sdk.discovery.huggingface.model.OnnxModelBundles.isComplete(artifact.components.map { it.repositoryRelativePath })) {
                return@withLock ModelLoadResult.Error("The ONNX bundle or configuration is unsupported.")
            }
            val controller = LoadAdmissionController(
                snapshotSource = snapshots::capture,
                recommendationSource = { candidate, snapshot ->
                    candidate.assessedPlans?.let { policy.recommend(suitability.assemble(it, snapshot), snapshot, candidate.profile) }
                        ?: PersonalizedRecommendation(candidate.assessmentKey, RecommendationCategory.NEEDS_INFORMATION, null, emptyList(), profile = candidate.profile)
                },
                artifactValidator = { candidate -> candidate.artifact?.let { resolver.revalidate(it) } == true },
                nativePreflight = { if (runtime.supported) NativeLoadPreflight.Fit else NativeLoadPreflight.Invalid },
                recoveryState = recovery,
                engineVersion = runtime.engineVersion,
                clock = { Clock.System.now().toEpochMilliseconds() },
            )
            val preferences = settings.getSettings().first()
            try {
                when (val result = coordinator.execute(
                    request = request,
                    engineVersion = runtime.engineVersion,
                    evaluateAdmission = { controller.evaluate(request, request.riskAcknowledgement) },
                    artifactValidator = { candidate -> candidate.artifact?.let { resolver.revalidate(it) } == true },
                    releasePartialState = runtime::unload,
                    nativeLoad = {
                        runtime.load(path, OnnxRuntimeConfig(contextTokens = plan.contextTokens,
                            threads = onnxCpuThreads(artifact.components.map { it.identity }, runtime.engineVersion),
                            provider = OnnxExecutionProvider.CPU, temperature = preferences.temperature))
                        systemPrompt = preferences.systemPrompt.ifBlank { "You are a helpful assistant." }
                        messages.clear()
                        messages += OnnxChatMessage("system", systemPrompt)
                        usedContext.value = runtime.tokenize(messages).size
                        NativeLoadOutcome.Succeeded<ModelLoadResult>(ModelLoadResult.Success(runtime.contextLimit))
                    },
                )) {
                    is CoordinatedLoadResult.AdmissionRequired -> ModelLoadResult.AdmissionRequired(result.admission)
                    is CoordinatedLoadResult.ArtifactChanged -> ModelLoadResult.Error("The ONNX files changed. Download the complete bundle again.")
                    is CoordinatedLoadResult.Completed -> result.value
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { ModelLoadResult.Error("ONNX load failed: ${failure.message?.take(200) ?: "unsupported graph"}") }
        }
    }

    override suspend fun allowExplicitRetry(request: LoadRequest) = coordinator.allowExplicitRetry(request, runtime.engineVersion)

    override fun generateResponse(userPrompt: String): Flow<InferenceChunk> = flow {
        session.withLock {
            val conversation = messages + OnnxChatMessage("user", userPrompt)
            val response = StringBuilder()
            val reasoning = OnnxReasoningParser()
            try {
                runtime.generate(conversation, 1024).collect { token ->
                    response.append(token.text)
                    usedContext.value = runtime.contextUsed
                    emit(reasoning.push(token.text))
                }
                emit(reasoning.finish())
            } finally {
                if (response.isNotEmpty()) {
                    messages += OnnxChatMessage("user", userPrompt)
                    messages += OnnxChatMessage("assistant", response.toString())
                }
            }
        }
    }

    override suspend fun unloadModel() {
        runtime.cancel()
        session.withLock { runtime.unload(); messages.clear(); usedContext.value = 0 }
    }
    override fun cancelGeneration() = runtime.cancel()
    override fun getContextUsed() = usedContext.value
    override fun getContextLimit() = runtime.contextLimit
    override fun getStopReason() = when (runtime.stopReason) {
        OnnxStopReason.NONE -> StopReason.NONE
        OnnxStopReason.EOS -> StopReason.EOG
        OnnxStopReason.TOKEN_LIMIT -> StopReason.MAX_TOKENS
        OnnxStopReason.CONTEXT_LIMIT -> StopReason.CONTEXT_FULL
        OnnxStopReason.CANCELLED -> StopReason.CANCELLED
        OnnxStopReason.ERROR -> StopReason.ERROR
    }
    override fun isContextAboveThreshold() = getContextUsed() >= getContextLimit() * 0.85
    override fun summarizeConversation(transcript: String): Flow<String> = flow {
        session.withLock {
            runtime.generate(listOf(OnnxChatMessage("system", systemPrompt),
                OnnxChatMessage("user", "Summarize the important facts and requests in this conversation:\n$transcript")), 256)
                .collect { emit(it.text) }
        }
    }
    override suspend fun resetContextWithSummary(summary: String, lastExchange: String): Boolean = session.withLock {
        val replacement = mutableListOf(OnnxChatMessage("system", "$systemPrompt\nConversation summary:\n$summary"))
        if (lastExchange.isNotBlank()) replacement += OnnxChatMessage("user", lastExchange)
        if (runtime.contextLimit == 0) return@withLock false
        val count = runtime.tokenize(replacement).size
        if (count >= runtime.contextLimit) return@withLock false
        messages.clear()
        messages += replacement
        usedContext.value = count
        true
    }
    override suspend fun resetContext() = session.withLock {
        messages.clear()
        messages += OnnxChatMessage("system", systemPrompt)
        usedContext.value = if (runtime.contextLimit == 0) 0 else runtime.tokenize(messages).size
    }
    override fun getRuntimeConfigString() = runtime.runtimeDescription

}
