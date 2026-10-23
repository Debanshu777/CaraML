package com.debanshu777.caraml

import android.os.Bundle
import android.Manifest
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.view.WindowCompat
import com.debanshu777.caraml.core.settings.initPreferencesDataStore
import com.debanshu777.caraml.core.performance.LocalAppStartupReporter
import com.debanshu777.caraml.core.theme.systemBarIconAppearance
import com.debanshu777.caraml.core.download.AndroidDownloadNotificationPermissionController
import org.koin.mp.KoinPlatform

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        initPreferencesDataStore(applicationContext)
        KoinPlatform.getKoin().get<AndroidDownloadNotificationPermissionController>().attach {
            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            var startupReady by remember { mutableStateOf(false) }
            ReportDrawnWhen { startupReady }
            CompositionLocalProvider(LocalAppStartupReporter provides { startupReady = it }) {
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    App(onEffectiveDarkThemeChanged = ::syncSystemBarAppearance)
                }
            }
        }
    }

    override fun onDestroy() {
        KoinPlatform.getKoin().get<AndroidDownloadNotificationPermissionController>().detach()
        super.onDestroy()
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
