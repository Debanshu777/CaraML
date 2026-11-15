package com.debanshu777.caraml.features.modelhub.presentation.details.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester

/** Keeps the scrolling placeholder and the pinned tab row attached to one pager. */
@Stable
class ModelDetailTabsState internal constructor(
    internal val pager: PagerState,
    internal val scroll: ScrollState,
    private val sticky: Boolean,
) {
    internal var pinOffset by mutableIntStateOf(0)
    internal var height by mutableIntStateOf(0)
    internal var available by mutableStateOf(false)
    internal val focusRequesters = List(3) { FocusRequester() }
    internal var focusedTab by mutableIntStateOf(-1)
    internal var restoringFocus by mutableStateOf(false)
    internal val pinned by derivedStateOf { sticky && available && height > 0 && scroll.value >= pinOffset }

    internal suspend fun select(index: Int, animate: Boolean) {
        if (index == pager.currentPage) return
        if (pinned) scroll.scrollTo(pinOffset)
        if (animate) pager.animateScrollToPage(index) else pager.scrollToPage(index)
    }
}

@Composable
internal fun rememberModelDetailTabsState(sticky: Boolean = false): ModelDetailTabsState {
    val pager = rememberPagerState(pageCount = { 3 })
    val scroll = rememberScrollState()
    return remember(pager, scroll, sticky) { ModelDetailTabsState(pager, scroll, sticky) }
}
