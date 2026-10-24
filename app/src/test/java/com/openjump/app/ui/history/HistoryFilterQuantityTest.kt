package com.openjump.app.ui.history

import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.UnitAwareNumericInputState
import com.openjump.app.protocol.ProtocolId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryFilterQuantityTest {
    @Test
    fun `restored encoder series keeps speed quantity and canonical threshold`() {
        val jumpSeries = ProtocolId.CMJ.storageKey
        assertEquals(MeasurementQuantity.SHORT_LENGTH_CM, historyFilterQuantity(jumpSeries))

        // This models the saved draft after the user changed CMJ to an Encoder exercise.
        val restoredSeries = EncoderExercise.BENCH_PRESS.name
        val quantity = requireNotNull(historyFilterQuantity(restoredSeries))
        assertEquals(MeasurementQuantity.SPEED_MPS, quantity)
        val input = UnitAwareNumericInputState.edited("2.5", UnitProfile.METRIC, quantity, Locale.ENGLISH)
        val recreatedInput = requireNotNull(UnitAwareNumericInputState.restoreSnapshot(input.saveSnapshot()))
        assertEquals(
            2.5,
            UnitAwareNumericInputState.canonical(recreatedInput, quantity, Locale.ENGLISH, UnitProfile.METRIC)!!,
            0.0,
        )
        assertEquals(
            2.5,
            MeasurementFormatting.parseCanonical("2.5", quantity, UnitProfile.METRIC, Locale.ENGLISH)!!,
            0.0,
        )
    }
}
