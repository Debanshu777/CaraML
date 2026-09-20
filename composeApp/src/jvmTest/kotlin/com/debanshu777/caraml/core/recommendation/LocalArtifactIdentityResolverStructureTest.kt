package com.debanshu777.caraml.core.recommendation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalArtifactIdentityResolverStructureTest {
    @Test
    fun matcherIgnoresUrlsRawStringsAndNestedCommentProse() {
        val source = """
            private const val docsUrl = "https://example.invalid/resolvePersistedHub?readSidecar=true"
            private val migrationNotes = ${"\"\"\""}
                allowLegacyFallback resolveLegacy writeSidecar deletePart sidecarPath
                LocalContent LegacyIdentitySidecar SidecarComponent MANIFEST_FILE_NAME
                .caraml-local-identity-v1.json
            ${"\"\"\""}
            /* Legacy artifact names may remain in prose.
               /* resolvePersistedHub readSidecar RevisionIdentity LocalContent */
               createLoadRequestFromVerifiedArtifact remains canonical.
            */
        """.trimIndent()

        assertTrue(forbiddenResolverSyntaxIn(source).isEmpty())
    }

    @Test
    fun matcherDetectsEveryRemovedDeclarationCallTypeAndConstant() {
        val source = """
            private suspend fun resolvePersistedHub() = resolveLegacy()
            private suspend fun resolveLegacy(allowLegacyFallback: Boolean) = readSidecar(sidecarPath())
            private suspend fun readSidecar() = writeSidecar()
            private suspend fun writeSidecar() = deletePart()
            private suspend fun deletePart() = Unit
            private fun sidecarPath() = MANIFEST_FILE_NAME
            private data class LegacyIdentitySidecar(val components: List<SidecarComponent>)
            private data class SidecarComponent(val path: String)
            private data class LocalContent(val digest: String)
            private val revision = RevisionIdentity.LocalContent("digest")
            private const val MANIFEST_FILE_NAME = ".caraml-local-identity-v1.json"
            private suspend fun createLoadRequest() = Unit
        """.trimIndent()

        assertEquals(
            FORBIDDEN_RESOLVER_SYNTAX.mapTo(linkedSetOf(), ForbiddenResolverSyntax::label),
            forbiddenResolverSyntaxIn(source),
        )
    }

    @Test
    fun productionArtifactResolutionHasNoLegacyLocalIdentityPath() {
        val commonMain = commonMainSourceRoot()
        val resolverSource = resolverSource(commonMain).readText()
        val forbiddenSyntax = forbiddenResolverSyntaxIn(resolverSource)
        assertTrue(
            forbiddenSyntax.isEmpty(),
            "Legacy artifact syntax remains in LocalArtifactIdentityResolver: $forbiddenSyntax",
        )
        assertTrue(
            Regex("""suspend\s+fun\s+resolve\s*\(""").containsMatchIn(resolverSource),
            "The canonical manifest-backed resolve entrypoint is missing",
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

    private fun forbiddenResolverSyntaxIn(source: String): Set<String> =
        FORBIDDEN_RESOLVER_SYNTAX
            .filter { it.pattern.containsMatchIn(source) }
            .mapTo(linkedSetOf(), ForbiddenResolverSyntax::label)

    private data class ForbiddenResolverSyntax(
        val label: String,
        val pattern: Regex,
    )

    private companion object {
        val FORBIDDEN_RESOLVER_SYNTAX = listOf(
            ForbiddenResolverSyntax(
                "allowLegacyFallback parameter or argument",
                Regex("""\ballowLegacyFallback\s*(?::\s*Boolean\b|=\s*(?:true|false)\b)"""),
            ),
            forbiddenFunction("resolvePersistedHub"),
            forbiddenFunction("resolveLegacy"),
            forbiddenFunction("readSidecar"),
            forbiddenFunction("writeSidecar"),
            forbiddenFunction("deletePart"),
            forbiddenFunction("sidecarPath"),
            forbiddenType("LegacyIdentitySidecar"),
            forbiddenType("SidecarComponent"),
            ForbiddenResolverSyntax(
                "RevisionIdentity.LocalContent type or reference",
                Regex(
                    """(?:\b(?:data\s+)?(?:class|object)\s+LocalContent\b|""" +
                        """\bRevisionIdentity\s*\.\s*LocalContent\b)""",
                ),
            ),
            ForbiddenResolverSyntax(
                "MANIFEST_FILE_NAME constant",
                Regex("""\b(?:const\s+)?val\s+MANIFEST_FILE_NAME\s*="""),
            ),
            ForbiddenResolverSyntax(
                ".caraml-local-identity-v1.json path literal",
                Regex("(?:=|/|\\()\\s*\"\\.caraml-local-identity-v1\\.json\""),
            ),
            forbiddenFunction("createLoadRequest"),
        )

        private fun forbiddenFunction(name: String) = ForbiddenResolverSyntax(
            "$name declaration or call",
            Regex("""\b${Regex.escape(name)}\s*\("""),
        )

        private fun forbiddenType(name: String) = ForbiddenResolverSyntax(
            "$name type or constructor",
            Regex(
                """(?:\b(?:data\s+)?(?:class|object)\s+${Regex.escape(name)}\b|""" +
                    """\b${Regex.escape(name)}\s*\()""",
            ),
        )

        const val COMMON_MAIN_FROM_ROOT = "composeApp/src/commonMain/kotlin"
        const val COMMON_MAIN_FROM_MODULE = "src/commonMain/kotlin"
    }
}
