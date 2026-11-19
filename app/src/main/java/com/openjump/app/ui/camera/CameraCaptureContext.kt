package com.openjump.app.ui.camera

import com.openjump.app.camera.JumpCameraState
import com.openjump.app.settings.RecordingFpsPolicy
import com.openjump.app.settings.RecordingUse

/**
 * Who owns the capture a [CameraScreen] composition is serving.
 *
 * [ProtocolFlow] is the existing Jump/Encoder flow: every derivation comes from
 * `AppSession` exactly as before, so existing call sites keep byte-identical
 * behaviour and the default stays backwards compatible.
 *
 * [Standalone] is the autonomous reuse (Angles intake): the screen must not
 * subscribe to protocol session state, must not inherit a stale encoder draft or
 * a stale roster, and must own window orientation through a lease so an incoming
 * route keeps the orientation it requested.
 */
enum class CameraCaptureContext {
    ProtocolFlow,
    Standalone;

    /** Protocol drafts/bilateral/encoder/roster flows drive this composition. */
    val usesProtocolSession: Boolean get() = this == ProtocolFlow

    /** Standalone owns window orientation; the protocol default keeps the legacy writes. */
    val usesOrientationLease: Boolean get() = this == Standalone
}

/**
 * Derivation policy for the opt-in context. Kept pure so the JVM tests exercise the
 * exact functions the screen consumes instead of a parallel model.
 */
object CameraCaptureContextPolicy {

    /**
     * Recording cadence use. The protocol flow keeps the existing split between the
     * Jump and Encoder saved defaults; standalone always uses the existing normal
     * Jump default and never inherits a stale encoder draft (no third preference,
     * no settings migration).
     */
    fun recordingUse(context: CameraCaptureContext, hasEncoderDraft: Boolean): RecordingUse =
        when (context) {
            CameraCaptureContext.ProtocolFlow -> RecordingFpsPolicy.useFor(hasEncoderDraft)
            CameraCaptureContext.Standalone -> RecordingUse.JUMPS
        }

    /** Series/bilateral attempt progress is a protocol affordance. */
    fun seriesProgressEnabled(context: CameraCaptureContext, hasActiveDraft: Boolean): Boolean =
        context.usesProtocolSession && hasActiveDraft

    /** The Jump flow step badge belongs to the protocol flow only. */
    fun jumpStepBadgeVisible(context: CameraCaptureContext): Boolean =
        context.usesProtocolSession

    /**
     * Testing-session athlete switching is a protocol affordance: standalone keeps the
     * intake-frozen athlete and can never reach `setCurrentAthlete`/`reassignActiveAthlete`,
     * even when a stale global testing session or roster is still present.
     */
    fun quickAthletePickerAvailable(
        context: CameraCaptureContext,
        rosterSize: Int,
        testingSessionId: Long?,
        hasEligibleAthlete: Boolean,
    ): Boolean = context.usesProtocolSession &&
        rosterSize > 0 &&
        testingSessionId != null &&
        hasEligibleAthlete

    /**
     * Android system Back on the standalone camera must leave through the same path as the camera
     * Back control (recorder pause + the owner's cancel-through-pop), otherwise the pending intake
     * stays in `AwaitingCapture` and the intro keeps Record/Import disabled until it is rebuilt.
     *
     * FINALIZING keeps its pre-existing confirmation handler as the only active one: the stopped
     * take must never be discarded silently, so this policy disables the extra handler there.
     * The protocol flow keeps its exact previous behaviour (no extra handler at all).
     */
    fun shouldHandleStandaloneSystemBack(context: CameraCaptureContext, state: JumpCameraState): Boolean =
        context == CameraCaptureContext.Standalone && state != JumpCameraState.FINALIZING
}
