package com.openjump.app.ui.encoder

import com.openjump.app.encoder.EncoderRepetition
import com.openjump.app.encoder.MovementDirection
import com.openjump.app.encoder.MovementPhase
import com.openjump.app.encoder.RepetitionQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EncoderResultUiStateTest {
    @Test
    fun `initial state selects first repetition in summary and empty result has no selection`() {
        assertEquals(
            EncoderResultUiState(EncoderResultView.SUMMARY, 0),
            EncoderResultUiState.initial(repetitions(3)),
        )
        assertNull(EncoderResultUiState.initial(emptyList()).selectedOrdinal)
    }

    @Test
    fun `tapping repetition row selects it and opens analysis`() {
        val selected = EncoderResultUiState.initial(repetitions(8))
            .selectFromList(repetitions(8), ordinal = 5)

        assertEquals(EncoderResultView.ANALYSIS, selected.view)
        assertEquals(5, selected.selectedOrdinal)
    }

    @Test
    fun `view changes preserve selection and video rail stays in video`() {
        val selected = EncoderResultUiState(EncoderResultView.ANALYSIS, selectedOrdinal = 5)
            .show(EncoderResultView.REPETITIONS)
            .show(EncoderResultView.VIDEO)
            .selectFromVideo(repetitions(8), ordinal = 6)

        assertEquals(EncoderResultView.VIDEO, selected.view)
        assertEquals(6, selected.selectedOrdinal)
        assertEquals(6, selected.show(EncoderResultView.ANALYSIS).selectedOrdinal)
    }

    @Test
    fun `analysis selector switches repetition without changing view`() {
        val state = EncoderResultUiState(EncoderResultView.ANALYSIS, selectedOrdinal = 5)
            .selectFromAnalysis(repetitions(8), ordinal = 6)

        assertEquals(EncoderResultView.ANALYSIS, state.view)
        assertEquals(6, state.selectedOrdinal)
        assertEquals(6, state.selectFromAnalysis(repetitions(8), ordinal = 99).selectedOrdinal)
    }

    @Test
    fun `opening selected repetition in video preserves its shared selection`() {
        val state = EncoderResultUiState(EncoderResultView.ANALYSIS, selectedOrdinal = 5)
            .showSelectedInVideo(repetitions(8), ordinal = 5)

        assertEquals(EncoderResultView.VIDEO, state.view)
        assertEquals(5, state.selectedOrdinal)
    }

    @Test
    fun `reconcile clears removed selection and selects first remaining repetition`() {
        val state = EncoderResultUiState(EncoderResultView.ANALYSIS, selectedOrdinal = 2)

        assertEquals(null, state.reconcile(emptyList()).selectedOrdinal)
        assertEquals(0, state.reconcile(repetitions(2)).selectedOrdinal)
    }

    @Test
    fun `removal follows successor then previous selection policy after ordinal renumbering`() {
        val beforeRemoval = repetitions(3)
        val selected = EncoderResultUiState(EncoderResultView.ANALYSIS, selectedOrdinal = 1)

        assertEquals(1, selected.afterRemoval(beforeRemoval, removedOrdinal = 1).selectedOrdinal)
        assertEquals(1, selected.afterRemoval(beforeRemoval, removedOrdinal = 2).selectedOrdinal)
        assertNull(selected.afterRemoval(repetitions(1), removedOrdinal = 0).selectedOrdinal)
    }

    @Test
    fun `uncertain notice uses one based canonical ordinals and excludes valid and invalid`() {
        val reps = repetitions(5).map { repetition ->
            repetition.copy(quality = when (repetition.ordinal) {
                1, 3 -> RepetitionQuality.UNCERTAIN
                4 -> RepetitionQuality.INVALID
                else -> RepetitionQuality.VALID
            })
        }
        assertEquals(listOf(2, 4), uncertainRepetitionNumbers(reps))
        // Filtering earlier repetitions must not turn the display numbers into 1 and 2.
        assertEquals(listOf(2, 4), uncertainRepetitionNumbers(reps.filter { it.ordinal in setOf(1, 3, 4) }))
    }

    @Test
    fun `uncertain notice recomputes from the current result without stale numbers`() {
        val first = repetitions(3).map { it.copy(quality = RepetitionQuality.UNCERTAIN) }
        assertEquals(listOf(1, 2, 3), uncertainRepetitionNumbers(first))
        assertEquals(emptyList<Int>(), uncertainRepetitionNumbers(repetitions(3)))
        assertEquals(emptyList<Int>(), uncertainRepetitionNumbers(emptyList()))
        assertEquals(listOf(1), uncertainRepetitionNumbers(first.take(1)))
    }

    private fun repetitions(count: Int): List<EncoderRepetition> = (0 until count).map { ordinal ->
        EncoderRepetition(
            ordinal = ordinal,
            startSample = ordinal,
            endSample = ordinal + 1,
            eccentricPhase = MovementPhase(MovementDirection.DOWN, ordinal, ordinal),
            concentricPhase = MovementPhase(MovementDirection.UP, ordinal + 1, ordinal + 1),
            quality = RepetitionQuality.VALID,
            reasons = emptySet(),
            metrics = emptyList(),
        )
    }
}
