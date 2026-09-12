@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.modelhub.presentation.details

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.debanshu777.caraml.core.drawer.AppDrawerShell
import com.debanshu777.caraml.core.drawer.LocalAppWindowWidth
import com.debanshu777.caraml.core.navigation.AppScreen
import com.debanshu777.caraml.core.rating.SdArchitecture
import com.debanshu777.caraml.core.recommendation.DiffusionComponentDescriptor
import com.debanshu777.caraml.core.recommendation.DiffusionMode
import com.debanshu777.caraml.core.recommendation.DiffusionModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.core.ui.motion.auroraMotionPolicy
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.features.modelhub.domain.RecommendedModelUiState
import com.debanshu777.caraml.features.modelhub.presentation.details.components.ModelDetailContent
import com.debanshu777.caraml.features.modelhub.presentation.details.components.GgufFileListItem
import com.debanshu777.caraml.features.modelhub.presentation.details.components.InstallBundleCard
import com.debanshu777.caraml.features.modelhub.presentation.search.GgufFileUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.InstallBundleUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.SetupComponentUiState
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.model.ListModelsResponse
import com.debanshu777.huggingfacemanager.model.ModelDetailResponse
import com.debanshu777.huggingfacemanager.sdcpp.ComponentRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelDetailsAuroraUiTest {

    @Test
    fun detailsUseSupportingPaneOnlyAtExpandedWidth() {
        assertEquals(false, modelDetailsUseSupportingPane(839.dp))
        assertEquals(true, modelDetailsUseSupportingPane(840.dp))
    }

    @Test
    fun fileDownloadStillInvokesTheExactPath() = runComposeUiTest {
        var requested = ""
        setContent {
            MaterialTheme {
                GgufFileListItem(
                    filename = "weights/model-q4.gguf",
                    sizeBytes = 1_073_741_824L,
                    isDownloaded = false,
                    progress = null,
                    isDownloading = false,
                    onDownloadClick = { requested = "weights/model-q4.gguf" },
                )
            }
        }

        onNodeWithContentDescription("Download weights/model-q4.gguf").performClick()
        runOnIdle { assertEquals("weights/model-q4.gguf", requested) }
    }

    @Test
    fun drawerShellWindowWidthMovesDetailsIntoSupportingPaneAtExpandedBoundary() = runComposeUiTest {
        var windowWidth by mutableStateOf(839.dp)
        setContent {
            MaterialTheme {
                val backStack = remember {
                    NavBackStack<NavKey>(AppScreen.Details("org/model"))
                }
                Box(Modifier.width(windowWidth).height(720.dp)) {
                    AppDrawerShell(
                        modifier = Modifier.fillMaxSize(),
                        backStack = backStack,
                    ) {
                        ModelDetailContent(
                            model = ModelDetailResponse(
                                modelId = "org/model",
                                author = "Model author",
                            ),
                            ggufFiles = emptyList(),
                            isDownloading = false,
                            onDownloadClick = { _, _, _ -> },
                            installBundleState = InstallBundleUiState(isSelfContained = true),
                            showInstallBundle = true,
                            windowWidth = LocalAppWindowWidth.current,
                        )
                    }
                }
            }
        }

        val compactOverview = onNodeWithText("Overview")
            .fetchSemanticsNode().positionInRoot
        val compactInstall = onNodeWithText("Install summary")
            .fetchSemanticsNode().positionInRoot
        assertTrue(compactInstall.y > compactOverview.y)

        runOnIdle { windowWidth = 840.dp }

        val expandedOverview = onNodeWithText("Overview")
            .fetchSemanticsNode().positionInRoot
        val expandedInstall = onNodeWithText("Install summary")
            .fetchSemanticsNode().positionInRoot
        assertTrue(expandedInstall.x > expandedOverview.x)
    }

    @Test
    fun detailsOutsideDrawerShellUseTheirOwnCompactConstraint() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.width(839.dp).height(720.dp)) {
                    ModelDetailContent(
                        model = ModelDetailResponse(modelId = "org/model"),
                        ggufFiles = emptyList(),
                        isDownloading = false,
                        onDownloadClick = { _, _, _ -> },
                        installBundleState = InstallBundleUiState(isSelfContained = true),
                        showInstallBundle = true,
                    )
                }
            }
        }

        val overview = onNodeWithText("Overview").fetchSemanticsNode().positionInRoot
        val install = onNodeWithText("Install summary").fetchSemanticsNode().positionInRoot
        assertTrue(install.y > overview.y)
    }

    @Test
    fun compactLargeTextKeepsOneScrollOwnerAndClickableInstallAction() = runComposeUiTest {
        val fixture = realisticInstallFixture()
        var installClicks = 0
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                MaterialTheme {
                    Box(Modifier.width(420.dp).height(280.dp)) {
                        ModelDetailContent(
                            model = ModelDetailResponse(
                                modelId = fixture.recommendation.repositoryId,
                                author = "Model author",
                                tags = listOf("text-to-image", "safetensors", "large model"),
                            ),
                            ggufFiles = emptyList(),
                            isDownloading = false,
                            onDownloadClick = { _, _, _ -> },
                            installBundleState = fixture.installState,
                            onVariantSelected = {},
                            onSmartInstall = { installClicks++ },
                            showInstallBundle = true,
                            recommendationState = fixture.recommendation,
                        )
                    }
                }
            }
        }

        val scrollOwners = onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        ).fetchSemanticsNodes()
        assertEquals(1, scrollOwners.size)

        onNode(hasText("Install", substring = true) and hasClickAction())
            .assertIsDisplayed()
            .performClick()
        runOnIdle { assertEquals(1, installClicks) }
    }

    @Test
    fun determinateFileProgressAnimatesOnlyTowardTheReportedValue() = runComposeUiTest {
        var progress by mutableStateOf(0f)
        mainClock.autoAdvance = false
        setContent {
            MaterialTheme {
                GgufFileListItem(
                    filename = "model-q4.gguf",
                    sizeBytes = 1_073_741_824L,
                    isDownloaded = false,
                    progress = progress,
                    isDownloading = true,
                    onDownloadClick = {},
                )
            }
        }
        mainClock.advanceTimeByFrame()

        runOnIdle { progress = 100f }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(90)

        val rangeInfo = onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
        ).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        requireNotNull(rangeInfo)
        assertTrue(rangeInfo.current > 0f && rangeInfo.current < 1f)

        mainClock.advanceTimeBy(100)
        val completed = onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
        ).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        assertEquals(1f, requireNotNull(completed).current, absoluteTolerance = 0.001f)
    }

    @Test
    fun reducedMotionAppliesReportedFileProgressImmediately() = runComposeUiTest {
        var progress by mutableStateOf(0f)
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(
                LocalAuroraMotionPolicy provides auroraMotionPolicy(durationScale = 0f),
            ) {
                MaterialTheme {
                    GgufFileListItem(
                        filename = "model-q4.gguf",
                        sizeBytes = 1_073_741_824L,
                        isDownloaded = false,
                        progress = progress,
                        isDownloading = true,
                        onDownloadClick = {},
                    )
                }
            }
        }
        mainClock.advanceTimeByFrame()

        runOnIdle { progress = 100f }
        mainClock.advanceTimeByFrame()

        val rangeInfo = onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
        ).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        assertEquals(1f, requireNotNull(rangeInfo).current, absoluteTolerance = 0.001f)
    }

    @Test
    fun determinateInstallProgressAnimatesTowardItsReportedFraction() = runComposeUiTest {
        var state by mutableStateOf(
            InstallBundleUiState(
                isInstalling = true,
                isSelfContained = true,
                overallProgress = 0f,
            ),
        )
        mainClock.autoAdvance = false
        setContent {
            MaterialTheme {
                InstallBundleCard(
                    modelId = "org/model",
                    state = state,
                    familyLabel = "Model family",
                    modelDescription = "Model description",
                    onVariantSelected = {},
                    onInstall = {},
                )
            }
        }
        mainClock.advanceTimeByFrame()

        runOnIdle { state = state.copy(overallProgress = 1f) }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(90)

        val reportedRange = onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
        ).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) }
            .single { it != androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate }
        assertTrue(reportedRange.current > 0f && reportedRange.current < 1f)

        mainClock.advanceTimeBy(100)
        val completed = onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
        ).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) }
            .single { it != androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate }
        assertEquals(1f, completed.current, absoluteTolerance = 0.001f)
    }

    @Test
    fun indeterminateInstallUsesOneMaterialProgressIndicator() = runComposeUiTest {
        setContent {
            MaterialTheme {
                InstallBundleCard(
                    modelId = "org/model",
                    state = InstallBundleUiState(
                        isInstalling = true,
                        isSelfContained = true,
                        overallProgress = null,
                    ),
                    familyLabel = "Model family",
                    modelDescription = null,
                    onVariantSelected = {},
                    onInstall = {},
                )
            }
        }

        val indeterminateIndicators = onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
        ).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) }
            .count { it == androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate }
        assertEquals(1, indeterminateIndicators)
    }
}

private data class InstallFixture(
    val installState: InstallBundleUiState,
    val recommendation: RecommendedModelUiState,
)

private fun realisticInstallFixture(): InstallFixture {
    val repositoryId = "org/model"
    val revision = "a".repeat(40)
    val objectId = "b".repeat(64)
    val primaryPath = "unet/model-q4.safetensors"
    val primaryArtifact = requireNotNull(
        DownloadArtifactIdentity.create(
            repositoryId = repositoryId,
            immutableRevision = revision,
            relativePath = primaryPath,
            remoteObjectId = "sha256:$objectId",
            expectedBytes = 4_294_967_296L,
        ),
    )
    val primaryFile = ModelFileIdentity(
        repositoryId = repositoryId,
        revision = revision,
        path = primaryPath,
        sizeBytes = primaryArtifact.expectedBytes,
        gitOid = null,
        lfsOid = objectId,
        xetHash = null,
        evidence = emptyList(),
    )
    val descriptor = DiffusionModelDescriptor(
        repositoryId = repositoryId,
        revision = revision,
        components = listOf(
            DiffusionComponentDescriptor(
                file = primaryFile,
                role = null,
                required = true,
                isPrimary = true,
            ),
        ),
        mode = DiffusionMode.IMAGE,
        family = "Flux",
        architecture = SdArchitecture.FLUX,
        quantizationDistribution = setOf("Q4"),
        requiredComponentsPresent = true,
        requiredEngineFeatures = emptySet(),
        evidence = emptyList(),
    )
    val variants = listOf(
        GgufFileUiState(
            path = primaryPath,
            filename = "model-q4.safetensors",
            sizeBytes = primaryArtifact.expectedBytes,
            isDownloaded = false,
            progress = null,
            artifact = primaryArtifact,
        ),
        GgufFileUiState(
            path = "unet/model-q8.safetensors",
            filename = "model-q8.safetensors",
            sizeBytes = 8_589_934_592L,
            isDownloaded = false,
            progress = null,
        ),
    )
    val installState = InstallBundleUiState(
        variants = variants,
        selectedVariantPath = primaryPath,
        components = listOf(
            setupComponent(ComponentRole.VAE, "ae.safetensors", "335 MB"),
            setupComponent(ComponentRole.CLIP_L, "clip_l.safetensors", "246 MB"),
            setupComponent(ComponentRole.T5XXL, "t5xxl_fp16.safetensors", "9.8 GB"),
        ),
        totalNewDownloadBytes = 14_495_514_624L,
        isSelfContained = false,
    )
    return InstallFixture(
        installState = installState,
        recommendation = RecommendedModelUiState(
            sourceModel = ListModelsResponse.Model(id = repositoryId),
            repositoryId = repositoryId,
            descriptorState = DescriptorState.ASSESSED,
            objectiveAssessment = null,
            personalizedResult = null,
            selectedVariantName = "Q4",
            stableModelId = repositoryId,
            sourceIndex = 0,
            selectedDescriptor = descriptor,
        ),
    )
}

private fun setupComponent(
    role: ComponentRole,
    filePath: String,
    sizeHint: String,
): SetupComponentUiState = SetupComponentUiState(
    role = role,
    repoId = "org/components",
    filePath = filePath,
    sizeHint = sizeHint,
    isDownloaded = false,
    progress = null,
    required = true,
)
