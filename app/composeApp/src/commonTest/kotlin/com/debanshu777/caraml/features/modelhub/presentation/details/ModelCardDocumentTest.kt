package com.debanshu777.caraml.features.modelhub.presentation.details

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import com.mikepenz.markdown.model.ImageWidth
import com.debanshu777.caraml.features.modelhub.presentation.details.components.ModelCardImageTransformer
import com.debanshu777.caraml.features.modelhub.presentation.details.components.modelCardDisplayMarkdown
import com.debanshu777.caraml.features.modelhub.presentation.details.components.resolveCardUrl
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ModelCardDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelCardDocumentTest {
    private val revision = "a".repeat(40)
    private val card = ModelCardDocument("org/model", revision, "", null)

    @Test
    fun anImageOnlyParagraphDoesNotCollapseToItsInitiallyEmptyHeight() {
        val slot = ModelCardImageTransformer(card).placeholderConfig(
            link = "image.png", density = Density(2f), containerSize = Size(320f, 0f),
            imageWidth = ImageWidth.IMAGE_WIDTH, imageSize = Size(640f, 320f), imageSizeChanged = null,
        )
        assertEquals(Size(160f, 80f), slot.size)
    }

    @Test
    fun relativeLinksAndImagesUseTheSameImmutableRevision() {
        assertEquals("https://huggingface.co/org/model/blob/$revision/docs/guide.md#usage", card.resolveCardUrl("./docs/guide.md#usage"))
        assertEquals("https://huggingface.co/org/model/resolve/$revision/assets/model%20diagram.png?download=true", card.resolveCardUrl("assets/sub/../model%20diagram.png?download=true", image = true))
        assertEquals("${card.sourceUrl}#architecture", card.resolveCardUrl("#architecture"))
        assertEquals("https://huggingface.co/docs/hub", card.resolveCardUrl("/docs/hub"))
        assertEquals("https://example.org/image.png", card.resolveCardUrl("//example.org/image.png", image = true))
        assertEquals("https://example.org/guide", card.resolveCardUrl("https://example.org/guide"))
        assertEquals("https://huggingface.co/org/model/resolve/$revision/assets/hero.webp", card.resolveCardUrl("<./assets/hero.webp>", image = true))
        assertEquals("https://example.org/guide", card.resolveCardUrl("<https://example.org/guide>"))
    }

    @Test
    fun nonWebSchemesAndEscapingPathsAreNotOpened() {
        listOf("javascript:alert(1)", "file:///private/file", "intent:app", "data:text/html,hello", "../secret", "%2e%2e/secret", "assets/%2fsecret", "https://example.org/\npath").forEach {
            assertNull(card.resolveCardUrl(it), it)
            assertNull(card.resolveCardUrl(it, image = true), it)
        }
    }

    @Test
    fun bracketedAbsoluteDestinationsRemainRenderableWithoutChangingCode() {
        val source = "![Figure](<https://example.org/my image.png>)\n[Guide](<https://example.org/guide>)\n`![Example](<https://example.org/code.png>)`"
        assertEquals("![Figure](https://example.org/my%20image.png)\n[Guide](https://example.org/guide)\n`![Example](<https://example.org/code.png>)`", modelCardDisplayMarkdown(source))
    }

    @Test
    fun htmlImagesAreRenderedWithoutChangingMarkdownCodeExamples() {
        val code = "```html\n<img src=\"example.png\" />\n```\n\n`<img src=\"inline.png\" />`"
        val markdown = "<div align=\"center\"><a href=\"docs/guide.md\"><img src=\"assets/logo.png\" alt=\"Model logo\" /></a></div>\n\n$code"
        val rendered = modelCardDisplayMarkdown(markdown)
        assertTrue(rendered.contains("[![Model logo](assets/logo.png)](docs/guide.md)"), rendered)
        assertTrue(rendered.contains(code), rendered)
        assertTrue(!rendered.contains("<div"), rendered)
    }
}
