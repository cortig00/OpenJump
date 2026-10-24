package com.openjump.app.settings

import android.content.Context
import android.content.SharedPreferences
import com.openjump.app.encoder.EncoderExercise

/** Persistent Encoder defaults. Per-analysis overrides live only in EncoderDraft. */
class EncoderSettings internal constructor(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE))

    /** Exact canonical kilograms. Unit preferences affect presentation only. */
    fun defaultLoadKg(exercise: EncoderExercise): Double =
        decodeDefaultLoadKg(prefs.all[loadKey(exercise)] as? String)

    fun setDefaultLoadKg(exercise: EncoderExercise, value: Double) {
        require(isValidLoadKg(value)) { "La carga debe ser finita y mayor o igual que cero." }
        // SharedPreferences has no Double type: a round-trip decimal string avoids Float loss.
        prefs.edit().putString(loadKey(exercise), value.toString()).apply()
    }

    fun resetDefaultLoads() {
        val editor = prefs.edit()
        EncoderExercise.entries.forEach { editor.remove(loadKey(it)) }
        editor.apply()
    }

    var defaultPlateDiameterCm: Double
        get() = prefs.getFloat(KEY_DEFAULT_PLATE_DIAMETER_CM, DEFAULT_PLATE_DIAMETER_CM.toFloat()).toDouble()
        set(value) {
            require(isValidPlateDiameterCm(value)) {
                "El diámetro debe estar entre ${MINIMUM_PLATE_DIAMETER_CM.toInt()} y ${MAXIMUM_PLATE_DIAMETER_CM.toInt()} cm."
            }
            prefs.edit().putFloat(KEY_DEFAULT_PLATE_DIAMETER_CM, value.toFloat()).apply()
        }

    companion object {
        const val DEFAULT_LOAD_KG = 0.0
        const val DEFAULT_PLATE_DIAMETER_CM = 45.0
        const val MINIMUM_PLATE_DIAMETER_CM = 10.0
        const val MAXIMUM_PLATE_DIAMETER_CM = 60.0
        private const val PREFERENCES_NAME = "openjump_settings"
        private const val KEY_DEFAULT_PLATE_DIAMETER_CM = "encoder_default_plate_diameter_cm"

        private fun loadKey(exercise: EncoderExercise) = "encoder_default_load_kg_${exercise.name}"

        fun isValidLoadKg(value: Double): Boolean = value.isFinite() && value >= 0.0

        internal fun decodeDefaultLoadKg(value: String?): Double =
            value?.toDoubleOrNull()?.takeIf(::isValidLoadKg) ?: DEFAULT_LOAD_KG

        fun isValidPlateDiameterCm(value: Double): Boolean =
            value.isFinite() && value in MINIMUM_PLATE_DIAMETER_CM..MAXIMUM_PLATE_DIAMETER_CM
    }
}
