@file:OptIn(io.github.debanshu777.caraml.core.api.ExperimentalCaraMLApi::class)

package io.github.debanshu777.caraml.plugin.llama

import com.debanshu777.runner.InferenceChunk
import com.debanshu777.runner.LlamaPreflightResult
import com.debanshu777.runner.LlamaRunner
import com.debanshu777.runner.LlamaLazyMode as NativeLazyMode
import com.debanshu777.runner.NativeRunnerConfig
import com.debanshu777.runner.PromptProcessingResult
import com.debanshu777.runner.StopReason
import com.debanshu777.runner.generateStructuredChunks
import io.github.debanshu777.caraml.core.api.*
import io.github.debanshu777.caraml.runtime.NativeRuntimeGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

@ExperimentalCaraMLApi
const val LLAMA_TEXT_CAPABILITY = "text.generate"

/** Typed, plugin-owned settings. They are snapshotted when the plan is assessed. */
@ExperimentalCaraMLApi
data class LlamaSessionOptions(
    val contextTokens: Int = 0,
    val minimumContextTokens: Int = 512,
    val threads: Int = 4,
    val batchThreads: Int = 0,
    val batchTokens: Int = 512,
    val microBatchTokens: Int = 512,
    val maxOutputsPerSequence: Int = 1,
    val flashAttention: Int = -1,
    val offloadKeyValue: Boolean = true,
    val keyCacheType: Int = 1,
    val valueCacheType: Int = 1,
    val gpuLayers: Int = -1,
    val useMemoryMap: Boolean = true,
    val lockMemory: Boolean = false,
    val lazyMode: LlamaLazyMode = LlamaLazyMode.AUTO,
    val temperature: Float = 0.3f,
    val autoFit: Boolean = true,
    val cpuAffinity: String = "",
    val batchCpuAffinity: String = "",
    val systemPrompt: String = "You are a helpful assistant.",
    val maxOutputTokens: Int = 4096,
) {
    init {
        require(contextTokens >= 0 && minimumContextTokens > 0)
        require(threads > 0 && batchThreads >= 0)
        require(batchTokens > 0 && microBatchTokens > 0 && maxOutputsPerSequence > 0)
        require(flashAttention in -1..1)
        require(keyCacheType >= 0 && valueCacheType >= 0)
        require(gpuLayers >= -1)
        require(temperature.isFinite() && temperature >= 0f)
        require(cpuAffinity.length <= 256 && batchCpuAffinity.length <= 256)
        require(systemPrompt.isNotBlank())
        require(maxOutputTokens in 1..32_768)
    }

    internal fun toNativeConfig() = NativeRunnerConfig(
        nCtx = contextTokens,
        nCtxMin = minimumContextTokens,
        nThreads = threads,
        nThreadsBatch = batchThreads,
        nBatch = batchTokens,
        nUbatch = microBatchTokens,
        nOutputsMaxPerSequence = maxOutputsPerSequence,
        flashAttn = flashAttention,
        offloadKqv = offloadKeyValue,
        typeK = keyCacheType,
        typeV = valueCacheType,
        nGpuLayers = gpuLayers,
        useMmap = useMemoryMap,
        useMlock = lockMemory,
        lazyMode = when (lazyMode) {
            LlamaLazyMode.OFF -> NativeLazyMode.OFF
            LlamaLazyMode.AUTO -> NativeLazyMode.AUTO
            LlamaLazyMode.ON -> NativeLazyMode.ON
        },
        temperature = temperature,
        autoFit = autoFit,
        cpuMask = cpuAffinity,
        cpuMaskBatch = batchCpuAffinity,
    )
}

@ExperimentalCaraMLApi
enum class LlamaLazyMode { OFF, AUTO, ON }

/** One user turn and the immutable settings used to admit the session. */
@ExperimentalCaraMLApi
data class LlamaRequest(
    override val operationId: OperationId,
    override val model: ModelDescriptor,
    override val artifact: ArtifactRef,
    val userPrompt: String,
    val options: LlamaSessionOptions = LlamaSessionOptions(),
) : EngineRequest {
    init { require(userPrompt.isNotBlank()) }

    override val capability = CapabilityId(LLAMA_TEXT_CAPABILITY)
    // Preflight produces the engine-owned resident estimate; per-operation charge comes from its plan.
    override val requestedResources = ResourceEstimate()
    override val requestFingerprint: String = fingerprint(options)

    fun nextTurn(operationId: OperationId, userPrompt: String) = copy(operationId = operationId, userPrompt = userPrompt)

    private fun fingerprint(options: LlamaSessionOptions): String {
        // Keep prompts out of plan summaries while binding the full system instruction and native config.
        val input = "$options"
        var hash = -0x340d631b7bdddcdbL
        for (char in input) {
            hash = hash xor char.code.toLong()
            hash *= 0x100000001b3L
        }
        return "llama-v1-${hash.toULong().toString(16)}"
    }
}

/** Local GGUF adapter. It has no dependency on discovery, storage, UI, or preferences. */
@ExperimentalCaraMLApi
class LlamaPlugin private constructor(
    private val nativeLibraryDirectory: String,
    bridgeFactory: () -> LlamaBridge,
) : EngineProvider {
    constructor(nativeLibraryDirectory: String = "") : this(nativeLibraryDirectory, { RunnerLlamaBridge(LlamaRunner()) })

    internal constructor(nativeLibraryDirectory: String, bridge: LlamaBridge) :
        this(nativeLibraryDirectory, { bridge })

    private val bridge: LlamaBridge by lazy(bridgeFactory)

    override val id = EngineId("io.github.debanshu777.caraml.llama")
    private var detectedVersion: String = "runner-uninitialized"
    override val version: EngineVersion get() = EngineVersion("llama-${detectedVersion}")
    override val capabilities = setOf(CapabilityId(LLAMA_TEXT_CAPABILITY))
    override val capacity = EngineCapacity(maxLoadedSessions = 1, maxConcurrentOperations = 1)
    private var loaded: Boolean = false

    override suspend fun preflight(request: EngineRequest, artifact: ArtifactLease): PreflightResult {
        val typed = request as? LlamaRequest ?: return PreflightResult.Unsupported("Expected a LlamaRequest")
        if (typed.model.format != null && !typed.model.format.equals("gguf", ignoreCase = true)) {
            return PreflightResult.Unsupported("Llama accepts local GGUF model artifacts")
        }
        val path = (artifact.location as? ArtifactLocation.LocalFile)?.path
            ?: return PreflightResult.Unsupported("Llama requires an ArtifactLocation.LocalFile")
        if (artifact.artifact != typed.artifact || path.isBlank()) {
            return PreflightResult.Unsupported("Artifact lease does not match the request")
        }
        val gateLease = NativeRuntimeGate.tryAcquire()
            ?: throw InferenceException.Busy(id, "the shared native runtime is in use")
        try {
            withContext(Dispatchers.Default) { bridge.initialize(nativeLibraryDirectory) }
            detectedVersion = withContext(Dispatchers.Default) {
                bridge.engineVersion()?.takeIf { it.isNotBlank() } ?: "runner-version-unavailable"
            }
            when (val result = withContext(Dispatchers.Default) { bridge.preflight(path, typed.options.toNativeConfig()) }) {
                is LlamaPreflightResult.Fit -> {
                    val estimate = estimate(result)
                        ?: return PreflightResult.Unsupported("Native resource estimate overflowed")
                    val effectiveConfig = typed.options.toNativeConfig().copy(
                        nCtx = result.report.fittedContextTokens,
                        nGpuLayers = result.report.fittedGpuLayers,
                        autoFit = false,
                    )
                    return PreflightResult.Ready(
                        LlamaPlan(
                            EnginePlanSummary(
                                engineId = id,
                                engineVersion = version,
                                artifactId = typed.artifact.id,
                                capability = typed.capability,
                                modelId = typed.model.id,
                                requestFingerprint = typed.requestFingerprint,
                                resources = estimate,
                                effectiveOptionsDescription = "fitted context=${result.report.fittedContextTokens}, gpuLayers=${result.report.fittedGpuLayers}",
                            ),
                            this,
                            path,
                            typed.model,
                            typed.artifact,
                            typed.options,
                            effectiveConfig,
                        ),
                    )
                }
                is LlamaPreflightResult.NoFit -> return PreflightResult.Unsupported("Model does not fit the available native memory")
                is LlamaPreflightResult.InvalidModel -> return PreflightResult.Unsupported("Invalid or incompatible GGUF model: ${result.reason}")
                is LlamaPreflightResult.Unavailable -> return PreflightResult.Unsupported("Llama preflight is unavailable: ${result.reason}")
            }
        } finally {
            withContext(NonCancellable) { gateLease.close() }
        }
    }

    override suspend fun load(plan: EnginePlan): EngineSession {
        val llamaPlan = plan as? LlamaPlan ?: throw InferenceException.InvalidPlan("Plan was not created by LlamaPlugin")
        if (llamaPlan.owner !== this || llamaPlan.summary.engineId != id || llamaPlan.summary.engineVersion != version) {
            throw InferenceException.InvalidPlan("Llama plan belongs to another plugin instance or engine version")
        }
        if (loaded) throw InferenceException.Busy(id, "this plugin already owns its native session")
        val gateLease = NativeRuntimeGate.tryAcquire()
            ?: throw InferenceException.Busy(id, "the shared native runtime is in use")
        val session = LlamaSession(
            this, bridge, llamaPlan.model, llamaPlan.summary.artifactId,
            llamaPlan.artifact, llamaPlan.summary.requestFingerprint, llamaPlan.options, gateLease,
        )
        var nativeLoadAttempted = false
        try {
            withContext(Dispatchers.Default) { bridge.initialize(nativeLibraryDirectory) }
            nativeLoadAttempted = true
            if (!withContext(Dispatchers.Default) { bridge.loadModel(llamaPlan.path, llamaPlan.effectiveConfig) }) {
                throw InferenceException.Unsupported("Llama could not load the assessed model")
            }
            loaded = true
            if (llamaPlan.options.systemPrompt.isNotBlank()) {
                when (PromptProcessingResult.fromNativeCode(withContext(Dispatchers.Default) { bridge.processSystemPrompt(llamaPlan.options.systemPrompt) })) {
                    PromptProcessingResult.Success -> Unit
                    PromptProcessingResult.ContextFull -> throw InferenceException.ResourceUnavailable("System prompt does not fit the loaded context")
                    PromptProcessingResult.Failure -> throw InferenceException.Unsupported("Llama failed to process the system prompt")
                }
            }
            return session
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                if (!nativeLoadAttempted) {
                    gateLease.close()
                } else {
                    try {
                        withContext(Dispatchers.Default) { bridge.unloadModel() }
                        loaded = false
                        gateLease.close()
                    } catch (cleanupFailure: Throwable) {
                        // Core must retain the mapped artifact and admission charge too.
                        throw UnreleasedEngineLoad(session, failure).also { it.addSuppressed(cleanupFailure) }
                    }
                }
            }
            throw failure
        }
    }

    private fun estimate(result: LlamaPreflightResult.Fit): ResourceEstimate? {
        var bytes = 0L
        for (pool in result.report.memoryPools) {
            val poolBytes = listOf(pool.modelBytes, pool.contextBytes, pool.computeBytes)
            for (value in poolBytes) {
                if (bytes > Long.MAX_VALUE - value) return null
                bytes += value
            }
        }
        return ResourceEstimate(residentBytes = bytes)
    }

    private data class LlamaPlan(
        override val summary: EnginePlanSummary,
        val owner: LlamaPlugin,
        val path: String,
        val model: ModelDescriptor,
        val artifact: ArtifactRef,
        val options: LlamaSessionOptions,
        val effectiveConfig: NativeRunnerConfig,
    ) : EnginePlan

    private class LlamaSession(
        private val plugin: LlamaPlugin,
        private val bridge: LlamaBridge,
        override val model: ModelDescriptor,
        private val artifactId: ArtifactId,
        private val artifact: ArtifactRef,
        private val requestFingerprint: String,
        private val options: LlamaSessionOptions,
        private val gateLease: NativeRuntimeGate.Lease,
    ) : EngineSession {
        override val engineId: EngineId get() = plugin.id
        private val operations = Mutex()
        private val state = Mutex()
        private var active: OperationId? = null
        private val used = mutableSetOf<OperationId>()
        private var closing = false
        private var closed = false

        override suspend fun generate(operationId: OperationId, request: EngineRequest): Flow<GenerationEvent> {
            val typed = request as? LlamaRequest ?: throw InferenceException.InvalidPlan("Expected a LlamaRequest")
            if (typed.operationId != operationId || typed.model.id != model.id || typed.artifact != artifact || typed.artifact.id != artifactId ||
                typed.options != options || typed.requestFingerprint != requestFingerprint
            ) throw InferenceException.InvalidPlan("Llama request changes the loaded model or typed options")
            var collected = false
            val collectionLock = Mutex()
            return flow {
                collectionLock.withLock {
                    if (collected) throw InferenceException.InvalidPlan("Llama generation flow is single-use")
                    collected = true
                }
                operations.withLock {
                    state.withLock {
                        if (closing || closed) throw InferenceException.InvalidPlan("Llama session is closing or closed")
                        if (!used.add(operationId)) throw InferenceException.InvalidPlan("Operation ID has already been used")
                        active = operationId
                    }
                    val started = TimeSource.Monotonic.markNow()
                    var tokenEvents = 0L
                    try {
                        when (PromptProcessingResult.fromNativeCode(bridge.processUserPrompt(typed.userPrompt, options.maxOutputTokens))) {
                            PromptProcessingResult.Success -> Unit
                            PromptProcessingResult.ContextFull -> {
                                emit(GenerationEvent.Failed(EngineError("CONTEXT_FULL", "Prompt does not fit the loaded context", recoverable = false)))
                                return@withLock
                            }
                            PromptProcessingResult.Failure -> {
                                emit(GenerationEvent.Failed(EngineError("PREFILL_FAILED", "Llama could not process the prompt")))
                                return@withLock
                            }
                        }
                        bridge.generateChunks().collect { chunk ->
                            if (chunk.isTokenEvent) tokenEvents++
                            if (chunk.reasoningResync) emit(GenerationEvent.TextResync(chunk.reasoningDelta, TextKind.REASONING))
                            else if (chunk.reasoningDelta.isNotEmpty()) emit(GenerationEvent.TextDelta(chunk.reasoningDelta, TextKind.REASONING))
                            if (chunk.contentResync) emit(GenerationEvent.TextResync(chunk.contentDelta, TextKind.CONTENT))
                            else if (chunk.contentDelta.isNotEmpty()) emit(GenerationEvent.TextDelta(chunk.contentDelta, TextKind.CONTENT))
                        }
                        when (bridge.stopReason()) {
                            StopReason.CANCELLED -> emit(GenerationEvent.Completed(CompletionReason.CANCELLED, GenerationMetrics(outputTokens = tokenEvents, elapsedMillis = started.elapsedNow().inWholeMilliseconds)))
                            StopReason.MAX_TOKENS -> emit(GenerationEvent.Completed(CompletionReason.LENGTH_LIMIT, GenerationMetrics(outputTokens = tokenEvents, elapsedMillis = started.elapsedNow().inWholeMilliseconds)))
                            StopReason.CONTEXT_FULL -> emit(GenerationEvent.Completed(CompletionReason.LENGTH_LIMIT, GenerationMetrics(outputTokens = tokenEvents, elapsedMillis = started.elapsedNow().inWholeMilliseconds)))
                            StopReason.ERROR -> emit(GenerationEvent.Failed(EngineError("NATIVE_FAILURE", "Llama generation failed")))
                            else -> emit(GenerationEvent.Completed(CompletionReason.END_OF_TURN, GenerationMetrics(outputTokens = tokenEvents, elapsedMillis = started.elapsedNow().inWholeMilliseconds)))
                        }
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable) { try { bridge.cancelGenerate() } catch (_: Throwable) { } }
                        throw cancelled
                    } catch (failure: Throwable) {
                        emit(GenerationEvent.Failed(EngineError("LLAMA_FAILURE", "Llama generation failed")))
                    } finally {
                        withContext(NonCancellable) {
                            try { bridge.finalizeGeneration() } finally {
                                state.withLock { if (active == operationId) active = null }
                            }
                        }
                    }
                }
            }.flowOn(Dispatchers.Default)
        }

        override suspend fun cancel(operationId: OperationId) {
            state.withLock {
                if (active == operationId) bridge.cancelGenerate()
            }
        }

        override suspend fun unload() = close()

        override suspend fun close() {
            withContext(NonCancellable) {
                state.withLock {
                    if (closed) return@withContext
                    closing = true
                    if (active != null) try { bridge.cancelGenerate() } catch (_: Throwable) { }
                }
                operations.withLock {
                    val current = state.withLock { !closed }
                    if (!current) return@withLock
                    // Do not release the process-wide GGML guard if native state may still be live.
                    withContext(Dispatchers.Default) { bridge.unloadModel() }
                    plugin.loaded = false
                    gateLease.close()
                    state.withLock { closed = true }
                }
            }
        }
    }
}

@ExperimentalCaraMLApi
internal interface LlamaBridge {
    fun initialize(nativeLibraryDirectory: String)
    fun engineVersion(): String?
    fun preflight(path: String, config: NativeRunnerConfig): LlamaPreflightResult
    fun loadModel(path: String, config: NativeRunnerConfig): Boolean
    fun processSystemPrompt(prompt: String): Int
    fun processUserPrompt(prompt: String, maxTokens: Int): Int
    fun generateChunks(): Flow<InferenceChunk>
    fun stopReason(): Int
    fun cancelGenerate()
    fun finalizeGeneration()
    fun unloadModel()
}

private class RunnerLlamaBridge(private val runner: LlamaRunner) : LlamaBridge {
    override fun initialize(nativeLibraryDirectory: String) = runner.initialize(nativeLibraryDirectory)
    override fun engineVersion() = runner.engineVersion()
    override fun preflight(path: String, config: NativeRunnerConfig) = runner.preflightModel(path, config)
    override fun loadModel(path: String, config: NativeRunnerConfig) = runner.loadModel(path, config)
    override fun processSystemPrompt(prompt: String) = runner.processSystemPrompt(prompt)
    override fun processUserPrompt(prompt: String, maxTokens: Int) = runner.processUserPrompt(prompt, maxTokens)
    override fun generateChunks() = runner.generateStructuredChunks()
    override fun stopReason() = runner.getStopReason()
    override fun cancelGenerate() = runner.cancelGenerate()
    override fun finalizeGeneration() = runner.finalizeGeneration()
    override fun unloadModel() = runner.unloadModel()
}
