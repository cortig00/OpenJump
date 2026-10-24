package com.openjump.app.ui.result

import com.openjump.app.ui.HOME_ROUTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * F-004 regression: per-attempt bilateral continuation returns to the selector
 * with the ephemeral session intact, while the final successful save lands on
 * the persisted `measurement/{id}` detail with Home kept below it (Back lands
 * Home). Both branches in `AppNav`'s result route call these helpers via the
 * production router, so this pins the production routing, not a copy.
 */
class BilateralSaveNavigationTest {

    @Test fun continuationWithOpenSessionReturnsToSelector() {
        assertEquals(BILATERAL_SELECTOR_ROUTE, bilateralContinuationTarget(bilateralActive = true))
        assertEquals("selector", bilateralContinuationTarget(bilateralActive = true))
    }

    @Test fun continuationNeverLandsOnASavedDetail() {
        val target = bilateralContinuationTarget(bilateralActive = true)

        assertFalse(target!!.startsWith("measurement/"))
    }

    @Test fun noBilateralSessionLeavesOrdinaryRoutingUntouched() {
        // Unilateral / series / collective Testing saves keep their own routing.
        assertNull(bilateralContinuationTarget(bilateralActive = false))
    }

    @Test fun finalSaveLandsOnPersistedDetail() {
        val destination = bilateralFinalSaveNavigation(42L)

        assertEquals("measurement/42", destination.detailRoute)
    }

    @Test fun finalSaveThreadsTheRealRoomId() {
        assertEquals("measurement/7", bilateralFinalSaveNavigation(7L).detailRoute)
        assertEquals("measurement/123456", bilateralFinalSaveNavigation(123456L).detailRoute)
    }

    @Test fun finalSaveRemovesStaleResultAndSelector() {
        val destination = bilateralFinalSaveNavigation(42L)

        // Shared HOME_ROUTE (not a fragile copy): Home stays, Back lands Home.
        assertEquals(HOME_ROUTE, destination.popUpToRoute)
        assertEquals("home", destination.popUpToRoute)
        assertFalse(destination.popUpToInclusive)
    }

    @Test fun finalSaveRejectsMissingId() {
        assertRejected(0L)
        assertRejected(-1L)
    }

    private fun assertRejected(id: Long) {
        try {
            bilateralFinalSaveNavigation(id)
            fail("assessmentId=$id must not produce a detail destination")
        } catch (expected: IllegalArgumentException) {
            // A failed Room commit keeps the session and retry UI instead of
            // navigating to a measurement that was never persisted.
        }
    }
}
