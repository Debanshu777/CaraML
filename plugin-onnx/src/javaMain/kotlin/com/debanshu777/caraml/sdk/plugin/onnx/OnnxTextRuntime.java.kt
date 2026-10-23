package com.debanshu777.caraml.sdk.plugin.onnx

import ai.onnxruntime.*
import ai.onnxruntime.providers.NNAPIFlags
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.EnumSet

actual fun createOnnxTextRuntime(): OnnxTextRuntime = OrtTextRuntime()

internal class OrtTextRuntime : OnnxTextRuntime {
    private val mutex = Mutex()
    private val environment by lazy { OrtEnvironment.getEnvironment().also { it.setTelemetry(false) } }
    private var session: OrtSession? = null
    private var tokenizer: HuggingFaceBpeTokenizer? = null
    private var configuration = OnnxRuntimeConfig()
    private var modelConfig = JsonObject(emptyMap())
    private var eos = emptySet<Int>()
    private var template: String? = null
    private var providerDeviceDescription: String? = null
    @Volatile private var runOptions: OrtSession.RunOptions? = null
    @Volatile private var cancelled = false
    @Volatile override var contextUsed = 0
        private set
    @Volatile override var runtimeDescription = "ONNX Runtime (unloaded)"
        private set
    override val supported = true
    override val engineVersion: String get() = "onnxruntime-${environment.version}-caraml-v1"
    override val contextLimit: Int get() = if (session == null) 0 else configuration.contextTokens
    @Volatile override var stopReason = OnnxStopReason.NONE
        private set

    override suspend fun load(modelPath: String, config: OnnxRuntimeConfig) = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeSession()
            providerDeviceDescription = null
            val graph = File(modelPath).canonicalFile
            require(graph.isFile && graph.extension.equals("onnx", true)) { "Select a complete ONNX graph bundle" }
            validateOnnxExternalData(graph)
            val root = sequenceOf(graph.parentFile, graph.parentFile?.parentFile)
                .filterNotNull().firstOrNull { File(it, "config.json").isFile && File(it, "tokenizer.json").isFile }
                ?: error("ONNX bundle is missing config.json or tokenizer.json")
            fun json(name: String): JsonObject? = File(root, name).takeIf(File::isFile)?.let {
                require(it.length() <= 32L * 1024 * 1024) { "ONNX metadata exceeds the size limit" }
                Json.parseToJsonElement(it.readText()).jsonObject
            }
            modelConfig = requireNotNull(json("config.json"))
            val tokenizerFile = File(root, "tokenizer.json")
            require(tokenizerFile.length() <= 32L * 1024 * 1024)
            val parsedTokenizer = HuggingFaceBpeTokenizer(tokenizerFile.readText())
            template = json("tokenizer_config.json")?.get("chat_template")?.let { (it as? JsonPrimitive)?.contentOrNull }
            // Templates are explicit supported profiles. Do not pretend arbitrary Jinja is supported.
            require(template == null ||
                (parsedTokenizer.has("<|im_start|>") && template!!.contains("<|im_start|>")) ||
                (parsedTokenizer.has("<|start_header_id|>") && template!!.contains("<|start_header_id|>"))) {
                "This ONNX chat template is not supported yet"
            }
            val generation = json("generation_config.json")
            eos = (generation?.get("eos_token_id") ?: modelConfig["eos_token_id"]).let { value ->
                when (value) {
                    is JsonArray -> value.map { it.jsonPrimitive.int }.toSet()
                    is JsonPrimitive -> setOfNotNull(value.intOrNull)
                    else -> emptySet()
                }
            } + listOfNotNull(parsedTokenizer.tokenId("<|im_end|>"), parsedTokenizer.tokenId("<|eot_id|>"))
            require(eos.isNotEmpty()) { "ONNX bundle is missing an EOS token" }
            val modelLimit = modelConfig["max_position_embeddings"]?.jsonPrimitive?.intOrNull ?: config.contextTokens
            configuration = config.copy(contextTokens = minOf(config.contextTokens, modelLimit))
            val requested = if (config.provider == OnnxExecutionProvider.AUTO) OnnxExecutionProvider.CPU else config.provider
            var chosen = requested
            var fallback: String? = null
            var loaded = try {
                openSession(graph, config, chosen)
            } catch (failure: Exception) {
                if (chosen == OnnxExecutionProvider.CPU) throw failure
                fallback = "$chosen unavailable (${(failure as? OrtException)?.code ?: failure.javaClass.simpleName}: ${failure.message?.take(240)})"
                chosen = OnnxExecutionProvider.CPU
                openSession(graph, config, chosen)
            }
            var loadedOpen = true
            try {
                require("input_ids" in loaded.inputNames && "logits" in loaded.outputNames) { "Expected a causal language model with input_ids and logits" }
                require(loaded.inputInfo.keys.all { it in setOf("input_ids", "attention_mask", "position_ids", "use_cache_branch") || it.startsWith("past_key_values.") }) {
                    "Unsupported ONNX decoder inputs: ${loaded.inputNames}"
                }
                loaded.inputInfo.filterKeys { it.startsWith("past_key_values.") }.forEach { (name, info) ->
                    val tensor = info.info as? TensorInfo ?: error("Invalid KV cache $name")
                    require(tensor.type == OnnxJavaType.FLOAT && tensor.shape.size == 4) { "Only rank-4 float32 KV caches are supported; choose q4/int8/fp32" }
                    require(presentName(name) in loaded.outputNames) { "Missing KV cache output for $name" }
                }
                if (chosen == OnnxExecutionProvider.COREML && loaded.inputNames.any { it.startsWith("past_key_values.") }) {
                    loaded.close()
                    loadedOpen = false
                    loaded = openSession(graph, config, OnnxExecutionProvider.CPU)
                    loadedOpen = true
                    chosen = OnnxExecutionProvider.CPU
                    fallback = "COREML does not support empty KV inputs"
                }
                session = loaded
                tokenizer = parsedTokenizer
                contextUsed = 0
                runtimeDescription = "ONNX Runtime ${environment.version}; $chosen; ${config.threads} threads" +
                    (if (chosen == OnnxExecutionProvider.WEBGPU) providerDeviceDescription?.let { "; $it" }.orEmpty() else "") +
                    (if (config.portableKernels) "; KleidiAI disabled" else "") +
                    (if (config.basicOptimizations) "; basic graph optimization" else "") +
                    (if (config.lowMemory) "; low-memory allocation" else "") +
                    (fallback?.let { "; $it, using CPU" } ?: "")
            } catch (failure: Throwable) {
                if (loadedOpen) loaded.close()
                throw failure
            }
        }
    }

    private fun openSession(graph: File, config: OnnxRuntimeConfig, provider: OnnxExecutionProvider): OrtSession =
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(config.threads)
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(if (config.basicOptimizations) OrtSession.SessionOptions.OptLevel.BASIC_OPT else OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setMemoryPatternOptimization(false) // sequence length changes on every token
            if (config.lowMemory) {
                options.setCPUArenaAllocator(false)
                options.addConfigEntry("session.disable_prepacking", "1")
            }
            options.addConfigEntry("session.intra_op.allow_spinning", "0")
            if (config.portableKernels) options.addConfigEntry("mlas.disable_kleidiai", "1")
            when (provider) {
                OnnxExecutionProvider.XNNPACK -> {
                    options.setIntraOpNumThreads(1)
                    options.addXnnpack(mapOf("intra_op_num_threads" to config.threads.toString()))
                }
                OnnxExecutionProvider.NNAPI -> options.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))
                OnnxExecutionProvider.COREML -> options.addCoreML(mapOf(
                    "ModelFormat" to "MLProgram", "MLComputeUnits" to "CPUAndGPU", "RequireStaticInputShapes" to "0",
                ))
                OnnxExecutionProvider.QNN_GPU, OnnxExecutionProvider.QNN_HTP -> options.addQnn(mapOf(
                    "backend_type" to if (provider == OnnxExecutionProvider.QNN_GPU) "gpu" else "htp",
                ))
                OnnxExecutionProvider.WEBGPU -> {
                    val library = config.providerLibraryDirectory?.let { File(it, System.mapLibraryName("onnxruntime_providers_webgpu")) }
                    if (library?.isFile == true) {
                        synchronized(registeredWebGpuLibraries) {
                            if (library.canonicalPath !in registeredWebGpuLibraries) {
                                environment.registerExecutionProviderLibrary("caraml-webgpu", library.canonicalPath)
                                registeredWebGpuLibraries += library.canonicalPath
                            }
                        }
                        val devices = environment.epDevices.filter { it.epName.contains("webgpu", ignoreCase = true) }
                        require(devices.isNotEmpty()) { "No compatible WebGPU device" }
                        val selected = devices.first()
                        providerDeviceDescription = "${selected.device.type} ${selected.device.vendor} ${selected.device.metadata["name"].orEmpty()}"
                        val providerOptions = buildMap {
                            if (selected.device.metadata["discovery"] == "Dawn Vulkan hardware adapter") put("dawnBackendType", "Vulkan")
                            if (config.profilePath != null) put("validationMode", "full")
                        }
                        options.addExecutionProvider(listOf(selected), providerOptions)
                    } else options.addWebGPU(emptyMap())
                }
                else -> Unit
            }
            config.profilePath?.let(options::enableProfiling)
            environment.createSession(graph.path, options)
        }

    override fun tokenize(messages: List<OnnxChatMessage>): IntArray = checkNotNull(tokenizer).let { it.encode(formatOnnxPrompt(messages, it, template, modelConfig["model_type"]?.jsonPrimitive?.content)) }

    override fun generate(messages: List<OnnxChatMessage>, maxNewTokens: Int): Flow<OnnxToken> = flow {
        mutex.withLock {
            val active = checkNotNull(session) { "Load an ONNX model first" }
            val codec = checkNotNull(tokenizer)
            require(maxNewTokens in 1..4096)
            val prompt = tokenize(messages)
            require(prompt.isNotEmpty() && prompt.size < configuration.contextTokens) { "Conversation exceeds the ONNX context limit" }
            val limit = minOf(maxNewTokens, configuration.contextTokens - prompt.size)
            val kvInputs = active.inputInfo.filterKeys { it.startsWith("past_key_values.") }
            val generated = ArrayList<Int>()
            var previous: OrtSession.Result? = null
            var text = ""
            var promptOffset = if (kvInputs.isEmpty()) prompt.size else minOf(128, prompt.size)
            var inputIds = prompt.copyOfRange(0, promptOffset)
            var pastLength = 0
            cancelled = false
            stopReason = OnnxStopReason.NONE
            contextUsed = prompt.size
            val run = OrtSession.RunOptions()
            runOptions = run
            try {
                while (generated.size < limit) {
                    currentCoroutineContext().ensureActive()
                    if (cancelled) { stopReason = OnnxStopReason.CANCELLED; return@withLock }
                    val owned = ArrayList<OnnxTensor>()
                    fun own(tensor: OnnxTensor) = tensor.also(owned::add)
                    val inputs = linkedMapOf<String, OnnxTensor>()
                    val totalLength = pastLength + inputIds.size
                    fun longs(values: LongArray, shape: LongArray) = own(OnnxTensor.createTensor(environment, LongBuffer.wrap(values), shape))
                    inputs["input_ids"] = longs(inputIds.map(Int::toLong).toLongArray(), longArrayOf(1, inputIds.size.toLong()))
                    if ("attention_mask" in active.inputNames) inputs["attention_mask"] = longs(LongArray(totalLength) { 1 }, longArrayOf(1, totalLength.toLong()))
                    if ("position_ids" in active.inputNames) inputs["position_ids"] = longs(LongArray(inputIds.size) { (pastLength + it).toLong() }, longArrayOf(1, inputIds.size.toLong()))
                    if ("use_cache_branch" in active.inputNames) inputs["use_cache_branch"] = own(OnnxTensor.createTensor(environment, booleanArrayOf(previous != null)))
                    for ((name, info) in kvInputs) {
                        inputs[name] = previous?.get(presentName(name))?.orElse(null) as? OnnxTensor ?: run {
                            val shape = (info.info as TensorInfo).shape.copyOf()
                            shape[0] = 1
                            shape[1] = shape[1].takeIf { it > 0 } ?: (modelConfig["num_key_value_heads"] ?: modelConfig["num_attention_heads"])!!.jsonPrimitive.long
                            shape[2] = 0
                            shape[3] = shape[3].takeIf { it > 0 } ?: (modelConfig["head_dim"]?.jsonPrimitive?.longOrNull
                                ?: (modelConfig.getValue("hidden_size").jsonPrimitive.long / modelConfig.getValue("num_attention_heads").jsonPrimitive.long))
                            own(OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), shape))
                        }
                    }
                    val output = try { active.run(inputs, run) } finally { owned.forEach(OnnxTensor::close) }
                    previous?.close()
                    previous = output
                    if (promptOffset < prompt.size && kvInputs.isNotEmpty()) {
                        val end = minOf(promptOffset + 128, prompt.size)
                        inputIds = prompt.copyOfRange(promptOffset, end)
                        promptOffset = end
                        pastLength = totalLength
                        continue
                    }
                    val logits = output.get("logits").get() as OnnxTensor
                    require(logits.info.type == OnnxJavaType.FLOAT) { "Only float32 logits are supported" }
                    val scores = logits.floatBuffer
                    val vocabSize = logits.info.shape.last().toInt()
                    require(vocabSize > 0 && scores.remaining() >= vocabSize)
                    val offset = scores.limit() - vocabSize
                    var best = 0
                    var bestScore = Float.NEGATIVE_INFINITY
                    repeat(vocabSize) { index ->
                        val score = scores.get(offset + index)
                        if (score > bestScore) { bestScore = score; best = index }
                    }
                    check(bestScore.isFinite()) { "ONNX model produced non-finite logits" }
                    if (configuration.temperature > 0f) {
                        val weights = DoubleArray(vocabSize) { index ->
                            kotlin.math.exp(((scores.get(offset + index) - bestScore) / configuration.temperature).toDouble())
                        }
                        var threshold = kotlin.random.Random.nextDouble() * weights.sum()
                        for (index in weights.indices) {
                            threshold -= weights[index]
                            if (threshold <= 0) { best = index; break }
                        }
                    }
                    if (best in eos) { stopReason = OnnxStopReason.EOS; return@withLock }
                    generated += best
                    contextUsed = prompt.size + generated.size
                    val decoded = codec.decode(generated)
                    check(decoded.startsWith(text)) { "Non-monotonic tokenizer output" }
                    emit(OnnxToken(best, decoded.substring(text.length)))
                    text = decoded
                    if (kvInputs.isEmpty()) {
                        inputIds = prompt + generated.toIntArray()
                        pastLength = 0
                    } else {
                        pastLength = totalLength
                        inputIds = intArrayOf(best)
                    }
                }
                stopReason = if (limit < maxNewTokens) OnnxStopReason.CONTEXT_LIMIT else OnnxStopReason.TOKEN_LIMIT
            } catch (failure: CancellationException) {
                stopReason = OnnxStopReason.CANCELLED
                throw failure
            } catch (failure: OrtException) {
                stopReason = if (cancelled) OnnxStopReason.CANCELLED else OnnxStopReason.ERROR
                if (!cancelled) throw failure
            } catch (failure: Exception) {
                stopReason = OnnxStopReason.ERROR
                throw failure
            } finally {
                runOptions = null
                run.close()
                previous?.close()
            }
        }
    }.flowOn(Dispatchers.Default)

    private fun presentName(input: String) = input.replace("past_key_values.", "present.")

    override fun cancel() {
        cancelled = true
        runCatching { runOptions?.setTerminate(true) }
    }

    override suspend fun unload() {
        cancel()
        withContext(Dispatchers.IO) { mutex.withLock { closeSession() } }
    }

    private fun closeSession() {
        if (configuration.profilePath != null) session?.let { runCatching { it.endProfiling() } }
        session?.close()
        session = null
        tokenizer = null
        contextUsed = 0
        runtimeDescription = "ONNX Runtime (unloaded)"
    }
}

private val registeredWebGpuLibraries = mutableSetOf<String>()
