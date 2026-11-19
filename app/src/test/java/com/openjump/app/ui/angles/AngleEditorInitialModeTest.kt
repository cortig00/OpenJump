package com.openjump.app.ui.angles

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regresión JVM del modo con el que arranca una composición nueva del editor de ángulos.
 *
 * El borrador A/B vive en el ViewModel y sobrevive a la recreación de la Activity, pero el modo de
 * la capa es estado de composición: se reiniciaba a EDIT y «Añadir» descartaba el borrador, así que
 * tras recrear no había ningún camino para colocar C sin volver a empezar. El modo inicial se deriva
 * del borrador vivo ([initialOverlayMode]) para que A y A+B se puedan completar.
 */
class AngleEditorInitialModeTest {

    @Test
    fun `sin borrador arranca en edicion`() {
        assertEquals(AngleOverlayMode.EDIT, initialOverlayMode(0))
    }

    @Test
    fun `con A colocado arranca en colocacion para poder colocar B`() {
        assertEquals(AngleOverlayMode.PLACEMENT, initialOverlayMode(1))
    }

    @Test
    fun `con A y B colocados arranca en colocacion para poder colocar C`() {
        assertEquals(AngleOverlayMode.PLACEMENT, initialOverlayMode(2))
    }
}
