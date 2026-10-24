package com.openjump.app.ui.fvp

import androidx.compose.runtime.saveable.Saver
import com.openjump.app.settings.MassUnit
import com.openjump.app.settings.ShortLengthUnit
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import java.util.Locale

/**
 * Campo numerico del paso 1 del flujo F-V-P y su canonico guardado (el que viaja al borrador).
 *
 * Es un enum CERRADO a proposito: el campo de h_po se teclea en longitud corta (cm/in) pero su
 * canonico son METROS, y esa conversion no puede quedar en manos de cada llamante.
 */
internal enum class FvpSetupNumberQuantity {
    /** Masa corporal / carga anadida: canonico en kg, campo en la unidad de masa del perfil. */
    MASS_KG,

    /** Componentes del h_po (flexion / extension): canonico en cm, campo en longitud corta. */
    SHORT_LENGTH_CM,

    /** h_po comun: campo en longitud corta (cm/in), canonico en METROS (`formatPushOffInput`). */
    PUSH_OFF_M,
}

/**
 * Estado de UN campo numerico del paso 1: texto visible, identidad EXACTA de la semilla, canonico
 * de referencia y la base (unidad + locale) con la que el texto esta escrito.
 *
 * Reglas (plan 13, D1/U1/U2):
 * - Mientras el campo no se edite (`text == seedText`), el canonico resuelto es EXACTAMENTE
 *   [seedCanonical] (bit a bit). Re-parsear la forma visible redondeada desplazaria el valor
 *   guardado (0,80 m se ve "31,4961" in y re-parsea 0,80000094 m, FUERA del dominio).
 * - Cambiar de perfil de unidades o de locale NO re-siembra desde el borrador: se RE-BASA el texto
 *   con su canonico (parseado con la base ANTIGUA, formateado con la NUEVA). Una edicion valida sin
 *   confirmar conserva su valor exacto gracias a [canonicalAnchor], y la semilla se reformatea
 *   tambien, de modo que volver a dejar el texto de la semilla recupera el valor EXACTO original.
 * - Un texto parcial o invalido NO se convierte en vacio, cero ni en la semilla anterior: se
 *   conserva tal cual, se enciende [requiresReview] y el paso 1 no deja avanzar hasta que el usuario
 *   lo edite de nuevo bajo las unidades actuales (copy ya existente `unit_input_review_required`).
 * - El estado es SAVEABLE (texto, semilla, canonico de referencia, unidad, locale y latch) para que
 *   una recreacion no pierda ni los anclajes exactos ni el aviso.
 *
 * `localeTag` se guarda como etiqueta BCP 47 (no como [Locale], que no es Bundle-friendly); la
 * comparacion con el locale actual se hace por etiqueta.
 */
internal data class FvpSetupNumber(
    val text: String,
    val seedText: String,
    val seedCanonical: Double?,
    val canonicalAnchor: Double?,
    val quantity: FvpSetupNumberQuantity,
    /** Id de la unidad visible del campo en el perfil con el que se escribio [text]. */
    val unitId: String,
    val localeTag: String,
    val requiresReview: Boolean = false,
) {

    /**
     * True mientras la base guardada (unidad o locale) no coincide con la actual. Durante esa
     * ventana de recomposicion el texto NO se interpreta: ni se pinta error de valor ni se resuelve
     * un canonico con la unidad equivocada. El efecto de re-base la cierra en el mismo frame.
     */
    fun isBasisPending(profile: UnitProfile, locale: Locale): Boolean =
        unitId != quantity.unitIdOf(profile) || localeTag != locale.toLanguageTag()

    /**
     * Canonico resuelto del campo, o `null` si el texto no es un valor valido bajo la base actual.
     *
     * `null` tambien durante la ventana de re-base ([isBasisPending]) y mientras `requiresReview`
     * este encendido: en ambos casos el paso 1 no puede avanzar con un valor deducido a medias.
     */
    fun canonical(profile: UnitProfile, locale: Locale): Double? {
        if (isBasisPending(profile, locale)) return null
        if (requiresReview) return null
        // A rebase can render an edited value exactly like the old seed. Its surviving anchor
        // is authoritative; an explicit edit clears it and can still revert to the seed.
        canonicalAnchor?.let { return it }
        if (seedCanonical != null && text == seedText) return seedCanonical
        val basis = quantity.profileOf(unitId) ?: return null
        return quantity.parse(text, basis, Locale.forLanguageTag(localeTag))
    }

    /** Snapshot estable para `rememberSaveable` (String / Double / Boolean / null). */
    fun saveSnapshot(): List<Any?> = listOf(
        text,
        seedText,
        seedCanonical,
        canonicalAnchor,
        quantity.name,
        unitId,
        localeTag,
        requiresReview,
    )

    companion object {
        /**
         * Campo sembrado desde un canonico del borrador (o vacio si no hay valor): el texto nace
         * siendo la semilla EXACTA de si mismo.
         */
        fun seeded(
            canonicalValue: Double?,
            quantity: FvpSetupNumberQuantity,
            profile: UnitProfile,
            locale: Locale,
        ): FvpSetupNumber {
            val usable = canonicalValue?.takeIf { it.isFinite() }
            val text = usable?.let { quantity.format(it, profile, locale) }.orEmpty()
            return FvpSetupNumber(
                text = text,
                seedText = text,
                seedCanonical = usable,
                canonicalAnchor = null,
                quantity = quantity,
                unitId = quantity.unitIdOf(profile),
                localeTag = locale.toLanguageTag(),
                requiresReview = false,
            )
        }

        /**
         * Edicion EXPLICITA del usuario: el texto pasa a ser entrada de la base ACTUAL, se apaga el
         * latch de revision y se descarta el canonico de referencia. La semilla NO se toca: dejar el
         * texto igual que la semilla (reformateada a la base actual) recupera su valor exacto.
         */
        fun edited(state: FvpSetupNumber, text: String, profile: UnitProfile, locale: Locale): FvpSetupNumber {
            val unitId = state.quantity.unitIdOf(profile)
            val localeTag = locale.toLanguageTag()
            val rebasedSeedText = if (state.seedCanonical != null &&
                (state.unitId != unitId || state.localeTag != localeTag)
            ) {
                state.quantity.format(state.seedCanonical, profile, locale)
            } else {
                state.seedText
            }
            return state.copy(
                text = text,
                seedText = rebasedSeedText,
                canonicalAnchor = null,
                unitId = unitId,
                localeTag = localeTag,
                requiresReview = false,
            )
        }

        /**
         * Re-base por cambio de perfil de unidades o de locale. El texto valido se reescribe con su
         * canonico (parseado con la base ANTIGUA), el invalido/parcial se conserva y se enciende el
         * latch. Sin cambios de base devuelve la MISMA instancia.
         */
        fun rebase(state: FvpSetupNumber, profile: UnitProfile, locale: Locale): FvpSetupNumber {
            val unitId = state.quantity.unitIdOf(profile)
            val localeTag = locale.toLanguageTag()
            if (state.unitId == unitId && state.localeTag == localeTag) return state
            // La SEMILLA se expresa siempre en la base ACTUAL, aunque el texto visible se conserve
            // (vacio o parcial): asi volver a teclear la forma visible de la semilla recupera su
            // valor exacto tambien despues de una transicion.
            val seedText = state.seedCanonical?.let { state.quantity.format(it, profile, locale) }
                ?: state.seedText
            if (state.text.isBlank()) {
                return state.copy(
                    seedText = seedText,
                    unitId = unitId,
                    localeTag = localeTag,
                    canonicalAnchor = null,
                    requiresReview = false,
                )
            }
            val canonical = if (state.requiresReview) {
                null
            } else if (state.canonicalAnchor != null) {
                state.canonicalAnchor
            } else if (state.seedCanonical != null && state.text == state.seedText) {
                // Campo INTACTO: manda la semilla exacta, nunca el re-parseo de su forma redondeada.
                state.seedCanonical
            } else {
                state.quantity.profileOf(state.unitId)?.let { oldBasis ->
                    state.quantity.parse(state.text, oldBasis, Locale.forLanguageTag(state.localeTag))
                }
            }
            if (canonical == null) {
                return state.copy(
                    seedText = seedText,
                    unitId = unitId,
                    localeTag = localeTag,
                    canonicalAnchor = null,
                    requiresReview = true,
                )
            }
            return state.copy(
                text = state.quantity.format(canonical, profile, locale),
                seedText = seedText,
                canonicalAnchor = canonical,
                unitId = unitId,
                localeTag = localeTag,
                requiresReview = false,
            )
        }

        /** Restauracion defensiva: cualquier entrada que no se pueda reconstruir se descarta. */
        fun restoreSnapshot(values: List<Any?>): FvpSetupNumber? {
            if (values.size != 8) return null
            val quantity = FvpSetupNumberQuantity.entries.firstOrNull { it.name == values[4] } ?: return null
            val unitId = values[5] as? String ?: return null
            if (quantity.profileOf(unitId) == null) return null
            for (index in listOf(2, 3)) {
                val raw = values[index]
                if (raw != null && (raw !is Number || !raw.toDouble().isFinite())) return null
            }
            val requiresReview = values[7] as? Boolean ?: return null
            return FvpSetupNumber(
                text = values[0] as? String ?: return null,
                seedText = values[1] as? String ?: return null,
                seedCanonical = (values[2] as? Number)?.toDouble(),
                canonicalAnchor = (values[3] as? Number)?.toDouble(),
                quantity = quantity,
                unitId = unitId,
                localeTag = values[6] as? String ?: return null,
                requiresReview = requiresReview,
            )
        }

        /** `Saver` para `rememberSaveable`: conserva anclajes exactos y latch de revision. */
        val Saver: Saver<FvpSetupNumber, List<Any?>> = Saver(
            save = { state -> state.saveSnapshot() },
            restore = { values -> restoreSnapshot(values) },
        )
    }
}

/** Unidad visible del campo dentro del perfil (solo la parte del perfil que este campo usa). */
private fun FvpSetupNumberQuantity.unitIdOf(profile: UnitProfile): String = when (this) {
    FvpSetupNumberQuantity.MASS_KG -> profile.mass.id
    FvpSetupNumberQuantity.SHORT_LENGTH_CM, FvpSetupNumberQuantity.PUSH_OFF_M -> profile.shortLength.id
}

/**
 * Perfil MINIMO con la misma base de este campo: solo cuenta la unidad que lo rige, asi que un
 * cambio de una unidad ajena (velocidad, distancia...) no obliga a re-basar el texto.
 *
 * `null` si el id guardado ya no existe en esta version de la app: el campo queda en revision en vez
 * de reinterpretarse contra otra unidad.
 */
private fun FvpSetupNumberQuantity.profileOf(unitId: String): UnitProfile? = when (this) {
    FvpSetupNumberQuantity.MASS_KG -> MassUnit.decode(unitId)?.let { UnitProfile(mass = it) }
    FvpSetupNumberQuantity.SHORT_LENGTH_CM, FvpSetupNumberQuantity.PUSH_OFF_M ->
        ShortLengthUnit.decode(unitId)?.let { UnitProfile(shortLength = it) }
}

/** Forma visible del canonico en la base dada (misma que siembra hoy la pantalla). */
private fun FvpSetupNumberQuantity.format(canonical: Double, profile: UnitProfile, locale: Locale): String =
    when (this) {
        FvpSetupNumberQuantity.MASS_KG ->
            formatCanonical(canonical, MeasurementQuantity.MASS_KG, profile, locale)
        FvpSetupNumberQuantity.SHORT_LENGTH_CM ->
            formatCanonical(canonical, MeasurementQuantity.SHORT_LENGTH_CM, profile, locale)
        FvpSetupNumberQuantity.PUSH_OFF_M -> formatPushOffInput(canonical, profile, locale)
    }

/** Texto en la base dada -> canonico del campo (kg / cm / m). `null` si no es un numero valido. */
private fun FvpSetupNumberQuantity.parse(text: String, profile: UnitProfile, locale: Locale): Double? =
    when (this) {
        FvpSetupNumberQuantity.MASS_KG ->
            MeasurementFormatting.parseCanonical(text, MeasurementQuantity.MASS_KG, profile, locale)
        FvpSetupNumberQuantity.SHORT_LENGTH_CM ->
            MeasurementFormatting.parseCanonical(text, MeasurementQuantity.SHORT_LENGTH_CM, profile, locale)
        FvpSetupNumberQuantity.PUSH_OFF_M -> parsePushOffInput(text, profile, locale)
    }
