package com.debanshu777.caraml.features.modelhub.presentation.details.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.debanshu777.caraml.core.recommendation.RecommendationCategory
import com.debanshu777.caraml.core.rating.ui.RecommendationStatusChip
import com.debanshu777.caraml.core.rating.ui.formatBytesHuman
import com.debanshu777.caraml.core.theme.AppTechnicalLabel
import com.debanshu777.caraml.core.theme.LocalSpacing
import com.debanshu777.caraml.core.theme.auroraColors
import com.debanshu777.caraml.core.theme.prism
import com.debanshu777.caraml.core.theme.prismShapes
import com.debanshu777.caraml.core.ui.components.CaraMLPane
import com.debanshu777.caraml.core.ui.components.CaraMLSectionHeader
import com.debanshu777.caraml.core.ui.components.AuroraFocalSurface
import com.debanshu777.caraml.core.ui.components.SignalRail
import com.debanshu777.caraml.core.ui.components.SignalTone
import com.debanshu777.caraml.core.ui.components.StatusMark
import com.debanshu777.caraml.features.modelhub.presentation.details.modelDetailsUseSupportingPane
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.features.modelhub.domain.RecommendedModelUiState
import com.debanshu777.caraml.features.modelhub.domain.projectBrowseSelection
import com.debanshu777.caraml.features.modelhub.domain.matchesExactBrowseGroup
import com.debanshu777.caraml.features.modelhub.domain.ModelArtifactClassifier
import com.debanshu777.caraml.features.modelhub.domain.ModelArtifactRole
import com.debanshu777.caraml.features.modelhub.presentation.search.GgufFileUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.InstallBundleUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.components.formatCompactMetric
import com.debanshu777.caraml.features.modelhub.presentation.search.components.browseEstimateLabel
import com.debanshu777.caraml.features.modelhub.presentation.search.components.browseVerdict
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
    onDownloadGroupClick: (String, List<DownloadMetadataDTO>) -> Unit = { _, _ -> },
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
    onPauseDownload: (String, String) -> Unit = { _, _ -> },
    onResumeDownload: (String, String) -> Unit = { _, _ -> },
    onCancelDownload: (String, String) -> Unit = { _, _ -> },
    onRetryDownload: (String, String) -> Unit = { _, _ -> },
) {
    if (model == null) return

    val modelId = model.modelId ?: model.id ?: ""
    val modelSetup = if (modelId.isNotBlank()) getModelSetup(modelId) else null
    val recommendedVariant = recommendedVariantPath(
        recommendationState?.selectedDescriptor,
        installBundleState.variants,
    )
    val browseSelection = recommendationState?.browseVariants?.let {
        projectBrowseSelection(it, installBundleState.selectedVariantPath)
    }
    val installEnabled = recommendedVariant != null &&
        installBundleState.selectedVariantPath == recommendedVariant
    // Submit every immutable member of the selected group. Filename-only local state can
    // refer to an older revision; the download coordinator performs exact-object reuse.
    val durableControl = installBundleState.durableControl
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
                            onDownloadGroupClick = onDownloadGroupClick,
                            heading = weightFilesHeading,
                            emptyLabel = weightFilesEmptyLabel,
                            recommendationState = recommendationState,
                            selectedVariantPath = installBundleState.selectedVariantPath,
                            onVariantSelected = onVariantSelected,
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
                    ModelRecommendationSection(
                        recommendationState,
                        onRecommendationInfoClick,
                        browseSelection?.estimate,
                        browseSelection?.displayName,
                    )
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
                            durableControl = durableControl,
                            onPause = { durableControl?.let { onPauseDownload(it.batchId, it.artifactId) } },
                            onResume = { durableControl?.let { onResumeDownload(it.batchId, it.artifactId) } },
                            onCancel = { durableControl?.let { onCancelDownload(it.batchId, it.artifactId) } },
                            onRetry = { durableControl?.let { onRetryDownload(it.batchId, it.artifactId) } },
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
                    ModelRecommendationSection(
                        recommendationState = recommendationState,
                        onRecommendationInfoClick = onRecommendationInfoClick,
                        browseFit = browseSelection?.estimate,
                        browseVariantName = browseSelection?.displayName,
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
                            onDownloadGroupClick = onDownloadGroupClick,
                            heading = weightFilesHeading,
                            emptyLabel = weightFilesEmptyLabel,
                            recommendationState = recommendationState,
                            selectedVariantPath = installBundleState.selectedVariantPath,
                            onVariantSelected = onVariantSelected,
                            onPauseDownload = onPauseDownload,
                            onResumeDownload = onResumeDownload,
                            onCancelDownload = onCancelDownload,
                            onRetryDownload = onRetryDownload,
                        )
                    }
                    ModelMetadataSection(model)
                }
                if (showInstallBundle) {
                    InstallBundleActionFooter(
                        state = installBundleState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("detail-action"),
                        onInstall = onSmartInstall,
                        installEnabled = installEnabled,
                        durableControl = durableControl,
                        onPause = { durableControl?.let { onPauseDownload(it.batchId, it.artifactId) } },
                        onResume = { durableControl?.let { onResumeDownload(it.batchId, it.artifactId) } },
                        onCancel = { durableControl?.let { onCancelDownload(it.batchId, it.artifactId) } },
                        onRetry = { durableControl?.let { onRetryDownload(it.batchId, it.artifactId) } },
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
    var descriptionExpanded by rememberSaveable(model.modelId, model.id) { mutableStateOf(false) }
    var titleExpanded by rememberSaveable(model.modelId, model.id, "title") { mutableStateOf(false) }
    var titleTruncated by remember(model.modelId, model.id) { mutableStateOf(false) }
    AuroraFocalSurface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("detail-overview"),
    ) {
        Column(
            modifier = Modifier.padding(spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.s),
        ) {
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
                maxLines = if (titleExpanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { titleTruncated = it.hasVisualOverflow },
            )
            if (titleExpanded || titleTruncated) {
                TextButton(onClick = { titleExpanded = !titleExpanded }) {
                    Text(if (titleExpanded) "Show less" else "Show full name")
                }
            }
            val technicalSummary = listOfNotNull(
                model.pipelineTag?.takeIf { it.isNotBlank() },
                model.libraryName?.takeIf { it.isNotBlank() },
            )
            if (technicalSummary.isNotEmpty()) {
                Text(
                    text = technicalSummary.joinToString("  ·  "),
                    style = AppTechnicalLabel,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (descriptionExpanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (it.length > 180) {
                    TextButton(onClick = { descriptionExpanded = !descriptionExpanded }) {
                        Text(if (descriptionExpanded) "Show less" else "Read description")
                    }
                }
            }
            if (model.downloads != null || model.likes != null) {
                HorizontalDivider(color = MaterialTheme.auroraColors.divider)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.l),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    model.downloads?.let { count ->
                        ModelMetric(
                            Icons.Default.Download,
                            "${formatCompactMetric(count.toLong())} downloads",
                        )
                    }
                    model.likes?.let { count ->
                        ModelMetric(
                            Icons.Default.FavoriteBorder,
                            "${formatCompactMetric(count.toLong())} likes",
                        )
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
    var detailsVisible by rememberSaveable(model.modelId, model.id, "details-visible") {
        mutableStateOf(false)
    }
    var expanded by rememberSaveable(model.modelId, model.id, "details-expanded") {
        mutableStateOf(false)
    }
    val primaryEntries = entries.take(PRIMARY_METADATA_COUNT)
    val displayedEntries = if (expanded) entries else primaryEntries
    val hasDisclosure = entries.size > PRIMARY_METADATA_COUNT || tags.isNotEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("detail-metadata"),
        verticalArrangement = Arrangement.spacedBy(spacing.s),
    ) {
        Surface(
            onClick = { detailsVisible = !detailsVisible },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.prismShapes.control,
            color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.72f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.l, vertical = spacing.m),
                horizontalArrangement = Arrangement.spacedBy(spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Technical details",
                        style = MaterialTheme.typography.prism.sectionTitle,
                    )
                    Text(
                        text = "${entries.size} properties${if (tags.isNotEmpty()) " · ${tags.size} tags" else ""}",
                        style = MaterialTheme.typography.prism.denseMetadata,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (detailsVisible) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (detailsVisible) {
                        "Collapse technical details"
                    } else {
                        "Expand technical details"
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (detailsVisible) {
            CaraMLPane(modifier = Modifier.fillMaxWidth()) {
                displayedEntries.forEachIndexed { index, entry ->
                    DetailRow(
                        label = entry.label,
                        value = entry.value,
                        modifier = Modifier.padding(
                            horizontal = spacing.l,
                            vertical = spacing.m,
                        ),
                    )
                    if (index != displayedEntries.lastIndex || expanded && tags.isNotEmpty()) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = spacing.l),
                            color = MaterialTheme.auroraColors.divider,
                            thickness = 1.dp,
                        )
                    }
                }
                if (expanded && tags.isNotEmpty()) {
                    Column(
                        modifier = Modifier.padding(spacing.l),
                        verticalArrangement = Arrangement.spacedBy(spacing.s),
                    ) {
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
                }
                if (hasDisclosure) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.padding(horizontal = spacing.s),
                    ) {
                        Text(if (expanded) "Show less" else "Show all")
                    }
                }
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
    browseFit: com.debanshu777.caraml.core.recommendation.BrowseFitEstimate? = null,
    browseVariantName: String? = null,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.s),
    ) {
        CaraMLSectionHeader(
            title = "Device fit",
        )
        val browse = browseFit?.let(::browseVerdict)
        if (browse != null) {
            StatusMark(
                label = browse.label,
                contentDescription = browse.description,
                tone = browse.tone,
                icon = browse.icon,
            )
        } else {
            RecommendationStatusChip(
                state = recommendationState?.descriptorState ?: DescriptorState.NEEDS_INFORMATION,
                recommendation = recommendationState?.personalizedResult,
                onInfoClick = onRecommendationInfoClick,
            )
        }
        browseFit?.let { estimate ->
            Text(
                text = "${if (recommendationState?.descriptorState == DescriptorState.NEEDS_INFORMATION) "Provisional variant" else "Selected variant"}: ${browseVariantName ?: recommendationState?.provisionalVariantName ?: "model file"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            estimate.downloadBytes?.let { bytes ->
                Text("Download ${formatBytesHuman(bytes)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary)
            }
            if (!estimate.resourceSnapshotFresh) {
                Text(
                    text = "Device resource snapshot is stale; fit is not current",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    onDownloadGroupClick: (String, List<DownloadMetadataDTO>) -> Unit,
    heading: String,
    emptyLabel: String,
    recommendationState: RecommendedModelUiState?,
    selectedVariantPath: String?,
    onVariantSelected: (String) -> Unit,
    onPauseDownload: (String, String) -> Unit,
    onResumeDownload: (String, String) -> Unit,
    onCancelDownload: (String, String) -> Unit,
    onRetryDownload: (String, String) -> Unit,
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
            supportingText = null,
        )
        if (ggufFiles.isEmpty()) {
            Text(
                text = emptyLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            ggufFiles.forEach { item ->
                val role = ModelArtifactClassifier.classify(item.path).role
                val matchingBrowseVariant = recommendationState?.browseVariants?.singleOrNull { item.path in it.filePaths }
                val groupMembers = matchingBrowseVariant?.takeIf { it.filePaths.size > 1 }?.filePaths
                    ?.mapNotNull { path -> ggufFiles.singleOrNull { it.path == path } }
                    ?.takeIf { it.size == matchingBrowseVariant.filePaths.size }
                val groupMetadata = groupMembers?.mapNotNull { exactArtifactMetadata(model, it) }
                    ?.takeIf { metadata ->
                        metadata.size == groupMembers.size && matchingBrowseVariant?.let { variant ->
                            matchesExactBrowseGroup(variant, metadata.map { it.artifact })
                        } == true
                    }
                val isPrimary = role == ModelArtifactRole.PRIMARY_MODEL
                val durableTask = item.durableControl
                val hasExactArtifact = item.artifact != null
                val isDescriptorChoice = item.artifact?.let { artifact ->
                    primaryDescriptorFile?.matches(artifact) == true
                } == true
                val isRecommendedArtifact = isDescriptorChoice &&
                    recommendationState?.personalizedResult?.category == RecommendationCategory.RECOMMENDED
                val isEvaluatedArtifact = isDescriptorChoice && !isRecommendedArtifact
                val isProvisionalSuggestion = recommendationState?.descriptorState == DescriptorState.NEEDS_INFORMATION &&
                    matchingBrowseVariant?.stableIdentity == recommendationState.stableModelId
                val isActiveDownload = isDownloading &&
                    activeDownloadArtifact != null &&
                    item.artifact == activeDownloadArtifact
                ArtifactFileRow(
                    item = item,
                    recommended = isRecommendedArtifact,
                    evaluated = isEvaluatedArtifact,
                    provisional = isProvisionalSuggestion,
                    selected = isPrimary && matchingBrowseVariant != null && matchingBrowseVariant.stableIdentity ==
                        selectedVariantPath?.let { selectedPath ->
                            recommendationState?.browseVariants?.singleOrNull { selectedPath in it.filePaths }?.stableIdentity
                        },
                    roleLabel = matchingBrowseVariant?.takeIf { it.filePaths.size > 1 }
                        ?.let { "Part ${it.filePaths.indexOf(item.path) + 1} of ${it.filePaths.size}" }
                        ?: role.label().takeUnless { role == ModelArtifactRole.PRIMARY_MODEL },
                    onSelect = if (isPrimary && matchingBrowseVariant != null) ({ onVariantSelected(item.path) }) else null,
                    isActiveDownload = isActiveDownload,
                    interactionLocked = isDownloading &&
                        durableTask?.artifactState !in durableArtifactControlStates,
                    downloadEnabled = if (matchingBrowseVariant?.filePaths?.size?.let { it > 1 } == true) {
                        groupMetadata != null
                    } else hasExactArtifact,
                    downloadActionDescription = groupMembers?.let { "Download all ${it.size} parts" },
                    durableState = durableTask?.artifactState,
                    onPause = { durableTask?.let { onPauseDownload(it.batchId, it.artifactId) } },
                    onResume = { durableTask?.let { onResumeDownload(it.batchId, it.artifactId) } },
                    onCancel = { durableTask?.let { onCancelDownload(it.batchId, it.artifactId) } },
                    onRetry = { durableTask?.let { onRetryDownload(it.batchId, it.artifactId) } },
                    onDownloadClick = {
                        if (groupMetadata != null) {
                            onDownloadGroupClick(model.modelId ?: model.id ?: "", groupMetadata)
                        } else if (matchingBrowseVariant?.filePaths?.size?.let { it > 1 } != true) {
                            dispatchExactArtifactDownload(model, item, onDownloadClick)
                        }
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
    evaluated: Boolean,
    provisional: Boolean,
    selected: Boolean,
    roleLabel: String?,
    onSelect: (() -> Unit)?,
    isActiveDownload: Boolean,
    interactionLocked: Boolean,
    downloadEnabled: Boolean,
    downloadActionDescription: String?,
    durableState: DownloadArtifactState?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDownloadClick: () -> Unit,
) {
    val colors = MaterialTheme.auroraColors
    val spacing = LocalSpacing.current
    val highlightLabel = when {
        recommended -> "Recommended"
        provisional -> "Suggested · fit unverified"
        evaluated -> "Evaluated variant"
        else -> null
    }
    Column(modifier = Modifier.fillMaxWidth()) {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.prismShapes.control,
        color = if (recommended || provisional || evaluated) colors.selectedSurface else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(
            modifier = Modifier
            .fillMaxWidth()
            .semantics {
                this.selected = selected
                stateDescription = when {
                    recommended -> "Recommended variant${if (selected) ", selected" else ""}"
                    provisional -> "Suggested variant, compatibility unverified${if (selected) ", selected" else ""}"
                    evaluated -> "Evaluated variant${if (selected) ", selected" else ""}"
                    selected -> "Selected variant"
                    else -> "Available file"
                }
            }
            .testTag("detail-artifact:${item.path}"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onSelect != null) {
                    IconButton(onClick = onSelect, enabled = !selected, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = if (selected) Icons.Default.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = if (selected) "Selected variant ${item.filename}" else "Select variant ${item.filename}",
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                downloadActionDescription = downloadActionDescription,
                interactionLocked = interactionLocked,
                durableState = durableState,
                onPause = onPause,
                onResume = onResume,
                onCancel = onCancel,
                onRetry = onRetry,
                supportingLabel = listOfNotNull(highlightLabel, roleLabel).joinToString(" · ").ifBlank { null },
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
            )
            }
            if (recommended || provisional || evaluated) {
                Box(Modifier.matchParentSize()) {
                    SignalRail(tone = SignalTone.Accent)
                }
            }
        }
      }
      HorizontalDivider(modifier = Modifier.padding(horizontal = spacing.m), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun ModelArtifactRole.label(): String = when (this) {
    ModelArtifactRole.PRIMARY_MODEL -> "GGUF model"
    ModelArtifactRole.PROJECTOR -> "Supporting projector file"
    ModelArtifactRole.ADAPTER -> "Supporting adapter file"
    ModelArtifactRole.UNVERIFIED -> "Role unverified"
}


private fun dispatchExactArtifactDownload(
    model: ModelDetailResponse,
    item: GgufFileUiState,
    onDownloadClick: (String, String, DownloadMetadataDTO) -> Unit,
) {
    val metadata = exactArtifactMetadata(model, item) ?: return
    onDownloadClick(model.modelId ?: model.id ?: "", item.path, metadata)
}

private fun exactArtifactMetadata(
    model: ModelDetailResponse,
    item: GgufFileUiState,
): DownloadMetadataDTO? {
    val artifact = item.artifact ?: return null
    return DownloadMetadataDTO(
        artifact = artifact,
        logicalRole = "model",
        sizeBytes = artifact.expectedBytes,
        author = model.author,
        libraryName = model.libraryName,
        pipelineTag = model.pipelineTag,
        contextLength = model.gguf?.contextLength,
    )
}

private fun primaryDescriptorFile(descriptor: ModelDescriptor?): ModelFileIdentity? = when (descriptor) {
    is LlmModelDescriptor -> descriptor.file
    is DiffusionModelDescriptor -> descriptor.components.singleOrNull { it.isPrimary }?.file
    null -> null
}

private fun ModelFileIdentity.matches(artifact: DownloadArtifactIdentity): Boolean {
    val remoteObjectId = lfsOid?.let { "sha256:$it" } ?: xetHash ?: gitOid
    return repositoryId == artifact.repositoryId &&
        revision.lowercase() == artifact.immutableRevision &&
        path == artifact.relativePath &&
        sizeBytes == artifact.expectedBytes &&
        remoteObjectId?.lowercase() == artifact.remoteObjectId
}

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
