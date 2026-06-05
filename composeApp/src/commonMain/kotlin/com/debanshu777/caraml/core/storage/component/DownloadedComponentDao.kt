package com.debanshu777.caraml.core.storage.component

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadedComponentDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertComponent(entity: DownloadedComponentEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLink(entity: ModelComponentLinkEntity)

    @Query("SELECT * FROM downloaded_component WHERE repo_id = :repoId AND file_path = :filePath LIMIT 1")
    suspend fun getByRepoAndPath(repoId: String, filePath: String): DownloadedComponentEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM downloaded_component WHERE repo_id = :repoId AND file_path = :filePath)")
    suspend fun isComponentDownloaded(repoId: String, filePath: String): Boolean

    @Query("SELECT * FROM downloaded_component")
    fun getAllComponents(): Flow<List<DownloadedComponentEntity>>

    @Query(
        """
        SELECT dc.* FROM downloaded_component dc
        INNER JOIN model_component_link mcl ON dc.id = mcl.component_id
        WHERE mcl.model_id = :modelId
        """
    )
    suspend fun getComponentsForModel(modelId: String): List<DownloadedComponentEntity>

    @Query("DELETE FROM downloaded_component WHERE repo_id = :repoId AND file_path = :filePath")
    suspend fun deleteByRepoAndPath(repoId: String, filePath: String)
}
