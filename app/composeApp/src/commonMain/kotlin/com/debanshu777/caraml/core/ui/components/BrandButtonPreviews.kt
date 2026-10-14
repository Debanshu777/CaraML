package com.debanshu777.caraml.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.drawer.BrandNavigationIcons
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.theme.CaraMLTheme
import com.debanshu777.caraml.core.theme.ThemeMode
import com.debanshu777.caraml.core.theme.ThemePreferences

@Preview(name = "Raised buttons — light", widthDp = 320)
@Composable
private fun BrandButtonsLightPreview() = BrandButtonGallery(ThemeMode.LIGHT)

@Preview(name = "Raised buttons — dark", widthDp = 320)
@Composable
private fun BrandButtonsDarkPreview() = BrandButtonGallery(ThemeMode.DARK)

@Composable
private fun BrandButtonGallery(mode: ThemeMode) {
    CaraMLTheme(ThemePreferences(themeMode = mode)) {
        Surface(color = AppTheme.colors.background) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BrandButton({}) { Text("Let's go") }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BrandButton({}, style = BrandButtonStyle.Secondary) { Text("Cancel") }
                    BrandButton({}, style = BrandButtonStyle.Destructive) { Text("Delete") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BrandButton({}, enabled = false) { Text("Unavailable") }
                    BrandIconButton({}) { Icon(BrandNavigationIcons.Close, "Close") }
                }
            }
        }
    }
}
