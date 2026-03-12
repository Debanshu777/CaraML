package com.debanshu777.caraml.features.chat.domain.usecase

import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelRepository

class TrackModelUsageUseCase(
    private val localModelRepository: LocalModelRepository,
) {
    suspend operator fun invoke(model: LocalModelEntity) {
        localModelRepository.incrementUsageCount(model.modelId, model.filename)
    }
}
