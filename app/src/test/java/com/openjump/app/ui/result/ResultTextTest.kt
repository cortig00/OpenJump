package com.openjump.app.ui.result

import com.openjump.app.settings.JumpTimingUnit
import com.openjump.app.settings.UnitProfile
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ResultTextTest {
    @Test
    fun `jump timeline duration follows selected milliseconds or seconds`() {
        assertEquals("500 ms", ResultText.durationUs(500_000L, Locale.ENGLISH, UnitProfile.METRIC))
        val seconds = UnitProfile.METRIC.copy(jumpTiming = JumpTimingUnit.SECOND)
        assertEquals("0.500 s", ResultText.durationUs(500_000L, Locale.ENGLISH, seconds))
    }

    @Test
    fun `timeline accessibility formatter does not alter encoder seconds contract`() {
        val profile = UnitProfile.UNITED_STATES.copy(jumpTiming = JumpTimingUnit.SECOND)
        assertEquals("2.000 s", ResultText.durationUs(2_000_000L, Locale.ENGLISH, profile))
    }
}
