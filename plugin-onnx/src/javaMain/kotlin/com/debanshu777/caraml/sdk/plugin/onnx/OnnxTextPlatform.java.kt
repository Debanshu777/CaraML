package com.debanshu777.caraml.sdk.plugin.onnx

import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.regex.Pattern

internal actual fun normalizeOnnxText(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
internal actual fun onnxUnicodeRegex(expression: String): OnnxTextPattern {
    val pattern = try { Pattern.compile(expression, Pattern.UNICODE_CHARACTER_CLASS).toRegex() }
        catch (_: IllegalArgumentException) { Regex(expression) }
    return OnnxTextPattern { text -> pattern.findAll(text).map { OnnxTextMatch(it.range, it.value) }.toList() }
}
internal actual fun onnxPromptDate(): String = LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US))
