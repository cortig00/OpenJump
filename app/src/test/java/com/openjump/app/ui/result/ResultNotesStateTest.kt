package com.openjump.app.ui.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultNotesStateTest {
    @Test fun `editor truncates UTF-16 input at 500`() {
        assertEquals(499, ResultNotesState().edit("x".repeat(499)).draft.length)
        assertEquals(500, ResultNotesState().edit("x".repeat(500)).draft.length)
        assertEquals(500, ResultNotesState().edit("x".repeat(501)).draft.length)
        assertEquals(500, ResultNotesState().edit("line one\n" + "x".repeat(500)).draft.length)
    }

    @Test fun `save failure preserves draft and pending request and retry`() {
        val draft = "  exact\ntext  "
        val failed = ResultNotesState().edit(draft).beginSave().failed("offline")
        assertEquals(draft, failed.draft)
        assertEquals(ResultNotesAction.SAVE, failed.pendingAction)
        assertEquals("offline", failed.error)
        val retry = failed.retry()
        assertTrue(retry.saving)
        assertEquals(ResultNotesAction.SAVE, retry.pendingAction)
    }

    @Test fun `delete failure preserves visible draft and pending delete`() {
        val failed = ResultNotesState.fromSaved("keep me").beginDelete().failed("offline")
        assertEquals("keep me", failed.draft)
        assertEquals(ResultNotesAction.DELETE, failed.pendingAction)
        assertEquals("offline", failed.error)
        assertEquals(ResultNotesAction.DELETE, failed.retry().pendingAction)
    }

    @Test fun `bilateral intermediate result has no measurement note editor`() {
        assertTrue(showMeasurementNotesForResult(false))
        assertTrue(!showMeasurementNotesForResult(true))
    }

    @Test fun `new edit clears error and pending action`() {
        val state = ResultNotesState.fromSaved("old").beginSave().failed("offline")
        val edited = state.edit("new")
        assertNull(edited.error)
        assertNull(edited.pendingAction)
        assertEquals("new", edited.draft)
    }
}
