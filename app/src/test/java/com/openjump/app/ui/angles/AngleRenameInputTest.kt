package com.openjump.app.ui.angles

import com.openjump.app.angles.AngleMarkNameContract
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regresión JVM de la validación del diálogo de renombrar ([validateAngleRename]).
 *
 * Es la frontera de UI: decide si Guardar está activo y qué texto de error se muestra. Debe usar el
 * mismo contrato que la persistencia y el backup ([AngleMarkNameContract]), medir sobre el texto que
 * realmente se enviará (recortado) y nunca devolver una versión truncada del nombre.
 */
class AngleRenameInputTest {

    private val max = AngleMarkNameContract.MAX_CODE_UNITS

    @Test
    fun `texto vacio o solo espacios es Blank`() {
        assertEquals(AngleRenameInput.Blank, validateAngleRename(""))
        assertEquals(AngleRenameInput.Blank, validateAngleRename("   "))
        assertEquals(AngleRenameInput.Blank, validateAngleRename("\n\t "))
    }

    @Test
    fun `el limite exacto se acepta y una unidad mas se rechaza sin truncar`() {
        val atLimit = "ñ".repeat(max)
        assertEquals(AngleRenameInput.Accepted(atLimit), validateAngleRename(atLimit))
        assertEquals(
            AngleRenameInput.TooLong(codeUnits = max + 1, maxCodeUnits = max),
            validateAngleRename(atLimit + "ñ"),
        )
    }

    @Test
    fun `se recorta el espacio sobrante antes de medir y de enviar`() {
        assertEquals(AngleRenameInput.Accepted("Rodilla"), validateAngleRename("  Rodilla  "))
        // Con el espacio de sobra, el texto completo pasaría del límite; recortado cabe exacto.
        assertEquals(
            AngleRenameInput.Accepted("ñ".repeat(max)),
            validateAngleRename("  " + "ñ".repeat(max) + "  "),
        )
    }

    @Test
    fun `un emoji cuenta dos unidades como en el backup`() {
        assertEquals(
            AngleRenameInput.Accepted("😀".repeat(max / 2)),
            validateAngleRename("😀".repeat(max / 2)),
        )
        assertEquals(
            AngleRenameInput.TooLong(codeUnits = max + 2, maxCodeUnits = max),
            validateAngleRename("😀".repeat(max / 2 + 1)),
        )
    }

    @Test
    fun `un nombre valido no ascii se devuelve integro`() {
        val name = "Rodilla ñ 😀 Ω 文"
        assertEquals(AngleRenameInput.Accepted(name), validateAngleRename(name))
    }
}
