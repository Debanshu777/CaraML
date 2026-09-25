package com.debanshu777.caraml.features.modelhub.presentation.search

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchScreenLayoutRegressionTest {

    @Test
    fun discoverAndLibraryDelegateToTheSharedOneScrollWorkbenchLayout() {
        val source = Files.readString(searchScreenSource())
        val discoverTab = source
            .substringAfter("internal fun SearchTabContent(")
            .substringBefore("internal fun ModelHubResultSummary(")
        val libraryTab = source
            .substringAfter("internal fun DownloadedTabContent(")
            .substringBefore("private fun LibraryReadinessToolbar(")
        val sharedLayout = source
            .substringAfter("internal fun ModelHubTabLayout(")
            .substringBefore("internal fun SearchTabContent(")

        assertFalse(
            discoverTab.contains("RecommendationProfileSection("),
            "The full profile editor must not consume fixed height above the results list.",
        )
        assertTrue(discoverTab.contains("ModelHubTabLayout("))
        assertTrue(libraryTab.contains("ModelHubTabLayout("))
        assertFalse(discoverTab.contains("LazyColumn("))
        assertFalse(libraryTab.contains("LazyColumn("))
        assertEquals(1, Regex("\\bLazyColumn\\(").findAll(sharedLayout).count())
        assertFalse(sharedLayout.contains("verticalScroll("))
    }

    @Test
    fun tabsStayAboveTheScrollableTabPanels() {
        val source = Files.readString(searchScreenSource())
        val screen = source
            .substringAfter("fun SearchScreen(")
            .substringBefore("internal fun SearchTabContent(")
        val layout = source
            .substringAfter("internal fun ModelHubScreenLayout(")
            .substringBefore("private fun ModelHubTabRow(")

        assertTrue(screen.contains("ModelHubScreenLayout("))
        assertTrue(layout.indexOf("ModelHubTabRow(") < layout.indexOf("discoverContent()"))
        assertFalse(
            screen.substringBefore("ModelHubTabRow(").contains("ModelHubOverview("),
            "The large overview cards must not push the primary tabs below the fold.",
        )
        assertTrue(screen.contains("listOf(\"Discover\", \"Library\")"))
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
