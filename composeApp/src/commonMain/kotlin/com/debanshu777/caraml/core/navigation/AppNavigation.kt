package com.debanshu777.caraml.core.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
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
import com.debanshu777.caraml.features.chat.presentation.ChatScreen
import com.debanshu777.caraml.features.chat.presentation.ChatViewModel
import com.debanshu777.caraml.features.modelhub.presentation.details.DetailsScreen
import com.debanshu777.caraml.features.modelhub.presentation.downloaded.DownloadedModelsViewModel
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelViewModel
import com.debanshu777.caraml.features.modelhub.presentation.search.SearchScreen
import com.debanshu777.caraml.features.settings.presentation.SettingsScreen
import com.debanshu777.caraml.features.settings.presentation.SettingsViewModel
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import com.debanshu777.caraml.core.ui.motion.AuroraMotionPolicy
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import org.koin.compose.viewmodel.koinViewModel

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
    NavDisplay(
        modifier = modifier,
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

@Composable
fun NavigationHost(
    modifier: Modifier,
    backStack: NavBackStack<NavKey>,
) {
    val motionPolicy = LocalAuroraMotionPolicy.current
    val detailOffsetPx = with(LocalDensity.current) { 16.dp.roundToPx() }
    val chatViewModel: ChatViewModel = koinViewModel()
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
                    val modelViewModel: ModelViewModel = koinViewModel()
                    val downloadedModelsViewModel: DownloadedModelsViewModel = koinViewModel()
                    SearchScreen(
                        modelViewModel = modelViewModel,
                        downloadedModelsViewModel = downloadedModelsViewModel,
                        onNavigateToDetails = { modelId, hubMode ->
                            backStack.add(AppScreen.Details(modelId, hubMode))
                        },
                        onSelectModelAndGoBack = { model ->
                            chatViewModel.selectModel(model)
                            returnHomeAfterModelSelection(backStack)
                        }
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
                    val modelViewModel: ModelViewModel = koinViewModel()
                    DetailsScreen(
                        viewModel = modelViewModel,
                        modelId = key.modelId,
                        hubBrowseMode = key.hubBrowseMode,
                        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                    )
                }
                entry(AppScreen.Settings) {
                    val settingsViewModel: SettingsViewModel = koinViewModel()
                    SettingsScreen(
                        viewModel = settingsViewModel
                    )
                }
            },
    )
}
