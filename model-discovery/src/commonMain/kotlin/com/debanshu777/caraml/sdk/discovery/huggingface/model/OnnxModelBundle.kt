package com.debanshu777.caraml.sdk.discovery.huggingface.model

/** Supported Hugging Face / Optimum decoder layouts, with one graph per variant.
 * Alternative quantizations are never downloaded together. File identities still come from
 * the immutable Hub tree and must be verified by the download layer. */
data class OnnxModelBundle(val graph: ModelFileTreeResponse, val files: List<ModelFileTreeResponse>) {
    val sizeBytes: Long get() = files.sumOf { it.lfs?.size ?: it.size ?: 0L }
}

object OnnxModelBundles {
    val metadataNames = setOf("config.json", "tokenizer.json", "tokenizer_config.json", "generation_config.json", "special_tokens_map.json")
    private val graphNames = setOf("model.onnx", "model_q4.onnx", "model_int8.onnx", "model_uint8.onnx", "model_quantized.onnx", "decoder_model_merged.onnx", "decoder_model_merged_quantized.onnx")

    fun isSupportedGraph(path: String): Boolean = path.substringAfterLast('/') in graphNames &&
        (path.substringBeforeLast('/', "") in setOf("", "onnx"))

    fun isBundlePath(path: String): Boolean = path in metadataNames || isSupportedGraph(path) ||
        graphNames.any { graph -> path == "$graph.data" || path == "onnx/$graph.data" ||
            path.matches(Regex("(?:onnx/)?${Regex.escape(graph)}_data(?:_[0-9]+)?")) }

    fun resolve(tree: List<ModelFileTreeResponse>): List<OnnxModelBundle> {
        if (tree.size > 4096 || tree.map { it.path }.distinct().size != tree.size) return emptyList()
        val metadata = tree.filter { it.path in metadataNames }
        if (!metadata.map { it.path }.containsAll(listOf("config.json", "tokenizer.json"))) return emptyList()
        return tree.filter { isSupportedGraph(it.path.orEmpty()) }.mapNotNull { graph ->
            val path = graph.path ?: return@mapNotNull null
            val external = tree.filter { candidate ->
                val other = candidate.path.orEmpty()
                other == "$path.data" || other.matches(Regex("${Regex.escape(path)}_data(?:_[0-9]+)?"))
            }
            val files = listOf(graph) + external.sortedBy { it.path } + metadata.sortedBy { it.path }
            if (files.any { (it.lfs?.size ?: it.size ?: 0L) <= 0 }) null else OnnxModelBundle(graph, files)
        }
    }

    fun isComplete(paths: List<String>): Boolean {
        if (paths.size != paths.distinct().size || !paths.containsAll(listOf("config.json", "tokenizer.json"))) return false
        val graph = paths.singleOrNull(::isSupportedGraph) ?: return false
        return paths.all { it == graph || it in metadataNames || it == "$graph.data" ||
            it.matches(Regex("${Regex.escape(graph)}_data(?:_[0-9]+)?")) }
    }
}
