package com.debanshu777.caraml.core.performance

import kotlin.concurrent.Volatile

/** Numeric completion signal for opt-in debug UI journeys; no model names, paths, or prompts. */
data class ModelLoadMeasurement(
    val attemptId: Long,
    val modelRowId: Long,
    val elapsedMs: Double,
    val outcome: String,
    val contextLimit: Int?,
)

object ModelLoadDiagnostics {
    /** Null in ordinary execution. Diagnostic code must restore its previous observer. */
    @Volatile var observer: ((ModelLoadMeasurement) -> Unit)? = null

    /** Debug journeys can measure the original full-load path in the same APK; never persisted. */
    @Volatile var forceFullReloadForMeasurement: Boolean = false

    internal fun report(observer: ((ModelLoadMeasurement) -> Unit)?, measurement: ModelLoadMeasurement) {
        // A diagnostic listener must never change the app's loading outcome.
        if (observer != null) runCatching { observer(measurement) }
    }
}
