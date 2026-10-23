package com.debanshu777.caraml.sdk.plugin.onnx

internal expect fun normalizeOnnxText(text: String): String
internal data class OnnxTextMatch(val range: IntRange, val value: String)
internal fun interface OnnxTextPattern { fun findAll(text: String): List<OnnxTextMatch> }
internal expect fun onnxUnicodeRegex(expression: String): OnnxTextPattern
internal expect fun onnxPromptDate(): String
