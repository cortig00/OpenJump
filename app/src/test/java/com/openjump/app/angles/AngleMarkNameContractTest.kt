package com.openjump.app.angles

import com.openjump.app.data.backup.MANUAL_BACKUP_MAX_STRING_CHARS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regresión JVM del contrato compartido del nombre de ángulo ([AngleMarkNameContract]).
 *
 * Lo que se fija aquí:
 *  1. el límite es EXACTAMENTE el presupuesto por cadena del preflight del backup (si alguien cambia
 *     uno de los dos sin el otro, este test falla antes de que un nombre aceptado rompa un backup);
 *  2. la unidad es la de `String.length` de Kotlin, es decir unidades de código UTF-16: una letra no
 *     ASCII del plano básico cuenta 1 y un emoji (par sustituto) cuenta 2.
 */
class AngleMarkNameContractTest {

    private val max = AngleMarkNameContract.MAX_CODE_UNITS

    @Test
    fun `el limite es el mismo presupuesto por cadena del preflight del backup`() {
        assertEquals(
            "El editor y el backup deben compartir el límite; derivarlo evita que uno acepte lo que el otro rechaza.",
            MANUAL_BACKUP_MAX_STRING_CHARS,
            max,
        )
        assertEquals(4_096, max)
    }

    @Test
    fun `el limite exacto cabe y una unidad mas no`() {
        assertTrue(AngleMarkNameContract.fits("a".repeat(max)))
        assertFalse(AngleMarkNameContract.fits("a".repeat(max + 1)))
        assertEquals(max, AngleMarkNameContract.codeUnits("a".repeat(max)))
        assertEquals(max + 1, AngleMarkNameContract.codeUnits("a".repeat(max + 1)))
    }

    @Test
    fun `las letras no ascii del plano basico cuentan una unidad`() {
        for (letter in listOf("ñ", "á", "ü", "Ω", "文", "ı", "ß")) {
            assertEquals("'$letter' debe contar 1 unidad UTF-16", 1, AngleMarkNameContract.codeUnits(letter))
            assertTrue(AngleMarkNameContract.fits(letter.repeat(max)))
            assertFalse(AngleMarkNameContract.fits(letter.repeat(max + 1)))
        }
    }

    @Test
    fun `un emoji fuera del bmp cuenta dos unidades igual que en el preflight`() {
        assertEquals(2, AngleMarkNameContract.codeUnits("😀"))
        assertTrue(AngleMarkNameContract.fits("😀".repeat(max / 2)))
        // Un emoji más ya no cabe: 4098 unidades.
        assertFalse(AngleMarkNameContract.fits("😀".repeat(max / 2) + "😀"))
        // Y un solo ASCII adicional sí desborda el límite: 4097 unidades.
        assertFalse(AngleMarkNameContract.fits("😀".repeat(max / 2) + "a"))
    }

    @Test
    fun `un nombre mixto valido llena el limite exacto sin salirse`() {
        val base = "Rodilla ñ 😀 Ω "
        val name = base.repeat(max / base.length) + "ñ".repeat(max % base.length)
        assertEquals(max, AngleMarkNameContract.codeUnits(name))
        assertTrue(AngleMarkNameContract.fits(name))
        assertTrue(name.isNotBlank())
        assertFalse(AngleMarkNameContract.fits(name + "x"))
    }
}
