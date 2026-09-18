package com.debanshu777.caraml.features.modelhub.presentation.details.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.download.DownloadArtifactState
import com.debanshu777.caraml.core.recommendation.DiffusionModelDescriptor
import com.debanshu777.caraml.core.recommendation.LlmModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.rating.ui.RecommendationStatusChip
import com.debanshu777.caraml.core.theme.AppTechnicalLabel
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.core.theme.auroraColors
import com.debanshu777.caraml.core.theme.prism
import com.debanshu777.caraml.core.ui.components.CaraMLSectionHeader
import com.debanshu777.caraml.core.ui.components.AuroraFocalSurface
import com.debanshu777.caraml.core.ui.components.SignalRail
import com.debanshu777.caraml.core.ui.components.SignalTone
import com.debanshu777.caraml.core.ui.components.StatusMark
import com.debanshu777.caraml.features.modelhub.presentation.details.modelDetailsUseSupportingPane
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.features.modelhub.domain.RecommendedModelUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.GgufFileUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.InstallBundleUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.relevantDownloadTask
import com.debanshu777.caraml.core.download.DownloadBatchSnapshot
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.model.ModelDetailResponse
import com.debanshu777.huggingfacemanager.sdcpp.getModelSetup

private val durableArtifactControlStates = setOf(
    DownloadArtifactState.QUEUED,
    DownloadArtifactState.RUNNING,
    DownloadArtifactState.PAUSED,
    DownloadArtifactState.WAITING_FOR_NETWORK,
    DownloadArtifactState.FAILED_RETRYABLE,
)

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
    val compactArtifactAction = if (showInstallBundle) {
        null
    } else {
        primaryArtifactItem(recommendationState?.selectedDescriptor, ggufFiles)
            ?.takeUnless { it.isDownloaded }
    }
    val compactArtifactTask = compactArtifactAction?.artifact?.let { artifact ->
        durableArtifactTask(downloadBatches, artifact)
    }
    val selectedInstallArtifact = installBundleState.selectedVariantPath?.let { selectedPath ->
        installBundleState.variants.singleOrNull { it.path == selectedPath }?.artifact
    }
    val durableBatch = relevantDownloadTask(downloadBatches, selectedInstallArtifact)?.batch
    val spacing = LocalSpacing.current

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val useSupportingPane = modelDetailsUseSupportingPane(windowWidth ?: maxWidth)
        if (useSupportingPane) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = spacing.s),
                horizontalArrangement = Arrangement.spacedBy(spacing.xl),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(spacing.xl),
                ) {
                    ModelOverviewSection(
                        model = model,
                        description = modelSetup?.description,
                        expanded = true,
                    )
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
                    modifier = Modifier
                        .width(336.dp)
                        .testTag("detail-support"),
                    verticalArrangement = Arrangement.spacedBy(spacing.xl),
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("detail-files"),
                            recommendedVariantPath = recommendedVariant,
                            installEnabled = installEnabled,
                            durableBatch = durableBatch,
                            onPause = { durableBatch?.batchId?.let(onPauseDownload) },
                            onResume = { durableBatch?.batchId?.let(onResumeDownload) },
                            onCancel = { durableBatch?.batchId?.let(onCancelDownload) },
                            onRetry = { durableBatch?.batchId?.let(onRetryDownload) },
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
                    verticalArrangement = Arrangement.spacedBy(spacing.xl),
                ) {
                    ModelOverviewSection(
                        model = model,
                        description = modelSetup?.description,
                        expanded = false,
                    )
                    ModelMetadataSection(model)
                    ModelRecommendationSection(
                        recommendationState = recommendationState,
                        onRecommendationInfoClick = onRecommendationInfoClick,
                        modifier = Modifier.testTag("detail-support"),
                    )
                    if (showInstallBundle) {
                        InstallBundleSummaryCard(
                            state = installBundleState,
                            familyLabel = modelSetup?.familyLabel,
                            modelDescription = null,
                            onVariantSelected = onVariantSelected,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("detail-files"),
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
                            footerOwnedArtifact = compactArtifactAction?.artifact,
                        )
                    }
                }
                if (showInstallBundle) {
                    InstallBundleActionFooter(
                        state = installBundleState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("detail-action"),
                        onInstall = onSmartInstall,
                        installEnabled = installEnabled,
                        durableBatch = durableBatch,
                        onPause = { durableBatch?.batchId?.let(onPauseDownload) },
                        onResume = { durableBatch?.batchId?.let(onResumeDownload) },
                        onCancel = { durableBatch?.batchId?.let(onCancelDownload) },
                        onRetry = { durableBatch?.batchId?.let(onRetryDownload) },
                    )
                } else if (compactArtifactAction != null) {
                    ArtifactDownloadActionFooter(
                        model = model,
                        item = compactArtifactAction,
                        isDownloading = isDownloading,
                        onDownloadClick = onDownloadClick,
                        durableState = compactArtifactTask?.state,
                        onPause = { compactArtifactTask?.batchId?.let(onPauseDownload) },
                        onResume = { compactArtifactTask?.batchId?.let(onResumeDownload) },
                        onCancel = { compactArtifactTask?.batchId?.let(onCancelDownload) },
                        onRetry = { compactArtifactTask?.batchId?.let(onRetryDownload) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("detail-action"),
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelOverviewSection(
    model: ModelDetailResponse,
    description: String?,
    expanded: Boolean,
) {
    val spacing = LocalSpacing.current
    val heading = splitRepositoryId(model.modelId ?: model.id.orEmpty())
    val owner = heading.owner ?: model.author?.trim()?.takeIf { it.isNotEmpty() }
    AuroraFocalSurface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("detail-overview"),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(spacing.l),
            verticalArrangement = Arrangement.spacedBy(spacing.s),
        ) {
            CaraMLSectionHeader(title = "Overview")
            owner?.let {
                Text(
                    text = it,
                    style = AppTechnicalLabel,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = heading.name,
                style = if (expanded) {
                    MaterialTheme.typography.prism.detailTitleExpanded
                } else {
                    MaterialTheme.typography.prism.detailTitleCompact
                },
                color = MaterialTheme.colorScheme.onSurface,
            )
            val technicalSummary = listOfNotNull(
                model.pipelineTag?.takeIf { it.isNotBlank() },
                model.libraryName?.takeIf { it.isNotBlank() },
            )
            if (technicalSummary.isNotEmpty()) {
                Text(
                    text = technicalSummary.joinToString("  ·  "),
                    style = AppTechnicalLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

private data class MetadataEntry(
    val label: String,
    val value: String,
)

private fun String?.nonBlankMetadata(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun ModelDetailResponse.metadataEntries(): List<MetadataEntry> = buildList {
    libraryName.nonBlankMetadata()?.let { add(MetadataEntry("Library", it)) }
    pipelineTag.nonBlankMetadata()?.let { add(MetadataEntry("Pipeline", it)) }
    cardData?.license.nonBlankMetadata()?.let { add(MetadataEntry("License", it)) }
    cardData?.baseModel
        ?.mapNotNull { it.nonBlankMetadata() }
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString(", ")
        ?.let { add(MetadataEntry("Base model", it)) }
    config?.modelType.nonBlankMetadata()?.let { add(MetadataEntry("Model type", it)) }
    config?.architectures
        ?.mapNotNull { it.nonBlankMetadata() }
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString()
        ?.let { add(MetadataEntry("Architectures", it)) }
    formatHubTimestamp(createdAt).nonBlankMetadata()?.let { add(MetadataEntry("Created", it)) }
    formatHubTimestamp(lastModified).nonBlankMetadata()?.let { add(MetadataEntry("Last modified", it)) }
}

@Composable
private fun ModelMetadataSection(model: ModelDetailResponse) {
    val entries = model.metadataEntries()
    val tags = visibleModelTags(
        tags = model.tags.orEmpty(),
        pipelineTag = model.pipelineTag,
        libraryName = model.libraryName,
        modelType = model.config?.modelType,
    )
    if (entries.isEmpty() && tags.isEmpty()) return

    val spacing = LocalSpacing.current
    var expanded by rememberSaveable(model.modelId, model.id) { mutableStateOf(false) }
    val primaryEntries = entries.take(PRIMARY_METADATA_COUNT)
    val displayedEntries = if (expanded) entries else primaryEntries
    val hasDisclosure = entries.size > PRIMARY_METADATA_COUNT || tags.isNotEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("detail-metadata"),
        verticalArrangement = Arrangement.spacedBy(spacing.s),
    ) {
        CaraMLSectionHeader(title = "Metadata")
        displayedEntries.forEach { entry ->
            DetailRow(entry.label, entry.value)
            HorizontalDivider(
                color = MaterialTheme.auroraColors.divider,
                thickness = 1.dp,
            )
        }
        if (expanded && tags.isNotEmpty()) {
            Text(
                text = "Tags",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.s),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                tags.take(MAX_VISIBLE_DETAIL_TAGS).forEach { tag ->
                    Text(
                        text = tag,
                        style = AppTechnicalLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics {
                            contentDescription = "Tag: $tag"
                        },
                    )
                }
            }
        }
        if (hasDisclosure) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.align(Alignment.Start),
            ) {
                Text(if (expanded) "Show less" else "Show all")
            }
        }
    }
}

private const val PRIMARY_METADATA_COUNT = 4
private const val MAX_VISIBLE_DETAIL_TAGS = 4

@Composable
private fun ModelRecommendationSection(
    recommendationState: RecommendedModelUiState?,
    onRecommendationInfoClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.fillMaxWidth(),
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
            StatusMark(
                label = variant,
                contentDescription = "Selected variant: $variant",
                tone = SignalTone.Accent,
                icon = Icons.Default.CheckCircle,
            )
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
    footerOwnedArtifact: DownloadArtifactIdentity? = null,
) {
    val spacing = LocalSpacing.current
    val selectedDescriptor = recommendationState?.selectedDescriptor
    val primaryDescriptorFile = primaryDescriptorFile(selectedDescriptor)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("detail-files"),
        verticalArrangement = Arrangement.spacedBy(spacing.s),
    ) {
        CaraMLSectionHeader(
            title = heading,
            supportingText = "Choose an assessed model weight to download",
        )
        if (ggufFiles.isEmpty()) {
            Text(
                text = emptyLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            ggufFiles.forEach { item ->
                val durableTask = durableArtifactTask(downloadBatches, item.artifact)
                val hasExactArtifact = item.artifact != null
                val needsInformationOnly = recommendationState == null ||
                    recommendationState.descriptorState == DescriptorState.NEEDS_INFORMATION
                val matchesSelectedDescriptor = item.artifact?.let { artifact ->
                    artifactMatches(selectedDescriptor, artifact)
                } == true
                val isRecommendedArtifact = item.artifact?.let { artifact ->
                    primaryDescriptorFile?.matches(artifact) == true
                } == true
                val isActiveDownload = isDownloading &&
                    activeDownloadArtifact != null &&
                    item.artifact == activeDownloadArtifact
                ArtifactFileRow(
                    item = item,
                    recommended = isRecommendedArtifact,
                    isActiveDownload = isActiveDownload,
                    interactionLocked = isDownloading &&
                        durableTask?.state !in durableArtifactControlStates,
                    downloadEnabled = hasExactArtifact && (needsInformationOnly || matchesSelectedDescriptor),
                    durableState = durableTask?.state,
                    onPause = { durableTask?.batchId?.let(onPauseDownload) },
                    onResume = { durableTask?.batchId?.let(onResumeDownload) },
                    onCancel = { durableTask?.batchId?.let(onCancelDownload) },
                    onRetry = { durableTask?.batchId?.let(onRetryDownload) },
                    showDownloadAction = item.artifact != footerOwnedArtifact,
                    onDownloadClick = {
                        dispatchExactArtifactDownload(model, item, onDownloadClick)
                    },
                )
            }
        }
    }
}

@Composable
private fun ArtifactFileRow(
    item: GgufFileUiState,
    recommended: Boolean,
    isActiveDownload: Boolean,
    interactionLocked: Boolean,
    downloadEnabled: Boolean,
    durableState: DownloadArtifactState?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    showDownloadAction: Boolean = true,
    onDownloadClick: () -> Unit,
) {
    val colors = MaterialTheme.auroraColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(if (recommended) colors.selectedSurface else MaterialTheme.colorScheme.surface)
            .semantics {
                selected = recommended
                if (recommended) stateDescription = "Recommended artifact"
            }
            .testTag("detail-artifact:${item.path}"),
    ) {
        if (recommended) {
            SignalRail(tone = SignalTone.Accent)
        }
        GgufFileTechnicalRow(
            filename = item.path.ifEmpty { item.filename },
            sizeBytes = item.sizeBytes,
            isDownloaded = item.isDownloaded,
            progress = item.progress,
            isDownloading = isActiveDownload,
            onDownloadClick = onDownloadClick,
            modifier = Modifier.weight(1f),
            downloadEnabled = downloadEnabled,
            interactionLocked = interactionLocked,
            durableState = durableState,
            onPause = onPause,
            onResume = onResume,
            onCancel = onCancel,
            onRetry = onRetry,
            showDownloadAction = showDownloadAction,
        )
    }
}

@Composable
private fun ArtifactDownloadActionFooter(
    model: ModelDetailResponse,
    item: GgufFileUiState,
    isDownloading: Boolean,
    onDownloadClick: (String, String, DownloadMetadataDTO) -> Unit,
    durableState: DownloadArtifactState?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(LocalSpacing.current.m),
            horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs),
            ) {
                Text(
                    text = "Selected artifact",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = item.path.ifBlank { item.filename }.substringAfterLast('/'),
                    style = AppTechnicalLabel,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            GgufFileAction(
                filename = item.path.ifBlank { item.filename },
                isDownloaded = item.isDownloaded,
                isDownloading = isDownloading,
                downloadEnabled = item.artifact != null,
                interactionLocked = isDownloading && durableState == null,
                durableState = durableState,
                onDownloadClick = { dispatchExactArtifactDownload(model, item, onDownloadClick) },
                onPause = onPause,
                onResume = onResume,
                onCancel = onCancel,
                onRetry = onRetry,
            )
        }
    }
}

private fun dispatchExactArtifactDownload(
    model: ModelDetailResponse,
    item: GgufFileUiState,
    onDownloadClick: (String, String, DownloadMetadataDTO) -> Unit,
) {
    val artifact = item.artifact ?: return
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
}

private fun descriptorFiles(descriptor: ModelDescriptor?): List<ModelFileIdentity> = when (descriptor) {
    is LlmModelDescriptor -> descriptor.files
    is DiffusionModelDescriptor -> descriptor.components
        .filter { it.required || it.isPrimary }
        .map { it.file }
    null -> emptyList()
}

private data class DurableArtifactTask(
    val batchId: String,
    val state: DownloadArtifactState,
)

private fun durableArtifactTask(
    batches: List<DownloadBatchSnapshot>,
    artifact: DownloadArtifactIdentity?,
): DurableArtifactTask? = relevantDownloadTask(batches, artifact)?.let { selection ->
    DurableArtifactTask(selection.batch.batchId, selection.task.state)
}

private fun primaryDescriptorFile(descriptor: ModelDescriptor?): ModelFileIdentity? = when (descriptor) {
    is LlmModelDescriptor -> descriptor.file
    is DiffusionModelDescriptor -> descriptor.components.singleOrNull { it.isPrimary }?.file
    null -> null
}

private fun primaryArtifactItem(
    descriptor: ModelDescriptor?,
    files: List<GgufFileUiState>,
): GgufFileUiState? {
    val primaryFile = primaryDescriptorFile(descriptor) ?: return null
    return files.singleOrNull { item ->
        item.artifact?.let(primaryFile::matches) == true
    }
}

private fun ModelFileIdentity.matches(artifact: DownloadArtifactIdentity): Boolean {
    val remoteObjectId = lfsOid?.let { "sha256:$it" } ?: xetHash ?: gitOid
    return repositoryId == artifact.repositoryId &&
        revision.lowercase() == artifact.immutableRevision &&
        path == artifact.relativePath &&
        sizeBytes == artifact.expectedBytes &&
        remoteObjectId?.lowercase() == artifact.remoteObjectId
}

private fun artifactMatches(
    descriptor: ModelDescriptor?,
    artifact: DownloadArtifactIdentity,
): Boolean = descriptorFiles(descriptor).singleOrNull { file -> file.matches(artifact) } != null

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
        primary.matches(artifact)
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
        style = AppTechnicalLabel,
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
