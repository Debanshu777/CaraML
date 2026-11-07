package com.debanshu777.caraml.core.data.inference

import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.performance.PerformanceSpan
import com.debanshu777.caraml.core.performance.beginPerformanceSpan
import com.debanshu777.caraml.core.performance.currentPerformanceOperationId
import com.debanshu777.caraml.core.performance.tracePerformanceOperation
import com.debanshu777.caraml.core.performance.tracePerformanceStage
import com.debanshu777.caraml.core.recommendation.*
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.sdk.plugin.llama.bridge.InferenceChunk
import com.debanshu777.caraml.sdk.plugin.llama.bridge.StopReason
import com.debanshu777.caraml.sdk.plugin.onnx.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock

/** ONNX shares exact-artifact validation, current-memory admission and crash recovery with GGUF. */
@OptIn(ExperimentalAtomicApi::class)
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
    @Volatile private var loadTraceOperationId: Int? = null

    private sealed interface ResidentState {
        class Loading : ResidentState
        class Ready(val request: LoadRequest, val key: ResidentOnnxReuseKey) : ResidentState
    }
    // Cancellation is synchronous and can arrive while loading or waiting for the artifact lease.
    private val residentState = AtomicReference<ResidentState?>(null)

    override suspend fun tryReuseInstalledModel(
        model: LocalModelEntity,
        artifact: ResolvedLocalArtifact,
    ): ModelLoadResult.Success? {
        if (!session.tryLock()) return null
        return try {
            val resident = residentState.load() as? ResidentState.Ready ?: return null
            if (!runtime.supported || runtime.contextLimit != resident.key.config.contextTokens) return null
            val candidate = resident.request.copy(model = model, identity = artifact.identity, artifact = artifact)
            val preferences = settings.getSettings().first()
            val candidateKey = ResidentOnnxReuseKey.from(candidate, preferences, resident.key.config, runtime.engineVersion)
            if (candidateKey != resident.key) return null
            tracePerformanceStage("load.onnxResidentReuse") {
                coordinator.withCurrentResidentArtifact(candidate) {
                    // A synchronous cancellation or replacement request may have invalidated this key
                    // while the lease was suspended. A stale snapshot cannot restore eligibility.
                    residentState.load() === resident && runtime.contextLimit == resident.key.config.contextTokens
                }?.takeIf { it }?.let { ModelLoadResult.Success(runtime.contextLimit) }
            }
        } catch (cancelled: CancellationException) {
            residentState.store(null)
            throw cancelled
        } catch (_: Exception) {
            residentState.store(null)
            null
        } finally {
            session.unlock()
        }
    }

    override suspend fun loadModel(request: LoadRequest): ModelLoadResult =
        tracePerformanceOperation("load.onnxRepository") {
            val attempt = ResidentState.Loading()
            residentState.store(attempt)
            try { loadModelMeasured(request, attempt) }
            finally { residentState.compareAndSet(attempt, null) }
        }

    private suspend fun loadModelMeasured(request: LoadRequest, attempt: ResidentState.Loading): ModelLoadResult {
        runtime.cancel()
        return withMeasuredSession {
            val artifact = request.artifact ?: return@withMeasuredSession ModelLoadResult.Error("The ONNX bundle is not verified.")
            onnxExportIssue(artifact.components.map { it.identity })?.let { return@withMeasuredSession ModelLoadResult.Error(it) }
            val plan = request.plan as? LlmRunPlan ?: return@withMeasuredSession ModelLoadResult.Error("Invalid ONNX configuration.")
            val path = (artifact.loadTarget as? VerifiedArtifactLoadTarget.File)?.path
                ?: return@withMeasuredSession ModelLoadResult.Error("The ONNX graph is missing.")
            if (!path.endsWith(".onnx") || artifact.identity != request.identity || plan.backend != BackendKind.CPU ||
                plan.keyCacheType != KvCacheType.F32 || plan.valueCacheType != KvCacheType.F32 ||
                !com.debanshu777.caraml.sdk.discovery.huggingface.model.OnnxModelBundles.isComplete(artifact.components.map { it.repositoryRelativePath })) {
                return@withMeasuredSession ModelLoadResult.Error("The ONNX bundle or configuration is unsupported.")
            }
            val controller = LoadAdmissionController(
                snapshotSource = snapshots::capture,
                recommendationSource = { candidate, snapshot ->
                    candidate.assessedPlans?.let { policy.recommend(suitability.assemble(it, snapshot), snapshot, candidate.profile) }
                        ?: PersonalizedRecommendation(candidate.assessmentKey, RecommendationCategory.NEEDS_INFORMATION, null, emptyList(), profile = candidate.profile)
                },
                artifactValidator = { candidate -> candidate.artifact?.let { resolver.revalidate(it) } == true },
                nativePreflight = { tracePerformanceStage("load.onnxSupportCheck") {
                    if (runtime.supported) NativeLoadPreflight.Fit else NativeLoadPreflight.Invalid
                } },
                recoveryState = recovery,
                engineVersion = runtime.engineVersion,
                clock = { Clock.System.now().toEpochMilliseconds() },
            )
            val preferences = tracePerformanceStage("load.onnxSettings") { settings.getSettings().first() }
            var loadedConfig: OnnxRuntimeConfig? = null
            try {
                when (val result = coordinator.execute(
                    request = request,
                    engineVersion = runtime.engineVersion,
                    evaluateAdmission = { controller.evaluate(request, request.riskAcknowledgement) },
                    artifactValidator = { candidate -> candidate.artifact?.let { resolver.revalidate(it) } == true },
                    releasePartialState = { tracePerformanceStage("load.onnxPartialRelease") { runtime.unload() } },
                    nativeLoad = {
                        loadTraceOperationId = null
                        val config = OnnxRuntimeConfig(contextTokens = plan.contextTokens,
                            threads = onnxCpuThreads(artifact.components.map { it.identity }, runtime.engineVersion),
                            provider = OnnxExecutionProvider.CPU, temperature = preferences.temperature)
                        tracePerformanceStage("load.onnxRuntime") {
                            runtime.load(path, config)
                        }
                        systemPrompt = preferences.systemPrompt.ifBlank { "You are a helpful assistant." }
                        messages.clear()
                        messages += OnnxChatMessage("system", systemPrompt)
                        usedContext.value = tracePerformanceStage("load.onnxSystemTokenize") { runtime.tokenize(messages).size }
                        loadedConfig = config
                        NativeLoadOutcome.Succeeded<ModelLoadResult>(ModelLoadResult.Success(runtime.contextLimit))
                    },
                )) {
                    is CoordinatedLoadResult.AdmissionRequired -> ModelLoadResult.AdmissionRequired(result.admission)
                    is CoordinatedLoadResult.ArtifactChanged -> ModelLoadResult.Error("The ONNX files changed. Download the complete bundle again.")
                    is CoordinatedLoadResult.Completed -> result.value.also { loaded ->
                        if (loaded is ModelLoadResult.Success) {
                            loadTraceOperationId = currentPerformanceOperationId()
                            loadedConfig?.let { config ->
                                ResidentOnnxReuseKey.from(request, preferences, config, runtime.engineVersion)
                                    ?.takeIf { runtime.contextLimit == config.contextTokens }
                                    ?.let { residentState.compareAndSet(attempt, ResidentState.Ready(request, it)) }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { ModelLoadResult.Error("ONNX load failed: ${failure.message?.take(200) ?: "unsupported graph"}") }
        }
    }

    override suspend fun allowExplicitRetry(request: LoadRequest) {
        residentState.store(null)
        coordinator.allowExplicitRetry(request, runtime.engineVersion)
    }

    override fun generateResponse(userPrompt: String): Flow<InferenceChunk> = flow {
        val generationSpan = beginPerformanceSpan("generation.onnx", loadTraceOperationId)
        var firstTokenSpan: PerformanceSpan? = null
        try {
            // Includes repository queueing through the first runtime token, not the first drawn frame.
            firstTokenSpan = beginPerformanceSpan("generation.onnxTimeToFirstToken", generationSpan?.operationId)
            withMeasuredSession(generationSpan?.operationId) {
                val conversation = messages + OnnxChatMessage("user", userPrompt)
                val response = StringBuilder()
                val reasoning = OnnxReasoningParser()
                try {
                    runtime.generate(conversation, 1024).collect { token ->
                        firstTokenSpan?.let { it.close(); firstTokenSpan = null }
                        response.append(token.text)
                        usedContext.value = runtime.contextUsed
                        emit(reasoning.push(token.text))
                    }
                    emit(reasoning.finish())
                    if (runtime.stopReason in setOf(OnnxStopReason.CANCELLED, OnnxStopReason.ERROR)) residentState.store(null)
                } catch (failure: Throwable) {
                    residentState.store(null)
                    throw failure
                } finally {
                    if (response.isNotEmpty()) {
                        messages += OnnxChatMessage("user", userPrompt)
                        messages += OnnxChatMessage("assistant", response.toString())
                    }
                }
            }
        } finally {
            firstTokenSpan?.close()
            generationSpan?.close()
        }
    }

    override suspend fun unloadModel() = tracePerformanceOperation("onnx.unload") {
        residentState.store(null)
        runtime.cancel()
        withMeasuredSession {
            try { runtime.unload() } finally { loadTraceOperationId = null }
            messages.clear()
            usedContext.value = 0
        }
    }
    override fun cancelGeneration() {
        residentState.store(null)
        runtime.cancel()
    }
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
        val span = beginPerformanceSpan("onnx.summarize", loadTraceOperationId)
        try {
            withMeasuredSession(span?.operationId) {
                residentState.store(null)
                runtime.generate(listOf(OnnxChatMessage("system", systemPrompt),
                    OnnxChatMessage("user", "Summarize the important facts and requests in this conversation:\n$transcript")), 256)
                    .collect { emit(it.text) }
            }
        } finally {
            span?.close()
        }
    }
    override suspend fun resetContextWithSummary(summary: String, lastExchange: String): Boolean =
        tracePerformanceOperation("onnx.resetWithSummary") {
            withMeasuredSession {
                residentState.store(null)
                val replacement = mutableListOf(OnnxChatMessage("system", "$systemPrompt\nConversation summary:\n$summary"))
                if (lastExchange.isNotBlank()) replacement += OnnxChatMessage("user", lastExchange)
                if (runtime.contextLimit == 0) return@withMeasuredSession false
                val count = tracePerformanceStage("onnx.contextTokenize") { runtime.tokenize(replacement).size }
                if (count >= runtime.contextLimit) return@withMeasuredSession false
                messages.clear()
                messages += replacement
                usedContext.value = count
                true
            }
        }
    override suspend fun resetContext() = tracePerformanceOperation("onnx.resetContext") {
        withMeasuredSession {
            residentState.store(null)
            messages.clear()
            messages += OnnxChatMessage("system", systemPrompt)
            usedContext.value = if (runtime.contextLimit == 0) 0 else
                tracePerformanceStage("onnx.contextTokenize") { runtime.tokenize(messages).size }
        }
    }

    private suspend inline fun <T> withMeasuredSession(operationId: Int? = null, block: () -> T): T {
        val wait = beginPerformanceSpan("onnx.sessionLockWait", operationId)
        try { session.lock() } finally { wait?.close() }
        return try { block() } finally { session.unlock() }
    }

    override fun getRuntimeConfigString() = runtime.runtimeDescription

}
