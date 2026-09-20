package com.debanshu777.huggingfacemanager.download

import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosCompletedDownloadImporterTest {
    @Test
    fun quarantinedManifestFailsBeforeMutatingTheExistingCheckpoint() = runTest {
        val fileSystem = FakeFileSystem()
        val modelsRoot = "/models".toPath()
        val provider = ImportTestStoragePathProvider(modelsRoot.toString())
        val identity = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = "org/model",
                immutableRevision = "a".repeat(40),
                relativePath = "model.gguf",
                remoteObjectId = null,
                expectedBytes = 11L,
            ),
        )
        val metadata = DownloadMetadataDTO(
            artifact = identity,
            logicalRole = "model",
            sizeBytes = identity.expectedBytes,
            author = null,
            libraryName = null,
            pipelineTag = null,
        )
        val modelRoot = provider.getModelsStorageDirectory(identity.repositoryId).toPath(normalize = true)
        val target = modelRoot / metadata.destinationRelativePath
        val staged = "$target.part".toPath()
        val journal = modelRoot / ArtifactManifestStore.JOURNAL_FILE_NAME
        val temporary = "${NSTemporaryDirectory().trimEnd('/')}/caraml-import-${NSUUID().UUIDString}.tmp"
            .toPath(normalize = true)
        val checkpointBytes = "keep-checkpoint".encodeToByteArray()
        val journalBytes = "tampered-journal".encodeToByteArray()
        val responseBytes = "new-payload".encodeToByteArray()
        check(responseBytes.size.toLong() == identity.expectedBytes)

        fileSystem.createDirectories(requireNotNull(staged.parent))
        fileSystem.write(staged) { write(checkpointBytes) }
        fileSystem.write(journal) { write(journalBytes) }
        FileSystem.SYSTEM.write(temporary) { write(responseBytes) }

        val importer = IosCompletedDownloadImporter(
            pathProvider = provider,
            manifestStoreFactory = { root -> ArtifactManifestStore(root, fileSystem) },
        )

        try {
            assertFailsWith<ArtifactVerificationException> {
                importer.import(
                    modelId = identity.repositoryId,
                    path = identity.relativePath,
                    metadata = metadata,
                    temporaryFilePath = temporary.toString(),
                    finalResponseUrl = "https://huggingface.co/org/model/resolve/main/model.gguf",
                    statusCode = 200,
                )
            }

            assertTrue(fileSystem.exists(staged), "quarantined recovery must preserve the checkpoint")
            assertContentEquals(checkpointBytes, fileSystem.read(staged) { readByteArray() })
            assertContentEquals(journalBytes, fileSystem.read(journal) { readByteArray() })
            assertContentEquals(responseBytes, FileSystem.SYSTEM.read(temporary) { readByteArray() })
            assertFalse(fileSystem.exists(target))
            assertFalse(fileSystem.exists(modelRoot / ArtifactManifestStore.MANIFEST_FILE_NAME))
        } finally {
            FileSystem.SYSTEM.delete(temporary, mustExist = false)
        }
    }
}

private class ImportTestStoragePathProvider(
    private val modelsRoot: String,
) : StoragePathProvider {
    override fun getModelsStorageDirectory(modelId: String): String = "$modelsRoot/${validateModelId(modelId)}"

    override fun getDatabasePath(): String = "$modelsRoot/test.db"

    override fun fileExists(path: String): Boolean = false

    override fun getAvailableStorageBytes(): Long = Long.MAX_VALUE

    override fun getTotalStorageBytes(): Long = Long.MAX_VALUE

    override fun isModelFileReadable(path: String): Boolean = fileExists(path)

    override fun isDirectoryReadable(path: String): Boolean = false

    override fun getFileSize(path: String): Long = 0L

    override fun renameFile(from: String, to: String): Boolean = false

    override fun deleteDownloadedModelContent(modelId: String, localPath: String): Boolean = false
}
