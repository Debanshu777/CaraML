package com.debanshu777.caraml.features.modelhub.presentation.components

import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ListModelsResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelRowMappersTest {
    @Test
    fun absentDiscoveryModelDoesNotRenderAndSparseMetadataStaysAbsent() {
        assertNull(discoveryModelRow(null))
        val row = discoveryModelRow(ListModelsResponse.Model(id = "owner/model"))!!
        assertTrue(row.metrics.isEmpty())
        assertEquals("owner", row.avatar?.author)
        assertNull(row.avatar?.url)
    }

    @Test
    fun discoveryKeepsAvatarAndZeroMetricsWithoutInventingMissingValues() {
        val row = discoveryModelRow(ListModelsResponse.Model(
            id = "owner/model", author = "Publisher", downloads = 0,
            authorData = ListModelsResponse.Model.AuthorData(avatarUrl = "https://huggingface.co/avatar.png"),
        ))!!
        assertEquals("Publisher", row.avatar?.author)
        assertEquals("https://huggingface.co/avatar.png", row.avatar?.url)
        assertEquals(listOf("0 downloads"), row.metrics.map { it.description })
    }

    @Test
    fun unsupportedLocalModelRemainsSelectableWithoutBecomingOpenable() {
        val model = localModel("weights.bin", LocalModelEntity.STATUS_READY)
        val normal = downloadedModelRow(model)
        assertFalse(normal.canOpen)
        assertEquals(listOf("Unsupported"), normal.statuses.map { it.label })
        val selected = downloadedModelRow(model, selectionMode = true, isSelected = true)
        assertTrue(selected.selectionMode)
        assertTrue(selected.isSelected)
        assertFalse(selected.canOpen)
        assertEquals("Selected owner/model, tap to deselect", selected.description)
    }

    @Test
    fun selectionSuppressesRepairWhileRetainingPartialReadiness() {
        val model = localModel("weights.gguf", LocalModelEntity.STATUS_PARTIAL)
        val normal = downloadedModelRow(model)
        assertTrue(normal.canRepair)
        assertTrue(normal.canOpen)
        assertEquals(listOf("Needs setup"), normal.statuses.map { it.label })
        val selecting = downloadedModelRow(model, selectionMode = true)
        assertFalse(selecting.canRepair)
        assertEquals(normal.statuses, selecting.statuses)
        assertEquals("Not selected owner/model, tap to select", selecting.description)
    }

    private fun localModel(filename: String, status: String) = LocalModelEntity(
        modelId = "owner/model", filename = filename, localPath = "/models/$filename",
        sizeBytes = null, downloadedAt = 0, author = null, libraryName = null,
        pipelineTag = "audio-classification", componentStatus = status,
    )
}
