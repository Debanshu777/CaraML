package com.debanshu777.caraml.features.modelhub.presentation.details.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.model.ModelDetailResponse
import com.debanshu777.caraml.features.modelhub.presentation.search.GgufFileUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.InstallBundleUiState
import com.debanshu777.caraml.features.modelhub.domain.RecommendedModelUiState
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.core.recommendation.DiffusionModelDescriptor
import com.debanshu777.caraml.core.recommendation.LlmModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.rating.ui.RecommendationStatusChip
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
) {
    if (model == null) return

    val modelId = model.modelId ?: model.id ?: ""
    val modelSetup = if (modelId.isNotBlank()) getModelSetup(modelId) else null
    val recommendedVariant = recommendedVariantPath(
        recommendationState?.selectedDescriptor,
        installBundleState.variants,
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Title / author
        Text(
            text = model.modelId ?: model.id ?: "Unknown",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        model.author?.let { author ->
            Text(
                text = author,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Downloads / likes
        if (model.downloads != null || model.likes != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                model.downloads?.let { count ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                model.likes?.let { count ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.FavoriteBorder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        RecommendationStatusChip(
            state = recommendationState?.descriptorState ?: DescriptorState.NEEDS_INFORMATION,
            recommendation = recommendationState?.personalizedResult,
            onInfoClick = onRecommendationInfoClick,
        )
        recommendationState?.selectedVariantName?.let {
            Text("Selected variant: $it", style = MaterialTheme.typography.labelMedium)
        }

        // Info card
        val hasInfo = model.libraryName != null || model.pipelineTag != null ||
            model.config?.modelType != null || model.config?.architectures?.filterNotNull()?.isNotEmpty() == true ||
            model.cardData?.license != null || model.cardData?.baseModel?.takeIf { it.isNotEmpty() } != null ||
            model.createdAt != null || model.lastModified != null
        if (hasInfo) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                }
            }
        }

        // Tags
        model.tags?.filterNotNull()?.takeIf { it.isNotEmpty() }?.let { tags ->
            Text(
                text = "Tags",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tags.forEach { tag ->
                    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp) {
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        if (showInstallBundle) {
            // ── Diffusion model: unified Smart Install card ──
            InstallBundleCard(
                modelId = modelId,
                state = installBundleState,
                familyLabel = modelSetup?.familyLabel,
                modelDescription = modelSetup?.description,
                onVariantSelected = onVariantSelected,
                onInstall = onSmartInstall,
                modifier = Modifier.fillMaxWidth(),
                recommendedVariantPath = recommendedVariant,
                installEnabled = recommendedVariant != null &&
                    installBundleState.selectedVariantPath == recommendedVariant,
            )
        } else {
            // ── Language model: per-file GGUF list ──
            Text(
                text = weightFilesHeading,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            if (ggufFiles.isNotEmpty()) {
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
                                    contextLength = model.gguf?.contextLength
                                )
                            )
                        },
                        modifier = Modifier.padding(vertical = 4.dp),
                        downloadEnabled = item.artifact?.let { artifact ->
                            artifactMatches(recommendationState?.selectedDescriptor, artifact)
                        } == true,
                    )
                }
            } else {
                Text(
                    text = weightFilesEmptyLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
