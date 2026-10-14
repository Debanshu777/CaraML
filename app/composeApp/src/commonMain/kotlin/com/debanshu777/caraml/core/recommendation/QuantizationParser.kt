package com.debanshu777.caraml.core.recommendation

sealed interface QuantizationEvidence {
    val quantizations: Set<String>

    data class Known(val quantization: String) : QuantizationEvidence {
        override val quantizations: Set<String> = setOf(quantization)
    }

    @ConsistentCopyVisibility
    data class Mixed private constructor(override val quantizations: Set<String>) : QuantizationEvidence {
        constructor(quantizations: Collection<String>) : this(quantizations.toSet())
    }

    data object Unknown : QuantizationEvidence {
        override val quantizations: Set<String> = emptySet()
    }
}

object QuantizationParser {
    private val token = Regex(
        pattern = """(?:IQ|Q)\d(?:_[A-Za-z0-9]+)*|FP(?:16|32)|BF16|F16|F32|INT(?:4|8)""",
        option = RegexOption.IGNORE_CASE,
    )

    fun parseFilename(filename: String): QuantizationEvidence {
        if (filename.isEmpty() || filename.length > DescriptorLimits.MAX_RELATIVE_PATH_LENGTH) {
            return QuantizationEvidence.Unknown
        }
        val stem = filename.substringAfterLast('/').substringBeforeLast('.')
        val matches = token.findAll(stem)
            .filter { match ->
                val before = stem.getOrNull(match.range.first - 1)
                val after = stem.getOrNull(match.range.last + 1)
                before?.isLetterOrDigit() != true && after?.isLetterOrDigit() != true
            }
            .map { it.value.uppercase() }
            .toSet()
        return when (matches.size) {
            0 -> QuantizationEvidence.Unknown
            1 -> QuantizationEvidence.Known(matches.single())
            else -> QuantizationEvidence.Mixed(matches)
        }
    }
}
