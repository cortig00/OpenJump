package com.openjump.app.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPortraitOverlayPolicyTest {
    @Test
    fun `tall phone keeps overlays within viewport and clear of Back`() {
        val metrics = CameraPortraitOverlayPolicy.calculate(
            widthDp = 393f,
            heightDp = 852f,
            fontScale = 1f,
        )

        assertEquals(15.72f, metrics.horizontalPaddingDp, 0.01f)
        assertEquals(64f, metrics.topOverlayStartClearanceDp, 0.01f)
        assertEquals(15.72f, metrics.topOverlayEndPaddingDp, 0.01f)
        assertEquals(313.28f, metrics.topOverlayWidthDp, 0.01f)
        assertEquals(
            393f,
            metrics.topOverlayStartClearanceDp + metrics.topOverlayWidthDp +
                metrics.topOverlayEndPaddingDp,
            0.01f,
        )
        assertEquals(361.56f, metrics.bottomOverlayWidthDp, 0.01f)
        assertEquals(80f, metrics.recordButtonSizeDp, 0.01f)
        assertFalse(metrics.compactControls)
        assertEquals(56f, metrics.quickAthleteTopOffsetDp, 0.01f)
        assertEquals(56f, metrics.experimentalBadgeTopOffsetDp, 0.01f)
    }

    @Test
    fun `short portrait viewport compacts controls without crossing Back`() {
        val metrics = CameraPortraitOverlayPolicy.calculate(
            widthDp = 360f,
            heightDp = 480f,
            fontScale = 1f,
        )

        assertEquals(14.4f, metrics.horizontalPaddingDp, 0.01f)
        assertTrue(metrics.compactControls)
        assertEquals(72f, metrics.recordButtonSizeDp, 0.01f)
        assertTrue(
            metrics.topOverlayStartClearanceDp + metrics.topOverlayWidthDp +
                metrics.topOverlayEndPaddingDp <= 360f,
        )
        assertTrue(metrics.bottomOverlayWidthDp <= 360f - 2f * metrics.horizontalPaddingDp)
    }

    @Test
    fun `large font stacks simultaneous picker and badge on separate rows`() {
        val metrics = CameraPortraitOverlayPolicy.calculate(
            widthDp = 411f,
            heightDp = 915f,
            fontScale = 1.4f,
            hasQuickAthletePicker = true,
            hasExperimentalBadge = true,
        )

        assertTrue(metrics.compactControls)
        assertEquals(88f, metrics.quickAthleteTopOffsetDp, 0.01f)
        assertEquals(144f, metrics.experimentalBadgeTopOffsetDp, 0.01f)
        assertTrue(metrics.experimentalBadgeTopOffsetDp - metrics.quickAthleteTopOffsetDp >= 48f)
        assertEquals(72f, metrics.recordButtonSizeDp, 0.01f)
        assertTrue(
            metrics.topOverlayStartClearanceDp + metrics.topOverlayWidthDp +
                metrics.topOverlayEndPaddingDp <= 411f,
        )
    }

    @Test
    fun `simultaneous picker and badge stack even on a regular-width phone`() {
        val metrics = CameraPortraitOverlayPolicy.calculate(
            widthDp = 393f,
            heightDp = 852f,
            fontScale = 1f,
            hasQuickAthletePicker = true,
            hasExperimentalBadge = true,
        )

        assertEquals(56f, metrics.quickAthleteTopOffsetDp, 0.01f)
        assertEquals(112f, metrics.experimentalBadgeTopOffsetDp, 0.01f)
        assertTrue(metrics.experimentalBadgeTopOffsetDp - metrics.quickAthleteTopOffsetDp >= 48f)
    }

    @Test
    fun `tablet portrait uses bounded overlays and full record target`() {
        val metrics = CameraPortraitOverlayPolicy.calculate(
            widthDp = 800f,
            heightDp = 1280f,
            fontScale = 1f,
        )

        assertEquals(24f, metrics.horizontalPaddingDp, 0.01f)
        assertEquals(560f, metrics.bottomOverlayWidthDp, 0.01f)
        assertEquals(80f, metrics.recordButtonSizeDp, 0.01f)
        assertFalse(metrics.compactControls)
        assertEquals(56f, metrics.quickAthleteTopOffsetDp, 0.01f)
        assertEquals(56f, metrics.experimentalBadgeTopOffsetDp, 0.01f)
        assertTrue(
            metrics.topOverlayStartClearanceDp + metrics.topOverlayWidthDp +
                metrics.topOverlayEndPaddingDp <= 800f,
        )
        assertTrue(metrics.topOverlayWidthDp <= 560f)
    }
}
