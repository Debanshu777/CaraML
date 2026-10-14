package com.debanshu777.caraml.features.modelhub.presentation.search

import com.debanshu777.caraml.sdk.discovery.huggingface.model.ModelSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelOrderingTest {
    @Test
    fun personalizedOrderingHasNoServerSerialization() {
        assertNull(ModelOrdering.Personalized.serverSortOrNull())
        assertEquals(
            ModelSort.DOWNLOADS,
            ModelOrdering.Server(ModelSort.DOWNLOADS).serverSortOrNull(),
        )
    }
}
