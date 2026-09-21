package com.debanshu777.caraml.features.modelhub.presentation.search

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelViewModelDurableDownloadStructureTest {
    @Test
    fun durableCoordinatorIsMandatoryAndOwnsEveryReadyPublication() {
        val source = Files.readString(modelViewModelSource())

        assertTrue(
            Regex("""private val downloadCoordinator:\s*DownloadCoordinator,""").containsMatchIn(source),
            "ModelViewModel must require the durable coordinator",
        )
        listOf(
            Regex("""downloadCoordinator\s*:\s*DownloadCoordinator\?"""),
            Regex("""downloadCoordinator\s*\?\."""),
            Regex("""downloadCoordinator\s*[!=]=\s*null"""),
        ).forEach { forbidden ->
            assertFalse(forbidden.containsMatchIn(source), "Nullable coordinator branch remains: $forbidden")
        }
        listOf(
            "downloadManager.publishBundle(",
            "downloadManager.validateBundle(",
            "persistDiffusionModelRecord(",
            "componentStatus = LocalModelEntity.STATUS_READY",
            "localModelRepository.insert(",
        ).forEach { forbidden ->
            assertFalse(source.contains(forbidden), "Direct Ready publication remains: $forbidden")
        }
    }

    private fun modelViewModelSource(): Path {
        val workingDirectory = Path.of(System.getProperty("user.dir"))
        return listOf(
            workingDirectory.resolve(MODEL_VIEW_MODEL_FROM_ROOT),
            workingDirectory.resolve(MODEL_VIEW_MODEL_FROM_MODULE),
        ).firstOrNull(Files::isRegularFile)
            ?: error("Could not locate ModelViewModel.kt from $workingDirectory")
    }

    private companion object {
        const val MODEL_VIEW_MODEL_FROM_ROOT =
            "composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/" +
                "presentation/search/ModelViewModel.kt"
        const val MODEL_VIEW_MODEL_FROM_MODULE =
            "src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/" +
                "presentation/search/ModelViewModel.kt"
    }
}
