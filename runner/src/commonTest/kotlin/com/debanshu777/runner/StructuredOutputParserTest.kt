package com.debanshu777.runner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StructuredOutputParserTest {

    private fun feed(parser: StructuredOutputParser, full: String, chunkSize: Int = 4):
        StructuredOutputParser.Snapshot {
        var i = 0
        var snapshot = StructuredOutputParser.Snapshot("", "")
        while (i < full.length) {
            val end = minOf(i + chunkSize, full.length)
            snapshot = parser.accept(full.substring(i, end))
            i = end
        }
        return parser.finish()
    }

    @Test
    fun outputOnly_thinkingEmpty_outputCaptured() {
        val parser = StructuredOutputParser()
        val result = feed(parser, "<output>Hello **world**.</output>")
        assertEquals("", result.thinking)
        assertEquals("Hello **world**.", result.output)
    }

    @Test
    fun thinkingPlusOutput_bothCaptured_thinkVariant() {
        val parser = StructuredOutputParser()
        val result = feed(parser, "<think>I should be brief.</think><output>Hi.</output>")
        assertEquals("I should be brief.", result.thinking)
        assertEquals("Hi.", result.output)
    }

    @Test
    fun thinkingPlusOutput_bothCaptured_thinkingVariant() {
        val parser = StructuredOutputParser()
        val result = feed(parser, "<thinking>Reasoning here.</thinking><output>Final.</output>")
        assertEquals("Reasoning here.", result.thinking)
        assertEquals("Final.", result.output)
    }

    @Test
    fun midTagSplits_handledAcrossSmallChunks() {
        val parser = StructuredOutputParser()
        // chunkSize=2 forces splits inside every tag
        val result = feed(
            parser,
            "<think>abc</think><output>def</output>",
            chunkSize = 2,
        )
        assertEquals("abc", result.thinking)
        assertEquals("def", result.output)
    }

    @Test
    fun fallbackPath_noOpenerWithinProbeLimit_treatedAsOutput() {
        val parser = StructuredOutputParser()
        // Send 80 chars of plain text with no recognized opener.
        val plain = "Just a plain answer without any structure tags. ".repeat(2)
        val result = feed(parser, plain)
        assertEquals("", result.thinking)
        assertTrue(result.output.contains("Just a plain answer"))
    }

    @Test
    fun whitespaceBeforeOpener_isStripped() {
        val parser = StructuredOutputParser()
        val result = feed(parser, "   \n\n<output>Body.</output>")
        assertEquals("", result.thinking)
        assertEquals("Body.", result.output)
    }

    @Test
    fun postOutputTail_isDiscarded() {
        val parser = StructuredOutputParser()
        val result = feed(parser, "<output>Done.</output>trailing-noise")
        assertEquals("Done.", result.output)
    }

    @Test
    fun whitespaceBetweenSections_isAllowed() {
        val parser = StructuredOutputParser()
        val result = feed(parser, "<think>R</think>\n  \n<output>O</output>")
        assertEquals("R", result.thinking)
        assertEquals("O", result.output)
    }

    @Test
    fun snapshotCumulative_outputBuildsAcrossCalls() {
        val parser = StructuredOutputParser()
        parser.accept("<output>a")
        val mid = parser.accept("b")
        assertEquals("ab", mid.output)
        val end = parser.accept("c</output>")
        assertEquals("abc", end.output)
    }

    @Test
    fun emptyTokensAreNoOps() {
        val parser = StructuredOutputParser()
        parser.accept("")
        parser.accept("<output>x")
        parser.accept("")
        val s = parser.accept("</output>")
        assertEquals("x", s.output)
    }

    @Test
    fun unterminatedOutput_finishFlushesPending() {
        val parser = StructuredOutputParser()
        parser.accept("<output>partial without close")
        val end = parser.finish()
        assertEquals("partial without close", end.output)
    }

    @Test
    fun unterminatedThinking_finishFlushesPending() {
        val parser = StructuredOutputParser()
        parser.accept("<think>still reasoning")
        val end = parser.finish()
        assertEquals("still reasoning", end.thinking)
        assertEquals("", end.output)
    }

    @Test
    fun finish_dropsPartialOutputCloseTag() {
        // Stream ends mid-`</output>`; the partial fragment must NOT leak into
        // the user-visible answer.
        val parser = StructuredOutputParser()
        parser.accept("<output>complete answer</outp")
        val end = parser.finish()
        assertEquals("complete answer", end.output)
        assertEquals("", end.thinking)
    }

    @Test
    fun fallbackFlag_flipsAfterProbeLimitWithoutOpener() {
        val parser = StructuredOutputParser()
        // 130 chars of plain text — exceeds FALLBACK_PROBE_LIMIT (128).
        val plain = "z".repeat(130)
        // Feed in small chunks so the parser observes incrementally.
        var i = 0
        while (i < plain.length) {
            parser.accept(plain.substring(i, minOf(i + 4, plain.length)))
            i += 4
        }
        assertTrue(parser.isInFallback, "expected parser to be in fallback after >128 chars")
    }

    @Test
    fun thinkingOpener_thinkingTag_capturesReasoning() {
        // Explicit coverage of the `<thinking>` (not just `<think>`) opener
        // through the chunk-splitting feed helper.
        val parser = StructuredOutputParser()
        val result = feed(
            parser,
            "<thinking>chain of thought</thinking><output>final.</output>",
            chunkSize = 3,
        )
        assertEquals("chain of thought", result.thinking)
        assertEquals("final.", result.output)
    }
}
