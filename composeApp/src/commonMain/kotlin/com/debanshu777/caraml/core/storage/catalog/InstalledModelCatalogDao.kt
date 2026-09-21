package com.debanshu777.caraml.core.storage.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.debanshu777.caraml.core.storage.component.DownloadedComponentEntity
import com.debanshu777.caraml.core.storage.component.ModelComponentLinkEntity
import com.debanshu777.caraml.core.storage.evidence.InstalledModelEvidenceEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity

data class InstalledCatalogRecord(
    val model: LocalModelEntity,
    val components: List<DownloadedComponentEntity>,
    val evidence: InstalledModelEvidenceEntity,
)

data class InstalledCatalogSnapshot(
    val model: LocalModelEntity,
    val components: List<DownloadedComponentEntity>,
    val evidence: InstalledModelEvidenceEntity?,
)

data class RemovedInstalledCatalog(
    val model: LocalModelEntity,
    /** Exact component rows whose final owner link was removed in the same transaction. */
    val unreferencedComponents: List<DownloadedComponentEntity>,
)

@Dao
interface InstalledModelCatalogDao {
    @Query(
        """
        SELECT * FROM local_model
        WHERE model_id = :modelId AND is_main_model = 1
        ORDER BY id DESC
        LIMIT 2
        """,
    )
    suspend fun snapshotModels(modelId: String): List<LocalModelEntity>

    @Query(
        """
        SELECT dc.id, dc.repo_id, dc.file_path, mcl.role AS role, dc.local_path,
            dc.size_bytes, dc.downloaded_at, dc.immutable_revision, dc.remote_object_id,
            dc.bundle_id, dc.content_sha256
        FROM downloaded_component dc
        INNER JOIN model_component_link mcl ON dc.id = mcl.component_id
        WHERE mcl.model_id = :modelId
        ORDER BY dc.repo_id, dc.immutable_revision, dc.file_path, dc.id
        LIMIT 65
        """,
    )
    suspend fun snapshotComponents(modelId: String): List<DownloadedComponentEntity>

    @Query("SELECT * FROM installed_model_evidence WHERE model_id = :modelId")
    suspend fun snapshotEvidence(modelId: String): InstalledModelEvidenceEntity?

    @Query("DELETE FROM model_component_link WHERE model_id = :modelId")
    suspend fun deleteLinks(modelId: String)

    @Query("DELETE FROM local_model WHERE model_id = :modelId")
    suspend fun deleteModels(modelId: String)

    @Query("DELETE FROM installed_model_evidence WHERE model_id = :modelId")
    suspend fun deleteEvidence(modelId: String)

    @Query("SELECT COUNT(*) FROM model_component_link WHERE component_id = :componentId")
    suspend fun countComponentLinks(componentId: Long): Long

    @Query("DELETE FROM downloaded_component WHERE id = :componentId")
    suspend fun deleteComponent(componentId: Long)

    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM local_model WHERE local_path = :localPath) +
            (SELECT COUNT(*) FROM downloaded_component dc
                INNER JOIN model_component_link mcl ON dc.id = mcl.component_id
                WHERE dc.local_path = :localPath
                  AND EXISTS (SELECT 1 FROM local_model lm WHERE lm.model_id = mcl.model_id))
        """,
    )
    suspend fun countCatalogStorageReferences(localPath: String): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertComponent(component: DownloadedComponentEntity): Long

    @Query(
        """
        SELECT id FROM downloaded_component
        WHERE repo_id = :repoId
          AND immutable_revision = :immutableRevision
          AND file_path = :filePath
          AND remote_object_id = :remoteObjectId
          AND bundle_id = :bundleId
          AND local_path = :localPath
        LIMIT 1
        """,
    )
    suspend fun findComponentId(
        repoId: String,
        immutableRevision: String,
        filePath: String,
        remoteObjectId: String,
        bundleId: String,
        localPath: String,
    ): Long?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLink(link: ModelComponentLinkEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertModel(model: LocalModelEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvidence(evidence: InstalledModelEvidenceEntity)

    @Transaction
    suspend fun snapshotReady(modelId: String): InstalledCatalogSnapshot? {
        val model = snapshotModels(modelId).singleOrNull()
            ?.takeIf { it.componentStatus == LocalModelEntity.STATUS_READY }
            ?: return null
        val components = snapshotComponents(modelId).takeIf { it.size <= MAX_SNAPSHOT_COMPONENTS }
            ?: return null
        return InstalledCatalogSnapshot(model, components, snapshotEvidence(modelId))
    }

    @Transaction
    suspend fun replaceReady(record: InstalledCatalogRecord) {
        val modelId = record.model.modelId
        require(record.model.componentStatus == LocalModelEntity.STATUS_READY) { "Catalog model is not ready" }
        require(record.evidence.modelId == modelId) { "Catalog evidence belongs to another model" }
        require(record.components.all(DownloadedComponentEntity::hasExactStorageIdentity)) {
            "Catalog component lacks exact identity"
        }
        require(record.components.distinctBy(DownloadedComponentEntity::exactStorageKey).size == record.components.size) {
            "Duplicate catalog component"
        }

        val previouslyLinked = snapshotComponents(modelId)
        deleteLinks(modelId)
        deleteModels(modelId)
        deleteEvidence(modelId)
        record.components.forEach { component ->
            val insertedId = insertComponent(component.copy(id = 0L))
            val componentId = if (insertedId != -1L) {
                insertedId
            } else {
                requireNotNull(
                    findComponentId(
                        component.repoId,
                        requireNotNull(component.immutableRevision),
                        component.filePath,
                        requireNotNull(component.remoteObjectId),
                        requireNotNull(component.bundleId),
                        component.localPath,
                    ),
                ) {
                    "Unable to resolve installed component"
                }
            }
            insertLink(
                ModelComponentLinkEntity(
                    modelId = modelId,
                    componentId = componentId,
                    role = component.role,
                ),
            )
        }
        insertModel(record.model.copy(id = 0L))
        insertEvidence(record.evidence)
        previouslyLinked.forEach { component ->
            if (countComponentLinks(component.id) == 0L) deleteComponent(component.id)
        }
    }

    /**
     * Removes only the exact Ready snapshot observed by the caller. A concurrent replacement makes
     * the value unequal and leaves the newer catalog untouched. Reference counts and orphan row
     * removal are evaluated within this Room transaction.
     */
    @Transaction
    suspend fun removeReadyIfMatches(expected: InstalledCatalogSnapshot): RemovedInstalledCatalog? {
        val current = snapshotReady(expected.model.modelId) ?: return null
        if (current != expected) return null
        deleteLinks(expected.model.modelId)
        deleteModels(expected.model.modelId)
        deleteEvidence(expected.model.modelId)
        val unreferenced = expected.components.filter { component ->
            countComponentLinks(component.id) == 0L
        }
        unreferenced.forEach { component -> deleteComponent(component.id) }
        return RemovedInstalledCatalog(expected.model, unreferenced)
    }

    private companion object {
        const val MAX_SNAPSHOT_COMPONENTS = 64
    }
}

private fun DownloadedComponentEntity.hasExactStorageIdentity(): Boolean =
    !immutableRevision.isNullOrBlank() && !remoteObjectId.isNullOrBlank() &&
        !bundleId.isNullOrBlank()

private fun DownloadedComponentEntity.exactStorageKey(): List<String> = listOf(
    repoId,
    requireNotNull(immutableRevision),
    filePath,
    requireNotNull(remoteObjectId),
    requireNotNull(bundleId),
    localPath,
)
