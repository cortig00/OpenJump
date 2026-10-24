package com.openjump.app.ui.result

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gates for the F4 unsaved-work confirmation on the non-bilateral result Discard:
 * Idle/Error confirm before [com.openjump.app.video.AppSession.reset], while
 * Saving/Saved never do (the save-navigation owns the exit, never a duplicate save).
 * The bilateral live route, InvalidResultScreen and onCorrect are untouched by this gate.
 */
class ResultDiscardConfirmTest {
    @Test fun idleConfirms() {
        assertTrue(shouldConfirmResultDiscard(SaveState.Idle))
    }

    @Test fun errorConfirms() {
        assertTrue(shouldConfirmResultDiscard(SaveState.Error("boom")))
    }

    @Test fun savingNeverConfirms() {
        assertFalse(shouldConfirmResultDiscard(SaveState.Saving))
    }

    @Test fun savedNeverConfirms() {
        assertFalse(shouldConfirmResultDiscard(SaveState.Saved))
    }
}
