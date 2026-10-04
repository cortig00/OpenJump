package com.openjump.app.camera

/**
 * Conservative exit policy for a take that is already stopped (FINALIZING).
 *
 * After Detener the recording is stopped: a voluntary exit while FINALIZING must require
 * explicit confirmation before discarding, and an unexpected ON_STOP must never make the take
 * disappear silently. A consolidated MediaStore URI is never deleted without that explicit
 * confirmation; pending rows are rolled back by [com.openjump.app.video.RecordedVideoPublisher]
 * and private sources are cleaned without leaving orphans.
 */
object CameraFinalizingExitPolicy {
    /** Voluntary Back/button exit while FINALIZING requires explicit discard confirmation. */
    fun requiresVoluntaryConfirmation(state: JumpCameraState): Boolean =
        state == JumpCameraState.FINALIZING

    /** An unexpected background transition while FINALIZING must preserve, not discard. */
    fun mustPreserveOnBackground(state: JumpCameraState): Boolean =
        state == JumpCameraState.FINALIZING

    enum class StalePublishDisposition {
        /** Keep the consolidated MediaStore URI for delivery on return (never silently delete). */
        PARK_CONSOLIDATED,
        /** Keep the private fallback file for delivery on return. */
        PARK_PRIVATE,
        /** No publishable result; surface the interruption reason on return. */
        PARK_ERROR,
        /** Explicit user-confirmed discard; caller owns cleanup including consolidated URIs. */
        EXPLICIT_DISCARD,
        /** Disposed, or explicit discard with no result; clean private state silently. A
         * consolidated gallery entry is left untouched (never deleted without consent). */
        CLEAN_PRIVATE_ONLY,
    }

    /**
     * Generation-scoped discard consent for a FINALIZING take.
     *
     * Returns the pre-cancel generation when a stale finalizer remains that needs the
     * consent signal, or null when no stale callback can exist: an already-parked outcome
     * means its finalizer finished, and an already-delivered take (savedDelivered, preview
     * still FINALIZING) leaves no stale publisher to consume it. Retaining consent there
     * would let the next take's stale finalizer delete its own MediaStore URI.
     */
    fun consentTokenForDiscard(
        generation: Long,
        finalizeStarted: Boolean,
        hadParkedOutcome: Boolean,
        savedDelivered: Boolean,
    ): Long? =
        if (finalizeStarted && !hadParkedOutcome && !savedDelivered) generation else null

    /**
     * Real-route disposition for a finalize coroutine that finished while its token was
     * invalidated (pause/dispose/background race). Traverses the same inputs the controller
     * observes: whether publication produced a URI, whether that URI is a consolidated
     * MediaStore entry, the generation-scoped discard consent, the stale token, and disposal.
     * Only the consented generation may take EXPLICIT_DISCARD, so a new take never inherits
     * a previous take's confirmation.
     */
    fun dispositionForStaleResult(
        hasResult: Boolean,
        privateSource: Boolean,
        explicitDiscardToken: Long?,
        staleToken: Long,
        disposed: Boolean,
    ): StalePublishDisposition {
        if (explicitDiscardToken != null && explicitDiscardToken == staleToken) return StalePublishDisposition.EXPLICIT_DISCARD
        if (!hasResult) {
            // No URI was consolidated: nothing user-visible to preserve. Clean private state,
            // unless an interruption reason must stay visible on return.
            return if (disposed) StalePublishDisposition.CLEAN_PRIVATE_ONLY
            else StalePublishDisposition.PARK_ERROR
        }
        // A URI exists. Consolidated MediaStore entries are never deleted without explicit
        // confirmation, so the foreground-stale path preserves them for delivery on return.
        // With no consumer left (disposed) nothing is parked: the gallery entry stays where
        // the user can see it and only private state is cleaned. Parking there would promise
        // an undeliverable outcome and orphan a private fallback.
        if (disposed) return StalePublishDisposition.CLEAN_PRIVATE_ONLY
        return if (privateSource) StalePublishDisposition.PARK_PRIVATE
        else StalePublishDisposition.PARK_CONSOLIDATED
    }
}
