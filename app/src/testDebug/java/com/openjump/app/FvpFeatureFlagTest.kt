package com.openjump.app

import org.junit.Assert.assertTrue
import org.junit.Test

/** Debug keeps the implemented F-V-P flow reachable for development and instrumented tests. */
class FvpFeatureFlagTest {
    @Test
    fun debugBuildOffersFvpProfile() {
        assertTrue(FeatureFlags.fvpEnabled)
    }
}
