package com.debanshu777.caraml.features.chat.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReasoningModelClassifierTest {

    @Test
    fun deepseekR1_variants_areReasoning() {
        assertTrue(ReasoningModelClassifier.isReasoningModel("DeepSeek-R1-Distill-Qwen-7B"))
        assertTrue(ReasoningModelClassifier.isReasoningModel("deepseek-r1"))
        assertTrue(ReasoningModelClassifier.isReasoningModel("deepseekr1-quant"))
    }

    @Test
    fun qwenThinking_variants_areReasoning() {
        assertTrue(ReasoningModelClassifier.isReasoningModel("Qwen3-30B-A3B-Thinking-2507"))
        assertTrue(ReasoningModelClassifier.isReasoningModel("qwen2-thinking-preview"))
    }

    @Test
    fun qwq_isReasoning() {
        assertTrue(ReasoningModelClassifier.isReasoningModel("QwQ-32B-Preview"))
        assertTrue(ReasoningModelClassifier.isReasoningModel("qwq"))
    }

    @Test
    fun o1Family_isReasoning() {
        assertTrue(ReasoningModelClassifier.isReasoningModel("o1-mini"))
        assertTrue(ReasoningModelClassifier.isReasoningModel("openai-o1-preview"))
    }

    @Test
    fun gptOssThinking_isReasoning() {
        assertTrue(ReasoningModelClassifier.isReasoningModel("gpt-oss-thinking-20b"))
        assertTrue(ReasoningModelClassifier.isReasoningModel("gptoss-thinking"))
    }

    @Test
    fun nonReasoningModels_returnFalse() {
        assertFalse(ReasoningModelClassifier.isReasoningModel("Llama-3.2-1B-Instruct"))
        assertFalse(ReasoningModelClassifier.isReasoningModel("Mistral-7B-v0.3"))
        assertFalse(ReasoningModelClassifier.isReasoningModel("Qwen2.5-7B-Instruct"))
        assertFalse(ReasoningModelClassifier.isReasoningModel("Phi-3-mini"))
        // o1 must be a token boundary, not embedded in unrelated names
        assertFalse(ReasoningModelClassifier.isReasoningModel("aio1ndefined"))
    }

    @Test
    fun blankInput_isNotReasoning() {
        assertFalse(ReasoningModelClassifier.isReasoningModel(""))
        assertFalse(ReasoningModelClassifier.isReasoningModel("   "))
    }
}
