package com.debanshu777.caraml.core.recommendation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalArtifactIdentityResolverStructureTest {
    @Test
    fun sanitizerBlanksCodeShapedSyntaxInEveryNonCodeRegion() {
        val source = """
            private val normal = "https://example.invalid/resolveLegacy() \" private fun readSidecar() = Unit"
            private val template = "ignored ${'$'}{ resolvePersistedHub() } const val MAX_MANIFEST_BYTES = 1"
            private val escapedSlash = "ignored \\\\ writeSidecar()"
            private val raw = ${"\"\"\""}
                private fun deletePart() = reuseUnchanged()
                private data class LegacyIdentitySidecar(val component: SidecarComponent)
                private const val MANIFEST_FILE_NAME = ".caraml-local-identity-v1.json"
            ${"\"\"\""}
            private val escapedQuote = '\''
            private val escapedBackslash = '\\'
            private val doubleQuote = '"'
            // private fun readBounded() = toSidecar()
            /* private fun resolveLegacy() = Unit
               /* private const val LEGACY_MANIFEST_VERSION = 1 */
               private data class LocalContent(val digest: String)
               private const val MAX_CHANGE_STAMP_LENGTH = 128
            */
            private fun canonicalResolve() = Unit
        """.trimIndent()

        val lexicalView = source.kotlinLexicalView()

        assertEquals(source.length, lexicalView.code.length)
        assertEquals(
            source.indices.filter { source[it] == '\n' },
            lexicalView.code.indices.filter { lexicalView.code[it] == '\n' },
        )
        assertTrue(forbiddenResolverSyntaxIn(source).isEmpty())
        assertTrue(lexicalView.code.contains("private fun canonicalResolve() = Unit"))
    }

    @Test
    fun sanitizerConservativelyBlanksUnterminatedNonCodeAtEof() {
        val unterminatedSources = listOf(
            "private val value = \"private fun readSidecar()",
            "private val value = \"\"\"private fun writeSidecar()",
            "private val value = 'x private fun deletePart()",
            "// private fun resolveLegacy()",
            "/* outer /* resolvePersistedHub() */ private fun reuseUnchanged()",
        )

        unterminatedSources.forEach { source ->
            assertTrue(forbiddenResolverSyntaxIn(source).isEmpty(), source)
            assertEquals(source.length, source.kotlinLexicalView().code.length)
        }
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
            private fun reuseUnchanged() = readBounded()
            private fun readBounded() = toSidecar()
            private fun toSidecar() = Unit
            private data class LegacyIdentitySidecar(val components: List<SidecarComponent>)
            private data class SidecarComponent(val path: String)
            private data class LocalContent(val digest: String)
            private val revision = RevisionIdentity.LocalContent("digest")
            private const val MANIFEST_FILE_NAME = ".caraml-local-identity-v1.json"
            private const val LEGACY_MANIFEST_VERSION = 1
            private const val MAX_MANIFEST_BYTES = 262144
            private const val MAX_CHANGE_STAMP_LENGTH = 128
            private suspend fun createLoadRequest() = Unit
        """.trimIndent()

        assertEquals(
            FORBIDDEN_CODE_SYNTAX.mapTo(linkedSetOf(), ForbiddenResolverSyntax::label).apply {
                add(LEGACY_SIDECAR_PATH_LABEL)
            },
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

    private fun forbiddenResolverSyntaxIn(source: String): Set<String> {
        val lexicalView = source.kotlinLexicalView()
        return FORBIDDEN_CODE_SYNTAX
            .filter { it.pattern.containsMatchIn(lexicalView.code) }
            .mapTo(linkedSetOf(), ForbiddenResolverSyntax::label)
            .apply {
                if (lexicalView.normalStringLiterals.any { literal ->
                        literal.content == LEGACY_SIDECAR_PATH &&
                            LEGACY_PATH_PREFIX.containsMatchIn(lexicalView.code.substring(0, literal.startOffset))
                    }
                ) {
                    add(LEGACY_SIDECAR_PATH_LABEL)
                }
            }
    }

    private fun String.kotlinLexicalView(): KotlinLexicalView {
        val code = StringBuilder(length)
        val normalStringLiterals = mutableListOf<NormalStringLiteral>()
        var state = KotlinLexicalState.CODE
        var index = 0
        var escaped = false
        var blockCommentDepth = 0
        var normalStringStartOffset = -1
        var normalStringContentStart = -1

        while (index < length) {
            when (state) {
                KotlinLexicalState.CODE -> when {
                    startsWith("//", index) -> {
                        code.append("  ")
                        state = KotlinLexicalState.LINE_COMMENT
                        index += 2
                    }

                    startsWith("/*", index) -> {
                        code.append("  ")
                        state = KotlinLexicalState.BLOCK_COMMENT
                        blockCommentDepth = 1
                        index += 2
                    }

                    startsWith("\"\"\"", index) -> {
                        code.append("   ")
                        state = KotlinLexicalState.RAW_STRING
                        index += 3
                    }

                    this[index] == '"' -> {
                        code.append(' ')
                        state = KotlinLexicalState.NORMAL_STRING
                        escaped = false
                        normalStringStartOffset = index
                        normalStringContentStart = index + 1
                        index++
                    }

                    this[index] == '\'' -> {
                        code.append(' ')
                        state = KotlinLexicalState.CHARACTER
                        escaped = false
                        index++
                    }

                    else -> code.append(this[index++])
                }

                KotlinLexicalState.NORMAL_STRING -> {
                    val character = this[index]
                    code.appendNonCode(character)
                    when {
                        escaped -> escaped = false
                        character == '\\' -> escaped = true
                        character == '"' -> {
                            normalStringLiterals += NormalStringLiteral(
                                startOffset = normalStringStartOffset,
                                content = substring(normalStringContentStart, index),
                            )
                            state = KotlinLexicalState.CODE
                        }
                    }
                    index++
                }

                KotlinLexicalState.RAW_STRING -> {
                    if (startsWith("\"\"\"", index)) {
                        code.append("   ")
                        state = KotlinLexicalState.CODE
                        index += 3
                    } else {
                        code.appendNonCode(this[index++])
                    }
                }

                KotlinLexicalState.CHARACTER -> {
                    val character = this[index]
                    code.appendNonCode(character)
                    when {
                        escaped -> escaped = false
                        character == '\\' -> escaped = true
                        character == '\'' -> state = KotlinLexicalState.CODE
                    }
                    index++
                }

                KotlinLexicalState.LINE_COMMENT -> {
                    val character = this[index]
                    code.appendNonCode(character)
                    if (character == '\n' || character == '\r') {
                        state = KotlinLexicalState.CODE
                    }
                    index++
                }

                KotlinLexicalState.BLOCK_COMMENT -> when {
                    startsWith("/*", index) -> {
                        code.append("  ")
                        blockCommentDepth++
                        index += 2
                    }

                    startsWith("*/", index) -> {
                        code.append("  ")
                        blockCommentDepth--
                        if (blockCommentDepth == 0) state = KotlinLexicalState.CODE
                        index += 2
                    }

                    else -> code.appendNonCode(this[index++])
                }
            }
        }
        return KotlinLexicalView(code.toString(), normalStringLiterals)
    }

    private fun StringBuilder.appendNonCode(character: Char) {
        append(if (character == '\n' || character == '\r') character else ' ')
    }

    private data class ForbiddenResolverSyntax(
        val label: String,
        val pattern: Regex,
    )

    private data class KotlinLexicalView(
        val code: String,
        val normalStringLiterals: List<NormalStringLiteral>,
    )

    private data class NormalStringLiteral(
        val startOffset: Int,
        val content: String,
    )

    private enum class KotlinLexicalState {
        CODE,
        NORMAL_STRING,
        RAW_STRING,
        CHARACTER,
        LINE_COMMENT,
        BLOCK_COMMENT,
    }

    private companion object {
        val FORBIDDEN_CODE_SYNTAX = listOf(
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
            forbiddenFunction("reuseUnchanged"),
            forbiddenFunction("toSidecar"),
            forbiddenFunction("readBounded"),
            forbiddenType("LegacyIdentitySidecar"),
            forbiddenType("SidecarComponent"),
            ForbiddenResolverSyntax(
                "RevisionIdentity.LocalContent type or reference",
                Regex(
                    """(?:\b(?:data\s+)?(?:class|object)\s+LocalContent\b|""" +
                        """\bRevisionIdentity\s*\.\s*LocalContent\b)""",
                ),
            ),
            forbiddenValue("MANIFEST_FILE_NAME"),
            forbiddenValue("LEGACY_MANIFEST_VERSION"),
            forbiddenValue("MAX_MANIFEST_BYTES"),
            forbiddenValue("MAX_CHANGE_STAMP_LENGTH"),
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

        private fun forbiddenValue(name: String) = ForbiddenResolverSyntax(
            "$name constant",
            Regex("""\b(?:const\s+)?val\s+${Regex.escape(name)}\s*="""),
        )

        const val LEGACY_SIDECAR_PATH = ".caraml-local-identity-v1.json"
        const val LEGACY_SIDECAR_PATH_LABEL = "$LEGACY_SIDECAR_PATH path literal"
        val LEGACY_PATH_PREFIX = Regex("""(?:=|/|\()\s*$""")
        const val COMMON_MAIN_FROM_ROOT = "composeApp/src/commonMain/kotlin"
        const val COMMON_MAIN_FROM_MODULE = "src/commonMain/kotlin"
    }
}
