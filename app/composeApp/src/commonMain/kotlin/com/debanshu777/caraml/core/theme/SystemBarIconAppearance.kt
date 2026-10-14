package com.debanshu777.caraml.core.theme

data class SystemBarIconAppearance(
    val useDarkStatusBarIcons: Boolean,
    val useDarkNavigationBarIcons: Boolean,
)

fun systemBarIconAppearance(darkTheme: Boolean): SystemBarIconAppearance =
    SystemBarIconAppearance(
        useDarkStatusBarIcons = !darkTheme,
        useDarkNavigationBarIcons = !darkTheme,
    )
