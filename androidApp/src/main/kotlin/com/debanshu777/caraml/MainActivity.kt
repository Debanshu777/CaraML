package com.debanshu777.caraml

import android.os.Bundle
import android.Manifest
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.view.WindowCompat
import com.debanshu777.caraml.core.settings.initPreferencesDataStore
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
            App(onEffectiveDarkThemeChanged = ::syncSystemBarAppearance)
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
