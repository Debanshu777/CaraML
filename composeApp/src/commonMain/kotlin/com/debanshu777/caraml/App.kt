package com.debanshu777.caraml

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.savedstate.serialization.SavedStateConfiguration
import com.debanshu777.caraml.ui.navigation.NavigableScreen
import com.debanshu777.caraml.ui.navigation.NavigationHost
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import kotlinx.serialization.serializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import org.koin.compose.koinInject

private val config =
    SavedStateConfiguration {
        serializersModule =
            SerializersModule {
                polymorphic(NavKey::class) {
                    subclass(NavigableScreen.Home::class, serializer<NavigableScreen.Home>())
                    subclass(NavigableScreen.Search::class, serializer<NavigableScreen.Search>())
                    subclass(NavigableScreen.Details::class, serializer<NavigableScreen.Details>())
                    subclass(NavigableScreen.Chat::class, serializer<NavigableScreen.Chat>())
                }
            }
    }

@Composable
fun App() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val backStack = rememberNavBackStack(config, NavigableScreen.Home)
            NavigationHost(
                modifier = Modifier.fillMaxSize(),
                backStack = backStack,
            )
        }
    }
}
