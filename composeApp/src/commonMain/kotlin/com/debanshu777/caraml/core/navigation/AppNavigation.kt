package com.debanshu777.caraml.core.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.metadata
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.debanshu777.caraml.core.recommendation.RecommendationRolloutModeSource
import com.debanshu777.caraml.features.chat.presentation.ChatScreen
import com.debanshu777.caraml.features.chat.presentation.ChatViewModel
import com.debanshu777.caraml.features.modelhub.presentation.details.DetailsScreen
import com.debanshu777.caraml.features.modelhub.presentation.downloaded.DownloadedModelsViewModel
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelViewModel
import com.debanshu777.caraml.features.modelhub.presentation.search.RecommendedModelLoadRequestResolver
import com.debanshu777.caraml.features.modelhub.presentation.search.SearchScreen
import com.debanshu777.caraml.features.modelhub.presentation.search.routeRecommendedModelSelection
import com.debanshu777.caraml.features.settings.presentation.SettingsScreen
import com.debanshu777.caraml.features.settings.presentation.SettingsViewModel
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import com.debanshu777.caraml.core.ui.motion.AuroraMotionPolicy
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import org.koin.compose.viewmodel.koinViewModel
import org.koin.compose.koinInject
import kotlinx.coroutines.launch

internal enum class NavigationTransitionFamily {
    Peer,
    Hierarchical,
}

internal fun navigationTransitionFamily(target: NavKey?): NavigationTransitionFamily =
    if (target is AppScreen.Details) {
        NavigationTransitionFamily.Hierarchical
    } else {
        NavigationTransitionFamily.Peer
    }

private fun navigationContentTransform(
    family: NavigationTransitionFamily,
    motionPolicy: AuroraMotionPolicy,
    reverse: Boolean,
    peerOffsetPx: Int,
): ContentTransform {
    if (!motionPolicy.spatialTransitionsEnabled) {
        return fadeIn(tween(motionPolicy.opacityDurationMillis)) togetherWith
            fadeOut(tween(motionPolicy.opacityDurationMillis))
    }

    return when (family) {
        NavigationTransitionFamily.Peer -> {
            val direction = if (reverse) -1 else 1
            val enter = fadeIn(tween(motionPolicy.peerTransitionMillis)) +
                slideInVertically(tween(motionPolicy.peerTransitionMillis)) {
                    peerOffsetPx * direction
                }
            val exit = fadeOut(tween(motionPolicy.peerTransitionMillis)) +
                slideOutVertically(tween(motionPolicy.peerTransitionMillis)) {
                    -peerOffsetPx * direction
                }
            enter togetherWith exit
        }

        NavigationTransitionFamily.Hierarchical -> {
            val direction = if (reverse) -1 else 1
            val enter = fadeIn(tween(motionPolicy.detailEnterMillis)) +
                slideInHorizontally(tween(motionPolicy.detailEnterMillis)) {
                    (it / 10) * direction
                }
            val exit = fadeOut(tween(motionPolicy.exitMillis)) +
                slideOutHorizontally(tween(motionPolicy.exitMillis)) {
                    -(it / 10) * direction
                }
            enter togetherWith exit
        }
    }
}

@Composable
fun NavigationHost(
    modifier: Modifier,
    backStack: NavBackStack<NavKey>,
) {
    val motionPolicy = LocalAuroraMotionPolicy.current
    val peerOffsetPx = with(LocalDensity.current) { 6.dp.roundToPx() }
    val chatViewModel: ChatViewModel = koinViewModel()
    val modelLoadRequestResolver: RecommendedModelLoadRequestResolver = koinInject()
    val recommendationRolloutModeSource: RecommendationRolloutModeSource = koinInject()
    val selectionScope = rememberCoroutineScope()
    NavDisplay(
        modifier = modifier,
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
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
                            routeRecommendedModelSelection(
                                mode = recommendationRolloutModeSource.current(),
                                selectLegacy = {
                                    chatViewModel.selectModel(model)
                                    if (backStack.lastOrNull() == AppScreen.Search) {
                                        backStack.removeLastOrNull()
                                    }
                                },
                                selectAssessed = {
                                    val recommendationStates = modelViewModel.recommendedModels.value
                                    selectionScope.launch {
                                        val loadRequest = modelLoadRequestResolver.resolve(
                                            model = model,
                                            states = recommendationStates,
                                        )
                                        chatViewModel.selectModel(model, loadRequest)
                                        if (backStack.lastOrNull() == AppScreen.Search) {
                                            backStack.removeLastOrNull()
                                        }
                                    }
                                },
                            )
                        }
                    )
                }
                entry<AppScreen.Details>(
                    metadata = metadata {
                        put(NavDisplay.PopTransitionKey) {
                            navigationContentTransform(
                                family = NavigationTransitionFamily.Hierarchical,
                                motionPolicy = motionPolicy,
                                reverse = true,
                                peerOffsetPx = peerOffsetPx,
                            )
                        }
                        put(NavDisplay.PredictivePopTransitionKey) { _ ->
                            navigationContentTransform(
                                family = NavigationTransitionFamily.Hierarchical,
                                motionPolicy = motionPolicy,
                                reverse = true,
                                peerOffsetPx = peerOffsetPx,
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
        transitionSpec = {
            navigationContentTransform(
                family = navigationTransitionFamily(backStack.lastOrNull()),
                motionPolicy = motionPolicy,
                reverse = false,
                peerOffsetPx = peerOffsetPx,
            )
        },
        popTransitionSpec = {
            navigationContentTransform(
                family = NavigationTransitionFamily.Peer,
                motionPolicy = motionPolicy,
                reverse = true,
                peerOffsetPx = peerOffsetPx,
            )
        },
        predictivePopTransitionSpec = { _ ->
            navigationContentTransform(
                family = NavigationTransitionFamily.Peer,
                motionPolicy = motionPolicy,
                reverse = true,
                peerOffsetPx = peerOffsetPx,
            )
        },
    )
}
