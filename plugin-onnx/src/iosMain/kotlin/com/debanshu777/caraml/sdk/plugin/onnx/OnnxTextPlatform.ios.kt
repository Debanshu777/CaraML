@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.debanshu777.caraml.sdk.plugin.onnx

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.precomposedStringWithCanonicalMapping
import platform.Foundation.NSRegularExpression
import platform.Foundation.NSMakeRange
import platform.Foundation.NSTextCheckingResult
import platform.Foundation.matchesInString
import kotlinx.cinterop.useContents

internal actual fun normalizeOnnxText(text: String): String = NSString.create(string = text).precomposedStringWithCanonicalMapping
internal actual fun onnxUnicodeRegex(expression: String): OnnxTextPattern {
    val regex = requireNotNull(NSRegularExpression.regularExpressionWithPattern(expression, 0u, null)) { "Unsupported tokenizer regex" }
    return OnnxTextPattern { text ->
        regex.matchesInString(text, 0u, NSMakeRange(0u, text.length.toULong())).map { result ->
            (result as NSTextCheckingResult).range.useContents {
                val start = location.toInt()
                val end = start + length.toInt()
                OnnxTextMatch(start until end, text.substring(start, end))
            }
        }
    }
}
internal actual fun onnxPromptDate(): String = NSDateFormatter().apply {
    locale = NSLocale("en_US_POSIX")
    dateFormat = "dd MMM yyyy"
}.stringFromDate(NSDate())
