package com.openjump.app.ui.angles

import com.openjump.app.angles.AngleMarkNameContract

/**
 * Resultado de validar el texto que la persona ha escrito en el diálogo de renombrar.
 *
 * Es una función pura (sin Compose) para poder probarla en JVM: el diálogo solo traduce el caso a
 * texto y decide si el botón Guardar está activo.
 */
internal sealed interface AngleRenameInput {

    /** Texto vacío o solo espacios; mismo criterio que [com.openjump.app.angles.AngleEditorState.rename]. */
    data object Blank : AngleRenameInput

    /** Nombre admitido, ya recortado. Se envía tal cual: nunca se trunca. */
    data class Accepted(val name: String) : AngleRenameInput

    /**
     * El nombre recortado supera [AngleMarkNameContract.MAX_CODE_UNITS] unidades UTF-16. Se rechaza
     * de forma visible en vez de recortarlo en silencio.
     */
    data class TooLong(
        val codeUnits: Int,
        val maxCodeUnits: Int,
    ) : AngleRenameInput
}

/**
 * Valida el texto crudo del campo de renombrado.
 *
 * El valor que se enviará al editor es el texto recortado (el editor recibe exactamente lo que se
 * validó aquí), y la longitud se mide en unidades de código UTF-16, la misma unidad del contrato
 * compartido [AngleMarkNameContract] y del preflight del backup.
 */
internal fun validateAngleRename(raw: String): AngleRenameInput {
    val name = raw.trim()
    if (name.isEmpty()) return AngleRenameInput.Blank
    if (!AngleMarkNameContract.fits(name)) {
        return AngleRenameInput.TooLong(
            codeUnits = AngleMarkNameContract.codeUnits(name),
            maxCodeUnits = AngleMarkNameContract.MAX_CODE_UNITS,
        )
    }
    return AngleRenameInput.Accepted(name)
}
