package com.openjump.app.ui.selector

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.openjump.app.protocol.EventType
import com.openjump.app.protocol.MeasurementValidationIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focal guard tests for the Fase 2 slice 1 selector hotfix.
 *
 * [withResultNavigationInFlight] is the only new pure logic: it drives the
 * disabled visual state of the temporal-jump Compute button while result
 * navigation is in flight. The synchronous `if (!inFlight) { inFlight = true }`
 * claim inside the screen's `onCompute` lambdas needs a Compose runtime, so it
 * is covered here only as a documented characterization of the exact idiom
 * plus parent-run gates/AVD smoke, not as genuine unit coverage.
 *
 * The observer tests below are genuine lifecycle coverage: they drive the real
 * [selectorResultNavigationObserver] production factory with real
 * [Lifecycle.Event] values and assert the pause/rearm wiring the screen
 * registers on its NavBackStackEntry.
 */
class ResultNavigationGuardTest {

    @Test
    fun `enabled Compute renders disabled while navigation in flight`() {
        val action = JumpMarkingAction.Compute(enabled = true, validationError = null)

        val guarded = withResultNavigationInFlight(action, resultNavigationInFlight = true)

        assertEquals(
            JumpMarkingAction.Compute(enabled = false, validationError = null),
            guarded,
        )
    }

    @Test
    fun `guarded Compute preserves validation context`() {
        val action = JumpMarkingAction.Compute(
            enabled = true,
            validationError = "order",
            issue = MeasurementValidationIssue.EVENT_ORDER,
        )

        val guarded =
            withResultNavigationInFlight(action, resultNavigationInFlight = true) as JumpMarkingAction.Compute

        assertFalse(guarded.enabled)
        assertEquals("order", guarded.validationError)
        assertEquals(MeasurementValidationIssue.EVENT_ORDER, guarded.issue)
    }

    @Test
    fun `Compute is untouched when no navigation in flight`() {
        val action = JumpMarkingAction.Compute(enabled = true, validationError = null)

        assertSame(action, withResultNavigationInFlight(action, resultNavigationInFlight = false))
    }

    @Test
    fun `Mark and Hidden actions pass through while in flight`() {
        val mark = JumpMarkingAction.Mark(EventType.TAKEOFF, enabled = true)

        assertSame(mark, withResultNavigationInFlight(mark, resultNavigationInFlight = true))
        assertSame(
            JumpMarkingAction.Hidden,
            withResultNavigationInFlight(JumpMarkingAction.Hidden, resultNavigationInFlight = true),
        )
    }

    @Test
    fun `synchronous claim idiom drops the second callback and resets per session`() {
        // Mirrors the screen lambda shape: `if (!inFlight) { inFlight = true; navigate() }`.
        var inFlight = false
        var navigations = 0
        fun onCompute() {
            if (!inFlight) {
                inFlight = true
                navigations += 1
            }
        }

        onCompute()
        onCompute()

        assertEquals(1, navigations)
        assertTrue(inFlight)

        // A new session recreates the holder (remember on session keys), clearing the guard.
        inFlight = false
        onCompute()

        assertEquals(2, navigations)
    }

    @Test
    fun `selector observer pauses playback on stop without rearming`() {
        var pauses = 0
        var rearms = 0
        val observer = selectorResultNavigationObserver(
            onBackground = { pauses += 1 },
            onSelectorResumed = { rearms += 1 },
        )

        observer.onStateChanged(FakeLifecycleOwner, Lifecycle.Event.ON_STOP)

        assertEquals(1, pauses)
        assertEquals(0, rearms)
    }

    @Test
    fun `selector observer rearms navigation guard on resume`() {
        var pauses = 0
        var rearms = 0
        val observer = selectorResultNavigationObserver(
            onBackground = { pauses += 1 },
            onSelectorResumed = { rearms += 1 },
        )

        observer.onStateChanged(FakeLifecycleOwner, Lifecycle.Event.ON_RESUME)

        assertEquals(0, pauses)
        assertEquals(1, rearms)
    }

    @Test
    fun `selector observer ignores other lifecycle events`() {
        var pauses = 0
        var rearms = 0
        val observer = selectorResultNavigationObserver(
            onBackground = { pauses += 1 },
            onSelectorResumed = { rearms += 1 },
        )

        observer.onStateChanged(FakeLifecycleOwner, Lifecycle.Event.ON_PAUSE)
        observer.onStateChanged(FakeLifecycleOwner, Lifecycle.Event.ON_START)
        observer.onStateChanged(FakeLifecycleOwner, Lifecycle.Event.ON_CREATE)

        assertEquals(0, pauses)
        assertEquals(0, rearms)
    }

    private object FakeLifecycleOwner : LifecycleOwner {
        override val lifecycle: Lifecycle
            get() = error("unused: selector observer ignores the source")
    }
}
