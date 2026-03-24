package com.debanshu777.caraml.core.navigation

import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
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
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun NavigationHost(
    modifier: Modifier,
    backStack: NavBackStack<NavKey>,
) {
    val chatViewModel: ChatViewModel = koinViewModel()
    NavDisplay(
        modifier = modifier,
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
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
                    )
                }
                entry(AppScreen.Search) {
                    val modelViewModel: ModelViewModel = koinViewModel()
                    val downloadedModelsViewModel: DownloadedModelsViewModel = koinViewModel()
                    SearchScreen(
                        modelViewModel = modelViewModel,
                        downloadedModelsViewModel = downloadedModelsViewModel,
                        onNavigateToDetails = { backStack.add(AppScreen.Details(it)) },
                        onSelectModelAndGoBack = { model ->
                            chatViewModel.selectModel(model)
                            backStack.removeLastOrNull()
                        }
                    )
                }
                entry<AppScreen.Details> { key ->
                    val modelViewModel: ModelViewModel = koinViewModel()
                    DetailsScreen(
                        viewModel = modelViewModel,
                        modelId = key.modelId,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry(AppScreen.Settings) {
                    val settingsViewModel: SettingsViewModel = koinViewModel()
                    val isRootDestination = backStack.size == 1
                    SettingsScreen(
                        viewModel = settingsViewModel,
                        onBack = { backStack.removeLastOrNull() },
                        isRootDestination = isRootDestination,
                    )
                }
            },
        transitionSpec = {
            // Slide in from right when navigating forward
            slideInHorizontally(initialOffsetX = { it }) togetherWith
                    slideOutHorizontally(targetOffsetX = { -it })
        },
        popTransitionSpec = {
            // Slide in from left when navigating back
            slideInHorizontally(initialOffsetX = { -it }) togetherWith
                    slideOutHorizontally(targetOffsetX = { it })
        },
        predictivePopTransitionSpec = {
            // Slide in from left when navigating back
            slideInHorizontally(initialOffsetX = { -it }) togetherWith
                    slideOutHorizontally(targetOffsetX = { it })
        },
    )
}
