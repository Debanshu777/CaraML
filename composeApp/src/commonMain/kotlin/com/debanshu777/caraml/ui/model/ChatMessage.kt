package com.debanshu777.caraml.ui.model

import kotlin.random.Random

enum class MessageRole {
    User,
    Assistant
}

data class ChatMessage(
    val id: String = generateId(),
    val role: MessageRole,
    val text: String
)

private fun generateId(): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return (1..16)
        .map { chars[Random.nextInt(chars.length)] }
        .joinToString("")
}
