package com.debanshu777.caraml.core.data.inference

import kotlin.test.*

class OnnxReasoningParserTest {
    @Test fun markersSplitAtEveryCharacterPreserveReasoningAndAnswer() {
        val parser = OnnxReasoningParser()
        val chunks = "<think>Reason carefully</think>The answer".map { parser.push(it.toString()) } + parser.finish()
        assertEquals("Reason carefully", chunks.joinToString("") { it.reasoningDelta })
        assertEquals("The answer", chunks.joinToString("") { it.contentDelta })
    }
    @Test fun ordinaryTextAndInterruptedReasoningArePreserved() {
        val parser = OnnxReasoningParser()
        assertEquals("Text with <think> in it", parser.push("Text with <think> in it").contentDelta)
        val interrupted = OnnxReasoningParser()
        val chunks = listOf(interrupted.push("<think>Working<"), interrupted.finish())
        assertEquals("Working<", chunks.joinToString("") { it.reasoningDelta })
    }
}
