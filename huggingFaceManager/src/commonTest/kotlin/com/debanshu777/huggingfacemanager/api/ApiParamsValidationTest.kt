package com.debanshu777.huggingfacemanager.api

import kotlin.test.Test
import kotlin.test.assertFailsWith

class ApiParamsValidationTest {
    @Test
    fun rejectsOversizedOrControlCharacterSearchQueries() {
        assertFailsWith<IllegalArgumentException> {
            SearchModelsParams(query = "a".repeat(257))
        }
        assertFailsWith<IllegalArgumentException> {
            SearchModelsParams(query = "model\u0000name")
        }
    }

    @Test
    fun rejectsUnboundedOrMalformedListFilters() {
        assertFailsWith<IllegalArgumentException> {
            ListModelsParams(library = List(17) { "gguf" })
        }
        assertFailsWith<IllegalArgumentException> {
            ListModelsParams(apps = listOf("llama.cpp&sort=downloads"))
        }
    }
}
