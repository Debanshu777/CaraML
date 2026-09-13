package com.debanshu777.caraml.core.navigation

import com.debanshu777.caraml.core.ui.motion.auroraMotionPolicy
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import kotlin.test.Test
import kotlin.test.assertEquals

class NavigationTransitionPolicyTest {

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
                expectedAxis = NavigationTransitionAxis.Vertical,
                expectedEnterMillis = 220,
                expectedExitMillis = 220,
                expectedEnterOffset = 6,
                expectedExitOffset = -6,
            ),
            TransitionExpectation(
                family = NavigationTransitionFamily.Peer,
                direction = NavigationTransitionDirection.Pop,
                expectedAxis = NavigationTransitionAxis.Vertical,
                expectedEnterMillis = 220,
                expectedExitMillis = 220,
                expectedEnterOffset = -6,
                expectedExitOffset = 6,
            ),
            TransitionExpectation(
                family = NavigationTransitionFamily.Hierarchical,
                direction = NavigationTransitionDirection.Forward,
                expectedAxis = NavigationTransitionAxis.Horizontal,
                expectedEnterMillis = 300,
                expectedExitMillis = 200,
                expectedEnterOffset = 100,
                expectedExitOffset = -100,
            ),
            TransitionExpectation(
                family = NavigationTransitionFamily.Hierarchical,
                direction = NavigationTransitionDirection.Pop,
                expectedAxis = NavigationTransitionAxis.Horizontal,
                expectedEnterMillis = 300,
                expectedExitMillis = 200,
                expectedEnterOffset = -100,
                expectedExitOffset = 100,
            ),
        )

        cases.forEach { expected ->
            val descriptor = navigationTransitionDescriptor(
                family = expected.family,
                direction = expected.direction,
                motionPolicy = motion,
                peerOffsetPx = 6,
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
                    peerOffsetPx = 6,
                )

                assertEquals(family, descriptor.family)
                assertEquals(direction, descriptor.direction)
                assertEquals(NavigationTransitionAxis.None, descriptor.axis)
                assertEquals(100, descriptor.enterDurationMillis)
                assertEquals(100, descriptor.exitDurationMillis)
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
