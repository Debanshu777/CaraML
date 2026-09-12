package com.debanshu777.caraml.features.modelhub.presentation.search

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchScreenLayoutRegressionTest {

    @Test
    fun searchTabUsesOneVerticalScrollOwnerForControlsAndResults() {
        val source = Files.readString(searchScreenSource())
        val searchTab = source
            .substringAfter("private fun SearchTabContent(")
            .substringBefore("private fun ModelKindFilterRow(")

        assertFalse(
            searchTab.contains("RecommendationProfileSection("),
            "The full profile editor must not consume fixed height above the results list.",
        )
        assertEquals(
            1,
            Regex("\\bLazyColumn\\(").findAll(searchTab).count(),
            "Search controls and results must share one LazyColumn instead of competing scroll regions.",
        )
        assertTrue(
            searchTab.indexOf("LazyColumn(") < searchTab.indexOf("ModelHubOverview("),
            "Storage and device context must be list content so it can scroll away from results.",
        )
        assertFalse(
            searchTab.contains(
                "modifier = Modifier\n                .fillMaxWidth()\n                .weight(1f)",
            ),
            "A weighted inner results viewport recreates the broken nested-scroll experience.",
        )
    }

    @Test
    fun tabsStayAboveTheScrollableTabPanels() {
        val source = Files.readString(searchScreenSource())
        val screen = source
            .substringAfter("fun SearchScreen(")
            .substringBefore("private fun SearchTabContent(")

        assertTrue(screen.indexOf("ModelHubTabRow(") < screen.indexOf("SearchTabContent("))
        assertFalse(
            screen.substringBefore("ModelHubTabRow(").contains("ModelHubOverview("),
            "The large overview cards must not push the primary tabs below the fold.",
        )
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
            "composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/" +
                "presentation/search/SearchScreen.kt"
        const val SEARCH_SCREEN_FROM_MODULE =
            "src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/" +
                "presentation/search/SearchScreen.kt"
    }
}
