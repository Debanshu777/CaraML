package com.debanshu777.caraml.features.modelhub.presentation.details.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.recommendation.DiffusionModelDescriptor
import com.debanshu777.caraml.core.recommendation.LlmModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.rating.ui.RecommendationStatusChip
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.core.ui.components.CaraMLPane
import com.debanshu777.caraml.core.ui.components.CaraMLSectionHeader
import com.debanshu777.caraml.core.ui.components.CaraMLStatusPill
import com.debanshu777.caraml.core.ui.components.AuroraFocalSurface
import com.debanshu777.caraml.core.ui.components.StatusTone
import com.debanshu777.caraml.features.modelhub.presentation.details.modelDetailsUseSupportingPane
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.features.modelhub.domain.RecommendedModelUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.GgufFileUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.InstallBundleUiState
import com.debanshu777.caraml.core.download.DownloadBatchSnapshot
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.model.ModelDetailResponse
import com.debanshu777.huggingfacemanager.sdcpp.getModelSetup

@Composable
fun ModelDetailContent(
    model: ModelDetailResponse?,
    ggufFiles: List<GgufFileUiState>,
    isDownloading: Boolean,
    onDownloadClick: (String, String, DownloadMetadataDTO) -> Unit,
    activeDownloadArtifact: DownloadArtifactIdentity? = null,
    weightFilesHeading: String = "GGUF files",
    weightFilesEmptyLabel: String = "No GGUF files found",
    // Diffusion-specific
    installBundleState: InstallBundleUiState = InstallBundleUiState(),
    onVariantSelected: (String) -> Unit = {},
    onSmartInstall: () -> Unit = {},
    showInstallBundle: Boolean = false,
    recommendationState: RecommendedModelUiState? = null,
    onRecommendationInfoClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    windowWidth: Dp? = null,
    downloadBatches: List<DownloadBatchSnapshot> = emptyList(),
    onPauseDownload: (String) -> Unit = {},
    onResumeDownload: (String) -> Unit = {},
    onCancelDownload: (String) -> Unit = {},
    onRetryDownload: (String) -> Unit = {},
) {
    if (model == null) return

    val modelId = model.modelId ?: model.id ?: ""
    val modelSetup = if (modelId.isNotBlank()) getModelSetup(modelId) else null
    val recommendedVariant = recommendedVariantPath(
        recommendationState?.selectedDescriptor,
        installBundleState.variants,
    )
    val installEnabled = recommendedVariant != null &&
        installBundleState.selectedVariantPath == recommendedVariant
    val spacing = LocalSpacing.current

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (modelDetailsUseSupportingPane(windowWidth ?: maxWidth)) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = spacing.s),
                horizontalArrangement = Arrangement.spacedBy(spacing.xl),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(spacing.l),
                ) {
                    ModelOverviewSection(model, modelSetup?.description)
                    ModelMetadataSection(model)
                    if (!showInstallBundle) {
                        ModelFileVariantsSection(
                            model = model,
                            ggufFiles = ggufFiles,
                            isDownloading = isDownloading,
                            activeDownloadArtifact = activeDownloadArtifact,
                            onDownloadClick = onDownloadClick,
                            heading = weightFilesHeading,
                            emptyLabel = weightFilesEmptyLabel,
                            recommendationState = recommendationState,
                            downloadBatches = downloadBatches,
                            onPauseDownload = onPauseDownload,
                            onResumeDownload = onResumeDownload,
                            onCancelDownload = onCancelDownload,
                            onRetryDownload = onRetryDownload,
                        )
                    }
                }
                Column(
                    modifier = Modifier.width(340.dp),
                    verticalArrangement = Arrangement.spacedBy(spacing.l),
                ) {
                    ModelRecommendationSection(recommendationState, onRecommendationInfoClick)
                    if (showInstallBundle) {
                        InstallBundleCard(
                            modelId = modelId,
                            state = installBundleState,
                            familyLabel = modelSetup?.familyLabel,
                            modelDescription = null,
                            onVariantSelected = onVariantSelected,
                            onInstall = onSmartInstall,
                            modifier = Modifier.fillMaxWidth(),
                            recommendedVariantPath = recommendedVariant,
                            installEnabled = installEnabled,
                            durableBatch = downloadBatches.firstOrNull(),
                            onPause = { downloadBatches.firstOrNull()?.batchId?.let(onPauseDownload) },
                            onResume = { downloadBatches.firstOrNull()?.batchId?.let(onResumeDownload) },
                            onCancel = { downloadBatches.firstOrNull()?.batchId?.let(onCancelDownload) },
                            onRetry = { downloadBatches.firstOrNull()?.batchId?.let(onRetryDownload) },
                        )
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(top = spacing.s),
                    verticalArrangement = Arrangement.spacedBy(spacing.l),
                ) {
                    ModelOverviewSection(model, modelSetup?.description)
                    ModelMetadataSection(model)
                    ModelRecommendationSection(recommendationState, onRecommendationInfoClick)
                    if (showInstallBundle) {
                        InstallBundleSummaryCard(
                            state = installBundleState,
                            familyLabel = modelSetup?.familyLabel,
                            modelDescription = null,
                            onVariantSelected = onVariantSelected,
                            modifier = Modifier.fillMaxWidth(),
                            recommendedVariantPath = recommendedVariant,
                        )
                    } else {
                        ModelFileVariantsSection(
                            model = model,
                            ggufFiles = ggufFiles,
                            isDownloading = isDownloading,
                            activeDownloadArtifact = activeDownloadArtifact,
                            onDownloadClick = onDownloadClick,
                            heading = weightFilesHeading,
                            emptyLabel = weightFilesEmptyLabel,
                            recommendationState = recommendationState,
                            downloadBatches = downloadBatches,
                            onPauseDownload = onPauseDownload,
                            onResumeDownload = onResumeDownload,
                            onCancelDownload = onCancelDownload,
                            onRetryDownload = onRetryDownload,
                        )
                    }
                }
                if (showInstallBundle) {
                    InstallBundleActionFooter(
                        state = installBundleState,
                        modifier = Modifier.fillMaxWidth().padding(vertical = spacing.s),
                        onInstall = onSmartInstall,
                        installEnabled = installEnabled,
                        durableBatch = downloadBatches.firstOrNull(),
                        onPause = { downloadBatches.firstOrNull()?.batchId?.let(onPauseDownload) },
                        onResume = { downloadBatches.firstOrNull()?.batchId?.let(onResumeDownload) },
                        onCancel = { downloadBatches.firstOrNull()?.batchId?.let(onCancelDownload) },
                        onRetry = { downloadBatches.firstOrNull()?.batchId?.let(onRetryDownload) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelOverviewSection(model: ModelDetailResponse, description: String?) {
    val spacing = LocalSpacing.current
    val heading = splitRepositoryId(model.modelId ?: model.id.orEmpty())
    val owner = heading.owner ?: model.author?.trim()?.takeIf { it.isNotEmpty() }
    AuroraFocalSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    ) {
        CaraMLPane(
            modifier = Modifier.fillMaxWidth().padding(1.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                modifier = Modifier.padding(spacing.l),
                verticalArrangement = Arrangement.spacedBy(spacing.s),
            ) {
                CaraMLSectionHeader(title = "Overview")
                owner?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = heading.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (model.downloads != null || model.likes != null) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(spacing.l),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        model.downloads?.let { count ->
                            ModelMetric(Icons.Default.Download, "$count downloads")
                        }
                        model.likes?.let { count ->
                            ModelMetric(Icons.Default.FavoriteBorder, "$count likes")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelMetric(icon: ImageVector, label: String) {
    val spacing = LocalSpacing.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModelMetadataSection(model: ModelDetailResponse) {
    val tags = visibleModelTags(
        tags = model.tags.orEmpty(),
        pipelineTag = model.pipelineTag,
        libraryName = model.libraryName,
        modelType = model.config?.modelType,
    )
    val hasInfo = model.libraryName != null || model.pipelineTag != null ||
        model.config?.modelType != null ||
        model.config?.architectures?.filterNotNull()?.isNotEmpty() == true ||
        model.cardData?.license != null ||
        model.cardData?.baseModel?.takeIf { it.isNotEmpty() } != null ||
        model.createdAt != null || model.lastModified != null
    if (!hasInfo && tags.isEmpty()) return

    val spacing = LocalSpacing.current
    CaraMLPane(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(spacing.l),
            verticalArrangement = Arrangement.spacedBy(spacing.s),
        ) {
            CaraMLSectionHeader(title = "Metadata")
            DetailRow("Library", model.libraryName)
            DetailRow("Pipeline", model.pipelineTag)
            model.config?.let { config ->
                DetailRow("Model type", config.modelType)
                config.architectures?.filterNotNull()?.joinToString()?.let { arch ->
                    DetailRow("Architectures", arch)
                }
            }
            model.cardData?.let { card ->
                DetailRow("License", card.license)
                card.baseModel?.takeIf { it.isNotEmpty() }?.let { models ->
                    DetailRow("Base model", models.joinToString(", "))
                }
            }
            DetailRow("Created", formatHubTimestamp(model.createdAt))
            DetailRow("Last modified", formatHubTimestamp(model.lastModified))
            if (tags.isNotEmpty()) {
                var expanded by rememberSaveable(model.modelId, model.id) { mutableStateOf(false) }
                val displayedTags = if (expanded) tags else tags.take(4)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.s),
                    verticalArrangement = Arrangement.spacedBy(spacing.s),
                ) {
                    displayedTags.forEach { tag ->
                        CaraMLStatusPill(
                            label = tag,
                            contentDescription = "Tag: $tag",
                            tone = StatusTone.Neutral,
                        )
                    }
                }
                if (tags.size > 4) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.align(Alignment.Start),
                    ) {
                        Text(if (expanded) "Show less" else "Show all")
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRecommendationSection(
    recommendationState: RecommendedModelUiState?,
    onRecommendationInfoClick: (() -> Unit)?,
) {
    val spacing = LocalSpacing.current
    CaraMLPane(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(spacing.l),
            verticalArrangement = Arrangement.spacedBy(spacing.s),
        ) {
            CaraMLSectionHeader(
                title = "Device fit",
                supportingText = "Recommendation evidence for this device",
            )
            RecommendationStatusChip(
                state = recommendationState?.descriptorState ?: DescriptorState.NEEDS_INFORMATION,
                recommendation = recommendationState?.personalizedResult,
                onInfoClick = onRecommendationInfoClick,
            )
            recommendationState?.selectedVariantName?.let { variant ->
                CaraMLStatusPill(
                    label = variant,
                    contentDescription = "Selected variant: $variant",
                    tone = StatusTone.Accent,
                )
            }
        }
    }
}

@Composable
private fun ModelFileVariantsSection(
    model: ModelDetailResponse,
    ggufFiles: List<GgufFileUiState>,
    isDownloading: Boolean,
    activeDownloadArtifact: DownloadArtifactIdentity?,
    onDownloadClick: (String, String, DownloadMetadataDTO) -> Unit,
    heading: String,
    emptyLabel: String,
    recommendationState: RecommendedModelUiState?,
    downloadBatches: List<DownloadBatchSnapshot>,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    CaraMLPane(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(spacing.l),
            verticalArrangement = Arrangement.spacedBy(spacing.s),
        ) {
            CaraMLSectionHeader(
                title = heading,
                supportingText = "Choose a model weight to download",
            )
            if (ggufFiles.isEmpty()) {
                Text(
                    text = emptyLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ggufFiles.forEach { item ->
                    val durableTask = downloadBatches.asSequence()
                        .flatMap { batch -> batch.artifacts.asSequence().map { batch.batchId to it } }
                        .firstOrNull { (_, artifact) -> artifact.request.metadata.artifact == item.artifact }
                    val hasExactArtifact = item.artifact != null
                    val needsInformationOnly = recommendationState == null ||
                        recommendationState.descriptorState == DescriptorState.NEEDS_INFORMATION
                    val matchesSelectedDescriptor = item.artifact?.let { artifact ->
                        artifactMatches(recommendationState?.selectedDescriptor, artifact)
                    } == true
                    val isActiveDownload = isDownloading &&
                        activeDownloadArtifact != null &&
                        item.artifact == activeDownloadArtifact
                    GgufFileListItem(
                        filename = item.path.ifEmpty { item.filename },
                        sizeBytes = item.sizeBytes,
                        isDownloaded = item.isDownloaded,
                        progress = item.progress,
                        isDownloading = isActiveDownload,
                        onDownloadClick = {
                            val artifact = item.artifact ?: return@GgufFileListItem
                            onDownloadClick(
                                model.modelId ?: model.id ?: "",
                                item.path,
                                DownloadMetadataDTO(
                                    artifact = artifact,
                                    logicalRole = "model",
                                    sizeBytes = artifact.expectedBytes,
                                    author = model.author,
                                    libraryName = model.libraryName,
                                    pipelineTag = model.pipelineTag,
                                    contextLength = model.gguf?.contextLength,
                                ),
                            )
                        },
                        downloadEnabled = hasExactArtifact && (needsInformationOnly || matchesSelectedDescriptor),
                        interactionLocked = isDownloading && downloadBatches.isEmpty(),
                        durableState = durableTask?.second?.state,
                        onPause = { durableTask?.first?.let(onPauseDownload) },
                        onResume = { durableTask?.first?.let(onResumeDownload) },
                        onCancel = { durableTask?.first?.let(onCancelDownload) },
                        onRetry = { durableTask?.first?.let(onRetryDownload) },
                    )
                }
            }
        }
    }
}

private fun descriptorFiles(descriptor: ModelDescriptor?): List<ModelFileIdentity> = when (descriptor) {
    is LlmModelDescriptor -> descriptor.files
    is DiffusionModelDescriptor -> descriptor.components
        .filter { it.required || it.isPrimary }
        .map { it.file }
    null -> emptyList()
}

private fun artifactMatches(
    descriptor: ModelDescriptor?,
    artifact: DownloadArtifactIdentity,
): Boolean = descriptorFiles(descriptor).singleOrNull { file ->
    val remoteObjectId = file.lfsOid?.let { "sha256:$it" } ?: file.xetHash ?: file.gitOid
    file.repositoryId == artifact.repositoryId &&
        file.revision.lowercase() == artifact.immutableRevision &&
        file.path == artifact.relativePath &&
        file.sizeBytes == artifact.expectedBytes &&
        remoteObjectId?.lowercase() == artifact.remoteObjectId
} != null

private fun recommendedVariantPath(
    descriptor: ModelDescriptor?,
    variants: List<GgufFileUiState>,
): String? {
    val primary = when (descriptor) {
        is LlmModelDescriptor -> descriptor.file
        is DiffusionModelDescriptor -> descriptor.components.singleOrNull { it.isPrimary }?.file
        null -> null
    } ?: return null
    return variants.singleOrNull { variant ->
        val artifact = variant.artifact ?: return@singleOrNull false
        val remoteObjectId = primary.lfsOid?.let { "sha256:$it" } ?: primary.xetHash ?: primary.gitOid
        primary.repositoryId == artifact.repositoryId &&
            primary.revision.lowercase() == artifact.immutableRevision &&
            primary.path == artifact.relativePath &&
            primary.sizeBytes == artifact.expectedBytes &&
            remoteObjectId?.lowercase() == artifact.remoteObjectId
    }?.path
}

@Composable
private fun DetailRow(
    label: String,
    value: String?,
    modifier: Modifier = Modifier
) {
    if (value == null) return
    val spacing = LocalSpacing.current
    val stackValue = value.length > 28 || '/' in value || ',' in value
    if (stackValue) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            DetailLabel(label)
            DetailValue(value)
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.s),
            verticalAlignment = Alignment.Top,
        ) {
            DetailLabel(label, Modifier.weight(0.4f))
            DetailValue(value, Modifier.weight(0.6f))
        }
    }
}

@Composable
private fun DetailLabel(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun DetailValue(value: String, modifier: Modifier = Modifier) {
    Text(
        text = value,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

internal data class RepositoryHeading(
    val owner: String?,
    val name: String,
)

internal fun splitRepositoryId(id: String): RepositoryHeading {
    val normalized = id.trim()
    if (normalized.isEmpty()) return RepositoryHeading(owner = null, name = "Unknown")
    val separator = normalized.indexOf('/')
    if (separator <= 0 || separator == normalized.lastIndex) {
        return RepositoryHeading(owner = null, name = normalized)
    }
    return RepositoryHeading(
        owner = normalized.substring(0, separator),
        name = normalized.substring(separator + 1),
    )
}

private val hubTimestampDate = Regex(
    """^(\d{4})-(\d{2})-(\d{2})(?:T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d+)?(?:Z|[+-](?:[01]\d|2[0-3]):[0-5]\d))?$""",
)
private val monthLabels = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

internal fun formatHubTimestamp(value: String?): String? {
    if (value == null) return null
    val match = hubTimestampDate.matchEntire(value) ?: return value
    val year = match.groupValues[1].toIntOrNull() ?: return value
    val month = match.groupValues[2].toIntOrNull() ?: return value
    val day = match.groupValues[3].toIntOrNull() ?: return value
    if (month !in 1..12 || day !in 1..daysInMonth(year, month)) return value
    return "$day ${monthLabels[month - 1]} $year"
}

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    2 -> if (year % 400 == 0 || (year % 4 == 0 && year % 100 != 0)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

internal fun visibleModelTags(
    tags: List<String?>,
    pipelineTag: String?,
    libraryName: String? = null,
    modelType: String? = null,
): List<String> {
    val representedFacts = listOfNotNull(pipelineTag, libraryName, modelType)
        .map { it.trim().lowercase() }
        .toSet()
    return tags.asSequence()
        .filterNotNull()
        .map { it.trim() }
        .filter { tag ->
            tag.isNotEmpty() && ':' !in tag && tag.lowercase() !in representedFacts
        }
        .distinctBy { it.lowercase() }
        .toList()
}
