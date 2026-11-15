package com.debanshu777.caraml.core.navigation

import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.CompositionLocalProvider
import com.debanshu777.caraml.core.ui.motion.LocalModelIdentityNavigation
import com.debanshu777.caraml.core.ui.motion.LocalModelSharedTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.metadata
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.drawer.LocalGenerationModeController
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.chat.domain.matchesGenerationMode
import com.debanshu777.caraml.features.chat.presentation.ChatScreen
import com.debanshu777.caraml.features.chat.presentation.ChatViewModel
import com.debanshu777.caraml.features.modelhub.presentation.details.DetailsScreen
import com.debanshu777.caraml.features.modelhub.presentation.downloaded.DownloadedModelsViewModel
import com.debanshu777.caraml.features.modelhub.presentation.downloaded.DownloadsScreen
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelDeviceScreen
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelViewModel
import com.debanshu777.caraml.features.modelhub.presentation.search.SearchScreen
import com.debanshu777.caraml.features.settings.presentation.SettingsScreen
import com.debanshu777.caraml.features.settings.presentation.SettingsViewModel
import com.debanshu777.caraml.core.ui.motion.AuroraMotionPolicy
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import org.koin.compose.viewmodel.koinViewModel
import com.debanshu777.caraml.core.performance.LocalChatInventoryReporter

internal enum class NavigationTransitionFamily {
    Peer,
    Hierarchical,
}

internal enum class NavigationTransitionDirection {
    Forward,
    Pop,
}

internal fun returnHomeAfterModelSelection(backStack: NavBackStack<NavKey>) {
    Snapshot.withMutableSnapshot {
        backStack.clear()
        backStack.add(AppScreen.Home)
    }
}

internal fun openDownloads(backStack: NavBackStack<NavKey>) {
    backStack.add(AppScreen.Downloads)
}

internal fun popNavigationEntry(backStack: NavBackStack<NavKey>) {
    if (backStack.size > 1) backStack.removeLastOrNull()
}

internal enum class NavigationTransitionAxis {
    None,
    Horizontal,
}

internal data class NavigationTransitionDescriptor(
    val family: NavigationTransitionFamily,
    val direction: NavigationTransitionDirection,
    val axis: NavigationTransitionAxis,
    val enterDurationMillis: Int,
    val exitDurationMillis: Int,
    private val detailOffsetPx: Int,
) {
    private val directionSign: Int
        get() = if (direction == NavigationTransitionDirection.Pop) -1 else 1

    fun enterOffset(containerSize: Int): Int = spatialOffset(containerSize) * directionSign

    fun exitOffset(containerSize: Int): Int = -spatialOffset(containerSize) * directionSign

    private fun spatialOffset(containerSize: Int): Int = when (axis) {
        NavigationTransitionAxis.None -> 0
        NavigationTransitionAxis.Horizontal -> detailOffsetPx
    }
}

private const val PeerTransitionMillis = 180
private const val DetailEnterMillis = 220
private const val DetailExitMillis = 180
private const val ReducedMotionTransitionMillis = 90

internal fun navigationTransitionFamily(target: NavKey?): NavigationTransitionFamily =
    if (target is AppScreen.Details) {
        NavigationTransitionFamily.Hierarchical
    } else {
        NavigationTransitionFamily.Peer
    }

internal fun navigationTransitionDescriptor(
    family: NavigationTransitionFamily,
    direction: NavigationTransitionDirection,
    motionPolicy: AuroraMotionPolicy,
    detailOffsetPx: Int,
): NavigationTransitionDescriptor = if (!motionPolicy.spatialTransitionsEnabled) {
    NavigationTransitionDescriptor(
        family = family,
        direction = direction,
        axis = NavigationTransitionAxis.None,
        enterDurationMillis = minOf(
            motionPolicy.opacityDurationMillis,
            ReducedMotionTransitionMillis,
        ),
        exitDurationMillis = minOf(
            motionPolicy.opacityDurationMillis,
            ReducedMotionTransitionMillis,
        ),
        detailOffsetPx = detailOffsetPx,
    )
} else {
    NavigationTransitionDescriptor(
        family = family,
        direction = direction,
        axis = when (family) {
            NavigationTransitionFamily.Peer -> NavigationTransitionAxis.None
            NavigationTransitionFamily.Hierarchical -> NavigationTransitionAxis.Horizontal
        },
        enterDurationMillis = when (family) {
            NavigationTransitionFamily.Peer -> PeerTransitionMillis
            NavigationTransitionFamily.Hierarchical -> DetailEnterMillis
        },
        exitDurationMillis = when (family) {
            NavigationTransitionFamily.Peer -> PeerTransitionMillis
            NavigationTransitionFamily.Hierarchical -> DetailExitMillis
        },
        detailOffsetPx = detailOffsetPx,
    )
}

private fun navigationContentTransform(
    descriptor: NavigationTransitionDescriptor,
): ContentTransform = when (descriptor.axis) {
    NavigationTransitionAxis.None ->
        fadeIn(tween(descriptor.enterDurationMillis)) togetherWith
            fadeOut(tween(descriptor.exitDurationMillis))

    NavigationTransitionAxis.Horizontal -> {
        val enter = fadeIn(tween(descriptor.enterDurationMillis)) +
            slideInHorizontally(tween(descriptor.enterDurationMillis)) {
                descriptor.enterOffset(it)
            }
        val exit = fadeOut(tween(descriptor.exitDurationMillis)) +
            slideOutHorizontally(tween(descriptor.exitDurationMillis)) {
                descriptor.exitOffset(it)
            }
        enter togetherWith exit
    }
}

@Composable
internal fun NavigationTransitionDisplay(
    modifier: Modifier,
    backStack: NavBackStack<NavKey>,
    motionPolicy: AuroraMotionPolicy,
    detailOffsetPx: Int,
    entryDecorators: List<NavEntryDecorator<NavKey>>,
    entryProvider: (NavKey) -> NavEntry<NavKey>,
) {
    SharedTransitionLayout(modifier) {
      CompositionLocalProvider(
          LocalModelSharedTransitionScope provides if (motionPolicy.spatialTransitionsEnabled) this else null,
          LocalModelIdentityNavigation provides ((backStack.lastOrNull() as? AppScreen.Details)?.preview?.transitionKey != null),
      ) {
    NavDisplay(
        modifier = Modifier,
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
        entryDecorators = entryDecorators,
        entryProvider = entryProvider,
        transitionSpec = {
            navigationContentTransform(
                navigationTransitionDescriptor(
                    family = navigationTransitionFamily(backStack.lastOrNull()),
                    direction = NavigationTransitionDirection.Forward,
                    motionPolicy = motionPolicy,
                    detailOffsetPx = detailOffsetPx,
                ),
            )
        },
        popTransitionSpec = {
            navigationContentTransform(
                navigationTransitionDescriptor(
                    family = NavigationTransitionFamily.Peer,
                    direction = NavigationTransitionDirection.Pop,
                    motionPolicy = motionPolicy,
                    detailOffsetPx = detailOffsetPx,
                ),
            )
        },
        predictivePopTransitionSpec = { _ ->
            navigationContentTransform(
                navigationTransitionDescriptor(
                    family = NavigationTransitionFamily.Peer,
                    direction = NavigationTransitionDirection.Pop,
                    motionPolicy = motionPolicy,
                    detailOffsetPx = detailOffsetPx,
                ),
            )
        },
    )
      }
    }
}

@Composable
fun NavigationHost(
    modifier: Modifier,
    backStack: NavBackStack<NavKey>,
) {
    val motionPolicy = LocalAuroraMotionPolicy.current
    val detailOffsetPx = with(LocalDensity.current) { AppTheme.spacing.spacing16.roundToPx() }
    val modeController = LocalGenerationModeController.current
    val chatViewModel: ChatViewModel = koinViewModel()
    val modelViewModel: ModelViewModel = koinViewModel()
    val downloadedModelsViewModel: DownloadedModelsViewModel = koinViewModel()
    val settingsViewModel: SettingsViewModel = koinViewModel()
    // Read during composition so inventory changes invalidate this reporting scope.
    val inventoryInitialized = chatViewModel.inventoryInitialized.collectAsState().value
    val reportInventory = LocalChatInventoryReporter.current
    SideEffect { reportInventory(inventoryInitialized) }

    NavigationTransitionDisplay(
        modifier = modifier,
        backStack = backStack,
        motionPolicy = motionPolicy,
        detailOffsetPx = detailOffsetPx,
        entryDecorators =
            listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
        entryProvider =
            entryProvider {
                entry(AppScreen.Home) {
                    ChatScreen(
                        viewModel = chatViewModel,
                        onNavigateToSearch = { backStack.add(AppScreen.Search) },
                        onNavigateToModelDetail = { modelId, mode ->
                            backStack.add(AppScreen.Search)
                            backStack.add(AppScreen.Details(modelId, mode))
                        },
                    )
                }
                entry(AppScreen.Search) {
                    SearchScreen(
                        modelViewModel = modelViewModel,
                        settingsViewModel = settingsViewModel,
                        downloadedModelsViewModel = downloadedModelsViewModel,
                        onOpenDownloads = { openDownloads(backStack) },
                        onNavigateToDetails = { modelId, hubMode, preview ->
                            backStack.add(AppScreen.Details(modelId, hubMode, preview))
                        },
                    )
                }
                entry(AppScreen.Device) {
                    ModelDeviceScreen(modelViewModel, settingsViewModel, onBack = { popNavigationEntry(backStack) })
                }
                entry(AppScreen.Downloads) {
                    DownloadsScreen(
                        modelViewModel = modelViewModel,
                        downloadedModelsViewModel = downloadedModelsViewModel,
                        onBack = { popNavigationEntry(backStack) },
                        onSelectModelAndGoBack = { model ->
                            val mode = modeController.mode.takeIf(model::matchesGenerationMode)
                                ?: GenerationMode.entries.firstOrNull(model::matchesGenerationMode)
                            if (mode != null) {
                                modeController.setState(mode)
                                chatViewModel.selectModel(model, mode)
                                returnHomeAfterModelSelection(backStack)
                            }
                        },
                        onNavigateToDetails = { modelId, hubMode ->
                            backStack.add(AppScreen.Details(modelId, hubMode))
                        },
                        onExploreModels = {
                            if (backStack.getOrNull(backStack.lastIndex - 1) == AppScreen.Search) {
                                popNavigationEntry(backStack)
                            } else {
                                backStack.add(AppScreen.Search)
                            }
                        },
                    )
                }
                entry<AppScreen.Details>(
                    metadata = metadata {
                        put(NavDisplay.PopTransitionKey) {
                            navigationContentTransform(
                                navigationTransitionDescriptor(
                                    family = NavigationTransitionFamily.Hierarchical,
                                    direction = NavigationTransitionDirection.Pop,
                                    motionPolicy = motionPolicy,
                                    detailOffsetPx = detailOffsetPx,
                                ),
                            )
                        }
                        put(NavDisplay.PredictivePopTransitionKey) { _ ->
                            navigationContentTransform(
                                navigationTransitionDescriptor(
                                    family = NavigationTransitionFamily.Hierarchical,
                                    direction = NavigationTransitionDirection.Pop,
                                    motionPolicy = motionPolicy,
                                    detailOffsetPx = detailOffsetPx,
                                ),
                            )
                        }
                    },
                ) { key ->
                    DetailsScreen(
                        viewModel = modelViewModel,
                        modelId = key.modelId,
                        hubBrowseMode = key.hubBrowseMode,
                        preview = key.preview,
                        onBack = { popNavigationEntry(backStack) },
                    )
                }
                entry(AppScreen.Settings) {
                    SettingsScreen(
                        viewModel = settingsViewModel,
                        onOpenDownloads = { openDownloads(backStack) },
                    )
                }
            },
    )
}
