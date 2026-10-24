package com.openjump.app.ui.encoder

import androidx.compose.runtime.saveable.Saver
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.settings.EncoderSettings
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.UnitAwareNumericInputState
import java.util.Locale

/** Setup-only drafts: switching exercise never overwrites another exercise's manual input. */
internal data class EncoderLoadDrafts(val inputs: Map<EncoderExercise, UnitAwareNumericInputState>) {
    fun input(exercise: EncoderExercise): UnitAwareNumericInputState = inputs.getValue(exercise)

    fun edited(exercise: EncoderExercise, text: String, profile: UnitProfile, locale: Locale) =
        copy(inputs = inputs + (exercise to UnitAwareNumericInputState.edited(text, profile, MeasurementQuantity.MASS_KG, locale)))

    fun rebase(profile: UnitProfile, locale: Locale) = copy(inputs = inputs.mapValues { (_, input) ->
        UnitAwareNumericInputState.rebase(input, profile, MeasurementQuantity.MASS_KG, locale)
    })

    fun saveSnapshot(): List<Any?> = EncoderExercise.entries.map { listOf(it.name) + input(it).saveSnapshot() }

    companion object {
        fun initial(defaultsKg: Map<EncoderExercise, Double>, profile: UnitProfile, locale: Locale) =
            EncoderLoadDrafts(EncoderExercise.entries.associateWith { exercise ->
                val canonicalKg = defaultsKg[exercise]?.takeIf(EncoderSettings::isValidLoadKg) ?: EncoderSettings.DEFAULT_LOAD_KG
                UnitAwareNumericInputState.initial(
                    MeasurementFormatting.formatValue(canonicalKg, MeasurementQuantity.MASS_KG, profile, locale, decimals = 2),
                    profile,
                    canonicalKg,
                )
            })

        fun restoreSnapshot(values: List<Any?>): EncoderLoadDrafts? {
            if (values.size != EncoderExercise.entries.size) return null
            val inputs = mutableMapOf<EncoderExercise, UnitAwareNumericInputState>()
            for (value in values) {
                val row = value as? List<*> ?: return null
                val exercise = EncoderExercise.entries.firstOrNull { it.name == row.firstOrNull() } ?: return null
                val input = UnitAwareNumericInputState.restoreSnapshot(row.drop(1)) ?: return null
                if (inputs.put(exercise, input) != null) return null
            }
            return EncoderLoadDrafts(inputs)
        }

        val Saver: Saver<EncoderLoadDrafts, List<Any?>> = Saver(
            save = { it.saveSnapshot() },
            restore = { restoreSnapshot(it) },
        )
    }
}
