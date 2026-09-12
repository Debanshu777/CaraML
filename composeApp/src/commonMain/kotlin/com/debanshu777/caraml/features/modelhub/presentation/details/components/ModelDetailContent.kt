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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
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
import com.debanshu777.caraml.core.ui.components.StatusTone
import com.debanshu777.caraml.features.modelhub.presentation.details.modelDetailsUseSupportingPane
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.features.modelhub.domain.RecommendedModelUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.GgufFileUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.InstallBundleUiState
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
                modifier = Modifier.fillMaxSize().padding(vertical = spacing.s),
                horizontalArrangement = Arrangement.spacedBy(spacing.xl),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(spacing.l),
                ) {
                    ModelOverviewSection(model, modelSetup?.description)
                    ModelMetadataSection(model)
                    if (!showInstallBundle) {
                        ModelFileVariantsSection(
                            model = model,
                            ggufFiles = ggufFiles,
                            isDownloading = isDownloading,
                            onDownloadClick = onDownloadClick,
                            heading = weightFilesHeading,
                            emptyLabel = weightFilesEmptyLabel,
                            recommendationState = recommendationState,
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .width(340.dp)
                        .verticalScroll(rememberScrollState()),
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
                            onDownloadClick = onDownloadClick,
                            heading = weightFilesHeading,
                            emptyLabel = weightFilesEmptyLabel,
                            recommendationState = recommendationState,
                        )
                    }
                }
                if (showInstallBundle) {
                    InstallBundleActionFooter(
                        state = installBundleState,
                        modifier = Modifier.fillMaxWidth().padding(vertical = spacing.s),
                        onInstall = onSmartInstall,
                        installEnabled = installEnabled,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelOverviewSection(model: ModelDetailResponse, description: String?) {
    val spacing = LocalSpacing.current
    CaraMLPane(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(spacing.l),
            verticalArrangement = Arrangement.spacedBy(spacing.s),
        ) {
            CaraMLSectionHeader(title = "Overview")
            Text(
                text = model.modelId ?: model.id ?: "Unknown",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            model.author?.let { author ->
                Text(
                    text = author,
                    style = MaterialTheme.typography.bodyMedium,
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.l),
                    verticalAlignment = Alignment.CenterVertically,
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

@Composable
private fun ModelMetadataSection(model: ModelDetailResponse) {
    val tags = model.tags?.filterNotNull().orEmpty()
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
            DetailRow("Created", model.createdAt)
            DetailRow("Last modified", model.lastModified)
            if (tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.s),
                    verticalArrangement = Arrangement.spacedBy(spacing.s),
                ) {
                    tags.forEach { tag ->
                        CaraMLStatusPill(
                            label = tag,
                            contentDescription = "Tag: $tag",
                            tone = StatusTone.Neutral,
                        )
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
    onDownloadClick: (String, String, DownloadMetadataDTO) -> Unit,
    heading: String,
    emptyLabel: String,
    recommendationState: RecommendedModelUiState?,
) {
    val spacing = LocalSpacing.current
    CaraMLPane(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(spacing.l),
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
                    GgufFileListItem(
                        filename = item.path.ifEmpty { item.filename },
                        sizeBytes = item.sizeBytes,
                        isDownloaded = item.isDownloaded,
                        progress = item.progress,
                        isDownloading = isDownloading,
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
                        downloadEnabled = item.artifact?.let { artifact ->
                            artifactMatches(recommendationState?.selectedDescriptor, artifact)
                        } == true,
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
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.s),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.6f),
        )
    }
}
