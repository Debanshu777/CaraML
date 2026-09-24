package com.debanshu777.caraml.core.media

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.exists
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

    @Test
    fun startupPrunesExpiredAbandonedSessionsButProtectsTheActiveSession() = runTest {
        val base = Files.createTempDirectory("caraml-media-startup-prune")
        val abandoned = base.resolve("generated-media-abandoned").createDirectories()
        abandoned.resolve("old.png").writeBytes(byteArrayOf(1, 2, 3))
        Files.setLastModifiedTime(abandoned, FileTime.fromMillis(1_000L))
        val active = base.resolve("generated-media-active").createDirectories()
        active.resolve("current.png").writeBytes(byteArrayOf(4))
        Files.setLastModifiedTime(active, FileTime.fromMillis(1_000L))
        val store = GeneratedMediaStore(
            baseDirectory = base.toString(),
            sessionId = "active",
            maxFileBytes = 16L,
            maxSessionBytes = 16L,
            maxGlobalBytes = 32L,
            maxAbandonedAgeMillis = 1_000L,
            clock = { 3_000L },
        )

        store.prepare()

        assertFalse(abandoned.exists())
        assertTrue(active.resolve("current.png").exists())
        store.clear()
        Files.deleteIfExists(base)
    }

    @Test
    fun startupEvictsLeastRecentlyUsedSessionsToKeepGlobalCapacityBounded() = runTest {
        val base = Files.createTempDirectory("caraml-media-global-limit")
        val oldest = base.resolve("generated-media-oldest").createDirectories()
        oldest.resolve("old.png").writeBytes(byteArrayOf(1, 2, 3, 4))
        Files.setLastModifiedTime(oldest, FileTime.fromMillis(1_000L))
        val newest = base.resolve("generated-media-newest").createDirectories()
        newest.resolve("new.png").writeBytes(byteArrayOf(5, 6, 7, 8))
        Files.setLastModifiedTime(newest, FileTime.fromMillis(2_000L))
        val store = GeneratedMediaStore(
            baseDirectory = base.toString(),
            sessionId = "active",
            maxFileBytes = 4L,
            maxSessionBytes = 4L,
            maxGlobalBytes = 8L,
            maxAbandonedAgeMillis = Long.MAX_VALUE,
            clock = { 3_000L },
        )

        store.prepare()

        assertFalse(oldest.exists())
        assertTrue(newest.resolve("new.png").exists())
        store.clear()
        newest.toFile().deleteRecursively()
        Files.deleteIfExists(base)
    }

    @Test
    fun cleanupProtectsSessionsOwnedByOtherActiveStores() = runTest {
        val base = Files.createTempDirectory("caraml-media-multi-store-protection")
        val firstStore = GeneratedMediaStore(
            baseDirectory = base.toString(),
            sessionId = "first",
            maxFileBytes = 4L,
            maxSessionBytes = 4L,
            maxGlobalBytes = 8L,
            maxAbandonedAgeMillis = Long.MAX_VALUE,
        )
        val secondStore = GeneratedMediaStore(
            baseDirectory = base.toString(),
            sessionId = "second",
            maxFileBytes = 4L,
            maxSessionBytes = 4L,
            maxGlobalBytes = 8L,
            maxAbandonedAgeMillis = Long.MAX_VALUE,
        )

        try {
            val firstImage = firstStore.saveImage("first-image", byteArrayOf(1, 2, 3, 4))
            val firstSession = base.resolve("generated-media-first")
            Files.setLastModifiedTime(firstSession, FileTime.fromMillis(1_000L))
            val abandoned = base.resolve("generated-media-abandoned").createDirectories()
            abandoned.resolve("old.png").writeBytes(byteArrayOf(5, 6, 7, 8))
            Files.setLastModifiedTime(abandoned, FileTime.fromMillis(2_000L))

            secondStore.prepare()
            secondStore.saveImage("second-image", byteArrayOf(9))

            assertContentEquals(byteArrayOf(1, 2, 3, 4), firstStore.read(firstImage))
            assertFalse(abandoned.exists())
        } finally {
            firstStore.clear()
            secondStore.clear()
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun activeStoresShareOneAggregateCapacityLimit() = runTest {
        val base = Files.createTempDirectory("caraml-media-multi-store-limit")
        val firstStore = GeneratedMediaStore(
            baseDirectory = base.toString(),
            sessionId = "first",
            maxFileBytes = 6L,
            maxSessionBytes = 6L,
            maxGlobalBytes = 8L,
            maxAbandonedAgeMillis = Long.MAX_VALUE,
        )
        val secondStore = GeneratedMediaStore(
            baseDirectory = base.toString(),
            sessionId = "second",
            maxFileBytes = 6L,
            maxSessionBytes = 6L,
            maxGlobalBytes = 8L,
            maxAbandonedAgeMillis = Long.MAX_VALUE,
        )

        try {
            firstStore.prepare()
            secondStore.prepare()
            firstStore.saveImage("first-image", ByteArray(6) { 1 })

            assertFailsWith<IllegalArgumentException> {
                secondStore.saveImage("second-image", ByteArray(3) { 2 })
            }
            assertFalse(base.resolve("generated-media-second/second-image.png").exists())
        } finally {
            firstStore.clear()
            secondStore.clear()
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun startupCleanupNeverTraversesARecognizedSessionSymlink() = runTest {
        val base = Files.createTempDirectory("caraml-media-symlink-root")
        val outside = Files.createTempDirectory("caraml-media-symlink-outside")
        val outsideFile = outside.resolve("keep.png")
        outsideFile.writeBytes(byteArrayOf(9))
        val link = base.resolve("generated-media-abandoned")
        runCatching { link.createSymbolicLinkPointingTo(outside) }.getOrElse {
            Files.deleteIfExists(outsideFile)
            Files.deleteIfExists(outside)
            Files.deleteIfExists(base)
            return@runTest
        }
        val store = GeneratedMediaStore(
            baseDirectory = base.toString(),
            sessionId = "active",
            maxFileBytes = 4L,
            maxSessionBytes = 4L,
            maxGlobalBytes = 8L,
            maxAbandonedAgeMillis = 0L,
            clock = { 3_000L },
        )

        store.prepare()

        assertTrue(outsideFile.exists())
        store.clear()
        Files.deleteIfExists(link)
        Files.deleteIfExists(outsideFile)
        Files.deleteIfExists(outside)
        Files.deleteIfExists(base)
    }
}
