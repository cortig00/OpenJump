package com.openjump.app

import org.junit.Assert.assertFalse
import org.junit.Test

/** Release is the production surface: the F-V-P profile must stay hidden behind the flag. */
class FvpFeatureFlagTest {
    @Test
    fun releaseBuildHidesFvpProfile() {
        assertFalse(FeatureFlags.fvpEnabled)
    }
}
