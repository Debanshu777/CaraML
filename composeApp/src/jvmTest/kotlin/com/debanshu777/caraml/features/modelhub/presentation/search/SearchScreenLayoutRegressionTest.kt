package com.debanshu777.caraml.features.modelhub.presentation.search

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchScreenLayoutRegressionTest {

    @Test
    fun profileEditorIsScrollableModalInsteadOfFixedSearchHeader() {
        val source = Files.readString(searchScreenSource())
        val searchHeader = source
            .substringAfter("private fun SearchTabContent(")
            .substringBefore("private fun RecommendationProfileAction(")

        assertFalse(
            searchHeader.contains("RecommendationProfileSection("),
            "The full profile editor must not consume fixed height above the results list.",
        )

        val editorSheet = source
            .substringAfter("private fun RecommendationProfileEditorSheet(")
            .substringBefore("private fun DeviceInfoSection(")
        assertTrue(editorSheet.contains("ModalBottomSheet("))
        assertTrue(
            editorSheet.contains("verticalScroll("),
            "Profile controls must remain reachable in compact-height windows.",
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
