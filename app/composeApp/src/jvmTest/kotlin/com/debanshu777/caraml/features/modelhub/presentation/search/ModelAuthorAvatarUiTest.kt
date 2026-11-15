@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelMonogram
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.ColorImage
import coil3.decode.DataSource
import coil3.compose.LocalPlatformContext
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemePreferences
import com.debanshu777.caraml.features.modelhub.presentation.search.components.ModelAuthorAvatar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

class ModelAuthorAvatarUiTest {
    @Test
    fun avatarFallbackUsesTheExactModelMonogramFrameAcrossTasks() = runComposeUiTest {
        val avatarVisible = mutableStateOf(true)
        val task = mutableStateOf<String?>(null)
        setContent {
            CaraMLTheme(ThemePreferences()) {
                if (avatarVisible.value) {
                    ModelAuthorAvatar(null, "Qwen", task = task.value)
                } else {
                    ModelMonogram("Qwen", Modifier.testTag("reference-monogram"), task.value)
                }
            }
        }
        listOf(null, "text-to-image", "text-to-video").forEach { pipeline ->
            runOnIdle { task.value = pipeline; avatarVisible.value = true }
            val avatar = onNodeWithTag("model-author-avatar", useUnmergedTree = true).captureToImage().toPixelMap()
            runOnIdle { avatarVisible.value = false }
            val monogram = onNodeWithTag("reference-monogram", useUnmergedTree = true).captureToImage().toPixelMap()
            assertEquals(monogram.width, avatar.width)
            assertEquals(monogram.height, avatar.height)
            for (y in 0 until avatar.height) {
                for (x in 0 until avatar.width) assertEquals(monogram[x, y], avatar[x, y], "Frame differs for $pipeline at $x,$y")
            }
        }
    }

    @Test
    fun sharedImageLoaderAutomaticallyDecodesSvgAvatarBytes() = runBlocking {
        // No injected decoder or interceptor: this exercises coil-svg's service registration.
        val loader = ImageLoader.Builder(PlatformContext.INSTANCE).build()
        try {
            val svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="40" height="40" viewBox="0 0 40 40">
                  <circle cx="20" cy="20" r="16" fill="#ff0000"/>
                </svg>
            """.trimIndent().encodeToByteArray()
            val result = loader.execute(
                ImageRequest.Builder(PlatformContext.INSTANCE)
                    .data(svg)
                    .size(40, 40)
                    .build(),
            )
            val success = assertIs<SuccessResult>(result)
            assertEquals(40, success.image.width)
            assertEquals(40, success.image.height)
        } finally {
            loader.shutdown()
        }
    }

    @Test
    fun transparentSuccessHidesInitialAndChangingUrlRestoresFallback() = runComposeUiTest {
        val url = mutableStateOf<String?>("https://avatars.example.test/transparent.svg")
        val loaded = CompletableDeferred<Unit>()
        var loader: ImageLoader? = null
        setContent {
            if (loader == null) {
                loader = ImageLoader.Builder(LocalPlatformContext.current)
                    .components {
                        add(Interceptor { chain ->
                            loaded.complete(Unit)
                            SuccessResult(
                                image = ColorImage(0x00000000),
                                request = chain.request,
                                dataSource = DataSource.MEMORY,
                            )
                        })
                    }.build()
            }
            CaraMLTheme(ThemePreferences()) {
                ModelAuthorAvatar(url.value, "Qwen", imageLoader = loader)
            }
        }
        try {
            waitUntil { loaded.isCompleted }
            waitForIdle()
            val avatar = onNodeWithTag("model-author-avatar", useUnmergedTree = true)
            // The interceptor completes before AsyncImage publishes its successful painter state.
            waitUntil {
                val pixels = avatar.captureToImage().toPixelMap()
                val background = pixels[pixels.width / 4, pixels.height / 4]
                (pixels.height / 4 until pixels.height * 3 / 4).all { y ->
                    (pixels.width / 4 until pixels.width * 3 / 4).all { x -> pixels[x, y] == background }
                }
            }
            val transparent = avatar.captureToImage().toPixelMap()
            val centralColors = buildSet {
                for (y in transparent.height / 4 until transparent.height * 3 / 4) {
                    for (x in transparent.width / 4 until transparent.width * 3 / 4) add(transparent[x, y])
                }
            }
            assertEquals(1, centralColors.size, "Transparent success must show only the background, without an initial")
            runOnIdle { url.value = null }
            val fallback = avatar.captureToImage().toPixelMap()
            assertTrue((fallback.height / 4 until fallback.height * 3 / 4).any { y ->
                (fallback.width / 4 until fallback.width * 3 / 4).any { x -> fallback[x, y] != transparent[x, y] }
            }, "Changing the URL must restore the author initial")
        } finally {
            loader?.shutdown()
        }
    }

    @Test
    fun missingAndFailedAvatarsKeepTheSameFallbackSizeAndDecorativeSemantics() = runComposeUiTest {
        val url = mutableStateOf<String?>(null)
        val failed = CompletableDeferred<Unit>()
        var loader: ImageLoader? = null
        setContent {
            if (loader == null) {
                loader = ImageLoader.Builder(LocalPlatformContext.current)
                    .components {
                        add(Interceptor { chain ->
                            failed.complete(Unit)
                            ErrorResult(image = null, request = chain.request, throwable = IllegalStateException("offline"))
                        })
                    }.build()
            }
            CaraMLTheme(ThemePreferences()) {
                ModelAuthorAvatar(url.value, "Qwen", imageLoader = loader)
            }
        }
        try {
            val avatar = onNodeWithTag("model-author-avatar", useUnmergedTree = true)
            avatar.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
            val missing = avatar.captureToImage().toPixelMap()
            runOnIdle { url.value = "https://avatars.example.test/qwen.svg" }
            waitUntil { failed.isCompleted }
            waitForIdle()
            val error = avatar.captureToImage().toPixelMap()
            assertEquals(missing.width, error.width)
            assertEquals(missing.height, error.height)
            for (y in 0 until missing.height) {
                for (x in 0 until missing.width) assertEquals(missing[x, y], error[x, y])
            }
        } finally {
            loader?.shutdown()
        }
    }
}
