package com.debanshu777.caraml.features.modelhub.presentation.search

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchScreenLayoutRegressionTest {

    @Test
    fun searchAndDownloadsShareOneScrollablePageLayout() {
        val search = searchScreenSource()
        val searchSource = Files.readString(search)
        val downloadsSource = Files.readString(search.parent.resolve("../downloaded/DownloadsScreen.kt"))
        val sharedLayout = searchSource
            .substringAfter("internal fun ModelHubTabLayout(")
            .substringBefore("internal fun SearchTabContent(")

        assertTrue(searchSource.contains("trailingAction ="))
        assertTrue(downloadsSource.contains("ModelHubTabLayout("))
        assertFalse(searchSource.contains("ModelHubTabRow("))
        assertFalse(downloadsSource.contains("LazyColumn("))
        assertEquals(1, Regex("\\bLazyColumn\\(").findAll(sharedLayout).count())
    }

    private fun searchScreenSource(): Path {
        val workingDirectory = Path.of(System.getProperty("user.dir"))
        val sourceFromRoot = workingDirectory.resolve(SEARCH_SCREEN_FROM_ROOT)
        val sourceFromModule = workingDirectory.resolve(SEARCH_SCREEN_FROM_MODULE)
        return listOf(sourceFromRoot, sourceFromModule)
            .firstOrNull(Files::isRegularFile)
            ?: error("Could not locate SearchScreen.kt from $workingDirectory")
    }

    private companion object {
        const val SEARCH_SCREEN_FROM_ROOT =
            "app/composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/" +
                "presentation/search/SearchScreen.kt"
        const val SEARCH_SCREEN_FROM_MODULE =
            "src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/" +
                "presentation/search/SearchScreen.kt"
    }
}
