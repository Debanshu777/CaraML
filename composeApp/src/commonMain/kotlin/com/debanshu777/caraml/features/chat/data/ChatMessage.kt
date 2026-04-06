package com.debanshu777.caraml.features.chat.data

import kotlin.random.Random

enum class MessageRole {
    User,
    Assistant,
    System
}

data class ChatMessage(
    val id: String = generateId(),
    val role: MessageRole,
    val text: String,
    val inferenceMetrics: InferenceMetrics? = null,
    val imageBytes: ByteArray? = null,
    val videoFrames: List<ByteArray>? = null,
    val metadata: Map<String, String>? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as ChatMessage
        if (id != other.id) return false
        if (role != other.role) return false
        if (text != other.text) return false
        if (inferenceMetrics != other.inferenceMetrics) return false
        if (!bytesEqual(imageBytes, other.imageBytes)) return false
        if (videoFrames?.size != other.videoFrames?.size) return false
        if (metadata != other.metadata) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + role.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + (inferenceMetrics?.hashCode() ?: 0)
        result = 31 * result + (imageBytes?.size ?: 0)
        result = 31 * result + (videoFrames?.size ?: 0)
        result = 31 * result + (metadata?.hashCode() ?: 0)
        return result
    }
}

private fun bytesEqual(a: ByteArray?, b: ByteArray?): Boolean {
    if (a === b) return true
    if (a == null || b == null) return false
    if (a.size != b.size) return false
    var i = 0
    while (i < a.size) {
        if (a[i] != b[i]) return false
        i++
    }
    return true
}

private fun generateId(): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return (1..16)
        .map { chars[Random.nextInt(chars.length)] }
        .joinToString("")
}
