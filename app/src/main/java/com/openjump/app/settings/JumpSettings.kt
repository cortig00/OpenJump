package com.openjump.app.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent jump-analysis preferences. They only seed new sessions: an active draft keeps its
 * own snapshot and saved measurements always use the data they stored.
 */
class JumpSettings internal constructor(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE))

    /**
     * Whether the selector asks for the optional movement start on CMJ, Abalakov and unilateral.
     * A missing or wrongly typed value reads as [DEFAULT_MARK_MOVEMENT_START] without crashing.
     */
    var markMovementStart: Boolean
        get() = decodeMarkMovementStart(prefs.all[KEY_MARK_MOVEMENT_START])
        set(value) {
            prefs.edit().putBoolean(KEY_MARK_MOVEMENT_START, value).apply()
        }

    companion object {
        /** Explicit product default: a new install marks only takeoff and landing. */
        const val DEFAULT_MARK_MOVEMENT_START = false
        internal const val PREFERENCES_NAME = "openjump_settings"
        internal const val KEY_MARK_MOVEMENT_START = "jump_mark_movement_start"

        internal fun decodeMarkMovementStart(value: Any?): Boolean =
            value as? Boolean ?: DEFAULT_MARK_MOVEMENT_START
    }
}
