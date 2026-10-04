package com.openjump.app.settings

import android.content.Context

/** Persistent Encoder defaults. Per-analysis overrides live only in EncoderDraft. */
class EncoderSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var defaultPlateDiameterCm: Double
        get() = prefs.getFloat(KEY_DEFAULT_PLATE_DIAMETER_CM, DEFAULT_PLATE_DIAMETER_CM.toFloat()).toDouble()
        set(value) {
            require(isValidPlateDiameterCm(value)) {
                "El diámetro debe estar entre ${MINIMUM_PLATE_DIAMETER_CM.toInt()} y ${MAXIMUM_PLATE_DIAMETER_CM.toInt()} cm."
            }
            prefs.edit().putFloat(KEY_DEFAULT_PLATE_DIAMETER_CM, value.toFloat()).apply()
        }

    companion object {
        const val DEFAULT_PLATE_DIAMETER_CM = 45.0
        const val MINIMUM_PLATE_DIAMETER_CM = 10.0
        const val MAXIMUM_PLATE_DIAMETER_CM = 60.0
        private const val PREFERENCES_NAME = "openjump_settings"
        private const val KEY_DEFAULT_PLATE_DIAMETER_CM = "encoder_default_plate_diameter_cm"

        fun isValidPlateDiameterCm(value: Double): Boolean =
            value.isFinite() && value in MINIMUM_PLATE_DIAMETER_CM..MAXIMUM_PLATE_DIAMETER_CM
    }
}
