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
    fun structuralScanIgnoresCommentsButPreservesStringConstants() {
        val source = """
            // resolvePersistedHub readSidecar LocalContent
            /* MANIFEST_FILE_NAME writeSidecar SidecarComponent */
            val sidecarName = ".caraml-local-identity-v1.json"
        """.trimIndent()

        val code = source.withoutKotlinComments()

        assertFalse(code.contains("resolvePersistedHub"))
        assertFalse(code.contains("MANIFEST_FILE_NAME"))
        assertTrue(code.contains(".caraml-local-identity-v1.json"))
    }

    @Test
    fun productionArtifactResolutionHasNoLegacyLocalIdentityPath() {
        val commonMain = commonMainSourceRoot()
        val sources = Files.walk(commonMain).use { paths ->
            paths.filter { it.isRegularFile() && it.extension == "kt" }
                .toList()
        }.associateWith { it.readText().withoutKotlinComments() }
        val forbiddenProductionSymbols = listOf(
            "allowLegacyFallback",
            "resolveLegacy",
            "resolvePersistedHub",
            "LocalContent",
            ".caraml-local-identity-v1.json",
            "LegacyIdentitySidecar",
            "SidecarComponent",
        )

        forbiddenProductionSymbols.forEach { forbidden ->
            val matches = sources.filterValues { it.contains(forbidden) }.keys
            assertTrue(matches.isEmpty(), "Legacy artifact symbol remains in production: $forbidden in $matches")
        }

        val resolverSource = sources.getValue(resolverSource(commonMain))
        val forbiddenResolverSymbols = listOf(
            "readSidecar",
            "writeSidecar",
            "deletePart",
            "sidecarPath",
            "MANIFEST_FILE_NAME",
        )
        forbiddenResolverSymbols.forEach { forbidden ->
            assertFalse(
                resolverSource.contains(forbidden),
                "Legacy sidecar symbol remains in the artifact resolver: $forbidden",
            )
        }
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

    private fun String.withoutKotlinComments(): String =
        replace(BLOCK_COMMENT, "")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

    private companion object {
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        const val COMMON_MAIN_FROM_ROOT = "composeApp/src/commonMain/kotlin"
        const val COMMON_MAIN_FROM_MODULE = "src/commonMain/kotlin"
    }
}
