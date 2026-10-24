package com.openjump.app.ui.encoder

import com.openjump.app.encoder.EncoderMetricKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderFormulaCatalogTest {
    @Test
    fun `formula ids and metric coverage are complete and unique`() {
        assertEquals(EncoderFormulaId.entries.toSet(), encoderFormulaCatalog.map { it.id }.toSet())
        assertEquals(EncoderFormulaId.entries.size, encoderFormulaCatalog.size)
        assertEquals(
            EncoderMetricKey.entries.toSet(),
            encoderFormulaCatalog.flatMap { it.metricKeys }.toSet(),
        )
    }

    @Test
    fun `every formula has visual and spoken content`() {
        encoderFormulaCatalog.forEach { formula ->
            assertNotEquals(0, formula.titleResource)
            assertNotEquals(0, formula.summaryResource)
            assertNotEquals(0, formula.explanationResource)
            assertNotEquals(0, formula.symbolsResource)
            assertNotEquals(0, formula.spokenEquationResource)
            assertNotEquals(0, formula.drawable)
        }
    }

    @Test
    fun `long equations provide compact vectors`() {
        val responsive = setOf(
            EncoderFormulaId.CUBIC_FIT,
            EncoderFormulaId.PHASE_TIMES,
            EncoderFormulaId.VELOCITY_LOSS,
        )
        responsive.forEach { id ->
            assertTrue(requireNotNull(encoderFormulaCatalog.firstOrNull { it.id == id }).compactDrawable != null)
        }
    }

    @Test
    fun `responsive formulas select compact vectors for narrow or enlarged content`() {
        val velocityLoss = requireNotNull(
            encoderFormulaCatalog.firstOrNull { it.id == EncoderFormulaId.VELOCITY_LOSS },
        )
        assertEquals(velocityLoss.drawable, velocityLoss.drawableFor(widthDp = 411f, fontScale = 1f))
        assertEquals(velocityLoss.compactDrawable, velocityLoss.drawableFor(widthDp = 320f, fontScale = 1f))
        assertEquals(velocityLoss.compactDrawable, velocityLoss.drawableFor(widthDp = 411f, fontScale = 1.3f))
    }

    @Test
    fun `MCV remains the primary metric formula`() {
        val mcv = requireNotNull(encoderFormulaCatalog.firstOrNull { EncoderMetricKey.MCV in it.metricKeys })
        assertEquals(EncoderFormulaId.MCV, mcv.id)
        assertNotEquals(0, mcv.summaryResource)
    }
}
