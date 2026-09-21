package com.debanshu777.caraml.core.storage.evidence

import com.debanshu777.caraml.core.recommendation.storage.EncodedModelEvidence
import com.debanshu777.caraml.core.recommendation.storage.InstalledEvidenceState
import com.debanshu777.caraml.core.recommendation.storage.PersistedModelEvidenceCodec

class InstalledModelEvidenceRepository(
    private val dao: InstalledModelEvidenceDao,
    private val codec: PersistedModelEvidenceCodec = PersistedModelEvidenceCodec(),
) {
    suspend fun get(modelId: String): EncodedModelEvidence? {
        validateModelId(modelId)
        return dao.get(modelId)?.toEncodedEvidence()
    }

    suspend fun put(modelId: String, evidence: EncodedModelEvidence, nowEpochMs: Long) {
        validateModelId(modelId)
        codec.decode(evidence)
        dao.upsert(evidence.toEntity(modelId, nowEpochMs))
    }

    suspend fun compareAndSet(
        modelId: String,
        expected: InstalledModelEvidenceEntity?,
        evidence: EncodedModelEvidence,
        nowEpochMs: Long,
    ): Boolean {
        validateModelId(modelId)
        require(expected == null || expected.modelId == modelId) { "Evidence owner mismatch" }
        codec.decode(evidence)
        return dao.compareAndSet(expected, evidence.toEntity(modelId, nowEpochMs))
    }

    private fun InstalledModelEvidenceEntity.toEncodedEvidence(): EncodedModelEvidence {
        val encoded = try {
            EncodedModelEvidence(
                state = InstalledEvidenceState.valueOf(evidenceState),
                schemaVersion = schemaVersion,
                payload = payload,
                sha256 = sha256,
            )
        } catch (cause: IllegalArgumentException) {
            throw IllegalStateException("Corrupt installed model evidence", cause)
        }
        try {
            codec.decode(encoded)
        } catch (cause: IllegalArgumentException) {
            throw IllegalStateException("Corrupt installed model evidence", cause)
        }
        return encoded
    }
}

internal fun EncodedModelEvidence.toEntity(
    modelId: String,
    publishedAtEpochMs: Long,
): InstalledModelEvidenceEntity {
    validateModelId(modelId)
    require(publishedAtEpochMs >= 0L) { "Invalid evidence publication time" }
    return InstalledModelEvidenceEntity(
        modelId = modelId,
        evidenceState = state.name,
        schemaVersion = schemaVersion,
        payload = payload,
        sha256 = sha256,
        publishedAtEpochMs = publishedAtEpochMs,
    )
}

private fun validateModelId(modelId: String) {
    require(
        modelId.isNotBlank() && modelId == modelId.trim() && modelId.length <= 193 &&
            modelId.none(Char::isISOControl),
    ) { "Invalid model id" }
}
