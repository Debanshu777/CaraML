package com.debanshu777.caraml.features.chat.domain.usecase

import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelRepository
import com.debanshu777.caraml.features.chat.domain.ChatConfig
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GetAvailableModelsUseCase(
    private val localModelRepository: LocalModelRepository,
    private val config: ChatConfig,
) {
    operator fun invoke(): Flow<ImmutableList<LocalModelEntity>> =
        localModelRepository.getAllDownloadedFiles()
            .map { models -> models.take(config.topModelCount).toImmutableList() }
}
