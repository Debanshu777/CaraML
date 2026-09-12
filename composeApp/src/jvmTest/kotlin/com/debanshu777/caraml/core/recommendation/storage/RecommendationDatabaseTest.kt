package com.debanshu777.caraml.core.recommendation.storage

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.recommendation.CalibrationKey
import com.debanshu777.caraml.core.recommendation.MemoryPool
import com.debanshu777.caraml.core.recommendation.MetricKind
import com.debanshu777.caraml.core.recommendation.ObservationOutcome
import com.debanshu777.caraml.core.storage.getDatabaseBuilder
import com.debanshu777.caraml.core.storage.getRoomDatabase
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RecommendationDatabaseTest {
    @Test
    fun recommendationRowsAreStoredAndPrunedInTheirOwnDatabase() = runTest {
        val directory = Files.createTempDirectory("caraml-recommendation-db")
        val appPath = directory.resolve("caraml.db")
        val recommendationPath = directory.resolve("recommendation_cache.db")
        val appDatabase = getRoomDatabase(getDatabaseBuilder(appPath.toString()))
        val recommendationDatabase = getRecommendationRoomDatabase(
            getRecommendationDatabaseBuilder(recommendationPath.toString()),
        )
        try {
            appDatabase.localModelDao().insert(
                LocalModelEntity(
                    modelId = "owner/model",
                    filename = "model.gguf",
                    localPath = "/private/model.gguf",
                    sizeBytes = 1L,
                    downloadedAt = 1L,
                    author = null,
                    libraryName = null,
                    pipelineTag = null,
                ),
            )
            val key = CalibrationKey(
                backend = BackendKind.CPU,
                architectureFamily = "llama",
                quantizationFamily = "q4_k",
                workloadBucket = "ctx-4096",
                engineVersion = "native-engine-v1",
                metricKind = MetricKind.MEMORY,
                memoryPool = MemoryPool.HOST.stableName,
            )
            recommendationDatabase.observationDao().insertAndPrune(
                samples = (0 until 600).map { index ->
                    RecommendationObservationEntity.from(
                        key,
                        predictedValue = 100.0,
                        observedValue = 110.0,
                        outcome = ObservationOutcome.SUCCESS,
                        capturedAtEpochMs = index.toLong() + 1L,
                    )
                },
                cutoffEpochMs = 0L,
                limit = 500,
            )

            assertEquals(500, recommendationDatabase.observationDao().count())
            assertEquals(1, appDatabase.localModelDao().getAllDownloadedFiles().first().size)
            assertFalse(appPath.toString() == recommendationPath.toString())
        } finally {
            recommendationDatabase.close()
            appDatabase.close()
        }
    }

    @Test
    fun deletingDisposableRecommendationDatabaseLeavesDownloadedModelsIntact() = runTest {
        val directory = Files.createTempDirectory("caraml-recommendation-isolation")
        val appPath = directory.resolve("caraml.db")
        val recommendationPath = directory.resolve("recommendation_cache.db")
        val appDatabase = getRoomDatabase(getDatabaseBuilder(appPath.toString()))
        var recommendationDatabase = getRecommendationRoomDatabase(
            getRecommendationDatabaseBuilder(recommendationPath.toString()),
        )
        appDatabase.localModelDao().insert(
            LocalModelEntity(
                modelId = "owner/model",
                filename = "model.gguf",
                localPath = "/private/model.gguf",
                sizeBytes = 1L,
                downloadedAt = 1L,
                author = null,
                libraryName = null,
                pipelineTag = null,
            ),
        )
        recommendationDatabase.observationDao().clearAll()
        recommendationDatabase.close()
        Files.deleteIfExists(recommendationPath)
        recommendationDatabase = getRecommendationRoomDatabase(
            getRecommendationDatabaseBuilder(recommendationPath.toString()),
        )
        try {
            assertEquals(0, recommendationDatabase.observationDao().count())
            assertEquals("owner/model", appDatabase.localModelDao().getAllDownloadedFiles().first().single().modelId)
        } finally {
            recommendationDatabase.close()
            appDatabase.close()
        }
    }
}
