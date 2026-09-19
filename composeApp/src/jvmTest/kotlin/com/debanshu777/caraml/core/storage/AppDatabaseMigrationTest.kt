package com.debanshu777.caraml.core.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.debanshu777.caraml.core.download.pendingEvidence
import com.debanshu777.caraml.core.storage.catalog.InstalledCatalogRecord
import com.debanshu777.caraml.core.storage.component.DownloadedComponentEntity
import com.debanshu777.caraml.core.storage.evidence.InstalledModelEvidenceEntity
import com.debanshu777.caraml.core.storage.evidence.InstalledModelEvidenceRepository
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppDatabaseMigrationTest {
    @Test
    fun migrationThreeToFourPreservesLocalModelsAndCreatesEmptyEvidenceTable() {
        val path = Files.createTempDirectory("caraml-app-migration").resolve("caraml.db").toString()
        BundledSQLiteDriver().open(path).use { connection ->
            createVersionThreeLocalModelTable(connection)
            connection.execSQL(
                """
                INSERT INTO local_model (
                    id, model_id, filename, local_path, size_bytes, downloaded_at,
                    author, library_name, pipeline_tag, usage_count, context_length,
                    model_type, component_status, is_main_model, arch
                ) VALUES (
                    7, 'owner/model', 'model.gguf', '/models/owner/model/model.gguf', 1024, 1,
                    'owner', 'gguf', 'text-generation', 3, 4096,
                    'text', 'ready', 1, 'llama'
                )
                """.trimIndent(),
            )

            APP_MIGRATION_3_4.migrate(connection)

            connection.prepare("SELECT model_id, filename FROM local_model WHERE id = 7").use { statement ->
                assertTrue(statement.step())
                assertEquals("owner/model", statement.getText(0))
                assertEquals("model.gguf", statement.getText(1))
            }
            connection.prepare("SELECT COUNT(*) FROM installed_model_evidence").use { statement ->
                assertTrue(statement.step())
                assertEquals(0L, statement.getLong(0))
                assertFalse(statement.step())
            }
        }
    }

    @Test
    fun catalogReplacementPersistsReadyModelComponentsAndEvidenceTogether() = runTest {
        val path = Files.createTempDirectory("caraml-catalog-publication").resolve("caraml.db").toString()
        val database = getRoomDatabase(getDatabaseBuilder(path))
        val record = catalogRecord("v1", publishedAtEpochMs = 10L)
        try {
            database.installedModelCatalogDao().replaceReady(record)

            val model = database.localModelDao().getAllDownloadedFiles().first().single()
            val component = database.downloadedComponentDao().getComponentsForModel(MODEL_ID).single()
            val evidence = InstalledModelEvidenceRepository(database.installedModelEvidenceDao()).get(MODEL_ID)
            assertEquals("model-v1.gguf", model.filename)
            assertEquals(LocalModelEntity.STATUS_READY, model.componentStatus)
            assertEquals("clip-v1.gguf", component.filePath)
            assertEquals(record.evidence.encoded(), evidence)
        } finally {
            database.close()
        }
    }

    @Test
    fun evidenceInsertFailureRollsBackCatalogReplacement() = runTest {
        val path = Files.createTempDirectory("caraml-catalog-rollback").resolve("caraml.db").toString()
        var database = getRoomDatabase(getDatabaseBuilder(path))
        val original = catalogRecord("original", publishedAtEpochMs = 1L)
        database.installedModelCatalogDao().replaceReady(original)
        database.close()

        BundledSQLiteDriver().open(path).use { connection ->
            connection.execSQL(
                """
                CREATE TRIGGER reject_replacement_evidence
                BEFORE INSERT ON installed_model_evidence
                WHEN NEW.published_at_epoch_ms = 2
                BEGIN
                    SELECT RAISE(ABORT, 'simulated evidence write failure');
                END
                """.trimIndent(),
            )
        }

        database = getRoomDatabase(getDatabaseBuilder(path))
        try {
            assertFails {
                database.installedModelCatalogDao().replaceReady(
                    catalogRecord("replacement", publishedAtEpochMs = 2L),
                )
            }

            val model = database.localModelDao().getAllDownloadedFiles().first().single()
            val component = database.downloadedComponentDao().getComponentsForModel(MODEL_ID).single()
            val evidence = database.installedModelEvidenceDao().get(MODEL_ID)
            assertEquals("model-original.gguf", model.filename)
            assertEquals("clip-original.gguf", component.filePath)
            assertEquals(original.evidence, evidence)
        } finally {
            database.close()
        }
    }
}

private const val MODEL_ID = "owner/model"

private fun catalogRecord(suffix: String, publishedAtEpochMs: Long): InstalledCatalogRecord {
    val evidence = pendingEvidence(
        listOf(
            identity(MODEL_ID, "model-$suffix.gguf", "a".repeat(40), "b".repeat(64)),
            identity("owner/component", "clip-$suffix.gguf", "c".repeat(40), "d".repeat(64)),
        ),
    )
    return InstalledCatalogRecord(
        model = LocalModelEntity(
            modelId = MODEL_ID,
            filename = "model-$suffix.gguf",
            localPath = "/models/owner/model/model-$suffix.gguf",
            sizeBytes = 10L,
            downloadedAt = publishedAtEpochMs,
            author = "owner",
            libraryName = "gguf",
            pipelineTag = "text-generation",
            modelType = "text",
            componentStatus = LocalModelEntity.STATUS_READY,
        ),
        components = listOf(
            DownloadedComponentEntity(
                repoId = "owner/component",
                filePath = "clip-$suffix.gguf",
                role = "clip",
                localPath = "/models/owner/component/clip-$suffix.gguf",
                sizeBytes = 5L,
                downloadedAt = publishedAtEpochMs,
            ),
        ),
        evidence = InstalledModelEvidenceEntity(
            modelId = MODEL_ID,
            evidenceState = evidence.state.name,
            schemaVersion = evidence.schemaVersion,
            payload = evidence.payload,
            sha256 = evidence.sha256,
            publishedAtEpochMs = publishedAtEpochMs,
        ),
    )
}

private fun InstalledModelEvidenceEntity.encoded() =
    com.debanshu777.caraml.core.recommendation.storage.EncodedModelEvidence(
        state = com.debanshu777.caraml.core.recommendation.storage.InstalledEvidenceState.valueOf(evidenceState),
        schemaVersion = schemaVersion,
        payload = payload,
        sha256 = sha256,
    )

private fun identity(
    repositoryId: String,
    path: String,
    revision: String,
    objectId: String,
): DownloadArtifactIdentity = requireNotNull(
    DownloadArtifactIdentity.create(repositoryId, revision, path, objectId, 10L),
)

private fun createVersionThreeLocalModelTable(connection: SQLiteConnection) {
    connection.execSQL(
        """
        CREATE TABLE local_model (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            model_id TEXT NOT NULL,
            filename TEXT NOT NULL,
            local_path TEXT NOT NULL,
            size_bytes INTEGER,
            downloaded_at INTEGER NOT NULL,
            author TEXT,
            library_name TEXT,
            pipeline_tag TEXT,
            usage_count INTEGER NOT NULL DEFAULT 0,
            context_length INTEGER,
            model_type TEXT,
            component_status TEXT,
            is_main_model INTEGER NOT NULL DEFAULT 1,
            arch TEXT
        )
        """.trimIndent(),
    )
}
