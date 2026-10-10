package com.debanshu777.caraml.features.chat.presentation

import com.debanshu777.caraml.core.ui.components.BrandPalState
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.MessageDelivery
import com.debanshu777.caraml.features.chat.data.MessageRole
import com.debanshu777.caraml.features.chat.presentation.components.messageCharacterState
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageCharacterStateTest {
    private val message = ChatMessage(role = MessageRole.Assistant, text = "")

    @Test
    fun characterUsesOnlyReportedLifecycleAndKeepsPartialRepliesStopped() {
        assertEquals(BrandPalState.Loading, state(message, streaming = true))
        assertEquals(BrandPalState.Thinking, state(message, streaming = true, thinking = "Reasoning"))
        assertEquals(BrandPalState.Replying, state(message.copy(text = "First words"), streaming = true))
        assertEquals(BrandPalState.Loading, state(message, streaming = true, media = true))
        assertEquals(BrandPalState.Paused, state(message.copy(text = "Partial reply", delivery = MessageDelivery.Stopped)))
        assertEquals(BrandPalState.Error, state(message.copy(delivery = MessageDelivery.Error)))
        assertEquals(BrandPalState.Success, state(message.copy(delivery = MessageDelivery.Complete)))
        // Old messages with no terminal evidence must not be presented as successfully completed.
        assertEquals(BrandPalState.Idle, state(message.copy(text = "Historical output")))
    }

    private fun state(message: ChatMessage, streaming: Boolean = false, thinking: String = "", media: Boolean = false) =
        messageCharacterState(message, streaming, thinking, media)
}
