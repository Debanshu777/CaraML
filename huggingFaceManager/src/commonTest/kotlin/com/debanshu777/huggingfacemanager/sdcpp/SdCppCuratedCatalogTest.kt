package com.debanshu777.huggingfacemanager.sdcpp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SdCppCuratedCatalogTest {

    @Test
    fun bundledJsonParses() {
        val catalog = loadSdCppCuratedCatalog()
        assertTrue(catalog.version >= 1)
        assertTrue(catalog.image.isNotEmpty(), "image list must not be empty")
        assertTrue(catalog.video.isNotEmpty(), "video list must not be empty")
    }

    @Test
    fun allRepoIdsMatchAllowedPattern() {
        val catalog = loadSdCppCuratedCatalog()
        val all = catalog.image + catalog.video
        all.forEach { entry ->
            assertTrue(
                SD_CPP_CURATED_REPO_ID_REGEX.matches(entry.repoId),
                "Invalid repoId: ${entry.repoId}"
            )
        }
    }

    @Test
    fun mapsToListModelsResponseWithExpectedCounts() {
        val catalog = loadSdCppCuratedCatalog()
        val imageList = catalog.image.toListModelsResponse()
        val videoList = catalog.video.toVideoListModelsResponse()
        assertEquals(catalog.image.size, imageList.models?.size)
        assertEquals(catalog.video.size, videoList.models?.size)
    }
}
