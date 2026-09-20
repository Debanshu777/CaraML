package com.debanshu777.caraml.core.recommendation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalArtifactIdentityResolverStructureTest {
    @Test
    fun productionArtifactResolutionHasNoLegacyLocalIdentityPath() {
        val commonMain = commonMainSourceRoot()
        val sources = Files.walk(commonMain).use { paths ->
            paths.filter { it.isRegularFile() && it.extension == "kt" }
                .toList()
        }
        val forbiddenSymbols = listOf(
            "allowLegacyFallback",
            "resolveLegacy",
            "LocalContent",
            ".caraml-local-identity-v1.json",
            "LegacyIdentitySidecar",
            "SidecarComponent",
        )

        forbiddenSymbols.forEach { forbidden ->
            val matches = sources.filter { it.readText().contains(forbidden) }
            assertTrue(matches.isEmpty(), "Legacy artifact symbol remains in production: $forbidden in $matches")
        }

        val resolverSource = resolverSource(commonMain).readText()
        assertTrue(
            Regex("""suspend\s+fun\s+resolve\s*\(""").containsMatchIn(resolverSource),
            "The canonical manifest-backed resolve entrypoint is missing",
        )
        assertFalse(
            Regex("""suspend\s+fun\s+createLoadRequest\s*\(""").containsMatchIn(resolverSource),
            "The legacy resolving request overload remains",
        )
        assertTrue(
            resolverSource.contains("suspend fun createLoadRequestFromVerifiedArtifact("),
            "The verified-artifact request constructor must remain",
        )
    }

    private fun commonMainSourceRoot(): Path {
        val workingDirectory = Path.of(System.getProperty("user.dir"))
        return listOf(
            workingDirectory.resolve(COMMON_MAIN_FROM_ROOT),
            workingDirectory.resolve(COMMON_MAIN_FROM_MODULE),
        ).firstOrNull(Files::isDirectory)
            ?: error("Could not locate commonMain production sources from $workingDirectory")
    }

    private fun resolverSource(commonMain: Path): Path = commonMain.resolve(
        "com/debanshu777/caraml/core/recommendation/LocalArtifactIdentityResolver.kt",
    )

    private companion object {
        const val COMMON_MAIN_FROM_ROOT = "composeApp/src/commonMain/kotlin"
        const val COMMON_MAIN_FROM_MODULE = "src/commonMain/kotlin"
    }
}
