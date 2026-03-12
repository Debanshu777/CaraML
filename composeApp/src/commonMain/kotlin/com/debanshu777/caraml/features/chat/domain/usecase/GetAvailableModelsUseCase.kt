package com.debanshu777.caraml.features.chat.domain.usecase

import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelRepository
import com.debanshu777.caraml.features.chat.domain.ChatConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GetAvailableModelsUseCase(
    private val localModelRepository: LocalModelRepository,
    private val config: ChatConfig,
) {
    operator fun invoke(): Flow<List<LocalModelEntity>> =
        localModelRepository.getAllDownloadedFiles()
            .map { models -> models.take(config.topModelCount) }
}
