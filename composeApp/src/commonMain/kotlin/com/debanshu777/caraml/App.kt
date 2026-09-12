package com.debanshu777.caraml

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.savedstate.serialization.SavedStateConfiguration
import com.debanshu777.caraml.core.drawer.AppDrawerShell
import com.debanshu777.caraml.core.navigation.AppScreen
import com.debanshu777.caraml.core.navigation.NavigationHost
import com.debanshu777.caraml.core.platform.AppLogger
import com.debanshu777.caraml.core.recommendation.LoadSessionCoordinator
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemeViewModel
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.serializer
import kotlinx.coroutines.CancellationException
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

private val config =
    SavedStateConfiguration {
        serializersModule =
            SerializersModule {
                polymorphic(NavKey::class) {
                    subclass(AppScreen.Home::class, serializer<AppScreen.Home>())
                    subclass(AppScreen.Search::class, serializer<AppScreen.Search>())
                    subclass(AppScreen.Details::class, serializer<AppScreen.Details>())
                    subclass(AppScreen.Settings::class, serializer<AppScreen.Settings>())
                }
            }
    }

@Composable
fun App() {
    val themeViewModel: ThemeViewModel = koinViewModel()
    val loadSessionCoordinator: LoadSessionCoordinator = koinInject()
    LaunchedEffect(loadSessionCoordinator) {
        try {
            loadSessionCoordinator.recoverAbandonedLoadAtStartup()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            AppLogger.e("LoadRecovery", "Startup recovery failed")
        }
    }
    val themePreferences by themeViewModel.preferences.collectAsState()
    CaraMLTheme(themePreferences) {
        Surface(
            modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.ime)
        ) {
            val backStack = rememberNavBackStack(config, AppScreen.Home)
            AppDrawerShell(backStack = backStack) {
                NavigationHost(
                    modifier = Modifier.fillMaxSize(),
                    backStack = backStack,
                )
            }
        }
    }
}
