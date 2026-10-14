@file:OptIn(com.debanshu777.caraml.sdk.api.ExperimentalCaraMLApi::class)

package com.debanshu777.caraml.sdk.plugin.diffusion

import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.DiffusionModelConfig
import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.DiffusionPreflightResult
import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.DiffusionRunner
import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.ImageGenParams
import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.SampleMethod
import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.DiffusionRuntimeBackend
import com.debanshu777.caraml.sdk.api.ArtifactId
import com.debanshu777.caraml.sdk.api.ArtifactLease
import com.debanshu777.caraml.sdk.api.ArtifactLocation
import com.debanshu777.caraml.sdk.api.CapabilityId
import com.debanshu777.caraml.sdk.api.CompletionReason
import com.debanshu777.caraml.sdk.api.EngineCapacity
import com.debanshu777.caraml.sdk.api.EngineError
import com.debanshu777.caraml.sdk.api.EngineId
import com.debanshu777.caraml.sdk.api.EnginePlan
import com.debanshu777.caraml.sdk.api.EnginePlanSummary
import com.debanshu777.caraml.sdk.api.EngineProvider
import com.debanshu777.caraml.sdk.api.EngineRequest
import com.debanshu777.caraml.sdk.api.EngineSession
import com.debanshu777.caraml.sdk.api.EngineVersion
import com.debanshu777.caraml.sdk.api.GeneratedMediaMetadata
import com.debanshu777.caraml.sdk.api.GeneratedOutputSink
import com.debanshu777.caraml.sdk.api.GenerationEvent
import com.debanshu777.caraml.sdk.api.InferenceException
import com.debanshu777.caraml.sdk.api.ModelDescriptor
import com.debanshu777.caraml.sdk.api.OperationId
import com.debanshu777.caraml.sdk.api.PreflightResult
import com.debanshu777.caraml.sdk.api.SuspendCloseable
import com.debanshu777.caraml.sdk.api.UnreleasedEngineLoad
import com.debanshu777.caraml.sdk.api.ResourceEstimate
import com.debanshu777.caraml.sdk.runtime.NativeRuntimeGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

private const val DIFFUSION_ENGINE_ID = "caraml.diffusion.sdcpp"
private const val DIFFUSION_ENGINE_VERSION = "1"
private const val IMAGE_CAPABILITY = "image-generation"
private const val MAX_IMAGE_DIMENSION = 1024
private const val MAX_IMAGE_PIXELS = 1_048_576L
private const val MAX_STEPS = 50
private const val MAX_PROMPT_CHARS = 4_096
private const val MAX_PNG_BYTES = 16L * 1024 * 1024
private const val PROGRESS_POLL_MILLIS = 250L
private const val PROVISIONAL_TRANSIENT_BYTES_PER_PIXEL = 64L

enum class DiffusionBackend { CPU, METAL, VULKAN, CUDA }
enum class DiffusionSampler { EULER, EULER_A, HEUN, DPM2, DPMPP2S_A, DPMPP2M, DPMPP2M_V2, IPNDM, IPNDM_V, LCM, DDIM_TRAILING, TCD }

/** Plugin-owned execution settings; native runner types stay behind the adapter. */
data class DiffusionImageOptions(
    val backend: DiffusionBackend = DiffusionBackend.CPU,
    val offloadToCpu: Boolean = false,
    val keepClipOnCpu: Boolean = false,
    val keepVaeOnCpu: Boolean = false,
    val flashAttention: Boolean = false,
    val useMmap: Boolean = false,
    val directConvolution: Boolean = false,
    val freeParamsImmediately: Boolean = false,
    val weightType: Int = -1,
    val flowShift: Float = Float.POSITIVE_INFINITY,
    val threads: Int = -1,
    val prediction: Int = -1,
    val vaeTiling: Boolean = false,
    val maxVram: String = "",
    val segmentedCompute: Boolean = false,
    val prefetch: Boolean = false,
    val autoFit: Boolean = false,
)

/** Plugin-owned image parameters; only PNG image generation is currently exposed. */
data class DiffusionImageParameters(
    val prompt: String,
    val negativePrompt: String = "",
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 20,
    val cfgScale: Float = 7f,
    val seed: Long = -1L,
    val sampler: DiffusionSampler = DiffusionSampler.EULER_A,
)

/**
 * Typed first-party request for a bounded single-image operation. This initial
 * adapter intentionally accepts a single leased model file and no external
 * components/LoRAs; bundle planning can be added once leases cover all inputs.
 */
data class DiffusionImageRequest(
    override val operationId: OperationId,
    override val model: ModelDescriptor,
    override val artifact: com.debanshu777.caraml.sdk.api.ArtifactRef,
    val options: DiffusionImageOptions = DiffusionImageOptions(),
    val image: DiffusionImageParameters,
) : EngineRequest {
    override val capability = CapabilityId(IMAGE_CAPABILITY)
    override val requestedResources: ResourceEstimate = ResourceEstimate(
        // Provisional admission estimate; it is not a measured safety bound.
        transientBytes = provisionalTransientBytes(image.width, image.height),
        outputBytes = MAX_PNG_BYTES,
        diskBytes = MAX_PNG_BYTES,
    )
    override val requestFingerprint: String = buildString {
        append(options)
        append('|').append(image.width).append('x').append(image.height)
        append('|').append(image.steps).append('|').append(image.cfgScale.toBits())
        append('|').append(image.seed).append('|').append(image.sampler.name)
    }
}

private fun provisionalTransientBytes(width: Int, height: Int): Long {
    val pixels = width.coerceAtLeast(0).toLong() * height.coerceAtLeast(0).toLong()
    return if (pixels > Long.MAX_VALUE / PROVISIONAL_TRANSIENT_BYTES_PER_PIXEL) {
        Long.MAX_VALUE
    } else {
        pixels * PROVISIONAL_TRANSIENT_BYTES_PER_PIXEL
    }
}

private fun DiffusionImageOptions.toRunnerConfig(modelPath: String) = DiffusionModelConfig(
    modelPath = modelPath,
    runtimeBackend = DiffusionRuntimeBackend.valueOf(backend.name),
    offloadToCpu = offloadToCpu,
    keepClipOnCpu = keepClipOnCpu,
    keepVaeOnCpu = keepVaeOnCpu,
    diffusionFlashAttn = flashAttention,
    enableMmap = useMmap,
    diffusionConvDirect = directConvolution,
    freeParamsImmediately = freeParamsImmediately,
    wtype = weightType,
    flowShift = flowShift,
    nThreads = threads,
    prediction = prediction,
    vaeTiling = vaeTiling,
    maxVram = maxVram,
    segmentedCompute = segmentedCompute,
    prefetch = prefetch,
    autoFit = autoFit,
)

private fun DiffusionImageParameters.toRunnerParams() = ImageGenParams(
    prompt = prompt,
    negativePrompt = negativePrompt,
    width = width,
    height = height,
    steps = steps,
    cfgScale = cfgScale,
    seed = seed,
    sampleMethod = SampleMethod.valueOf(sampler.name),
)

/** Native boundary kept internal so contract tests can supply deterministic fakes. */
internal interface DiffusionRuntime {
    fun initialize(nativeLibDir: String)
    fun preflight(config: DiffusionModelConfig): DiffusionPreflightResult
    fun load(config: DiffusionModelConfig): Boolean
    fun generate(params: ImageGenParams): ByteArray?
    fun progress(): IntArray
    fun cancel(): Boolean
    fun release()
}

internal fun interface DiffusionRuntimeFactory { fun create(): DiffusionRuntime }

private class RunnerRuntime(private val runner: DiffusionRunner) : DiffusionRuntime {
    override fun initialize(nativeLibDir: String) = runner.initialize(nativeLibDir)
    override fun preflight(config: DiffusionModelConfig) = runner.preflightModel(config)
    override fun load(config: DiffusionModelConfig) = runner.loadModel(config)
    override fun generate(params: ImageGenParams) = runner.txt2Img(params)
    override fun progress() = runner.getStepProgress()
    override fun cancel() = runner.cancelGeneration()
    override fun release() = runner.release()
}

/**
 * Optional image engine implementation. The output sink is borrowed and must
 * be safe to use for the lifetime of each generated operation.
 */
class DiffusionEngineProvider internal constructor(
    private val outputSink: GeneratedOutputSink,
    private val nativeLibDir: String,
    private val dispatcher: CoroutineDispatcher,
    private val runtimeFactory: DiffusionRuntimeFactory,
) : EngineProvider {
    constructor(
        outputSink: GeneratedOutputSink,
        nativeLibDir: String,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(outputSink, nativeLibDir, dispatcher, DiffusionRuntimeFactory { RunnerRuntime(DiffusionRunner()) })

    private val identity = Any()

    override val id = EngineId(DIFFUSION_ENGINE_ID)
    override val version = EngineVersion(DIFFUSION_ENGINE_VERSION)
    override val capabilities = setOf(CapabilityId(IMAGE_CAPABILITY))
    override val capacity = EngineCapacity(maxLoadedSessions = 1, maxConcurrentOperations = 1)

    override suspend fun preflight(request: EngineRequest, artifact: ArtifactLease): PreflightResult {
        val imageRequest = request as? DiffusionImageRequest
            ?: return PreflightResult.Unsupported("Diffusion requires a DiffusionImageRequest")
        val path = validateBinding(imageRequest, artifact)
            ?: return PreflightResult.Unsupported("Diffusion needs one matching local model artifact")
        if (!imageRequest.isWithinPluginBounds()) {
            return PreflightResult.Unsupported("Image request exceeds the diffusion plugin's bounded workload")
        }
        val gateLease = NativeRuntimeGate.tryAcquire()
            ?: return PreflightResult.Unsupported("Another native engine operation is active")
        try {
            return withContext(dispatcher) {
                val runtime = runtimeFactory.create()
                try {
                    runtime.initialize(nativeLibDir)
                    when (val result = runtime.preflight(imageRequest.options.toRunnerConfig(path))) {
                        is DiffusionPreflightResult.Fit -> {
                            val residentBytes = result.report.components.fold(0L) { total, component ->
                                if (Long.MAX_VALUE - total < component.parameterBytes) Long.MAX_VALUE
                                else total + component.parameterBytes
                            }
                            val summary = EnginePlanSummary(
                                engineId = id,
                                engineVersion = version,
                                artifactId = artifact.artifact.id,
                                capability = imageRequest.capability,
                                modelId = imageRequest.model.id,
                                requestFingerprint = imageRequest.requestFingerprint,
                                resources = imageRequest.requestedResources.copy(residentBytes = residentBytes),
                                effectiveOptionsDescription = "${imageRequest.image.width}x${imageRequest.image.height}, ${imageRequest.image.steps} steps",
                            )
                            PreflightResult.Ready(
                                DiffusionPlan(
                                    owner = identity,
                                    summary = summary,
                                    modelPath = path,
                                    model = imageRequest.model,
                                    artifact = artifact.artifact,
                                    options = imageRequest.options,
                                    operationId = imageRequest.operationId,
                                ),
                            )
                        }
                        is DiffusionPreflightResult.NoFit -> PreflightResult.Unsupported("Diffusion model does not fit the selected runtime profile")
                        is DiffusionPreflightResult.InvalidModel -> PreflightResult.Unsupported("Diffusion model or configuration is invalid")
                        is DiffusionPreflightResult.Unavailable -> PreflightResult.Unsupported("Diffusion native preflight is unavailable")
                    }
                } finally {
                    runtime.release()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return PreflightResult.Unsupported("Diffusion native preflight failed")
        } finally {
            withContext(NonCancellable) { gateLease.close() }
        }
    }

    override suspend fun load(plan: EnginePlan): EngineSession {
        val diffusionPlan = plan as? DiffusionPlan
            ?: throw InferenceException.InvalidPlan("Plan was not created by the diffusion engine")
        if (diffusionPlan.owner !== identity || diffusionPlan.summary.engineId != id || diffusionPlan.summary.engineVersion != version) {
            throw InferenceException.InvalidPlan("Diffusion plan belongs to another provider or engine version")
        }
        val gateLease = NativeRuntimeGate.tryAcquire()
            ?: throw InferenceException.Busy(id, "a native engine operation is active")
        var runtime: DiffusionRuntime? = null
        try {
            val createdRuntime = runtimeFactory.create()
            runtime = createdRuntime
            withContext(dispatcher) {
                createdRuntime.initialize(nativeLibDir)
                if (!createdRuntime.load(diffusionPlan.options.toRunnerConfig(diffusionPlan.modelPath))) {
                    throw InferenceException.Unsupported("Diffusion model load failed")
                }
            }
            return DiffusionImageSession(diffusionPlan, createdRuntime, gateLease, outputSink, dispatcher)
        } catch (failure: Throwable) {
            cleanupFailedLoad(runtime, gateLease, dispatcher, failure)
            if (failure is CancellationException || failure is InferenceException) throw failure
            throw InferenceException.Unsupported("Diffusion model load failed")
        }
    }
}

private suspend fun cleanupFailedLoad(
    runtime: DiffusionRuntime?,
    gateLease: NativeRuntimeGate.Lease,
    dispatcher: CoroutineDispatcher,
    failure: Throwable,
) {
    val owner = object : SuspendCloseable {
        override suspend fun close() = withContext(NonCancellable) {
            runtime?.let { withContext(dispatcher) { it.release() } }
            gateLease.close()
        }
    }
    try {
        owner.close()
    } catch (cleanupFailure: Throwable) {
        throw UnreleasedEngineLoad(owner, failure).also { it.addSuppressed(cleanupFailure) }
    }
}

private data class DiffusionPlan(
    val owner: Any,
    override val summary: EnginePlanSummary,
    val modelPath: String,
    val model: ModelDescriptor,
    val artifact: com.debanshu777.caraml.sdk.api.ArtifactRef,
    val options: DiffusionImageOptions,
    val operationId: OperationId,
) : EnginePlan

private fun validateBinding(request: DiffusionImageRequest, artifact: ArtifactLease): String? {
    if (request.artifact != artifact.artifact) return null
    val local = artifact.location as? ArtifactLocation.LocalFile ?: return null
    if (local.path.isBlank() || '\u0000' in local.path) return null
    return local.path
}

private fun DiffusionImageRequest.isWithinPluginBounds(): Boolean =
    image.prompt.isNotBlank() && image.prompt.length <= MAX_PROMPT_CHARS &&
        '\u0000' !in image.prompt && '\u0000' !in image.negativePrompt &&
        image.negativePrompt.length <= MAX_PROMPT_CHARS &&
        image.width in 64..MAX_IMAGE_DIMENSION && image.height in 64..MAX_IMAGE_DIMENSION &&
        image.width % 8 == 0 && image.height % 8 == 0 &&
        image.width.toLong() * image.height.toLong() <= MAX_IMAGE_PIXELS &&
        image.steps in 1..MAX_STEPS && image.cfgScale.isFinite() && image.cfgScale in 0f..30f

private class DiffusionImageSession(
    private val plan: DiffusionPlan,
    private val runtime: DiffusionRuntime,
    private val nativeLease: NativeRuntimeGate.Lease,
    private val outputSink: GeneratedOutputSink,
    private val dispatcher: CoroutineDispatcher,
) : EngineSession {
    private val state = Mutex()
    private var closed = false
    private var usedOperations = mutableSetOf<OperationId>()

    override val engineId = plan.summary.engineId
    override val model = plan.model

    override suspend fun generate(operationId: OperationId, request: EngineRequest): Flow<GenerationEvent> {
        val imageRequest = request as? DiffusionImageRequest
            ?: throw InferenceException.InvalidPlan("Diffusion requires a typed image request")
        if (operationId != imageRequest.operationId || operationId != plan.operationId ||
            imageRequest.model.id != plan.summary.modelId ||
            imageRequest.model != plan.model ||
            imageRequest.artifact != plan.artifact ||
            imageRequest.capability != plan.summary.capability ||
            imageRequest.requestFingerprint != plan.summary.requestFingerprint ||
            !imageRequest.isWithinPluginBounds()
        ) {
            throw InferenceException.InvalidPlan("Image request no longer matches the admitted diffusion plan")
        }

        return channelFlow {
            if (!state.tryLock()) throw InferenceException.Busy(engineId, "diffusion session is active")
            if (closed) {
                state.unlock()
                throw InferenceException.Busy(engineId, "diffusion session is closed")
            }
            if (!usedOperations.add(operationId)) {
                state.unlock()
                throw InferenceException.InvalidPlan("A diffusion operation can only be collected once")
            }
            var staged: com.debanshu777.caraml.sdk.api.StagedOutput? = null
            var committedResult: com.debanshu777.caraml.sdk.api.GeneratedMediaResult? = null
            var progressJob: kotlinx.coroutines.Job? = null
            try {
                send(GenerationEvent.Progress(0.0, "Starting image generation"))
                progressJob = launch(dispatcher) {
                    while (true) {
                        val progress = withContext(dispatcher) { runtime.progress() }
                        if (progress.size >= 2 && progress[1] > 0) {
                            send(GenerationEvent.Progress(
                                (progress[0].toDouble() / progress[1].toDouble()).coerceIn(0.0, 1.0),
                                "Generating image",
                            ))
                        }
                        delay(PROGRESS_POLL_MILLIS)
                    }
                }
                val png = runNativeGeneration(imageRequest.image.toRunnerParams())
                if (png.isEmpty() || png.size.toLong() > MAX_PNG_BYTES) {
                    throw InferenceException.Unsupported("Native image output is empty or exceeds the output limit")
                }

                val result = try {
                    staged = outputSink.begin(operationId, png.size.toLong())
                    staged!!.write(png)
                    withContext(NonCancellable) {
                        staged!!.commit(
                            GeneratedMediaMetadata(mimeType = "image/png", width = imageRequest.image.width, height = imageRequest.image.height),
                        ).also { committedResult = it }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    throw OutputPublicationFailure(failure)
                }
                staged = null
                send(GenerationEvent.MediaPublished(result))
                send(GenerationEvent.Completed(CompletionReason.END_OF_TURN))
            } catch (cancelled: CancellationException) {
                cancelNative(operationId)
                if (committedResult == null) abortQuietly(staged)
                throw cancelled
            } catch (failure: Throwable) {
                if (committedResult == null) abortQuietly(staged)
                val publicationFailed = failure is OutputPublicationFailure
                send(GenerationEvent.Failed(EngineError(
                    if (publicationFailed) "diffusion_output_publication_failed" else "diffusion_generation_failed",
                    if (publicationFailed) "Generated image could not be committed to the output sink" else "Diffusion image generation failed",
                )))
            } finally {
                withContext(NonCancellable) {
                    progressJob?.cancel()
                    progressJob?.join()
                }
                state.unlock()
            }
        }
    }

    private suspend fun runNativeGeneration(params: ImageGenParams): ByteArray = coroutineScope {
        val work = async(dispatcher) { runtime.generate(params) }
        try {
            work.await() ?: throw InferenceException.Unsupported("Diffusion image generation returned no output")
        } catch (cancelled: CancellationException) {
            // Cancellation is lock-free at the native boundary; join keeps the process lease
            // and session serialization until the synchronous native call really returns.
            cancelNative(plan.operationId)
            withContext(NonCancellable) { work.join() }
            throw cancelled
        }
    }

    override suspend fun cancel(operationId: OperationId) {
        cancelNative(operationId)
    }

    override suspend fun unload() {
        // The session owns this plan's sole operation; signal cancellation before
        // waiting for its lock so a blocking native call can return promptly.
        cancelNative(plan.operationId)
        state.lock()
        try {
            if (!closed) {
                withContext(NonCancellable) {
                    var teardownConfirmed = false
                    try {
                        withContext(dispatcher) { runtime.release() }
                        teardownConfirmed = true
                    } finally {
                        closed = true
                        // Never admit another engine if native state may still be live.
                        if (teardownConfirmed) nativeLease.close()
                    }
                }
            }
        } finally {
            state.unlock()
        }
    }

    override suspend fun close() = unload()

    private fun cancelNative(operationId: OperationId) {
        if (operationId == plan.operationId) {
            try {
                runtime.cancel()
            } catch (_: Throwable) {
                // Cancellation is best effort; native execution still owns its session gate.
            }
        }
    }
}

private class OutputPublicationFailure(cause: Throwable) : Exception(cause)

private suspend fun abortQuietly(staged: com.debanshu777.caraml.sdk.api.StagedOutput?) {
    withContext(NonCancellable) {
        try {
            staged?.abort()
        } catch (_: Throwable) {
            // Preserve the operation's original terminal status if cleanup also fails.
        }
    }
}
