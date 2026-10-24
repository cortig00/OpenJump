package com.openjump.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSettingsTest {

    @Test
    fun `preferencia legacy por encima de 240 migra a Auto`() {
        assertEquals(CameraSettings.AUTO, CameraSettings.normalizePreferredFps(480, listOf(960, 480, 240)))
    }

    @Test
    fun `preferencia de producto disponible se conserva`() {
        assertEquals(120, CameraSettings.normalizePreferredFps(120, listOf(60, 120, 240)))
    }

    @Test
    fun `FPS degraded 30 and legacy 28 migrate to Auto`() {
        assertEquals(CameraSettings.AUTO, CameraSettings.normalizePreferredFps(30, listOf(30, 60)))
        assertEquals(CameraSettings.AUTO, CameraSettings.normalizePreferredFps(28, listOf(30, 60)))
    }

    @Test
    fun `sin metadata conserva preferencia valida`() {
        assertEquals(240, CameraSettings.normalizePreferredFps(240, emptyList()))
    }

    @Test
    fun `valores no positivos se normalizan a Auto`() {
        assertEquals(CameraSettings.AUTO, CameraSettings.normalizePreferredFps(-1, listOf(240)))
    }

    @Test
    fun `fresh install seeds Auto Auto`() {
        val snapshot = RecordingFpsPolicy.Snapshot(
            legacyRaw = null,
            jumpsRaw = null,
            encoderRaw = null,
            splitDone = false,
        )
        val writes = RecordingFpsPolicy.splitWrites(snapshot)
        assertEquals(
            mapOf(
                RecordingFpsPolicy.JUMPS_KEY to CameraSettings.AUTO,
                RecordingFpsPolicy.ENCODER_KEY to CameraSettings.AUTO,
                RecordingFpsPolicy.SPLIT_DONE_KEY to true,
            ),
            writes,
        )
        assertEquals(CameraSettings.AUTO, RecordingFpsPolicy.effectiveRaw(snapshot, RecordingUse.JUMPS))
        assertEquals(CameraSettings.AUTO, RecordingFpsPolicy.effectiveRaw(snapshot, RecordingUse.ENCODER))
    }

    @Test
    fun `legacy product values seed both modes`() {
        listOf(CameraSettings.AUTO, 60, 120, 240).forEach { legacy ->
            val snapshot = RecordingFpsPolicy.Snapshot(legacy, null, null, false)
            assertEquals(
                mapOf(
                    RecordingFpsPolicy.JUMPS_KEY to legacy,
                    RecordingFpsPolicy.ENCODER_KEY to legacy,
                    RecordingFpsPolicy.SPLIT_DONE_KEY to true,
                ),
                RecordingFpsPolicy.splitWrites(snapshot),
            )
        }
    }

    @Test
    fun `unsupported legacy raw is preserved then normalizes to Auto`() {
        val snapshot = RecordingFpsPolicy.Snapshot(480, null, null, false)
        val writes = RecordingFpsPolicy.splitWrites(snapshot)
        assertEquals(480, writes[RecordingFpsPolicy.JUMPS_KEY])
        assertEquals(480, writes[RecordingFpsPolicy.ENCODER_KEY])
        assertEquals(
            CameraSettings.AUTO,
            CameraSettings.normalizePreferredFps(480, listOf(60, 120, 240)),
        )
    }

    @Test
    fun `first write snapshots legacy to both before overwriting one mode`() {
        val snapshot = RecordingFpsPolicy.Snapshot(120, null, null, false)
        val writes = RecordingFpsPolicy.writeFor(snapshot, RecordingUse.JUMPS, 60)
        assertEquals(60, writes[RecordingFpsPolicy.JUMPS_KEY])
        assertEquals(120, writes[RecordingFpsPolicy.ENCODER_KEY])
        assertEquals(true, writes[RecordingFpsPolicy.SPLIT_DONE_KEY])
        // Single atomic map: exactly the split keys plus the single overwrite.
        assertEquals(3, writes.size)

        val encoderFirst = RecordingFpsPolicy.writeFor(snapshot, RecordingUse.ENCODER, 240)
        assertEquals(120, encoderFirst[RecordingFpsPolicy.JUMPS_KEY])
        assertEquals(240, encoderFirst[RecordingFpsPolicy.ENCODER_KEY])
    }

    @Test
    fun `after split writes never leak across modes`() {
        val split = RecordingFpsPolicy.Snapshot(120, 60, 120, true)
        assertEquals(
            mapOf(RecordingFpsPolicy.JUMPS_KEY to 240),
            RecordingFpsPolicy.writeFor(split, RecordingUse.JUMPS, 240),
        )
        assertEquals(
            mapOf(RecordingFpsPolicy.ENCODER_KEY to CameraSettings.AUTO),
            RecordingFpsPolicy.writeFor(split, RecordingUse.ENCODER, CameraSettings.AUTO),
        )
        assertTrue(RecordingFpsPolicy.splitWrites(split).isEmpty())
    }

    @Test
    fun `after split reads never fall back to mutable legacy`() {
        val split = RecordingFpsPolicy.Snapshot(
            legacyRaw = 240,
            jumpsRaw = 60,
            encoderRaw = CameraSettings.AUTO,
            splitDone = true,
        )
        assertEquals(60, RecordingFpsPolicy.effectiveRaw(split, RecordingUse.JUMPS))
        assertEquals(CameraSettings.AUTO, RecordingFpsPolicy.effectiveRaw(split, RecordingUse.ENCODER))
    }

    @Test
    fun `reopen keeps independence and reset is per mode`() {
        val reopened = RecordingFpsPolicy.Snapshot(120, 240, 60, true)
        assertEquals(240, RecordingFpsPolicy.effectiveRaw(reopened, RecordingUse.JUMPS))
        assertEquals(60, RecordingFpsPolicy.effectiveRaw(reopened, RecordingUse.ENCODER))
        val resetJumps = reopened.copy(jumpsRaw = CameraSettings.AUTO)
        assertEquals(CameraSettings.AUTO, RecordingFpsPolicy.effectiveRaw(resetJumps, RecordingUse.JUMPS))
        assertEquals(60, RecordingFpsPolicy.effectiveRaw(resetJumps, RecordingUse.ENCODER))
    }

    @Test
    fun `migration maps touch only fps keys`() {
        val allowed = setOf(
            RecordingFpsPolicy.JUMPS_KEY,
            RecordingFpsPolicy.ENCODER_KEY,
            RecordingFpsPolicy.SPLIT_DONE_KEY,
        )
        val snapshot = RecordingFpsPolicy.Snapshot(120, null, null, false)
        assertTrue(RecordingFpsPolicy.splitWrites(snapshot).keys.all { it in allowed })
        assertTrue(
            RecordingFpsPolicy.writeFor(snapshot, RecordingUse.ENCODER, 60).keys.all { it in allowed },
        )
    }

    @Test
    fun `recording use follows encoder draft presence`() {
        assertEquals(RecordingUse.ENCODER, RecordingFpsPolicy.useFor(true))
        assertEquals(RecordingUse.JUMPS, RecordingFpsPolicy.useFor(false))
        assertEquals(RecordingFpsPolicy.JUMPS_KEY, RecordingFpsPolicy.keyFor(RecordingUse.JUMPS))
        assertEquals(RecordingFpsPolicy.ENCODER_KEY, RecordingFpsPolicy.keyFor(RecordingUse.ENCODER))
    }
}
