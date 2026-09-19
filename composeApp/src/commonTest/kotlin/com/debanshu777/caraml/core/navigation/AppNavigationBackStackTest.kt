package com.debanshu777.caraml.core.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlin.test.Test
import kotlin.test.assertEquals

class AppNavigationBackStackTest {
    @Test
    fun selectingInstalledModelFromRootSearchLeavesHomeAsNonEmptyRoot() {
        val backStack = NavBackStack<NavKey>(AppScreen.Search)

        returnHomeAfterModelSelection(backStack)

        assertEquals(listOf(AppScreen.Home), backStack.toList())
    }

    @Test
    fun selectingInstalledModelFromNestedSearchCollapsesToHomeRoot() {
        val backStack = NavBackStack<NavKey>(AppScreen.Home, AppScreen.Search)

        returnHomeAfterModelSelection(backStack)

        assertEquals(listOf(AppScreen.Home), backStack.toList())
    }
}
