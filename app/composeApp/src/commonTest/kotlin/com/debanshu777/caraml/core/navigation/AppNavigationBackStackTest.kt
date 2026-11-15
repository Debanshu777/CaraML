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

    @Test
    fun openingDownloadsKeepsSearchAndItsEntryStateOnTheBackStack() {
        val backStack = NavBackStack<NavKey>(AppScreen.Home, AppScreen.Search)

        openDownloads(backStack)

        assertEquals(listOf(AppScreen.Home, AppScreen.Search, AppScreen.Downloads), backStack.toList())
        popNavigationEntry(backStack)
        assertEquals(listOf(AppScreen.Home, AppScreen.Search), backStack.toList())
    }

    @Test
    fun downloadsCanBeOpenedDirectlyAndDetailsReturnToDownloads() {
        val backStack = NavBackStack<NavKey>(AppScreen.Home)

        openDownloads(backStack)
        backStack.add(AppScreen.Details("owner/model"))
        popNavigationEntry(backStack)

        assertEquals(listOf(AppScreen.Home, AppScreen.Downloads), backStack.toList())
    }

    @Test
    fun selectingInstalledModelFromDownloadsReturnsToHome() {
        val backStack = NavBackStack<NavKey>(AppScreen.Home, AppScreen.Downloads)

        returnHomeAfterModelSelection(backStack)

        assertEquals(listOf(AppScreen.Home), backStack.toList())
    }
}
