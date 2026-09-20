package com.debanshu777.caraml.core.recommendation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalArtifactIdentityResolverStructureTest {
    @Test
    fun plainStringsCharsAndCommentsDoNotExposeLegacySyntax() {
        val source = """
            private val normal = "https://example.invalid/resolveLegacy() \" private fun readSidecar() = Unit"
            private val escapedDollar = "literal \${DOLLAR}{resolvePersistedHub()}"
            private val literalDollar = "${DOLLAR}{'${DOLLAR}'}resolveLegacy()"
            private val pathProse = "old file .caraml-local-identity-v1.json is not executable"
            private val raw = ${TRIPLE_QUOTE}
                private fun deletePart() = reuseUnchanged()
                private data class LegacyIdentitySidecar(val component: SidecarComponent)
                private const val MANIFEST_FILE_NAME = ".caraml-local-identity-v1.json"
                ${DOLLAR}{'${DOLLAR}'}resolveLegacy()
            ${TRIPLE_QUOTE}
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

        assertTrue(source.contains("\${'$'}resolveLegacy()"))
        assertEquals(source.length, lexicalView.code.length)
        assertEquals(
            source.indices.filter { source[it] == '\n' },
            lexicalView.code.indices.filter { lexicalView.code[it] == '\n' },
        )
        assertTrue(forbiddenResolverSyntaxIn(source).isEmpty())
        assertTrue(lexicalView.code.contains("private fun canonicalResolve() = Unit"))
    }

    @Test
    fun normalAndRawStringTemplatesExposeExecutableLegacySyntax() {
        val source = """
            private val normal = "normal ${DOLLAR}{ run {
                val nestedRaw = ${TRIPLE_QUOTE}nested raw ${DOLLAR}{deletePart()}${TRIPLE_QUOTE}
                resolvePersistedHub()
            } }"
            private val shorthand = "short ${DOLLAR}resolveLegacy"
            private val raw = ${TRIPLE_QUOTE}
                raw ${DOLLAR}{ run {
                    val nested = "nested ${DOLLAR}{writeSidecar()}"
                    val plain = "deletePart()"
                    val brace = '}'
                    // sidecarPath()
                    /* outer reuseUnchanged() /* nested readBounded() */ */
                    fun toSidecar() = readBounded()
                    toSidecar()
                } }
            ${TRIPLE_QUOTE}
        """.trimIndent()

        assertTrue(source.contains("\${deletePart()}"))
        assertTrue(source.contains("\$resolveLegacy"))
        assertFalse(source.contains("\${'$'}"))
        assertTrue(
            forbiddenResolverSyntaxIn(source).containsAll(
                setOf(
                    "resolveLegacy code identifier",
                    "resolvePersistedHub code identifier",
                    "deletePart code identifier",
                    "writeSidecar code identifier",
                    "toSidecar code identifier",
                    "readBounded code identifier",
                ),
            ),
        )
        assertFalse(forbiddenResolverSyntaxIn(source).contains("readSidecar code identifier"))
        assertFalse(forbiddenResolverSyntaxIn(source).contains("reuseUnchanged code identifier"))
        assertFalse(forbiddenResolverSyntaxIn(source).contains("sidecarPath code identifier"))
    }

    @Test
    fun exactLegacySidecarPathIsDetectedInNormalAndRawCodeValues() {
        val normalSource = """private val path = "$LEGACY_SIDECAR_PATH""""
        val rawSource = "private val path = $TRIPLE_QUOTE$LEGACY_SIDECAR_PATH$TRIPLE_QUOTE"
        val returnSource = """private fun legacyPath(): String { return "$LEGACY_SIDECAR_PATH" }"""

        assertTrue(LEGACY_SIDECAR_PATH_LABEL in forbiddenResolverSyntaxIn(normalSource))
        assertTrue(LEGACY_SIDECAR_PATH_LABEL in forbiddenResolverSyntaxIn(rawSource))
        assertTrue(LEGACY_SIDECAR_PATH_LABEL in forbiddenResolverSyntaxIn(returnSource))
    }

    @Test
    fun unterminatedNonCodeStaysBlankButUnclosedTemplatesFailClosed() {
        val ignoredAtEof = listOf(
            "private val value = \"private fun readSidecar()",
            "private val value = \"\"\"private fun writeSidecar()",
            "private val value = 'x private fun deletePart()",
            "// private fun resolveLegacy()",
            "/* outer /* resolvePersistedHub() */ private fun reuseUnchanged()",
        )
        ignoredAtEof.forEach { source ->
            assertTrue(forbiddenResolverSyntaxIn(source).isEmpty(), source)
            assertEquals(source.length, source.kotlinLexicalView().code.length)
        }

        val unclosedNormalTemplate =
            "private val value = \"prefix " + DOLLAR + "{run { resolveLegacy()"
        val unclosedRawTemplate =
            "private val value = \"\"\"prefix " + DOLLAR + "{run { readSidecar()"

        assertTrue("resolveLegacy code identifier" in forbiddenResolverSyntaxIn(unclosedNormalTemplate))
        assertTrue("readSidecar code identifier" in forbiddenResolverSyntaxIn(unclosedRawTemplate))
    }

    @Test
    fun matcherDetectsEveryRemovedCodeIdentifierAndPath() {
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
        val lexicalView = resolverSource.kotlinLexicalView()
        val forbiddenSyntax = forbiddenResolverSyntaxIn(resolverSource)
        assertTrue(
            forbiddenSyntax.isEmpty(),
            "Legacy artifact syntax remains in LocalArtifactIdentityResolver: $forbiddenSyntax",
        )
        assertTrue(
            Regex("""suspend\s+fun\s+resolve\s*\(""").containsMatchIn(lexicalView.code),
            "The canonical manifest-backed resolve entrypoint is missing",
        )
        assertTrue(
            lexicalView.code.contains("suspend fun createLoadRequestFromVerifiedArtifact("),
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
                if (LEGACY_SIDECAR_PATH in lexicalView.stringLiterals) {
                    add(LEGACY_SIDECAR_PATH_LABEL)
                }
            }
    }

    private fun String.kotlinLexicalView(): KotlinLexicalView {
        val code = StringBuilder(length)
        val stringLiterals = mutableListOf<String>()
        val contexts = ArrayDeque<KotlinLexicalContext>()
        contexts.addLast(KotlinLexicalContext.Code)
        var index = 0

        while (index < length) {
            when (val context = contexts.last()) {
                KotlinLexicalContext.Code,
                is KotlinLexicalContext.TemplateCode,
                -> when {
                    startsWith("//", index) -> {
                        code.append("  ")
                        contexts.addLast(KotlinLexicalContext.LineComment)
                        index += 2
                    }

                    startsWith("/*", index) -> {
                        code.append("  ")
                        contexts.addLast(KotlinLexicalContext.BlockComment())
                        index += 2
                    }

                    startsWith(TRIPLE_QUOTE, index) -> {
                        code.append("   ")
                        contexts.addLast(
                            KotlinLexicalContext.RawString(
                                contentStart = index + TRIPLE_QUOTE.length,
                            ),
                        )
                        index += TRIPLE_QUOTE.length
                    }

                    this[index] == '"' -> {
                        code.append(' ')
                        contexts.addLast(
                            KotlinLexicalContext.NormalString(
                                contentStart = index + 1,
                            ),
                        )
                        index++
                    }

                    this[index] == '\'' -> {
                        code.append(' ')
                        contexts.addLast(KotlinLexicalContext.Character())
                        index++
                    }

                    context is KotlinLexicalContext.TemplateCode && this[index] == '{' -> {
                        code.append('{')
                        context.braceDepth++
                        index++
                    }

                    context is KotlinLexicalContext.TemplateCode && this[index] == '}' -> {
                        context.braceDepth--
                        if (context.braceDepth == 0) {
                            code.append(' ')
                            contexts.removeLast()
                        } else {
                            code.append('}')
                        }
                        index++
                    }

                    else -> code.append(this[index++])
                }

                is KotlinLexicalContext.NormalString -> {
                    val character = this[index]
                    when {
                        context.escaped -> {
                            code.appendNonCode(character)
                            context.escaped = false
                            index++
                        }

                        character == '\\' -> {
                            code.append(' ')
                            context.escaped = true
                            index++
                        }

                        character == '"' -> {
                            code.append(' ')
                            if (!context.hasTemplate) {
                                stringLiterals += substring(context.contentStart, index)
                            }
                            contexts.removeLast()
                            index++
                        }

                        startsTemplateExpression(index) -> {
                            code.append("  ")
                            context.hasTemplate = true
                            contexts.addLast(KotlinLexicalContext.TemplateCode())
                            index += 2
                        }

                        startsTemplateIdentifier(index) -> {
                            context.hasTemplate = true
                            index = copyTemplateIdentifier(code, index)
                        }

                        else -> {
                            code.appendNonCode(character)
                            index++
                        }
                    }
                }

                is KotlinLexicalContext.RawString -> when {
                    startsWith(TRIPLE_QUOTE, index) -> {
                        code.append("   ")
                        if (!context.hasTemplate) {
                            stringLiterals += substring(context.contentStart, index)
                        }
                        contexts.removeLast()
                        index += TRIPLE_QUOTE.length
                    }

                    startsTemplateExpression(index) -> {
                        code.append("  ")
                        context.hasTemplate = true
                        contexts.addLast(KotlinLexicalContext.TemplateCode())
                        index += 2
                    }

                    startsTemplateIdentifier(index) -> {
                        context.hasTemplate = true
                        index = copyTemplateIdentifier(code, index)
                    }

                    else -> {
                        code.appendNonCode(this[index])
                        index++
                    }
                }

                is KotlinLexicalContext.Character -> {
                    val character = this[index]
                    code.appendNonCode(character)
                    when {
                        context.escaped -> context.escaped = false
                        character == '\\' -> context.escaped = true
                        character == '\'' -> contexts.removeLast()
                    }
                    index++
                }

                KotlinLexicalContext.LineComment -> {
                    val character = this[index]
                    code.appendNonCode(character)
                    if (character == '\n' || character == '\r') contexts.removeLast()
                    index++
                }

                is KotlinLexicalContext.BlockComment -> when {
                    startsWith("/*", index) -> {
                        code.append("  ")
                        context.depth++
                        index += 2
                    }

                    startsWith("*/", index) -> {
                        code.append("  ")
                        context.depth--
                        if (context.depth == 0) contexts.removeLast()
                        index += 2
                    }

                    else -> {
                        code.appendNonCode(this[index])
                        index++
                    }
                }
            }
        }
        return KotlinLexicalView(code.toString(), stringLiterals)
    }

    private fun String.startsTemplateExpression(index: Int): Boolean =
        startsWith(DOLLAR.toString() + "{", index)

    private fun String.startsTemplateIdentifier(index: Int): Boolean =
        this[index] == DOLLAR && getOrNull(index + 1)?.isKotlinIdentifierStart() == true

    private fun String.copyTemplateIdentifier(code: StringBuilder, dollarOffset: Int): Int {
        code.append(' ')
        var index = dollarOffset + 1
        while (index < length && this[index].isKotlinIdentifierPart()) {
            code.append(this[index])
            index++
        }
        return index
    }

    private fun Char.isKotlinIdentifierStart(): Boolean = this == '_' || isLetter()

    private fun Char.isKotlinIdentifierPart(): Boolean = isKotlinIdentifierStart() || isDigit()

    private fun StringBuilder.appendNonCode(character: Char) {
        append(if (character == '\n' || character == '\r') character else ' ')
    }

    private data class ForbiddenResolverSyntax(
        val label: String,
        val pattern: Regex,
    )

    private data class KotlinLexicalView(
        val code: String,
        val stringLiterals: List<String>,
    )

    private sealed interface KotlinLexicalContext {
        data object Code : KotlinLexicalContext

        data class TemplateCode(var braceDepth: Int = 1) : KotlinLexicalContext

        data class NormalString(
            val contentStart: Int,
            var escaped: Boolean = false,
            var hasTemplate: Boolean = false,
        ) : KotlinLexicalContext

        data class RawString(
            val contentStart: Int,
            var hasTemplate: Boolean = false,
        ) : KotlinLexicalContext

        data class Character(var escaped: Boolean = false) : KotlinLexicalContext

        data object LineComment : KotlinLexicalContext

        data class BlockComment(var depth: Int = 1) : KotlinLexicalContext
    }

    private companion object {
        val FORBIDDEN_CODE_SYNTAX = listOf(
            forbiddenIdentifier("allowLegacyFallback"),
            forbiddenIdentifier("resolvePersistedHub"),
            forbiddenIdentifier("resolveLegacy"),
            forbiddenIdentifier("readSidecar"),
            forbiddenIdentifier("writeSidecar"),
            forbiddenIdentifier("deletePart"),
            forbiddenIdentifier("sidecarPath"),
            forbiddenIdentifier("reuseUnchanged"),
            forbiddenIdentifier("toSidecar"),
            forbiddenIdentifier("readBounded"),
            forbiddenIdentifier("LegacyIdentitySidecar"),
            forbiddenIdentifier("SidecarComponent"),
            forbiddenIdentifier("LocalContent"),
            forbiddenIdentifier("MANIFEST_FILE_NAME"),
            forbiddenIdentifier("LEGACY_MANIFEST_VERSION"),
            forbiddenIdentifier("MAX_MANIFEST_BYTES"),
            forbiddenIdentifier("MAX_CHANGE_STAMP_LENGTH"),
            forbiddenIdentifier("createLoadRequest"),
        )

        private fun forbiddenIdentifier(name: String) = ForbiddenResolverSyntax(
            "$name code identifier",
            Regex("""\b${Regex.escape(name)}\b"""),
        )

        const val DOLLAR = '$'
        const val TRIPLE_QUOTE = "\"\"\""
        const val LEGACY_SIDECAR_PATH = ".caraml-local-identity-v1.json"
        const val LEGACY_SIDECAR_PATH_LABEL = "$LEGACY_SIDECAR_PATH path literal"
        const val COMMON_MAIN_FROM_ROOT = "composeApp/src/commonMain/kotlin"
        const val COMMON_MAIN_FROM_MODULE = "src/commonMain/kotlin"
    }
}
