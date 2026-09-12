package com.debanshu777.caraml.core.recommendation.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.debanshu777.caraml.core.recommendation.CalibrationKey
import com.debanshu777.caraml.core.recommendation.ObservationOutcome

@Entity(
    tableName = "recommendation_observation",
    indices = [
        Index(
            value = [
                "engineVersion",
                "estimatorVersion",
                "backend",
                "architectureFamily",
                "quantFamily",
                "workloadBucket",
                "metricKind",
                "memoryPool",
            ],
        ),
        Index(value = ["capturedAtEpochMs"]),
    ],
)
data class RecommendationObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val engineVersion: String,
    val estimatorVersion: Int,
    val backend: String,
    val architectureFamily: String,
    val quantFamily: String,
    val workloadBucket: String,
    val metricKind: String,
    val memoryPool: String?,
    val predictedValue: Double,
    val observedValue: Double,
    val completedUnits: Long?,
    val elapsedNanoseconds: Long?,
    val outcome: String,
    val capturedAtEpochMs: Long,
    val similarity: Double = 1.0,
) {
    companion object {
        fun from(
            key: CalibrationKey,
            predictedValue: Double,
            observedValue: Double,
            completedUnits: Long? = null,
            elapsedNanoseconds: Long? = null,
            outcome: ObservationOutcome,
            capturedAtEpochMs: Long,
            similarity: Double = 1.0,
        ) = RecommendationObservationEntity(
            engineVersion = key.engineVersion,
            estimatorVersion = key.estimatorVersion,
            backend = key.backend.name,
            architectureFamily = key.architectureFamily,
            quantFamily = key.quantizationFamily,
            workloadBucket = key.workloadBucket,
            metricKind = key.metricKind.name,
            memoryPool = key.memoryPool,
            predictedValue = predictedValue,
            observedValue = observedValue,
            completedUnits = completedUnits,
            elapsedNanoseconds = elapsedNanoseconds,
            outcome = outcome.name,
            capturedAtEpochMs = capturedAtEpochMs,
            similarity = similarity,
        )
    }
}
