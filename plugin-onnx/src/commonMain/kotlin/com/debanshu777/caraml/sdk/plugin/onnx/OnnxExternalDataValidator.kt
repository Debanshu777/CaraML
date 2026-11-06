package com.debanshu777.caraml.sdk.plugin.onnx


/** Streams protobuf metadata and skips tensor bytes; never reads a whole multi-GB graph into RAM.
 * Validates tensor references in initializers, nested subgraphs, functions and training graphs.
 * Only the external-data layouts owned by the Hub bundle resolver are accepted. */
internal fun validateOnnxGraph(modelPath: String) {
    val graph = onnxCanonicalPath(modelPath)
    val name = graph.substringAfterLast('/')
    val parent = graph.substringBeforeLast('/')
    val source = openOnnxReader(graph)
    try {
        var fields = 0
        fun varint(end: Long): Long {
            var value = 0L
            for (shift in 0..63 step 7) {
                require(source.position < end) { "Truncated ONNX protobuf" }
                val byte = source.readByte()
                require(shift < 63 || byte <= 1) { "Invalid ONNX varint" }
                value = value or ((byte and 127).toLong() shl shift)
                if (byte and 128 == 0) return value
            }
            error("Invalid ONNX varint")
        }
        fun message(kind: ProtoKind, end: Long, depth: Int) {
            require(depth <= 64) { "ONNX graph nesting exceeds the supported limit" }
            var key: String? = null
            var value: String? = null
            while (source.position < end) {
                require(++fields <= 2_000_000) { "ONNX metadata exceeds the supported limit" }
                val tag = varint(end)
                require(tag > 0 && tag <= Int.MAX_VALUE)
                val field = (tag ushr 3).toInt()
                when ((tag and 7).toInt()) {
                    0 -> varint(end)
                    1 -> { require(end - source.position >= 8); source.seek(source.position + 8) }
                    5 -> { require(end - source.position >= 4); source.seek(source.position + 4) }
                    2 -> {
                        val size = varint(end)
                        require(size >= 0 && size <= end - source.position) { "Invalid ONNX field length" }
                        val stop = source.position + size
                        val child = when (kind) {
                            ProtoKind.MODEL -> when (field) { 7 -> ProtoKind.GRAPH; 20 -> ProtoKind.TRAINING; 25 -> ProtoKind.FUNCTION; else -> null }
                            ProtoKind.GRAPH -> when (field) { 1 -> ProtoKind.NODE; 5 -> ProtoKind.TENSOR; 15 -> ProtoKind.SPARSE; else -> null }
                            ProtoKind.NODE -> if (field == 5) ProtoKind.ATTRIBUTE else null
                            ProtoKind.ATTRIBUTE -> when (field) { 5, 10 -> ProtoKind.TENSOR; 6, 11 -> ProtoKind.GRAPH; 22, 23 -> ProtoKind.SPARSE; else -> null }
                            ProtoKind.SPARSE -> if (field in 1..2) ProtoKind.TENSOR else null
                            ProtoKind.TENSOR -> if (field == 13) ProtoKind.EXTERNAL else null
                            ProtoKind.FUNCTION -> when (field) { 7 -> ProtoKind.NODE; 11 -> ProtoKind.ATTRIBUTE; else -> null }
                            ProtoKind.TRAINING -> if (field in 1..2) ProtoKind.GRAPH else null
                            ProtoKind.EXTERNAL -> null
                        }
                        if (kind == ProtoKind.EXTERNAL && field in 1..2) {
                            require(size <= 1024) { "ONNX external-data reference is too long" }
                            val text = ByteArray(size.toInt()).also(source::readFully).decodeToString()
                            if (field == 1) { require(key == null); key = text } else { require(value == null); value = text }
                        } else if (child != null) message(child, stop, depth + 1)
                        source.seek(stop)
                    }
                    else -> error("Unsupported ONNX protobuf wire type")
                }
            }
            if (kind == ProtoKind.EXTERNAL) require(key in setOf("location", "offset", "length", "checksum") && value != null) {
                "Unsupported ONNX external-data attribute"
            }
            if (kind == ProtoKind.EXTERNAL && key == "location") {
                val location = requireNotNull(value) { "Missing ONNX external-data location" }
                require(location == "${name}.data" || location.matches(Regex("${Regex.escape(name)}_data(?:_[0-9]+)?"))) {
                    "Unsupported ONNX external-data path: download a standard Hugging Face ONNX bundle"
                }
                val companion = "$parent/$location"
                require(onnxCanonicalPath(companion).substringBeforeLast('/') == parent && onnxIsFile(companion)) {
                    "The ONNX external weights are missing or outside the model bundle"
                }
            }
        }
        message(ProtoKind.MODEL, source.length, 0)
    } finally { source.close() }
}

private enum class ProtoKind { MODEL, GRAPH, NODE, ATTRIBUTE, TENSOR, SPARSE, FUNCTION, TRAINING, EXTERNAL }
