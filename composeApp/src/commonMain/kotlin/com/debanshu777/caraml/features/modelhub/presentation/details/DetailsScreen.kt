package com.debanshu777.caraml.features.modelhub.presentation.details

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.debanshu777.caraml.core.rating.SuitabilityResult
import com.debanshu777.caraml.core.rating.ui.SuitabilityInfoSheet
import com.debanshu777.caraml.features.modelhub.presentation.details.components.ModelDetailContent
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsScreen(
    viewModel: ModelViewModel,
    modelId: String,
    hubBrowseMode: ModelHubBrowseMode,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val modelDetail by viewModel.modelDetail.collectAsState()
    val isDetailLoading by viewModel.isDetailLoading.collectAsState()
    val detailError by viewModel.detailError.collectAsState()
    val ggufFiles by viewModel.ggufFiles.collectAsState()
    val isDownloading by viewModel.isDownloading.collectAsState()
    val downloadError by viewModel.downloadError.collectAsState()
    val installBundleState by viewModel.installBundleState.collectAsState()
    val storageInfo by viewModel.storageInfo.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var ratingSheetModelId by remember { mutableStateOf<String?>(null) }
    var ratingSheetResult by remember { mutableStateOf<SuitabilityResult?>(null) }

    val isDiffusion = hubBrowseMode == ModelHubBrowseMode.DiffusionImage ||
        hubBrowseMode == ModelHubBrowseMode.DiffusionVideo

    LaunchedEffect(modelId, hubBrowseMode) {
        viewModel.loadDetail(modelId, hubBrowseMode)
    }

    LaunchedEffect(downloadError) {
        val error = downloadError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error)
        viewModel.clearDownloadError()
    }

    LaunchedEffect(installBundleState.installError) {
        val error = installBundleState.installError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error)
        viewModel.clearDownloadError()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                val detail = modelDetail
                when {
                    isDetailLoading -> CircularProgressIndicator()
                    detailError != null -> Text(
                        text = detailError ?: "Could not load model details. Please try again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    detail != null -> {
                        val (weightHeading, weightEmpty) = when (hubBrowseMode) {
                            ModelHubBrowseMode.LanguageModels ->
                                "GGUF files" to "No GGUF files found"
                            ModelHubBrowseMode.DiffusionImage,
                            ModelHubBrowseMode.DiffusionVideo ->
                                "Weight files" to
                                    "No weight files found (.gguf, .safetensors, .ckpt, .pth)"
                        }
                        val ratingCallback: ((String, SuitabilityResult) -> Unit)? = { id, result ->
                            ratingSheetModelId = id
                            ratingSheetResult = result
                        }
                        ModelDetailContent(
                            model = detail,
                            ggufFiles = ggufFiles,
                            isDownloading = isDownloading,
                            onDownloadClick = { id, path, metadata ->
                                viewModel.startDownload(id, path, metadata)
                            },
                            weightFilesHeading = weightHeading,
                            weightFilesEmptyLabel = weightEmpty,
                            installBundleState = installBundleState,
                            onVariantSelected = { path -> viewModel.selectVariant(path) },
                            onSmartInstall = { viewModel.smartInstall(modelId) },
                            showInstallBundle = isDiffusion,
                            deviceHints = storageInfo.deviceHints,
                            onRatingInfoClick = ratingCallback,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }

    val sheetResult = ratingSheetResult
    val sheetModelId = ratingSheetModelId
    if (sheetResult != null && sheetModelId != null) {
        SuitabilityInfoSheet(
            modelId = sheetModelId,
            result = sheetResult,
            deviceHints = storageInfo.deviceHints,
            onDismiss = {
                ratingSheetResult = null
                ratingSheetModelId = null
            },
        )
    }
}
