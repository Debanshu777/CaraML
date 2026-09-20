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
        SELECT dc.* FROM downloaded_component dc
        INNER JOIN model_component_link mcl ON dc.id = mcl.component_id
        WHERE mcl.model_id = :modelId
        ORDER BY dc.repo_id, dc.file_path, dc.id
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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertComponent(component: DownloadedComponentEntity): Long

    @Query("SELECT id FROM downloaded_component WHERE repo_id = :repoId AND file_path = :filePath LIMIT 1")
    suspend fun findComponentId(repoId: String, filePath: String): Long?

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
        require(record.components.distinctBy { it.repoId to it.filePath }.size == record.components.size) {
            "Duplicate catalog component"
        }

        deleteLinks(modelId)
        deleteModels(modelId)
        deleteEvidence(modelId)
        record.components.forEach { component ->
            val insertedId = insertComponent(component.copy(id = 0L))
            val componentId = if (insertedId != -1L) {
                insertedId
            } else {
                requireNotNull(findComponentId(component.repoId, component.filePath)) {
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
    }

    private companion object {
        const val MAX_SNAPSHOT_COMPONENTS = 64
    }
}
