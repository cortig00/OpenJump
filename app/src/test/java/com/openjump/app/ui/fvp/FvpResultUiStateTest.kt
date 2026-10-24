package com.openjump.app.ui.fvp

import com.openjump.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard del estado PURO de las secciones del resultado F–V–P (slice A del plan
 * `.pi/plans/fvp-results-sections.md`).
 *
 * No comprueba Compose ni el pager: fija las invariantes que el pager y cualquier restauracion
 * futura asumen — orden estable de secciones, indentidad por NOMBRE (nunca un ordinal fragil),
 * `testTag` unicos y ausencia de repliegue silencioso a Resumen ante un indice o nombre invalido.
 */
class FvpResultUiStateTest {

    @Test
    fun sectionOrderIsThePagerOrderAndNamesAreStable() {
        // El ORDEN es el de las pestanas/paginas; los NOMBRES son la identidad que puede
        // persistirse. Renombrar una constante cambia el contrato de restauracion: este test lo
        // obliga a ser una decision explicita.
        assertEquals(
            listOf("SUMMARY", "CHARTS", "DATA", "QUALITY"),
            FvpResultSection.entries.map { it.name },
        )
        // Indice de pagina y vuelta: una sola fuente de la relacion seccion <-> pagina.
        FvpResultSection.entries.forEachIndexed { index, section ->
            assertEquals(index, fvpPageOf(section))
            assertEquals(section, fvpSectionAt(index))
        }
        assertEquals(FvpResultSection.entries.size, 4)
    }

    @Test
    fun labelsAreTheLocalizedSectionTitles() {
        assertEquals(R.string.fvp_section_summary, FvpResultSection.SUMMARY.labelRes)
        assertEquals(R.string.fvp_section_charts, FvpResultSection.CHARTS.labelRes)
        assertEquals(R.string.fvp_section_data, FvpResultSection.DATA.labelRes)
        assertEquals(R.string.fvp_section_quality, FvpResultSection.QUALITY.labelRes)
    }

    @Test
    fun testTagsAreUniqueAndDoNotDependOnTheOrdinal() {
        val tabs = FvpResultSection.entries.map { it.tabTag }
        val panels = FvpResultSection.entries.map { it.panelTag }
        assertEquals("ninguna pestana puede repetir testTag", tabs.size, tabs.toSet().size)
        assertEquals("ningun panel puede repetir testTag", panels.size, panels.toSet().size)
        assertTrue("pestanas y paneles no comparten espacio de tags", (tabs.toSet() intersect panels.toSet()).isEmpty())
        // El tag se deriva del sufijo estable, no del indice: reordenar el enum no lo cambia.
        assertEquals("fvp-section-tab-summary", FvpResultSection.SUMMARY.tabTag)
        assertEquals("fvp-section-panel-quality", FvpResultSection.QUALITY.panelTag)
    }

    @Test
    fun unknownPageOrNameReturnsNullInsteadOfSummary() {
        // Sin repliegue silencioso: un indice corrupto o un nombre desconocido NO puede pintar (o
        // restaurar) Resumen como si fuera el estado guardado.
        assertNull(fvpSectionAt(-1))
        assertNull(fvpSectionAt(FvpResultSection.entries.size))
        assertNull(fvpSectionAt(Int.MAX_VALUE))
        assertNull(fvpSectionFromName(null))
        assertNull(fvpSectionFromName(""))
        assertNull(fvpSectionFromName("resumen"))
        FvpResultSection.entries.forEach { section ->
            assertEquals(section, fvpSectionFromName(section.name))
        }
    }
}
