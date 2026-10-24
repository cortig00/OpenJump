package com.openjump.app.ui.encoder

import org.junit.Assert.assertEquals
import org.junit.Test

class EncoderResultNavigationTest {
    @Test
    fun `repetition row opens analysis for the tapped ordinal`() {
        val repetitions = (0..7).map(::repetition)

        val state = EncoderResultUiState.initial(repetitions)
            .selectFromList(repetitions, ordinal = 5)

        assertEquals(EncoderResultView.ANALYSIS, state.view)
        assertEquals(5, state.selectedOrdinal)
    }

    @Test
    fun `switching views preserves the selected repetition`() {
        val state = EncoderResultUiState(EncoderResultView.ANALYSIS, selectedOrdinal = 5)
            .show(EncoderResultView.REPETITIONS)
            .show(EncoderResultView.SUMMARY)

        assertEquals(EncoderResultView.SUMMARY, state.view)
        assertEquals(5, state.selectedOrdinal)
    }

    @Test
    fun `selecting a repetition in video updates selection without navigating away`() {
        val repetitions = (0..7).map(::repetition)

        val state = EncoderResultUiState(EncoderResultView.VIDEO, selectedOrdinal = 5)
            .selectFromVideo(repetitions, ordinal = 6)

        assertEquals(EncoderResultView.VIDEO, state.view)
        assertEquals(6, state.selectedOrdinal)
    }

    private fun repetition(ordinal: Int) = com.openjump.app.encoder.EncoderRepetition(
        ordinal = ordinal,
        startSample = ordinal,
        endSample = ordinal + 1,
        eccentricPhase = com.openjump.app.encoder.MovementPhase(
            com.openjump.app.encoder.MovementDirection.DOWN,
            ordinal,
            ordinal,
        ),
        concentricPhase = com.openjump.app.encoder.MovementPhase(
            com.openjump.app.encoder.MovementDirection.UP,
            ordinal + 1,
            ordinal + 1,
        ),
        quality = com.openjump.app.encoder.RepetitionQuality.VALID,
        reasons = emptySet(),
        metrics = emptyList(),
    )
}
