package com.debanshu777.caraml.core.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.debanshu777.caraml.core.ui.motion.auroraMotionPolicy
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import kotlin.test.Test
import kotlin.test.assertEquals

class NavigationTransitionPolicyTest {

    @Test
    fun modelSelectionReturnsToCreateWithoutEverEmptyingRootedModelsStack() {
        val rootedModels = NavBackStack<NavKey>(AppScreen.Search)
        val nestedModels = NavBackStack<NavKey>(AppScreen.Home, AppScreen.Search)

        returnFromModelSelection(rootedModels)
        returnFromModelSelection(nestedModels)

        assertEquals(listOf(AppScreen.Home), rootedModels.toList())
        assertEquals(listOf(AppScreen.Home), nestedModels.toList())
    }

    @Test
    fun primaryDestinationsUsePeerMotionAndDetailsUseHierarchicalMotion() {
        assertEquals(NavigationTransitionFamily.Peer, navigationTransitionFamily(AppScreen.Home))
        assertEquals(NavigationTransitionFamily.Peer, navigationTransitionFamily(AppScreen.Search))
        assertEquals(NavigationTransitionFamily.Peer, navigationTransitionFamily(AppScreen.Settings))
        assertEquals(
            NavigationTransitionFamily.Hierarchical,
            navigationTransitionFamily(
                AppScreen.Details("org/model", ModelHubBrowseMode.LanguageModels),
            ),
        )
    }

    @Test
    fun forwardAndPopDescriptorsPreserveEachTransitionFamilyContract() {
        val motion = auroraMotionPolicy(durationScale = 1f)

        val cases = listOf(
            TransitionExpectation(
                family = NavigationTransitionFamily.Peer,
                direction = NavigationTransitionDirection.Forward,
                expectedAxis = NavigationTransitionAxis.None,
                expectedEnterMillis = 180,
                expectedExitMillis = 180,
                expectedEnterOffset = 0,
                expectedExitOffset = 0,
            ),
            TransitionExpectation(
                family = NavigationTransitionFamily.Peer,
                direction = NavigationTransitionDirection.Pop,
                expectedAxis = NavigationTransitionAxis.None,
                expectedEnterMillis = 180,
                expectedExitMillis = 180,
                expectedEnterOffset = 0,
                expectedExitOffset = 0,
            ),
            TransitionExpectation(
                family = NavigationTransitionFamily.Hierarchical,
                direction = NavigationTransitionDirection.Forward,
                expectedAxis = NavigationTransitionAxis.Horizontal,
                expectedEnterMillis = 220,
                expectedExitMillis = 180,
                expectedEnterOffset = 16,
                expectedExitOffset = -16,
            ),
            TransitionExpectation(
                family = NavigationTransitionFamily.Hierarchical,
                direction = NavigationTransitionDirection.Pop,
                expectedAxis = NavigationTransitionAxis.Horizontal,
                expectedEnterMillis = 220,
                expectedExitMillis = 180,
                expectedEnterOffset = -16,
                expectedExitOffset = 16,
            ),
        )

        cases.forEach { expected ->
            val descriptor = navigationTransitionDescriptor(
                family = expected.family,
                direction = expected.direction,
                motionPolicy = motion,
                detailOffsetPx = 16,
            )

            assertEquals(expected.family, descriptor.family)
            assertEquals(expected.direction, descriptor.direction)
            assertEquals(expected.expectedAxis, descriptor.axis)
            assertEquals(expected.expectedEnterMillis, descriptor.enterDurationMillis)
            assertEquals(expected.expectedExitMillis, descriptor.exitDurationMillis)
            assertEquals(expected.expectedEnterOffset, descriptor.enterOffset(containerSize = 1_000))
            assertEquals(expected.expectedExitOffset, descriptor.exitOffset(containerSize = 1_000))
        }
    }

    @Test
    fun reducedMotionDescriptorsAreFadeOnlyForEveryFamilyAndDirection() {
        val reducedMotion = auroraMotionPolicy(durationScale = 0f)

        NavigationTransitionFamily.entries.forEach { family ->
            NavigationTransitionDirection.entries.forEach { direction ->
                val descriptor = navigationTransitionDescriptor(
                    family = family,
                    direction = direction,
                    motionPolicy = reducedMotion,
                    detailOffsetPx = 16,
                )

                assertEquals(family, descriptor.family)
                assertEquals(direction, descriptor.direction)
                assertEquals(NavigationTransitionAxis.None, descriptor.axis)
                assertEquals(90, descriptor.enterDurationMillis)
                assertEquals(90, descriptor.exitDurationMillis)
                assertEquals(0, descriptor.enterOffset(containerSize = 1_000))
                assertEquals(0, descriptor.exitOffset(containerSize = 1_000))
            }
        }
    }
}

private data class TransitionExpectation(
    val family: NavigationTransitionFamily,
    val direction: NavigationTransitionDirection,
    val expectedAxis: NavigationTransitionAxis,
    val expectedEnterMillis: Int,
    val expectedExitMillis: Int,
    val expectedEnterOffset: Int,
    val expectedExitOffset: Int,
)
