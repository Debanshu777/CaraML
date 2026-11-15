package com.debanshu777.caraml.features.modelhub.presentation.details

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction

/** Scroll vertically past a horizontal pager; performScrollTo targets the nearest scrollable axis. */
internal fun SemanticsNodeInteraction.scrollDetailsIntoView(): SemanticsNodeInteraction {
    val target = fetchSemanticsNode()
    if (generateSequence(target) { it.parent }.any {
        it.config.getOrNull(SemanticsProperties.TestTag) == "page-sticky-tabs"
    }) return this
    var root = target
    while (root.parent != null) root = root.parent!!
    val chromeBottom = root.stickyChromeBottom()
    var ancestor = target.parent
    var interaction = this
    while (ancestor != null) {
        interaction = interaction.onParent()
        if (SemanticsProperties.VerticalScrollAxisRange in ancestor.config && SemanticsActions.ScrollBy in ancestor.config) {
            val viewport = ancestor.boundsInRoot
            // Fixed chrome overlays the scroller; a node at y=0 can exist but cannot be tapped.
            val visibleTop = maxOf(viewport.top, chromeBottom)
            val top = target.positionInRoot.y
            val bottom = top + target.size.height
            val delta = when {
                target.size.height > viewport.bottom - visibleTop -> top - visibleTop
                top < visibleTop -> top - visibleTop
                bottom > viewport.bottom -> bottom - viewport.bottom
                else -> 0f
            }
            if (delta != 0f) interaction.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
            return this
        }
        ancestor = ancestor.parent
    }
    return performScrollTo()
}

private fun SemanticsNode.stickyChromeBottom(): Float =
    if (config.getOrNull(SemanticsProperties.TestTag) in setOf("page-sticky-chrome", "page-sticky-tabs")) boundsInRoot.bottom
    else children.maxOfOrNull { it.stickyChromeBottom() } ?: 0f
