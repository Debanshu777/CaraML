@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.debanshu777.caraml.sdk.plugin.onnx

import com.debanshu777.caraml.sdk.plugin.onnx.bridge.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.native.ref.createCleaner

actual fun createOnnxTextRuntime(): OnnxTextRuntime = AppleOnnxTextRuntime()

private class AppleHandle {
    val pointer = checkNotNull(caraml_onnx_create()) { "Cannot initialize ONNX Runtime" }
    private val cleanup = createCleaner(pointer) { caraml_onnx_destroy(it) }
}

private class AppleOnnxTextRuntime : OnnxTextRuntime {
    private val mutex = Mutex()
    private var native: AppleHandle? = null
    private val cancelled = MutableStateFlow(false)
    private val usage = MutableStateFlow(0)
    private val limit = MutableStateFlow(0)
    private val stopped = MutableStateFlow(OnnxStopReason.NONE)
    private val description = MutableStateFlow("ONNX Runtime (unloaded)")
    private var tokenizer: HuggingFaceBpeTokenizer? = null
    private var template: String? = null
    private var modelType: String? = null
    private var eos = emptySet<Int>()
    private var config = OnnxRuntimeConfig()
    override val supported = true
    override val engineVersion get() = "onnxruntime-${caraml_onnx_version()?.toKString()}-caraml-v1"
    override val contextUsed get() = usage.value
    override val contextLimit get() = limit.value
    override val stopReason get() = stopped.value
    override val runtimeDescription get() = description.value

    override suspend fun load(modelPath: String, config: OnnxRuntimeConfig) = withContext(Dispatchers.Default) {
        mutex.withLock {
            closeSession()
            val graph = onnxCanonicalPath(modelPath)
            require(graph.endsWith(".onnx") && onnxIsFile(graph)) { "Select an ONNX graph bundle" }
            validateOnnxGraph(graph)
            val parent = graph.substringBeforeLast('/')
            val root = listOf(parent, parent.substringBeforeLast('/')).firstOrNull {
                onnxIsFile("$it/config.json") && onnxIsFile("$it/tokenizer.json")
            } ?: error("ONNX bundle is missing config.json or tokenizer.json")
            fun json(name: String) = "$root/$name".takeIf(::onnxIsFile)?.let {
                Json.parseToJsonElement(readOnnxMetadata(it)).jsonObject
            }
            val model = requireNotNull(json("config.json"))
            val codec = HuggingFaceBpeTokenizer(readOnnxMetadata("$root/tokenizer.json"))
            template = (json("tokenizer_config.json")?.get("chat_template") as? JsonPrimitive)?.contentOrNull
            require(template == null || (codec.has("<|im_start|>") && template!!.contains("<|im_start|>")) ||
                (codec.has("<|start_header_id|>") && template!!.contains("<|start_header_id|>"))) { "Unsupported ONNX chat template" }
            eos = (json("generation_config.json")?.get("eos_token_id") ?: model["eos_token_id"]).let { value ->
                when (value) {
                    is JsonArray -> value.map { it.jsonPrimitive.int }.toSet()
                    is JsonPrimitive -> setOfNotNull(value.intOrNull)
                    else -> emptySet()
                }
            } + listOfNotNull(codec.tokenId("<|im_end|>"), codec.tokenId("<|eot_id|>"))
            require(eos.isNotEmpty()) { "Missing EOS token" }
            val handle = native ?: AppleHandle().also { native = it }
            val coreml = config.provider in setOf(OnnxExecutionProvider.AUTO, OnnxExecutionProvider.COREML)
            val heads = (model["num_key_value_heads"] ?: model["num_attention_heads"])?.jsonPrimitive?.intOrNull ?: 0
            val dim = model["head_dim"]?.jsonPrimitive?.intOrNull ?: run {
                val hidden = model["hidden_size"]?.jsonPrimitive?.intOrNull ?: 0
                val count = model["num_attention_heads"]?.jsonPrimitive?.intOrNull ?: 1
                hidden / count.coerceAtLeast(1)
            }
            check(caraml_onnx_load(handle.pointer, graph, config.threads, if (coreml) 1 else 0, heads, dim, if (config.lowMemory) 1 else 0) == 1) {
                caraml_onnx_error(handle.pointer)?.toKString() ?: "ONNX load failed"
            }
            this@AppleOnnxTextRuntime.config = config
            modelType = model["model_type"]?.jsonPrimitive?.content
            tokenizer = codec
            limit.value = minOf(config.contextTokens, model["max_position_embeddings"]?.jsonPrimitive?.intOrNull ?: config.contextTokens)
            description.value = caraml_onnx_description(handle.pointer)?.toKString().orEmpty() +
                if (config.provider !in setOf(OnnxExecutionProvider.AUTO, OnnxExecutionProvider.COREML, OnnxExecutionProvider.CPU))
                    "; ${config.provider} is unavailable in this Apple build, using CPU" else ""
            usage.value = 0
        }
    }

    override fun tokenize(messages: List<OnnxChatMessage>): IntArray {
        val codec = checkNotNull(tokenizer) { "Load an ONNX model first" }
        return codec.encode(formatOnnxPrompt(messages, codec, template, modelType))
    }

    override fun generate(messages: List<OnnxChatMessage>, maxNewTokens: Int): Flow<OnnxToken> = flow {
        mutex.withLock {
            val handle = checkNotNull(native).pointer
            val codec = checkNotNull(tokenizer)
            require(maxNewTokens in 1..4096)
            val prompt = tokenize(messages)
            require(prompt.isNotEmpty() && prompt.size < contextLimit) { "Conversation exceeds the ONNX context limit" }
            val maximum = minOf(maxNewTokens, contextLimit - prompt.size)
            val cache = caraml_onnx_has_cache(handle) == 1
            val generated = ArrayList<Int>()
            var text = ""
            var offset = if (cache) minOf(128, prompt.size) else prompt.size
            var input = prompt.copyOfRange(0, offset)
            var past = 0
            cancelled.value = false
            stopped.value = OnnxStopReason.NONE
            usage.value = prompt.size
            caraml_onnx_begin(handle)
            try {
                while (generated.size < maximum) {
                    currentCoroutineContext().ensureActive()
                    if (cancelled.value) { stopped.value = OnnxStopReason.CANCELLED; return@withLock }
                    val ids = LongArray(input.size) { input[it].toLong() }
                    val succeeded = ids.usePinned { caraml_onnx_step(handle, it.addressOf(0), ids.size, past) == 1 }
                    if (!succeeded && cancelled.value) { stopped.value = OnnxStopReason.CANCELLED; return@withLock }
                    check(succeeded) { caraml_onnx_error(handle)?.toKString() ?: "ONNX decode failed" }
                    val total = past + input.size
                    if (cache && offset < prompt.size) {
                        val end = minOf(offset + 128, prompt.size)
                        input = prompt.copyOfRange(offset, end); offset = end; past = total
                        continue
                    }
                    val scores = checkNotNull(caraml_onnx_scores(handle))
                    val vocabulary = caraml_onnx_vocab_size(handle)
                    require(vocabulary > 0)
                    var best = 0
                    var bestScore = Float.NEGATIVE_INFINITY
                    repeat(vocabulary) { if (scores[it] > bestScore) { best = it; bestScore = scores[it] } }
                    check(bestScore.isFinite()) { "ONNX model produced non-finite logits" }
                    if (config.temperature > 0f) {
                        val weights = DoubleArray(vocabulary) { kotlin.math.exp(((scores[it] - bestScore) / config.temperature).toDouble()) }
                        var threshold = kotlin.random.Random.nextDouble() * weights.sum()
                        for (index in weights.indices) {
                            threshold -= weights[index]
                            if (threshold <= 0) { best = index; break }
                        }
                    }
                    if (best in eos) { stopped.value = OnnxStopReason.EOS; return@withLock }
                    generated += best
                    usage.value = prompt.size + generated.size
                    val decoded = codec.decode(generated)
                    check(decoded.startsWith(text)) { "Non-monotonic tokenizer output" }
                    emit(OnnxToken(best, decoded.substring(text.length)))
                    text = decoded
                    if (cache) { input = intArrayOf(best); past = total }
                    else { input = prompt + generated.toIntArray(); past = 0 }
                }
                stopped.value = if (maximum < maxNewTokens) OnnxStopReason.CONTEXT_LIMIT else OnnxStopReason.TOKEN_LIMIT
            } catch (failure: CancellationException) {
                stopped.value = OnnxStopReason.CANCELLED
                throw failure
            } catch (failure: Exception) {
                stopped.value = OnnxStopReason.ERROR
                throw failure
            } finally { caraml_onnx_begin(handle) }
        }
    }.flowOn(Dispatchers.Default)

    override fun cancel() {
        cancelled.value = true
        native?.let { caraml_onnx_cancel(it.pointer) }
    }
    override suspend fun unload() {
        cancel()
        mutex.withLock { closeSession() }
    }
    private fun closeSession() {
        native?.let { caraml_onnx_unload(it.pointer) }
        tokenizer = null; limit.value = 0; usage.value = 0
        description.value = "ONNX Runtime (unloaded)"
    }
}
