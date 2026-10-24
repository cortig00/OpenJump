package com.openjump.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPreparationDelayTest {
    @Test fun remainingDelayFollowsSingleStopToFirstFrameThreshold() {
        assertEquals(850L, remainingVideoPreparationFeedbackDelayMs(null, now = 2_000L))
        assertEquals(450L, remainingVideoPreparationFeedbackDelayMs(stoppedAt = 1_000L, now = 1_400L))
        assertEquals(0L, remainingVideoPreparationFeedbackDelayMs(stoppedAt = 1_000L, now = 1_850L))
        assertEquals(0L, remainingVideoPreparationFeedbackDelayMs(stoppedAt = 1_000L, now = 4_000L))
        assertEquals(850L, remainingVideoPreparationFeedbackDelayMs(stoppedAt = 3_000L, now = 2_000L))
    }
}
