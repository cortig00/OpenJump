package com.openjump.app.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The local workflow lens. It is reversible context, not an account or role. */
enum class ExperienceMode { PERSONAL, COACH }

/** Persists only the user's current workflow preference; it never changes ownership or drafts. */
class ExperienceModeStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(readMode())
    val mode: StateFlow<ExperienceMode> = _mode.asStateFlow()
    val current: StateFlow<ExperienceMode> get() = mode

    fun set(mode: ExperienceMode) {
        preferences.edit().putString(KEY, mode.name).apply()
        _mode.value = mode
    }

    fun setMode(mode: ExperienceMode) = set(mode)

    private fun readMode(): ExperienceMode {
        val stored = preferences.getString(KEY, null)
        if (stored == LEGACY_BOTH) {
            // Both used the same team hub as Coach. Preserve that screen after upgrading.
            preferences.edit().putString(KEY, ExperienceMode.COACH.name).commit()
        }
        return decode(stored)
    }

    companion object {
        internal fun decode(value: String?): ExperienceMode = when (value) {
            LEGACY_BOTH, ExperienceMode.COACH.name -> ExperienceMode.COACH
            ExperienceMode.PERSONAL.name -> ExperienceMode.PERSONAL
            else -> ExperienceMode.PERSONAL
        }

        private const val LEGACY_BOTH = "BOTH"
        private const val PREFERENCES = "openjump_settings"
        private const val KEY = "experience_mode"
    }
}
