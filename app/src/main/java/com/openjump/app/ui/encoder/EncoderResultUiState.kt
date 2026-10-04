package com.openjump.app.ui.encoder

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.openjump.app.encoder.EncoderRepetition

internal enum class EncoderResultView {
    SUMMARY,
    REPETITIONS,
    ANALYSIS,
    VIDEO,
}

/** Ephemeral state shared by the four views of one active or saved result entry. */
internal data class EncoderResultUiState(
    val view: EncoderResultView = EncoderResultView.SUMMARY,
    val selectedOrdinal: Int? = null,
) {
    fun show(target: EncoderResultView): EncoderResultUiState = copy(view = target)

    /** Selecting a row is a direct action into that repetition's analysis. */
    fun selectFromList(repetitions: List<EncoderRepetition>, ordinal: Int): EncoderResultUiState =
        if (repetitions.any { it.ordinal == ordinal }) {
            copy(view = EncoderResultView.ANALYSIS, selectedOrdinal = ordinal)
        } else {
            this
        }

    /** The persistent analysis selector updates the current repetition without changing views. */
    fun selectFromAnalysis(repetitions: List<EncoderRepetition>, ordinal: Int): EncoderResultUiState =
        if (repetitions.any { it.ordinal == ordinal }) copy(view = EncoderResultView.ANALYSIS, selectedOrdinal = ordinal) else this

    /** The video rail updates the shared selection without leaving the video view. */
    fun selectFromVideo(repetitions: List<EncoderRepetition>, ordinal: Int): EncoderResultUiState =
        if (repetitions.any { it.ordinal == ordinal }) copy(selectedOrdinal = ordinal) else this

    fun showSelectedInVideo(repetitions: List<EncoderRepetition>, ordinal: Int): EncoderResultUiState =
        if (repetitions.any { it.ordinal == ordinal }) {
            copy(view = EncoderResultView.VIDEO, selectedOrdinal = ordinal)
        } else {
            this
        }

    /**
     * Analyzer removal renumbers ordinals. selectionAfterRemoval deliberately returns the
     * successor/previous index, which is also the selected repetition's new ordinal.
     */
    fun afterRemoval(repetitions: List<EncoderRepetition>, removedOrdinal: Int): EncoderResultUiState =
        copy(selectedOrdinal = selectionAfterRemoval(repetitions, removedOrdinal))

    fun reconcile(repetitions: List<EncoderRepetition>): EncoderResultUiState = when {
        repetitions.isEmpty() -> copy(selectedOrdinal = null)
        selectedOrdinal != null && repetitions.any { it.ordinal == selectedOrdinal } -> this
        else -> copy(selectedOrdinal = repetitions.first().ordinal)
    }

    companion object {
        fun initial(repetitions: List<EncoderRepetition>) =
            EncoderResultUiState(selectedOrdinal = repetitions.firstOrNull()?.ordinal)

        val StateSaver: Saver<EncoderResultUiState, Any> = listSaver<EncoderResultUiState, Int>(
            save = { state -> listOf(state.view.ordinal, state.selectedOrdinal ?: -1) },
            restore = { saved ->
                EncoderResultUiState(
                    view = EncoderResultView.entries.getOrElse(saved[0]) { EncoderResultView.SUMMARY },
                    selectedOrdinal = saved[1].takeIf { it >= 0 },
                )
            },
        )
    }
}
