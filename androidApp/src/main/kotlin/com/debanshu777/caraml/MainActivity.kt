package com.debanshu777.caraml

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.view.WindowCompat
import com.debanshu777.caraml.core.settings.initPreferencesDataStore
import com.debanshu777.caraml.core.theme.systemBarIconAppearance

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        initPreferencesDataStore(applicationContext)
        setContent {
            App(onEffectiveDarkThemeChanged = ::syncSystemBarAppearance)
        }
    }

    private fun syncSystemBarAppearance(darkTheme: Boolean) {
        val appearance = systemBarIconAppearance(darkTheme)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = appearance.useDarkStatusBarIcons
            isAppearanceLightNavigationBars = appearance.useDarkNavigationBarIcons
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
