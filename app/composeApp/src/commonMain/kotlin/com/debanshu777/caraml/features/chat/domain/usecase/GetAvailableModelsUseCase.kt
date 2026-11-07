package com.debanshu777.caraml.features.chat.domain.usecase

import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelRepository
import com.debanshu777.caraml.features.chat.domain.ChatConfig
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.chat.domain.filterForMode
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
            .map { models ->
                // Frequent chat models must not crowd image/video models out of
                // the inventory before the workspace applies its mode filter.
                val available = GenerationMode.entries.flatMap { mode ->
                    models.filterForMode(mode).take(config.topModelCount)
                }.toSet()
                models.filter { it in available }.toImmutableList()
            }
}
