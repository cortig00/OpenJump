package com.openjump.app.ui.camera

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Standalone orientation ownership on a fake host. These are the ordering rules that
 * keep the camera from clobbering an incoming route that asks for `FULL_USER`, and that
 * keep the immediate `LOCKED` before a take inside the same ownership.
 */
class CameraOrientationLeaseTest {

    private class FakeHost(var current: Int) : CameraOrientationHost {
        val writes = mutableListOf<Int>()
        override val currentRequestedOrientation: Int get() = current
        override fun requestOrientation(requested: Int) {
            if (current != requested) writes += requested
            current = requested
        }
    }

    private val fullSensor = CameraOrientationMode.FULL_SENSOR.requestedOrientation()
    private val locked = CameraOrientationMode.LOCKED.requestedOrientation()
    private val portrait = CameraOrientationMode.PORTRAIT.requestedOrientation()
    private val fullUser = ActivityInfo.SCREEN_ORIENTATION_FULL_USER

    @Test
    fun `resume applies full sensor then locks the immediate take start`() {
        val host = FakeHost(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        val lease = CameraOrientationLease(host)

        lease.acquire()
        assertTrue(lease.ownsWindow)
        assertTrue(lease.update(CameraOrientationMode.FULL_SENSOR))
        assertEquals(listOf(fullSensor), host.writes)

        // toggleRecording -> LOCKED immediately before startRecording, through the lease.
        assertTrue(lease.update(CameraOrientationMode.LOCKED))
        assertEquals(listOf(fullSensor, locked), host.writes)

        // Repeated identical requests are no-ops and keep ownership.
        assertFalse(lease.update(CameraOrientationMode.LOCKED))
        assertTrue(lease.ownsWindow)
        assertEquals(listOf(fullSensor, locked), host.writes)
    }

    @Test
    fun `locked take start does not lose ownership so ready restores full sensor`() {
        val host = FakeHost(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        val lease = CameraOrientationLease(host)
        lease.acquire()

        lease.update(CameraOrientationMode.LOCKED) // take start
        // STARTING/RECORDING/FINALIZING keep LOCKED.
        assertFalse(lease.update(CameraOrientationMode.LOCKED))
        // READY after finalization gets the flexible orientation back.
        assertTrue(lease.update(CameraOrientationMode.FULL_SENSOR))
        assertEquals(listOf(locked, fullSensor), host.writes)
        assertTrue(lease.ownsWindow)
    }

    @Test
    fun `pause restores the value found on acquisition only while still ours`() {
        val host = FakeHost(fullUser)
        val lease = CameraOrientationLease(host)
        lease.acquire()
        lease.update(CameraOrientationMode.FULL_SENSOR)
        lease.update(CameraOrientationMode.LOCKED)

        assertTrue(lease.release())
        assertEquals(fullUser, host.current)
        assertEquals(listOf(fullSensor, locked, fullUser), host.writes)
        assertFalse(lease.ownsWindow)

        // Idempotent: a second pause/dispose writes nothing.
        assertFalse(lease.release())
        assertEquals(listOf(fullSensor, locked, fullUser), host.writes)
    }

    @Test
    fun `incoming full user request before an update is never clobbered`() {
        val host = FakeHost(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        val lease = CameraOrientationLease(host)
        lease.acquire()
        lease.update(CameraOrientationMode.FULL_SENSOR)

        // Incoming workspace route asks FULL_USER while the camera is still composed.
        host.current = fullUser
        assertFalse(lease.update(CameraOrientationMode.LOCKED))
        assertEquals(fullUser, host.current)
        assertEquals(listOf(fullSensor), host.writes)
        assertFalse(lease.ownsWindow)

        // ... and dispose must not restore over the foreign value either.
        assertFalse(lease.release())
        assertEquals(fullUser, host.current)
        assertEquals(listOf(fullSensor), host.writes)
    }

    @Test
    fun `incoming full user request before pause is not overwritten`() {
        val host = FakeHost(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        val lease = CameraOrientationLease(host)
        lease.acquire()
        lease.update(CameraOrientationMode.LOCKED)

        host.current = fullUser
        assertFalse(lease.release())
        assertEquals(fullUser, host.current)
        assertEquals(listOf(locked), host.writes)
    }

    @Test
    fun `resume reacquires the window after a foreign route change`() {
        val host = FakeHost(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        val lease = CameraOrientationLease(host)
        lease.acquire()
        lease.update(CameraOrientationMode.FULL_SENSOR)
        host.current = fullUser
        assertFalse(lease.update(CameraOrientationMode.LOCKED))
        lease.release()

        // Fresh resume: the previous value is the real one seen now, and the target applies again.
        lease.acquire()
        assertTrue(lease.ownsWindow)
        assertTrue(lease.update(CameraOrientationMode.FULL_SENSOR))
        assertEquals(listOf(fullSensor, fullSensor), host.writes)

        // The reacquired previous is FULL_USER, so pause restores it.
        assertTrue(lease.release())
        assertEquals(fullUser, host.current)
    }

    @Test
    fun `paused lease never writes`() {
        val host = FakeHost(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        val lease = CameraOrientationLease(host)

        // Side effects can land while paused: acquire was never called.
        assertFalse(lease.update(CameraOrientationMode.FULL_SENSOR))
        assertFalse(lease.update(CameraOrientationMode.LOCKED))
        assertFalse(lease.release())
        assertEquals(emptyList<Int>(), host.writes)

        lease.acquire()
        lease.update(CameraOrientationMode.LOCKED)
        lease.release()
        val afterPause = host.writes.toList()
        assertFalse(lease.update(CameraOrientationMode.LOCKED))
        assertFalse(lease.update(CameraOrientationMode.FULL_SENSOR))
        assertEquals(afterPause, host.writes)
    }

    @Test
    fun `previous already equal to the requested value skips redundant writes`() {
        val host = FakeHost(fullSensor)
        val lease = CameraOrientationLease(host)
        lease.acquire()

        assertFalse(lease.update(CameraOrientationMode.FULL_SENSOR))
        assertEquals(emptyList<Int>(), host.writes)
        // Nothing to restore: acquisition value is what we still hold.
        assertFalse(lease.release())
        assertEquals(emptyList<Int>(), host.writes)
        assertEquals(fullSensor, host.current)
    }

    @Test
    fun `repeated acquire while resumed does not overwrite the saved previous value`() {
        val host = FakeHost(fullUser)
        val lease = CameraOrientationLease(host)
        lease.acquire()
        lease.update(CameraOrientationMode.LOCKED)
        // A duplicate resume must not adopt our own LOCKED as the value to restore.
        lease.acquire()
        assertTrue(lease.release())
        assertEquals(fullUser, host.current)
    }

    @Test
    fun `disposal without a take restores the incoming orientation`() {
        val host = FakeHost(fullUser)
        val lease = CameraOrientationLease(host)
        lease.acquire()
        lease.update(CameraOrientationMode.FULL_SENSOR)
        // Legacy afterDispose() would force PORTRAIT; the lease restores what it found.
        assertTrue(lease.release())
        assertEquals(fullUser, host.current)
        assertFalse(host.writes.contains(portrait))
    }
}
