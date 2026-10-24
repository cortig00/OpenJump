package com.openjump.app.ui.fvp

import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import java.util.Locale

/**
 * Resolucion de los campos numericos del flujo F-V-P. Parte PURA (sin Compose): los campos teclean
 * un texto en la unidad del perfil, pero lo que se guarda es un canonico (kg, cm, m) y la forma
 * VISIBLE del campo esta REDONDEADA. Re-parsear esa forma redondeada mientras el campo sigue
 * intacto desplaza el valor guardado (0,40 m en EE. UU. se muestra "15,748" y re-parsea 0,3999992 m;
 * 80 kg se muestra "176,3698" y re-parsea 79,9999956 kg).
 *
 * Regla unica de esta capa: si el texto del campo es EXACTAMENTE el texto con el que se sembro
 * (`seedText`), el canonico resuelto es EXACTAMENTE el valor de la semilla (`seedCanonicalValue`,
 * [Double] bit a bit); en cualquier otro caso se parsea como siempre
 * ([MeasurementFormatting.parseCanonical] / [parsePushOffInput]), sin tolerancias, sin redondeos
 * nuevos y sin valores por defecto inventados.
 *
 * La semilla vive junto al texto en la pantalla (mismas claves de `rememberSaveable`) y solo cambia
 * cuando el texto se re-siembra desde el borrador: sugerencia de masa del perfil, re-sembrado de la
 * altura al reutilizar un salto guardado o re-inicializacion por cambio de perfil de unidades.
 * Editar el texto NUNCA toca la semilla: escribir y volver a dejar el texto original recupera el
 * valor exacto del borrador, que es justo lo que se espera de un campo sin cambios efectivos.
 *
 * Relacion con [MeasurementFormatting.parseCanonicalInput]: esa funcion ya existe y compara con
 * `trim()` (`unchangedCanonicalValue`), pero esta capa es DELIBERADAMENTE exacta (igualdad de
 * cadenas, sin `trim` ni normalizacion) porque la forma VISIBLE del campo esta redondeada a su
 * precision de visualizacion: una comparacion normalizada daria por "intacto" un texto que no lo
 * es. La coincidencia exacta tampoco puede distinguir un campo que nunca se toco de uno reescrito
 * o pegado al MISMO literal, pero ese caso queda acotado por la precision de visualizacion del
 * campo y el canonico resuelto es el CORRECTO dentro del dominio: en el limite de 0,80 m del
 * perfil de EE. UU. el texto visible "31,4961" resuelve 0,80 m (en dominio), no el 0,80000094 m que
 * devolveria re-parsear esa forma redondeada.
 */
private fun resolveSeeded(text: String, seedText: String, seedValue: Double?, parse: () -> Double?): Double? =
    if (seedValue != null && text == seedText) seedValue else parse()

/** Masa corporal / carga anadida: canonico en kg, campo en la unidad de masa del perfil. */
internal fun resolveSeededMassKg(
    text: String,
    seedText: String,
    seedMassKg: Double?,
    unitProfile: UnitProfile,
    locale: Locale,
): Double? = resolveSeeded(text, seedText, seedMassKg) {
    MeasurementFormatting.parseCanonical(text, MeasurementQuantity.MASS_KG, unitProfile, locale)
}

/** Componentes del h_po (flexion/ext ension): canonico en cm, campo en longitud corta (cm/in). */
internal fun resolveSeededShortLengthCm(
    text: String,
    seedText: String,
    seedCm: Double?,
    unitProfile: UnitProfile,
    locale: Locale,
): Double? = resolveSeeded(text, seedText, seedCm) {
    MeasurementFormatting.parseCanonical(text, MeasurementQuantity.SHORT_LENGTH_CM, unitProfile, locale)
}

/** h_po: se teclea en longitud corta (cm/in) y su canonico guardado son METROS. */
internal fun resolveSeededPushOffM(
    text: String,
    seedText: String,
    seedPushOffM: Double?,
    unitProfile: UnitProfile,
    locale: Locale,
): Double? = resolveSeeded(text, seedText, seedPushOffM) {
    parsePushOffInput(text, unitProfile, locale)
}

/** Altura de un intento: se teclea en longitud corta (cm/in) y su canonico guardado son METROS. */
internal fun resolveSeededHeightM(
    text: String,
    seedText: String,
    seedHeightM: Double?,
    unitProfile: UnitProfile,
    locale: Locale,
): Double? = resolveSeeded(text, seedText, seedHeightM) {
    MeasurementFormatting.parseCanonical(text, MeasurementQuantity.SHORT_LENGTH_CM, unitProfile, locale)
        ?.div(CENTIMETERS_PER_METER)
}

/**
 * Texto y canonico con los que hay que re-sembrar un campo cuando el borrador cambia desde fuera.
 */
internal data class FvpFieldReseed(val text: String, val canonicalValue: Double?)

/**
 * Decision PURA de la reconciliacion del campo de altura: el selector de "reutilizar salto
 * guardado" (y cualquier otra escritura externa) cambia `draftHeightM` sin pasar por el campo de
 * texto, asi que el texto visible debe reflejarlo SIN pisar lo que el usuario esta tecleando (solo
 * cuando de verdad representa otro valor).
 *
 * Devuelve `null` cuando no hay que tocar nada; en caso contrario, el texto del borrador formateado
 * en la unidad del campo y el canonico que hay que recordar como semilla (para que el texto
 * re-sembrado resuelva EXACTAMENTE el valor nuevo).
 */
internal fun reconcileHeightField(
    text: String,
    seedText: String,
    seedHeightM: Double?,
    draftHeightM: Double?,
    unitProfile: UnitProfile,
    locale: Locale,
): FvpFieldReseed? {
    if (resolveSeededHeightM(text, seedText, seedHeightM, unitProfile, locale) == draftHeightM) return null
    return FvpFieldReseed(
        text = formatCanonical(
            draftHeightM?.times(CENTIMETERS_PER_METER),
            MeasurementQuantity.SHORT_LENGTH_CM,
            unitProfile,
            locale,
        ),
        canonicalValue = draftHeightM,
    )
}
