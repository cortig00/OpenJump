package com.openjump.app.ui.selector

import com.openjump.app.protocol.VideoSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InitialRecordedPreparationTest {
    @Test fun firstRecordedVideoUsesDelayedFeedback() {
        assertTrue(shouldDelayInitialRecordingSpinner(VideoSource.RECORDED, hasCachedIndex = false))
    }

    @Test fun reusedVideoAndImportedVideoKeepTheirExistingLoadingBehavior() {
        assertFalse(shouldDelayInitialRecordingSpinner(VideoSource.RECORDED, hasCachedIndex = true))
        assertFalse(shouldDelayInitialRecordingSpinner(VideoSource.IMPORTED, hasCachedIndex = false))
        assertFalse(shouldDelayInitialRecordingSpinner(null, hasCachedIndex = false))
    }
}
