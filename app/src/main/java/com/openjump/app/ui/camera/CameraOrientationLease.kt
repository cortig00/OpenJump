package com.openjump.app.ui.camera

import android.content.pm.ActivityInfo

/**
 * Minimal window-orientation port. Production uses the hosting [android.app.Activity];
 * JVM tests use a fake so ownership/ordering is exercised without a device.
 */
interface CameraOrientationHost {
    /** Currently requested orientation, as the platform reports it. */
    val currentRequestedOrientation: Int

    /** Requests an orientation; implementations skip redundant platform writes. */
    fun requestOrientation(requested: Int)
}

/**
 * Platform requested-orientation for a camera mode. These are compile-time int
 * constants, so this mapping (and the lease below) stays unit-testable on the JVM
 * without touching Android at runtime.
 */
fun CameraOrientationMode.requestedOrientation(): Int = when (this) {
    CameraOrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    CameraOrientationMode.FULL_SENSOR -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    CameraOrientationMode.LOCKED -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
}

/**
 * Scoped window-orientation ownership for the standalone camera.
 *
 * The protocol flow keeps writing the orientation directly, as before. Standalone
 * cannot: leaving the camera with an unconditional PORTRAIT write could clobber an
 * incoming route that legitimately asked for `FULL_USER`.
 *
 * Contract:
 * - [acquire] on `ON_RESUME` remembers the real previous value and adopts the window.
 * - [update] writes only while the window still holds the value this lease set. Any
 *   third-party change drops ownership without writing (and without restoring a
 *   value that is no longer ours) until the next resume re-acquires it.
 * - [release] on `ON_PAUSE`/dispose restores the previous value only while it is
 *   still ours; it is idempotent and writes nothing when ownership was lost.
 * - While paused the lease never writes.
 */
class CameraOrientationLease(private val host: CameraOrientationHost) {

    private var resumed = false
    private var lost = false
    private var previous: Int? = null
    private var lastRequested: Int? = null

    /** True while the window still holds a value this lease adopted/wrote. */
    val ownsWindow: Boolean get() = resumed && !lost

    /** `ON_RESUME`: adopt the window and remember what to restore. Idempotent. */
    fun acquire() {
        if (resumed) return
        resumed = true
        lost = false
        // Re-read on every resume: the real value can differ from the first visit.
        previous = host.currentRequestedOrientation
        lastRequested = previous
    }

    /**
     * Applies [mode] only while this lease still owns the window.
     * Every standalone write (including the immediate LOCKED before a take starts)
     * goes through here; a direct write would make the next update believe a foreign
     * route took the window and would silently drop ownership.
     *
     * @return true when the host value was actually changed.
     */
    fun update(mode: CameraOrientationMode): Boolean {
        if (!resumed || lost) return false
        val ours = lastRequested ?: return false
        if (host.currentRequestedOrientation != ours) {
            // Someone else changed the window (e.g. the incoming workspace asking FULL_USER):
            // release ownership without writing and never restore a foreign value.
            lost = true
            lastRequested = null
            previous = null
            return false
        }
        val requested = mode.requestedOrientation()
        if (requested == ours) return false
        host.requestOrientation(requested)
        lastRequested = requested
        return true
    }

    /**
     * `ON_PAUSE`/dispose: restore the value found on acquisition, only while the host
     * still holds what we last requested. Idempotent and never clobbers a foreign value.
     *
     * @return true when the host value was actually changed.
     */
    fun release(): Boolean {
        if (!resumed) return false
        resumed = false
        val restore = previous
        val ours = lastRequested
        val ownershipLost = lost
        previous = null
        lastRequested = null
        lost = false
        if (ownershipLost || restore == null || ours == null) return false
        if (host.currentRequestedOrientation != ours) return false
        if (restore == ours) return false
        host.requestOrientation(restore)
        return true
    }
}
