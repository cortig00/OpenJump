package com.openjump.app.ui.camera

import com.openjump.app.camera.JumpCameraState
import com.openjump.app.settings.RecordingUse
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Consumed-context policy for the opt-in [CameraScreen] reuse.
 *
 * The first group asserts the exact derivation functions the screen calls. The second
 * group is a STATIC source guard only: it cannot simulate CameraX on the JVM, so it
 * proves the wiring (fresh gate reads, lease-routed LOCKED, no protocol subscriptions
 * in standalone) is present in the production source. It is not runtime/UI evidence.
 */
class CameraCaptureContextTest {

    // --- consumed policy -----------------------------------------------------

    @Test
    fun `protocol flow keeps the existing encoder and jump cadence split`() {
        assertEquals(
            RecordingUse.ENCODER,
            CameraCaptureContextPolicy.recordingUse(CameraCaptureContext.ProtocolFlow, hasEncoderDraft = true),
        )
        assertEquals(
            RecordingUse.JUMPS,
            CameraCaptureContextPolicy.recordingUse(CameraCaptureContext.ProtocolFlow, hasEncoderDraft = false),
        )
    }

    @Test
    fun `standalone always uses the normal jump default even with a stale encoder draft`() {
        assertEquals(
            RecordingUse.JUMPS,
            CameraCaptureContextPolicy.recordingUse(CameraCaptureContext.Standalone, hasEncoderDraft = true),
        )
        assertEquals(
            RecordingUse.JUMPS,
            CameraCaptureContextPolicy.recordingUse(CameraCaptureContext.Standalone, hasEncoderDraft = false),
        )
    }

    @Test
    fun `standalone has no protocol session and owns orientation`() {
        assertTrue(CameraCaptureContext.ProtocolFlow.usesProtocolSession)
        assertFalse(CameraCaptureContext.ProtocolFlow.usesOrientationLease)
        assertFalse(CameraCaptureContext.Standalone.usesProtocolSession)
        assertTrue(CameraCaptureContext.Standalone.usesOrientationLease)
    }

    @Test
    fun `standalone drops stale draft series and step affordances`() {
        assertFalse(CameraCaptureContextPolicy.seriesProgressEnabled(CameraCaptureContext.Standalone, hasActiveDraft = true))
        assertFalse(CameraCaptureContextPolicy.seriesProgressEnabled(CameraCaptureContext.Standalone, hasActiveDraft = false))
        assertTrue(CameraCaptureContextPolicy.seriesProgressEnabled(CameraCaptureContext.ProtocolFlow, hasActiveDraft = true))
        assertFalse(CameraCaptureContextPolicy.seriesProgressEnabled(CameraCaptureContext.ProtocolFlow, hasActiveDraft = false))

        assertFalse(CameraCaptureContextPolicy.jumpStepBadgeVisible(CameraCaptureContext.Standalone))
        assertTrue(CameraCaptureContextPolicy.jumpStepBadgeVisible(CameraCaptureContext.ProtocolFlow))
    }

    @Test
    fun `standalone never offers the stale testing-session athlete picker`() {
        // Stale global testing session + roster must not reach setCurrentAthlete/reassignActiveAthlete.
        assertFalse(
            CameraCaptureContextPolicy.quickAthletePickerAvailable(
                context = CameraCaptureContext.Standalone,
                rosterSize = 3,
                testingSessionId = 999L,
                hasEligibleAthlete = true,
            ),
        )
        assertTrue(
            CameraCaptureContextPolicy.quickAthletePickerAvailable(
                context = CameraCaptureContext.ProtocolFlow,
                rosterSize = 3,
                testingSessionId = 42L,
                hasEligibleAthlete = true,
            ),
        )
        assertFalse(
            CameraCaptureContextPolicy.quickAthletePickerAvailable(
                context = CameraCaptureContext.ProtocolFlow,
                rosterSize = 0,
                testingSessionId = 42L,
                hasEligibleAthlete = false,
            ),
        )
    }

    @Test
    fun `standalone system back leaves through the camera back path except while finalizing`() {
        // Exact consumed policy: standalone handles Android system Back in every non-FINALIZING
        // state so the recorder pause + owner cancel/pop run; FINALIZING keeps the pre-existing
        // confirmation handler (dialog/discard) as the only active one. Protocol flow is untouched.
        JumpCameraState.entries.forEach { state ->
            assertFalse(
                "protocol flow must keep its previous system Back behaviour ($state)",
                CameraCaptureContextPolicy.shouldHandleStandaloneSystemBack(
                    CameraCaptureContext.ProtocolFlow,
                    state,
                ),
            )
            assertEquals(
                "standalone system Back handling in $state",
                state != JumpCameraState.FINALIZING,
                CameraCaptureContextPolicy.shouldHandleStandaloneSystemBack(
                    CameraCaptureContext.Standalone,
                    state,
                ),
            )
        }
        assertEquals(
            "the FINALIZING confirmation handler must stay the only active one",
            false,
            CameraCaptureContextPolicy.shouldHandleStandaloneSystemBack(
                CameraCaptureContext.Standalone,
                JumpCameraState.FINALIZING,
            ),
        )
    }

    @Test
    fun `standalone system back handler reuses the back control leave path`() {
        // STATIC source guard only: it proves the wiring/order, not CameraX or UI behaviour.
        val source = requireCameraScreenSource()
        val policyLine =
            "enabled = CameraCaptureContextPolicy.shouldHandleStandaloneSystemBack(captureContext, pipelineState)"
        assertTrue("the standalone handler must be gated by the consumed policy", source.contains(policyLine))
        assertTrue(
            "the pre-existing FINALIZING confirmation handler stays unchanged",
            source.contains("BackHandler(enabled = pipelineState == JumpCameraState.FINALIZING)"),
        )
        assertTrue(
            "the standalone handler must be declared after the local leaveCamera (local scope)",
            source.indexOf(policyLine) > source.indexOf("fun leaveCamera()"),
        )
        assertEquals(
            "exactly two system Back handlers: FINALIZING confirmation plus standalone leave",
            2,
            Regex("BackHandler\\s*\\(").findAll(source).count(),
        )
    }

    // --- static source guard (declared static, not UI proof) ------------------

    @Test
    fun `screen defaults keep the legacy protocol flow and an open gate`() {
        val source = requireCameraScreenSource()
        assertTrue(
            "captureContext must default to the legacy protocol flow",
            source.contains("captureContext: CameraCaptureContext = CameraCaptureContext.ProtocolFlow"),
        )
        assertTrue(
            "canStartNewVideo must default to the legacy always-true gate",
            source.contains("canStartNewVideo: () -> Boolean = { true }"),
        )
    }

    @Test
    fun `standalone never collects protocol session flows`() {
        val source = requireCameraScreenSource()
        listOf(
            "AppSession.draft",
            "AppSession.bilateral",
            "AppSession.encoderDraft",
            "AppSession.seriesTargetAttempts",
            "AppSession.testingParticipantIds",
        ).forEach { flow ->
            assertTrue(
                "$flow must be read through the context-gated helper",
                source.contains("(usesProtocolSession, $flow"),
            )
        }
        assertFalse(
            "no unconditional protocol subscription may remain",
            source.contains("AppSession.draft.collectAsState()"),
        )
    }

    @Test
    fun `fresh new-take gate is read before recording and before the import picker`() {
        val source = requireCameraScreenSource()
        assertTrue(
            source.contains("val currentCanStartNewVideo by rememberUpdatedState(canStartNewVideo)"),
        )
        // Read at start time, not from the captured composition value, and keep the
        // conservative legacy guard: the composition value canRecord composes the
        // record-control state, so reading recordControl directly here would skip it.
        assertTrue(source.contains("canRecord && compatibleNow && currentCanStartNewVideo()"))
        assertFalse(
            "the restored toggle must not bypass the composition guard",
            source.contains("recordControl.canRecord && compatibleNow"),
        )
        // Read when the import picker launches, before any activity result can land.
        assertTrue(source.contains("if (!currentCanStartNewVideo()) return"))
        // Feeds the derived UI so the button reads unavailable during the handoff.
        assertTrue(source.contains("recordControl.canRecord && isActiveCompatible && canStartNewVideoNow"))
    }

    @Test
    fun `protocol session helpers read the current StateFlow value without a placeholder`() {
        // Legacy behaviour: collectAsState() on a StateFlow exposes the current value in the
        // first composition, so a seeded encoder draft/series/roster is never replaced by a
        // null/neutral placeholder for one frame (a null encoder draft derives JUMPS instead
        // of ENCODER). STATIC source guard only: it does not prove CameraX/UI runtime.
        val source = requireCameraScreenSource()
        assertTrue(
            "nullable protocol helper must take the concrete StateFlow type",
            source.contains("flow: StateFlow<T?>"),
        )
        assertTrue(
            "a non-null StateFlow overload must sit next to the neutral Flow helper",
            source.contains("flow: StateFlow<T>"),
        )
        assertFalse(
            "a nullable protocol flow must never be seeded with a null placeholder",
            source.contains("collectAsState(initial = null)"),
        )
        assertEquals(
            "both StateFlow overloads must read the immediate value",
            2,
            Regex("flow\\.collectAsState\\(\\)").findAll(source).count(),
        )
        assertTrue(
            "plain Flow (AthleteRepository.active) keeps the legacy neutral initial value",
            source.contains("flow.collectAsState(initial = neutral)"),
        )
    }

    @Test
    fun `standalone locked orientation goes through the lease`() {
        val source = requireCameraScreenSource()
        assertTrue(
            "standalone LOCKED before startRecording must route through the lease",
            source.contains("orientationLease?.update(CameraOrientationMode.LOCKED)"),
        )
        assertTrue(
            "protocol flow keeps the original direct window write",
            source.contains("activity?.applyRequestedOrientation(CameraOrientationMode.LOCKED)"),
        )
    }

    @Test
    fun `orientation policy mapping stays the baseline one`() {
        // Guards against a new standalone-only mapping: the shared policy is consumed as today.
        assertEquals(CameraOrientationMode.FULL_SENSOR, CameraOrientationPolicy.forState(JumpCameraState.READY))
        assertEquals(CameraOrientationMode.LOCKED, CameraOrientationPolicy.forState(JumpCameraState.RECORDING))
        val source = requireCameraScreenSource()
        assertTrue(source.contains("val orientationTarget by rememberUpdatedState(CameraOrientationPolicy.forState(pipelineState))"))
        assertTrue(source.contains("orientationLease?.acquire()"))
        assertTrue(source.contains("orientationLease?.release()"))
    }

    private fun requireCameraScreenSource(): String {
        assertNotNull("CameraScreen.kt must be reachable from the unit-test working dir", cameraScreenSource())
        return cameraScreenSource()!!
    }

    private fun cameraScreenSource(): String? {
        val relative = "src/main/java/com/openjump/app/ui/camera/CameraScreen.kt"
        var dir: File? = File("").absoluteFile
        repeat(5) {
            for (candidate in listOf(dir?.let { File(it, relative) }, dir?.let { File(it, "app/$relative") })) {
                if (candidate != null && candidate.isFile) return candidate.readText()
            }
            dir = dir?.parentFile
        }
        return null
    }
}
