package com.debanshu777.caraml.core.data.inference

class PromptContextFullException : IllegalStateException(
    "The message does not fit in the model context. Shorten it or start a new chat."
)

private const val MAX_TEXT_PROMPT_CHARACTERS = 32_768

internal fun isPromptLengthSupported(prompt: String): Boolean =
    prompt.isNotBlank() && prompt.length <= MAX_TEXT_PROMPT_CHARACTERS
