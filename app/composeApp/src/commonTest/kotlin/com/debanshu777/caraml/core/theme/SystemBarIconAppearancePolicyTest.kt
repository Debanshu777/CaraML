package com.debanshu777.caraml.core.theme

import kotlin.test.Test
import kotlin.test.assertEquals

class SystemBarIconAppearancePolicyTest {

    @Test
    fun lightThemeUsesDarkSystemBarIcons() {
        assertEquals(
            SystemBarIconAppearance(
                useDarkStatusBarIcons = true,
                useDarkNavigationBarIcons = true,
            ),
            systemBarIconAppearance(darkTheme = false),
        )
    }

    @Test
    fun darkThemeUsesLightSystemBarIcons() {
        assertEquals(
            SystemBarIconAppearance(
                useDarkStatusBarIcons = false,
                useDarkNavigationBarIcons = false,
            ),
            systemBarIconAppearance(darkTheme = true),
        )
    }
}
