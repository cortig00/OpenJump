package com.openjump.app.ui.selector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisOrientationPolicyTest {

    @Test
    fun `analysis requests full sensor while visible`() {
        assertEquals(
            AnalysisOrientationMode.FULL_SENSOR,
            AnalysisOrientationPolicy.onEnter(),
        )
    }

    @Test
    fun `analysis restores portrait on dispose`() {
        assertEquals(
            AnalysisOrientationMode.PORTRAIT,
            AnalysisOrientationPolicy.onExit(),
        )
    }
}

class AnalysisLayoutTest {

    @Test
    fun `wider window maps to side-by-side mode`() {
        assertEquals(
            AnalysisLayoutMode.LANDSCAPE,
            AnalysisLayout.forDimensions(800f, 400f),
        )
    }

    @Test
    fun `taller window keeps single column mode`() {
        assertEquals(
            AnalysisLayoutMode.PORTRAIT,
            AnalysisLayout.forDimensions(400f, 800f),
        )
    }

    @Test
    fun `square window keeps single column mode`() {
        assertEquals(
            AnalysisLayoutMode.PORTRAIT,
            AnalysisLayout.forDimensions(400f, 400f),
        )
    }

    @Test
    fun `mode follows actual dimensions at the boundary`() {
        assertEquals(
            AnalysisLayoutMode.LANDSCAPE,
            AnalysisLayout.forDimensions(801f, 800f),
        )
        assertEquals(
            AnalysisLayoutMode.PORTRAIT,
            AnalysisLayout.forDimensions(800f, 801f),
        )
    }

    @Test
    fun `side-by-side weights fill the row without overflow`() {
        val total = AnalysisLayout.VIDEO_WEIGHT + AnalysisLayout.CONTROLS_WEIGHT
        assertEquals(1f, total, 0.001f)
        assertTrue(AnalysisLayout.VIDEO_WEIGHT in 0.5f..0.7f)
        assertTrue(AnalysisLayout.CONTROLS_WEIGHT in 0.3f..0.5f)
    }

    @Test
    fun `pane tags are distinct and stable`() {
        assertTrue(AnalysisLayout.VIDEO_PANE_TAG.isNotBlank())
        assertTrue(AnalysisLayout.CONTROLS_PANE_TAG.isNotBlank())
        assertTrue(AnalysisLayout.VIDEO_PANE_TAG != AnalysisLayout.CONTROLS_PANE_TAG)
    }
}
