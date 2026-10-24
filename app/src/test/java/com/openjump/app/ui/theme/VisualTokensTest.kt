package com.openjump.app.ui.theme

import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Visual-slice regression guard (F-012/F-013/F-016 scope: light nav/stepper
 * brand-green tokens and sans tabular timestamp).
 *
 * Asserts the approved policy rather than screen pixels: navigation + stepper
 * use the brand-green family, the blue/lavender role stays reserved for data,
 * and numeric text uses sans tabular figures instead of ad-hoc monospace.
 */
class VisualTokensTest {

    @Test
    fun `light nav uses brand green not lavender`() {
        assertEquals(LightPrimaryContainer, LightNavigationSelectedContainer)
        assertEquals(LightOnPrimaryContainer, LightNavigationSelectedContent)
        assertNotEquals(LightSecondaryContainer, LightNavigationSelectedContainer)
    }

    @Test
    fun `light stepper completed uses brand green not blue`() {
        assertEquals(LightPrimaryContainer, LightProgressCompletedContainer)
        assertEquals(LightOnPrimaryContainer, LightProgressCompletedContent)
        // Blue data role is retained for charts, not for progress.
        assertEquals(LightSecondary, LightDataBlue)
        assertNotEquals(LightDataBlue, LightProgressCompletedContainer)
    }

    @Test
    fun `numeric timestamp token is sans tabular not monospace`() {
        assertEquals(null, OpenJumpTypes.Timestamp.fontFamily)
        assertNotEquals(FontFamily.Monospace, OpenJumpTypes.Timestamp.fontFamily)
        assertEquals("tnum", OpenJumpTypes.Timestamp.fontFeatureSettings)
    }
}
