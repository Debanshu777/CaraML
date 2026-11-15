@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.details

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.WindowInsets
import com.debanshu777.caraml.core.ui.components.FrostedPageScaffold
import com.debanshu777.caraml.core.ui.layout.AppContentKind
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelHubBackHeader
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemeMode
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.features.modelhub.presentation.details.components.ModelDetailContent
import com.debanshu777.caraml.features.modelhub.presentation.search.saveModelHubEvidence
import com.debanshu777.caraml.sdk.discovery.huggingface.api.error.DataError
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ModelCardDocument
import com.debanshu777.caraml.sdk.discovery.huggingface.model.ModelDetailResponse
import com.sun.net.httpserver.HttpServer
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelDetailsImageUiTest {
    @Test
    fun imageSuccessFailureAndNonRetryableCardHaveVisibleOutcomes() = runComposeUiTest {
        val bitmap = BufferedImage(640, 320, BufferedImage.TYPE_INT_RGB)
        bitmap.createGraphics().apply { color = Color(70, 100, 160); fillRect(0, 0, 640, 320); color = Color.WHITE; fillOval(240, 80, 160, 160); dispose() }
        val bytes = ByteArrayOutputStream().also { ImageIO.write(bitmap, "png", it) }.toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/image.png") { exchange ->
            exchange.responseHeaders.add("Content-Type", "image/png")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/missing.png") { exchange -> exchange.sendResponseHeaders(404, -1); exchange.close() }
        server.start()
        val origin = "http://127.0.0.1:${server.address.port}"
        fun document(body: String) = prepareModelCard(ModelCardDocument("sample/images", "a".repeat(40), body, null))
        var state by mutableStateOf<ModelCardUiState>(document("# Images\n\n![Sample figure](<$origin/image.png>)"))
        var opened: String? = null
        var theme by mutableStateOf(ThemeMode.LIGHT)
        try {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) { opened = uri }
                }) {
                    CaraMLTheme(ThemePreferences(themeMode = theme, reduceMotion = true)) {
                        Box(Modifier.requiredSize(390.dp, 780.dp).testTag("visual-root")) {
                            FrostedPageScaffold(AppContentKind.Details,
                                header = { ModelHubBackHeader({}, label = null) },
                                safeInsets = WindowInsets(0, 0, 0, 0)) { insets ->
                                ModelDetailContent(ModelDetailResponse(id = "sample/images"), emptyList(), false, { _, _, _ -> },
                                    modelCardState = state, contentPadding = PaddingValues(top = insets.calculateTopPadding() + 12.dp, bottom = 16.dp))
                            }
                        }
                    }
                }
            }
            onNodeWithText("Model card").performClick()
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Sample figure").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithContentDescription("Sample figure").assertIsDisplayed()
            saveModelHubEvidence("model-card-image-success.png")
            runOnIdle { state = document("# Images\n\n![Missing diagram](<$origin/missing.png>)") }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Image unavailable: Missing diagram").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Open image").assertIsDisplayed().performClick()
            runOnIdle { assertEquals("$origin/missing.png", opened) }
            saveModelHubEvidence("model-card-image-failure.png")
            runOnIdle { theme = ThemeMode.DARK }
            saveModelHubEvidence("model-card-image-failure-dark.png")
            runOnIdle { state = ModelCardUiState.Failed(DataError.Network.PayloadTooLarge) }
            onNodeWithText("Open on Hugging Face").assertIsDisplayed().performClick()
            runOnIdle { assertEquals("https://huggingface.co/sample/images", opened) }
            saveModelHubEvidence("model-card-unavailable.png")
        } finally { server.stop(0) }
    }
}
