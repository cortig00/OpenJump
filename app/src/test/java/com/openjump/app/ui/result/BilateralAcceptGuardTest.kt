package com.openjump.app.ui.result

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focal guard for the F5 bilateral double-tap fix: while the synchronous
 * claim-once flag is held, the bilateral Accept renders disabled; the next
 * attempt (fresh remember scope) re-enables it. The unilateral branch keeps
 * its Saving/Saved guard and never consults the bilateral flag.
 */
class BilateralAcceptGuardTest {

    @Test fun bilateralAcceptEnabledBeforeClaim() {
        assertTrue(resultAcceptEnabled(true, bilateralAcceptInFlight = false, SaveState.Idle))
    }

    @Test fun bilateralAcceptDisabledWhileInFlight() {
        assertFalse(resultAcceptEnabled(true, bilateralAcceptInFlight = true, SaveState.Idle))
        assertFalse(resultAcceptEnabled(true, bilateralAcceptInFlight = true, SaveState.Saving))
    }

    @Test fun unilateralIgnoresBilateralFlag() {
        assertTrue(resultAcceptEnabled(false, bilateralAcceptInFlight = true, SaveState.Idle))
        assertTrue(resultAcceptEnabled(false, bilateralAcceptInFlight = false, SaveState.Error("boom")))
        assertFalse(resultAcceptEnabled(false, bilateralAcceptInFlight = false, SaveState.Saving))
        assertFalse(resultAcceptEnabled(false, bilateralAcceptInFlight = false, SaveState.Saved))
    }
}
