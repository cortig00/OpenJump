package com.openjump.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice 1: after Detener the take must never disappear silently. These tests traverse the real
 * [JumpCameraStateMachine] route (bind -> ready -> record -> finalize -> terminal) and assert the
 * conservative exit policy at each point, plus the stale-publish ownership before/during/after
 * publication. No lambda is faked: every state comes from the actual machine transition.
 *
 * Discard consent is generation-scoped: the controller captures the pre-cancel generation via
 * [CameraFinalizingExitPolicy.consentTokenForDiscard] and the stale path only honors that exact
 * token, so the tests below always feed the policy-produced consent into the disposition, the
 * same way the controller consumes it.
 */
class CameraFinalizingExitPolicyTest {

    private fun driveTo(state: JumpCameraState): JumpCameraStateMachine {
        val machine = JumpCameraStateMachine()
        val token = machine.beginBinding()
        if (state == JumpCameraState.BINDING) return machine
        machine.bound(token)
        if (state == JumpCameraState.BINDING) return machine
        machine.streaming(token)
        if (state == JumpCameraState.READY) return machine
        machine.beginRecording(token)
        if (state == JumpCameraState.STARTING) return machine
        machine.started(token)
        if (state == JumpCameraState.RECORDING) return machine
        machine.beginFinalize(token)
        return machine
    }

    private fun driveNewTake(machine: JumpCameraStateMachine): Long {
        val token = machine.beginBinding()
        machine.bound(token)
        machine.streaming(token)
        machine.beginRecording(token)
        machine.started(token)
        machine.beginFinalize(token)
        return machine.generation
    }

    @Test
    fun `voluntary exit requires confirmation only while finalizing`() {
        val states = listOf(
            JumpCameraState.BLOCKED,
            JumpCameraState.BINDING,
            JumpCameraState.READY,
            JumpCameraState.STARTING,
            JumpCameraState.RECORDING,
            JumpCameraState.FINALIZING,
            JumpCameraState.ERROR,
        )
        states.forEach { state ->
            val expected = state == JumpCameraState.FINALIZING
            assertEquals(
                "voluntary confirmation for $state",
                expected,
                CameraFinalizingExitPolicy.requiresVoluntaryConfirmation(state),
            )
        }
        // Real route check: the machine actually reaches FINALIZING through stop.
        val machine = driveTo(JumpCameraState.FINALIZING)
        assertEquals(JumpCameraState.FINALIZING, machine.state)
        assertTrue(CameraFinalizingExitPolicy.requiresVoluntaryConfirmation(machine.state))
    }

    @Test
    fun `background preserves only while finalizing`() {
        assertFalse(CameraFinalizingExitPolicy.mustPreserveOnBackground(JumpCameraState.READY))
        assertFalse(CameraFinalizingExitPolicy.mustPreserveOnBackground(JumpCameraState.RECORDING))
        assertFalse(CameraFinalizingExitPolicy.mustPreserveOnBackground(JumpCameraState.STARTING))
        assertFalse(CameraFinalizingExitPolicy.mustPreserveOnBackground(JumpCameraState.ERROR))
        val machine = driveTo(JumpCameraState.FINALIZING)
        assertTrue(CameraFinalizingExitPolicy.mustPreserveOnBackground(machine.state))
    }

    @Test
    fun `normal states keep the existing pause path without confirmation`() {
        // Before any take (READY) and during an unstopped recording, exit needs no FINALIZING
        // confirmation: the conservative gate is scoped to the already-stopped take.
        val ready = driveTo(JumpCameraState.READY)
        assertEquals(JumpCameraState.READY, ready.state)
        assertFalse(CameraFinalizingExitPolicy.requiresVoluntaryConfirmation(ready.state))
        assertFalse(CameraFinalizingExitPolicy.mustPreserveOnBackground(ready.state))
        val recording = driveTo(JumpCameraState.RECORDING)
        assertEquals(JumpCameraState.RECORDING, recording.state)
        assertFalse(CameraFinalizingExitPolicy.requiresVoluntaryConfirmation(recording.state))
    }

    @Test
    fun `before publication an invalid take reports instead of vanishing`() {
        // Before publication: finalize without valid output is not publishable, so the stale path
        // must surface the interruption reason on return rather than dropping it silently.
        assertFalse(RecordingOutputPolicy.isPublishable(false, true, 10, 2))
        assertFalse(RecordingOutputPolicy.isPublishable(true, true, 0, 2))
        val machine = driveTo(JumpCameraState.FINALIZING)
        val staleToken = machine.generation
        val disposition = CameraFinalizingExitPolicy.dispositionForStaleResult(
            hasResult = false,
            privateSource = false,
            explicitDiscardToken = null,
            staleToken = staleToken,
            disposed = false,
        )
        assertEquals(
            CameraFinalizingExitPolicy.StalePublishDisposition.PARK_ERROR,
            disposition,
        )
    }

    @Test
    fun `during publication a consolidated uri parks only while a consumer can return`() {
        // During/after publication race: the MediaStore URI already exists while the token went
        // stale. With the screen still able to return it parks for delivery, never deletes.
        val machine = driveTo(JumpCameraState.FINALIZING)
        val staleToken = machine.generation
        assertEquals(
            CameraFinalizingExitPolicy.StalePublishDisposition.PARK_CONSOLIDATED,
            CameraFinalizingExitPolicy.dispositionForStaleResult(
                hasResult = true,
                privateSource = false,
                explicitDiscardToken = null,
                staleToken = staleToken,
                disposed = false,
            ),
        )
        // Disposed, no consumer can return: the gallery entry stays where the user can see it
        // (removing it needs explicit confirmation), so nothing is parked and only private
        // state is cleaned. Parking there would promise an undeliverable outcome.
        assertEquals(
            CameraFinalizingExitPolicy.StalePublishDisposition.CLEAN_PRIVATE_ONLY,
            CameraFinalizingExitPolicy.dispositionForStaleResult(
                hasResult = true,
                privateSource = false,
                explicitDiscardToken = null,
                staleToken = staleToken,
                disposed = true,
            ),
        )
    }

    @Test
    fun `after publication the private fallback parks while disposed cleans orphans`() {
        val machine = driveTo(JumpCameraState.FINALIZING)
        val staleToken = machine.generation
        // Gallery failure fallback: the private file backs the delivered Uri, so it parks.
        assertEquals(
            CameraFinalizingExitPolicy.StalePublishDisposition.PARK_PRIVATE,
            CameraFinalizingExitPolicy.dispositionForStaleResult(
                hasResult = true,
                privateSource = true,
                explicitDiscardToken = null,
                staleToken = staleToken,
                disposed = false,
            ),
        )
        // Disposed with only a private cache file: clean it to avoid orphans (nothing user-visible).
        assertEquals(
            CameraFinalizingExitPolicy.StalePublishDisposition.CLEAN_PRIVATE_ONLY,
            CameraFinalizingExitPolicy.dispositionForStaleResult(
                hasResult = true,
                privateSource = true,
                explicitDiscardToken = null,
                staleToken = staleToken,
                disposed = true,
            ),
        )
        // Disposed with no result: clean private state, nothing to surface.
        assertEquals(
            CameraFinalizingExitPolicy.StalePublishDisposition.CLEAN_PRIVATE_ONLY,
            CameraFinalizingExitPolicy.dispositionForStaleResult(
                hasResult = false,
                privateSource = false,
                explicitDiscardToken = null,
                staleToken = staleToken,
                disposed = true,
            ),
        )
    }

    @Test
    fun `explicit discard consent reaches the stale finalizer after cancel`() {
        // Real race setup: the machine actually reaches FINALIZING, then the confirmed discard
        // invalidates the token while the finalizer is still copying. The stale callback must
        // still observe the consent and own cleanup, including a consolidated URI. The consent
        // token is produced by the policy exactly as the controller captures it pre-cancel.
        val machine = driveTo(JumpCameraState.FINALIZING)
        val token = machine.generation
        val consent = CameraFinalizingExitPolicy.consentTokenForDiscard(
            generation = token,
            finalizeStarted = true,
            hadParkedOutcome = false,
            savedDelivered = false,
        )
        assertEquals(token, consent)
        machine.cancel()
        assertFalse(machine.isCurrent(token))
        assertEquals(
            CameraFinalizingExitPolicy.StalePublishDisposition.EXPLICIT_DISCARD,
            CameraFinalizingExitPolicy.dispositionForStaleResult(
                hasResult = true,
                privateSource = false,
                explicitDiscardToken = consent,
                staleToken = token,
                disposed = false,
            ),
        )
        assertEquals(
            CameraFinalizingExitPolicy.StalePublishDisposition.EXPLICIT_DISCARD,
            CameraFinalizingExitPolicy.dispositionForStaleResult(
                hasResult = false,
                privateSource = false,
                explicitDiscardToken = consent,
                staleToken = token,
                disposed = false,
            ),
        )
    }

    @Test
    fun `stale previous token does not authorize a new take`() {
        // B1 cross-take window: Take1 FINALIZING with in-flight consent, then machine cancel
        // (discard) followed by a brand-new binding/take. The new take's stale result must park,
        // never take EXPLICIT_DISCARD from the previous take's confirmation.
        val machine = driveTo(JumpCameraState.FINALIZING)
        val firstToken = machine.generation
        val firstConsent = CameraFinalizingExitPolicy.consentTokenForDiscard(
            generation = firstToken,
            finalizeStarted = true,
            hadParkedOutcome = false,
            savedDelivered = false,
        )
        assertEquals(firstToken, firstConsent)
        machine.cancel()
        assertFalse(machine.isCurrent(firstToken))
        val secondToken = driveNewTake(machine)
        assertEquals(JumpCameraState.FINALIZING, machine.state)
        assertFalse(firstToken == secondToken)
        assertFalse(machine.isCurrent(firstToken))
        assertTrue(machine.isCurrent(secondToken))
        // Previous consent still authorizes only its own stale token.
        assertEquals(
            CameraFinalizingExitPolicy.StalePublishDisposition.EXPLICIT_DISCARD,
            CameraFinalizingExitPolicy.dispositionForStaleResult(
                hasResult = true,
                privateSource = false,
                explicitDiscardToken = firstConsent,
                staleToken = firstToken,
                disposed = false,
            ),
        )
        // The new take's stale consolidated URI parks: no dialog for Take2, no delete.
        assertEquals(
            CameraFinalizingExitPolicy.StalePublishDisposition.PARK_CONSOLIDATED,
            CameraFinalizingExitPolicy.dispositionForStaleResult(
                hasResult = true,
                privateSource = false,
                explicitDiscardToken = firstConsent,
                staleToken = secondToken,
                disposed = false,
            ),
        )
        // A null consent (no discard for Take2) also parks the new take.
        assertEquals(
            CameraFinalizingExitPolicy.StalePublishDisposition.PARK_CONSOLIDATED,
            CameraFinalizingExitPolicy.dispositionForStaleResult(
                hasResult = true,
                privateSource = false,
                explicitDiscardToken = null,
                staleToken = secondToken,
                disposed = false,
            ),
        )
    }

    @Test
    fun `delivered-before-preview and parked consent is not retained`() {
        // B1 resurrection: discard after emitSavedResult (savedDelivered, preview still
        // FINALIZING) or after a parked outcome must not retain consent for the next take.
        val machine = driveTo(JumpCameraState.FINALIZING)
        val token = machine.generation
        assertNull(
            CameraFinalizingExitPolicy.consentTokenForDiscard(
                generation = token,
                finalizeStarted = true,
                hadParkedOutcome = false,
                savedDelivered = true,
            ),
        )
        assertNull(
            CameraFinalizingExitPolicy.consentTokenForDiscard(
                generation = token,
                finalizeStarted = true,
                hadParkedOutcome = true,
                savedDelivered = false,
            ),
        )
        assertNull(
            CameraFinalizingExitPolicy.consentTokenForDiscard(
                generation = token,
                finalizeStarted = false,
                hadParkedOutcome = false,
                savedDelivered = false,
            ),
        )
        // In-flight without delivery or park still consents, consumed through the same path
        // the controller uses for the stale callback.
        val inFlightConsent = CameraFinalizingExitPolicy.consentTokenForDiscard(
            generation = token,
            finalizeStarted = true,
            hadParkedOutcome = false,
            savedDelivered = false,
        )
        assertEquals(token, inFlightConsent)
        machine.cancel()
        val nextToken = driveNewTake(machine)
        // A null retained consent never deletes the next take's gallery entry.
        assertEquals(
            CameraFinalizingExitPolicy.StalePublishDisposition.PARK_CONSOLIDATED,
            CameraFinalizingExitPolicy.dispositionForStaleResult(
                hasResult = true,
                privateSource = false,
                explicitDiscardToken = null,
                staleToken = nextToken,
                disposed = false,
            ),
        )
    }

    @Test
    fun `dispose race cleans private state without parking an undeliverable outcome`() {
        // Real race setup: FINALIZING reached, then dispose wins while the finalizer is in
        // flight. Every output shape must clean private state and park nothing: no consumer
        // can return, the gallery entry stays visible, and no cache file orphans.
        val machine = driveTo(JumpCameraState.FINALIZING)
        val token = machine.generation
        machine.dispose()
        assertFalse(machine.isCurrent(token))
        assertEquals(
            CameraFinalizingExitPolicy.StalePublishDisposition.CLEAN_PRIVATE_ONLY,
            CameraFinalizingExitPolicy.dispositionForStaleResult(
                hasResult = true,
                privateSource = false,
                explicitDiscardToken = null,
                staleToken = token,
                disposed = true,
            ),
        )
        assertEquals(
            CameraFinalizingExitPolicy.StalePublishDisposition.CLEAN_PRIVATE_ONLY,
            CameraFinalizingExitPolicy.dispositionForStaleResult(
                hasResult = true,
                privateSource = true,
                explicitDiscardToken = null,
                staleToken = token,
                disposed = true,
            ),
        )
        assertEquals(
            CameraFinalizingExitPolicy.StalePublishDisposition.CLEAN_PRIVATE_ONLY,
            CameraFinalizingExitPolicy.dispositionForStaleResult(
                hasResult = false,
                privateSource = false,
                explicitDiscardToken = null,
                staleToken = token,
                disposed = true,
            ),
        )
    }

    // NOTE (honest scope): the tests above are pure-policy checks. They traverse the real
    // [JumpCameraStateMachine] to establish each race (token invalidation via cancel/dispose
    // plus generation-scoped consent consumed exactly as the controller feeds it into the
    // disposition) and assert the ownership mapping, but the controller's File/ContentResolver
    // branches (cache deletes, MediaStore delete on explicit discard) need Android and must be
    // verified on an AVD; they are not exercised here.

    @Test
    fun `explicit user confirmation is the only path that may remove a consolidated uri`() {
        val machine = driveTo(JumpCameraState.FINALIZING)
        val token = machine.generation
        val consent = CameraFinalizingExitPolicy.consentTokenForDiscard(
            generation = token,
            finalizeStarted = true,
            hadParkedOutcome = false,
            savedDelivered = false,
        )
        assertEquals(token, consent)
        assertEquals(
            CameraFinalizingExitPolicy.StalePublishDisposition.EXPLICIT_DISCARD,
            CameraFinalizingExitPolicy.dispositionForStaleResult(
                hasResult = true,
                privateSource = false,
                explicitDiscardToken = consent,
                staleToken = token,
                disposed = false,
            ),
        )
        // Without the matching consent the same consolidated URI parks instead of deleting.
        assertEquals(
            CameraFinalizingExitPolicy.StalePublishDisposition.PARK_CONSOLIDATED,
            CameraFinalizingExitPolicy.dispositionForStaleResult(
                hasResult = true,
                privateSource = false,
                explicitDiscardToken = null,
                staleToken = token,
                disposed = false,
            ),
        )
    }

    @Test
    fun `terminalized take leaves the finalizing gate`() {
        val machine = driveTo(JumpCameraState.FINALIZING)
        val token = machine.generation
        assertTrue(machine.terminal(token, true))
        assertEquals(JumpCameraState.READY, machine.state)
        assertFalse(CameraFinalizingExitPolicy.requiresVoluntaryConfirmation(machine.state))
        assertFalse(CameraFinalizingExitPolicy.mustPreserveOnBackground(machine.state))
    }
}
