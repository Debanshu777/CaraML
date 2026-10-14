package com.debanshu777.runner

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LlamaRunnerValidationTest {
    @Test
    fun loadAndPreflightRejectTheSameMalformedPathsBeforeNativeEntry() {
        for (path in listOf("", " \t", "model.gguf\u0000suffix", "a".repeat(4_097), "é".repeat(2_049))) {
            assertFailsWith<IllegalArgumentException> {
                validateLoadModelArgs(path, NativeRunnerConfig())
            }
            assertIs<LlamaPreflightResult.InvalidModel>(
                runLlamaPreflight(path, NativeRunnerConfig()) { _, _ ->
                    error("Invalid path reached native entry")
                },
            )
        }
    }

    @Test
    fun pathsAtUtf8ByteLimitRemainAccepted() {
        for (path in listOf("model.gguf", "a".repeat(4_096), "é".repeat(2_048))) {
            validateLoadModelArgs(path, NativeRunnerConfig())
            var reachedNative = false
            runLlamaPreflight(path, NativeRunnerConfig()) { _, _ ->
                reachedNative = true
                longArrayOf(3, 0, 0, 0)
            }
            assertTrue(reachedNative)
        }
    }

    @Test
    fun cacheTypesMatchPinnedUpstreamCacheAllowlist() {
        val supported = setOf(0, 1, 2, 3, 6, 7, 8, 20, 30)
        for (type in -1..43) {
            val expected = type in supported
            val keyConfig = NativeRunnerConfig(typeK = type)
            val valueConfig = NativeRunnerConfig(typeV = type)
            if (expected) {
                assertTrue(isValidNativeRunnerConfig(keyConfig), "key type $type")
                assertTrue(isValidNativeRunnerConfig(valueConfig), "value type $type")
            } else {
                assertFalse(isValidNativeRunnerConfig(keyConfig), "key type $type")
                assertFalse(isValidNativeRunnerConfig(valueConfig), "value type $type")
            }
        }
    }

    @Test
    fun cpuAffinityAcceptsDecimalIndicesRangesAndListsForEitherPool() {
        for (mask in listOf("", "0", "7", "511", "4-7", "4,5,6,7", "0-3,7,510-511")) {
            assertTrue(isValidNativeRunnerConfig(NativeRunnerConfig(cpuMask = mask)), mask)
            assertTrue(isValidNativeRunnerConfig(NativeRunnerConfig(cpuMaskBatch = mask)), mask)
        }
    }

    @Test
    fun cpuAffinityRejectsMalformedAndUnboundedInputBeforeNativeEntry() {
        for (mask in listOf(
            "-1", "512", "0-512", "7-4", "4-", "-7", "4,,7", ",4", "4,",
            "4--7", "4-5-7", "0x80", "f0", " 7", "7 ", "7\u0000", "７",
            "9999999999999999999999", "0,".repeat(2048) + "0",
        )) {
            assertFalse(isValidNativeRunnerConfig(NativeRunnerConfig(cpuMask = mask)), mask)
            assertFalse(isValidNativeRunnerConfig(NativeRunnerConfig(cpuMaskBatch = mask)), mask)
        }
    }
}
