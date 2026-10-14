package com.debanshu777.huggingfacemanager.download

import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test

class DarwinArtifactDurabilityTest {
    @Test
    fun fileAndParentDirectoryCanBeDurablySynchronized() {
        val root = "${NSTemporaryDirectory().trimEnd('/')}/caraml-durability-${NSUUID().UUIDString}"
            .toPath(normalize = true)
        val fileSystem = FileSystem.SYSTEM
        fileSystem.createDirectories(root)
        try {
            fileSystem.write(root / "artifact") { writeUtf8("durable") }
            val durability = platformArtifactDurability(root)

            durability.syncFile("artifact")
            durability.syncDirectory(".")
        } finally {
            fileSystem.deleteRecursively(root, mustExist = false)
        }
    }
}
