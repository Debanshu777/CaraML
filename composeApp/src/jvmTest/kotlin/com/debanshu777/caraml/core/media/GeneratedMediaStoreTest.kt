package com.debanshu777.caraml.core.media

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeneratedMediaStoreTest {
    @Test
    fun savesReadsAndClearsSessionMedia() = runTest {
        val base = Files.createTempDirectory("caraml-media-test")
        val store = GeneratedMediaStore(
            baseDirectory = base.toString(),
            sessionId = "session",
            maxFileBytes = 16L,
        )

        val imagePath = store.saveImage("message-1", byteArrayOf(1, 2, 3))
        val framePaths = store.saveVideo(
            "message-2",
            listOf(byteArrayOf(4), byteArrayOf(5, 6)),
        )

        assertContentEquals(byteArrayOf(1, 2, 3), store.read(imagePath))
        assertEquals(2, framePaths.size)
        assertContentEquals(byteArrayOf(5, 6), store.read(framePaths[1]))

        store.clear()

        assertFalse(Files.exists(base.resolve("generated-media-session")))
        Files.deleteIfExists(base)
    }

    @Test
    fun rejectsUnsafeNamesAndPathsOutsideItsSessionRoot() = runTest {
        val base = Files.createTempDirectory("caraml-media-containment")
        val outside = Files.createTempFile("caraml-media-outside", ".png")
        outside.writeBytes(byteArrayOf(9))
        val store = GeneratedMediaStore(
            baseDirectory = base.toString(),
            sessionId = "session",
            maxFileBytes = 16L,
        )

        assertFailsWith<IllegalArgumentException> {
            store.saveImage("../escape", byteArrayOf(1))
        }
        assertNull(store.read(outside.toString()))
        assertTrue(Files.exists(outside))

        store.clear()
        Files.deleteIfExists(outside)
        Files.deleteIfExists(base)
    }

    @Test
    fun refusesEmptyOrOversizedMediaBeforeWriting() = runTest {
        val base = Files.createTempDirectory("caraml-media-limit")
        val store = GeneratedMediaStore(
            baseDirectory = base.toString(),
            sessionId = "session",
            maxFileBytes = 4L,
        )

        assertFailsWith<IllegalArgumentException> {
            store.saveImage("empty", byteArrayOf())
        }
        assertFailsWith<IllegalArgumentException> {
            store.saveVideo("large", listOf(byteArrayOf(1, 2, 3, 4, 5)))
        }

        store.clear()
        Files.deleteIfExists(base)
    }

    @Test
    fun boundsTheWholeSessionAndLeavesNoPartialVideo() = runTest {
        val base = Files.createTempDirectory("caraml-media-session-limit")
        val store = GeneratedMediaStore(
            baseDirectory = base.toString(),
            sessionId = "session",
            maxFileBytes = 4L,
            maxSessionBytes = 5L,
        )

        val imagePath = store.saveImage("image", byteArrayOf(1, 2, 3))

        assertFailsWith<IllegalArgumentException> {
            store.saveVideo("video", listOf(byteArrayOf(4, 5), byteArrayOf(6)))
        }
        assertContentEquals(byteArrayOf(1, 2, 3), store.read(imagePath))
        assertFalse(Files.exists(base.resolve("generated-media-session/video")))

        store.clear()
        Files.deleteIfExists(base)
    }
}
