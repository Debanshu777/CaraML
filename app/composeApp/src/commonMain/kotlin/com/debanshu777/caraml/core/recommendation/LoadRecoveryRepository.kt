package com.debanshu777.caraml.core.recommendation

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import okio.Buffer

data class PendingLoadMarker(
    val markerId: String,
    val modelDigest: String,
    val configDigest: String,
    val engineVersion: String,
    val startedAtEpochMs: Long,
)

data class SuspectedLoadFailure(
    val modelDigest: String,
    val configDigest: String,
    val engineVersion: String,
    val detectedAtEpochMs: Long,
    val occurrenceCount: Int,
)

enum class StableLoadFailure {
    ALLOCATION,
    UNSUPPORTED_CONFIGURATION,
    INVALID_MODEL,
    UNKNOWN,
}

class PendingLoadMarkerExistsException : Exception("A prior load marker must be recovered at startup")

class LoadRecoveryRepository(
    private val dataStore: DataStore<Preferences>,
    private val engineVersion: String,
    private val clock: () -> Long,
) : LoadRecoveryState {
    init {
        require(isValidEngineVersion(engineVersion))
    }

    suspend fun beginLoad(identity: ModelFileIdentity, plan: RunPlan): PendingLoadMarker {
        require(identity.hasValidExactIdentity()) { "Invalid model identity" }
        require(validateRunPlan(plan) == null) { "Invalid execution configuration" }
        val startedAt = clock()
        require(startedAt >= 0L)
        val modelDigest = digestIdentity(identity)
        val configDigest = digestPlan(plan)
        val marker = PendingLoadMarker(
            markerId = digestStrings(modelDigest, configDigest, engineVersion, startedAt.toString()),
            modelDigest = modelDigest,
            configDigest = configDigest,
            engineVersion = engineVersion,
            startedAtEpochMs = startedAt,
        )
        dataStore.edit { preferences ->
            if (preferences[PENDING_KEY] != null) {
                throw PendingLoadMarkerExistsException()
            }
            preferences[PENDING_KEY] = marker.encode()
        }
        return marker
    }

    suspend fun markLoadSucceeded(marker: PendingLoadMarker) {
        transition(marker) { preferences, now ->
            val records = preferences.readRecords(now).filterNot { it.matches(marker) }
            preferences.writeRecords(records, now)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun markLoadFailed(marker: PendingLoadMarker, reason: StableLoadFailure) {
        transition(marker)
    }

    suspend fun markLoadCancelled(marker: PendingLoadMarker) {
        transition(marker)
    }

    suspend fun recordSuspectedFailure(marker: PendingLoadMarker) {
        transition(marker) { preferences, now ->
            val records = preferences.readRecords(now).toMutableList()
            records.record(marker, now)
            preferences.writeRecords(records, now)
        }
    }

    suspend fun recoverPendingLoad(nowEpochMs: Long = clock()): SuspectedLoadFailure? {
        require(nowEpochMs >= 0L)
        var recovered: SuspectedLoadFailure? = null
        dataStore.edit { preferences ->
            val pending = preferences[PENDING_KEY]?.decodeMarker()
            preferences.remove(PENDING_KEY)
            val records = preferences.readRecords(nowEpochMs).toMutableList()
            if (pending != null && pending.isWithinWindow(nowEpochMs)) {
                val record = records.record(pending, nowEpochMs)
                recovered = record.toSuspectedFailure()
            }
            preferences.writeRecords(records, nowEpochMs)
        }
        return recovered
    }

    override suspend fun quarantine(
        identity: ModelFileIdentity,
        plan: RunPlan,
        engineVersion: String,
    ): LoadQuarantine {
        if (!identity.hasValidExactIdentity() || validateRunPlan(plan) != null) return LoadQuarantine.NONE
        return quarantine(digestIdentity(identity), digestPlan(plan), engineVersion)
    }

    suspend fun quarantine(
        modelDigest: String,
        configDigest: String,
        engineVersion: String,
    ): LoadQuarantine {
        if (!modelDigest.isSha256() || !configDigest.isSha256() || !isValidEngineVersion(engineVersion)) {
            return LoadQuarantine.NONE
        }
        val now = clock()
        var count = 0
        dataStore.edit { preferences ->
            val records = preferences.readRecords(now)
            preferences.writeRecords(records, now)
            count = records.firstOrNull {
                it.modelDigest == modelDigest &&
                    it.configDigest == configDigest &&
                    it.engineVersion == engineVersion
            }?.occurrenceCount ?: 0
        }
        return when {
            count >= KNOWN_UNSTABLE_THRESHOLD -> LoadQuarantine.KNOWN_UNSTABLE
            count == 1 -> LoadQuarantine.TEMPORARY
            else -> LoadQuarantine.NONE
        }
    }

    override suspend fun allowExplicitRetry(
        identity: ModelFileIdentity,
        plan: RunPlan,
        engineVersion: String,
    ) {
        if (!identity.hasValidExactIdentity() || validateRunPlan(plan) != null || !isValidEngineVersion(engineVersion)) return
        val modelDigest = digestIdentity(identity)
        val configDigest = digestPlan(plan)
        val now = clock()
        dataStore.edit { preferences ->
            preferences.writeRecords(
                preferences.readRecords(now).filterNot {
                    it.modelDigest == modelDigest &&
                        it.configDigest == configDigest &&
                        it.engineVersion == engineVersion
                },
                now,
            )
        }
    }

    internal suspend fun recoveryRecordCount(): Int =
        dataStore.data.first().readRecords(clock()).size

    private suspend fun transition(
        marker: PendingLoadMarker,
        onMatchingMarker: (androidx.datastore.preferences.core.MutablePreferences, Long) -> Unit = { _, _ -> },
    ) {
        dataStore.edit { preferences ->
            val current = preferences[PENDING_KEY]?.decodeMarker()
            if (current?.markerId == marker.markerId) {
                preferences.remove(PENDING_KEY)
                onMatchingMarker(preferences, clock())
            }
        }
    }

    private fun MutableList<RecoveryRecord>.record(
        marker: PendingLoadMarker,
        detectedAt: Long,
    ): RecoveryRecord {
        val index = indexOfFirst { it.matches(marker) }
        val previousCount = if (index >= 0) removeAt(index).occurrenceCount else 0
        val updated = RecoveryRecord(
            modelDigest = marker.modelDigest,
            configDigest = marker.configDigest,
            engineVersion = marker.engineVersion,
            occurrenceCount = (previousCount + 1).coerceAtMost(KNOWN_UNSTABLE_THRESHOLD),
            lastDetectedAtEpochMs = detectedAt,
        )
        add(updated)
        return updated
    }

    private fun Preferences.readRecords(now: Long): List<RecoveryRecord> {
        val serialized = this[HISTORY_KEY] ?: return emptyList()
        if (serialized.length > MAX_SERIALIZED_STATE_LENGTH) return emptyList()
        return serialized.lineSequence()
            .take(MAX_RECOVERY_RECORDS + 1)
            .mapNotNull(RecoveryRecord::decode)
            .filter { it.isWithinWindow(now) }
            .distinctBy { Triple(it.modelDigest, it.configDigest, it.engineVersion) }
            .sortedByDescending(RecoveryRecord::lastDetectedAtEpochMs)
            .take(MAX_RECOVERY_RECORDS)
            .toList()
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.writeRecords(
        records: List<RecoveryRecord>,
        now: Long,
    ) {
        val value = records.asSequence()
            .filter { it.isWithinWindow(now) }
            .sortedByDescending(RecoveryRecord::lastDetectedAtEpochMs)
            .take(MAX_RECOVERY_RECORDS)
            .joinToString("\n", transform = RecoveryRecord::encode)
        if (value.isEmpty()) remove(HISTORY_KEY) else this[HISTORY_KEY] = value
    }

    private data class RecoveryRecord(
        val modelDigest: String,
        val configDigest: String,
        val engineVersion: String,
        val occurrenceCount: Int,
        val lastDetectedAtEpochMs: Long,
    ) {
        fun matches(marker: PendingLoadMarker): Boolean =
            modelDigest == marker.modelDigest &&
                configDigest == marker.configDigest &&
                engineVersion == marker.engineVersion

        fun isWithinWindow(now: Long): Boolean =
            lastDetectedAtEpochMs in (now - RECOVERY_WINDOW_MS)..safeWindowEnd(now)

        fun encode(): String = listOf(
            modelDigest,
            configDigest,
            engineVersion,
            occurrenceCount.toString(),
            lastDetectedAtEpochMs.toString(),
        ).joinToString(FIELD_SEPARATOR)

        fun toSuspectedFailure() = SuspectedLoadFailure(
            modelDigest,
            configDigest,
            engineVersion,
            lastDetectedAtEpochMs,
            occurrenceCount,
        )

        companion object {
            fun decode(value: String): RecoveryRecord? {
                if (value.length > MAX_RECORD_LENGTH) return null
                val fields = value.split(FIELD_SEPARATOR, limit = 5)
                if (fields.size != 5) return null
                val count = fields[3].toIntOrNull() ?: return null
                val timestamp = fields[4].toLongOrNull() ?: return null
                return RecoveryRecord(fields[0], fields[1], fields[2], count, timestamp).takeIf {
                    it.modelDigest.isSha256() &&
                        it.configDigest.isSha256() &&
                        isValidEngineVersion(it.engineVersion) &&
                        it.occurrenceCount in 1..KNOWN_UNSTABLE_THRESHOLD &&
                        it.lastDetectedAtEpochMs >= 0L
                }
            }
        }
    }

    private fun PendingLoadMarker.encode(): String = listOf(
        markerId,
        modelDigest,
        configDigest,
        engineVersion,
        startedAtEpochMs.toString(),
    ).joinToString(FIELD_SEPARATOR)

    private fun String.decodeMarker(): PendingLoadMarker? {
        if (length > MAX_RECORD_LENGTH) return null
        val fields = split(FIELD_SEPARATOR, limit = 5)
        if (fields.size != 5) return null
        val timestamp = fields[4].toLongOrNull() ?: return null
        return PendingLoadMarker(fields[0], fields[1], fields[2], fields[3], timestamp).takeIf {
            it.markerId.isSha256() &&
                it.modelDigest.isSha256() &&
                it.configDigest.isSha256() &&
                isValidEngineVersion(it.engineVersion) &&
                it.startedAtEpochMs >= 0L
        }
    }

    private fun PendingLoadMarker.isWithinWindow(now: Long): Boolean =
        startedAtEpochMs in (now - RECOVERY_WINDOW_MS)..safeWindowEnd(now)

    companion object {
        const val RECOVERY_WINDOW_MS: Long = 7L * 24 * 60 * 60 * 1_000
        const val MAX_RECOVERY_RECORDS: Int = 64

        private const val KNOWN_UNSTABLE_THRESHOLD = 2
        private const val MAX_ENGINE_VERSION_LENGTH = 128
        private const val MAX_RECORD_LENGTH = 512
        private const val MAX_SERIALIZED_STATE_LENGTH = MAX_RECOVERY_RECORDS * (MAX_RECORD_LENGTH + 1)
        private const val FIELD_SEPARATOR = "|"
        private val PENDING_KEY = stringPreferencesKey("load_recovery_pending_v1")
        private val HISTORY_KEY = stringPreferencesKey("load_recovery_history_v1")

        private fun isValidEngineVersion(value: String): Boolean =
            value.isNotBlank() &&
                value.length <= MAX_ENGINE_VERSION_LENGTH &&
                value.all { it.isLetterOrDigit() || it in "._-+" }

        private fun String.isSha256(): Boolean =
            length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

        private fun safeWindowEnd(now: Long): Long =
            if (now > Long.MAX_VALUE - RECOVERY_WINDOW_MS) Long.MAX_VALUE else now + RECOVERY_WINDOW_MS

        private fun digestIdentity(identity: ModelFileIdentity): String = digestStrings(
            "model-v1",
            identity.repositoryId,
            identity.revision.lowercase(),
            identity.path,
            identity.sizeBytes.toString(),
            identity.gitOid.orEmpty(),
            identity.lfsOid.orEmpty(),
            identity.xetHash.orEmpty(),
        )

        private fun digestPlan(plan: RunPlan): String = digestStrings("config-v1", plan.stableKey)

        private fun digestStrings(vararg fields: String): String {
            val buffer = Buffer()
            fields.forEach { field ->
                val bytes = field.encodeToByteArray()
                buffer.writeInt(bytes.size)
                buffer.write(bytes)
            }
            return buffer.snapshot().sha256().hex()
        }
    }
}
