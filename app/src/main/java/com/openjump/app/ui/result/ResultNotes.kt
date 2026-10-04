package com.openjump.app.ui.result

import com.openjump.app.protocol.NoteNormalizer

/** Pending operation for the note editor. */
enum class ResultNotesAction { SAVE, DELETE }

/** Pure state for live and saved result note editors. */
data class ResultNotesState(
    val draft: String = "",
    val saved: String? = null,
    val saving: Boolean = false,
    val error: String? = null,
    val pendingAction: ResultNotesAction? = null,
) {
    val canEdit: Boolean get() = !saving

    fun edit(value: String): ResultNotesState = copy(
        draft = value.take(NoteNormalizer.MAX_LENGTH),
        saving = false,
        error = null,
        pendingAction = null,
    )

    fun beginSave(): ResultNotesState = copy(
        saving = true,
        error = null,
        pendingAction = ResultNotesAction.SAVE,
    )

    fun beginDelete(): ResultNotesState = copy(
        draft = saved.orEmpty(),
        saving = true,
        error = null,
        pendingAction = ResultNotesAction.DELETE,
    )

    fun failed(message: String): ResultNotesState = copy(
        saving = false,
        error = message,
        // Keep the action so retry repeats exactly the same request.
        pendingAction = pendingAction,
    )

    fun saved(value: String?): ResultNotesState = copy(
        draft = value.orEmpty(),
        saved = value,
        saving = false,
        error = null,
        pendingAction = null,
    )

    fun deleted(): ResultNotesState = saved(null)

    fun retry(): ResultNotesState = if (pendingAction == null) this else copy(
        saving = true,
        error = null,
    )

    companion object {
        fun fromSaved(value: String?): ResultNotesState = ResultNotesState(
            draft = value.orEmpty(),
            saved = value,
        )
    }
}

/** Editor input is deliberately not trimmed: spaces and newlines remain visible while typing. */
/** Intermediate unilateral screens in a bilateral flow never own a note editor. */
internal fun showMeasurementNotesForResult(bilateralActive: Boolean): Boolean = !bilateralActive

object ResultNotesReducer {
    fun edit(state: ResultNotesState, value: String): ResultNotesState = state.edit(value)
    fun saveRequested(state: ResultNotesState): ResultNotesState = state.beginSave()
    fun deleteRequested(state: ResultNotesState): ResultNotesState = state.beginDelete()
    fun saveFailed(state: ResultNotesState, message: String): ResultNotesState = state.failed(message)
    fun saveSucceeded(state: ResultNotesState, value: String?): ResultNotesState = state.saved(value)
    fun deleteFailed(state: ResultNotesState, message: String): ResultNotesState = state.failed(message)
    fun deleteSucceeded(state: ResultNotesState): ResultNotesState = state.deleted()
    fun retry(state: ResultNotesState): ResultNotesState = state.retry()
}
