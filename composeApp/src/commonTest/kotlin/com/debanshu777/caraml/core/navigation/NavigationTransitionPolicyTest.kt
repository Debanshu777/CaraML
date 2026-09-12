package com.debanshu777.caraml.core.navigation

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
}
