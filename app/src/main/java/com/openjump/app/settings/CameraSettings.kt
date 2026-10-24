package com.openjump.app.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Preferencias de cámara persistidas (SharedPreferences). Simple y suficiente
 * para el FPS preferido y las ayudas independientes de cámara.
 */
class CameraSettings {
    private val prefs: SharedPreferences

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    internal constructor(prefs: SharedPreferences) {
        this.prefs = prefs
    }

    /** Keep the existing key so users who already dismissed the Jump guide do not see it again. */
    var hasSeenJumpCameraGuide: Boolean
        get() = prefs.getBoolean(KEY_JUMP_RECORDING_GUIDE_SEEN, false)
        set(value) = prefs.edit().putBoolean(KEY_JUMP_RECORDING_GUIDE_SEEN, value).apply()

    /** Reserved for a future, independent Encoder camera guide; no Encoder UI yet. */
    var hasSeenEncoderCameraGuide: Boolean
        get() = prefs.getBoolean(KEY_ENCODER_CAMERA_GUIDE_SEEN, false)
        set(value) = prefs.edit().putBoolean(KEY_ENCODER_CAMERA_GUIDE_SEEN, value).apply()

    /**
     * Legacy FPS preference, preserved for diagnostics and as the one-time
     * migration seed. Recording code must use [jumpPreferredFps],
     * [encoderPreferredFps] or [getPreferredFps] instead; this accessor never
     * migrates and never reflects the independent keys.
     */
    var preferredFps: Int
        get() = prefs.getInt(KEY_PREFERRED_FPS, AUTO)
        set(value) = prefs.edit().putInt(KEY_PREFERRED_FPS, value).apply()

    /** FPS preferido de Saltos. La primera lectura/escritura migra el legado a ambos modos. */
    var jumpPreferredFps: Int
        get() = getPreferredFps(RecordingUse.JUMPS)
        set(value) = setPreferredFps(RecordingUse.JUMPS, value)

    /** FPS preferido de Encoder, independiente de Saltos tras la migración única. */
    var encoderPreferredFps: Int
        get() = getPreferredFps(RecordingUse.ENCODER)
        set(value) = setPreferredFps(RecordingUse.ENCODER, value)

    fun getPreferredFps(use: RecordingUse): Int = synchronized(MIGRATION_LOCK) {
        ensureSplitMigration()
        prefs.getInt(RecordingFpsPolicy.keyFor(use), AUTO)
    }

    fun setPreferredFps(use: RecordingUse, value: Int) = synchronized(MIGRATION_LOCK) {
        applyWrites(RecordingFpsPolicy.writeFor(snapshot(), use, value))
    }

    private fun snapshot(): RecordingFpsPolicy.Snapshot = RecordingFpsPolicy.Snapshot(
        legacyRaw = prefs.getIntOrNull(KEY_PREFERRED_FPS),
        jumpsRaw = prefs.getIntOrNull(RecordingFpsPolicy.JUMPS_KEY),
        encoderRaw = prefs.getIntOrNull(RecordingFpsPolicy.ENCODER_KEY),
        splitDone = prefs.getBoolean(KEY_PREFERRED_FPS_SPLIT_DONE, false),
    )

    private fun ensureSplitMigration() {
        val writes = RecordingFpsPolicy.splitWrites(snapshot())
        if (writes.isNotEmpty()) applyWrites(writes)
    }

    private fun applyWrites(writes: Map<String, Any>) {
        if (writes.isEmpty()) return
        val editor = prefs.edit()
        writes.forEach { (key, value) ->
            when (value) {
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
            }
        }
        editor.apply()
    }

    private fun android.content.SharedPreferences.getIntOrNull(key: String): Int? =
        if (contains(key)) getInt(key, AUTO) else null

    companion object {
        const val AUTO = 0
        val PRODUCT_FPS: List<Int> = listOf(60, 120, 240)

        /** Migrates legacy values (28/90/480/960...) without silently inventing a profile. */
        fun normalizePreferredFps(preferredFps: Int, availableFps: Collection<Int>): Int {
            if (preferredFps <= AUTO || preferredFps !in PRODUCT_FPS) return AUTO
            if (availableFps.isEmpty() || preferredFps in availableFps) return preferredFps
            return availableFps.filter { it in PRODUCT_FPS && it < preferredFps }
                .maxOrNull() ?: AUTO
        }

        private const val KEY_PREFERRED_FPS = RecordingFpsPolicy.LEGACY_KEY
        private const val KEY_PREFERRED_FPS_SPLIT_DONE = RecordingFpsPolicy.SPLIT_DONE_KEY
        private const val KEY_JUMP_RECORDING_GUIDE_SEEN = "jump_recording_guide_seen"
        private const val KEY_ENCODER_CAMERA_GUIDE_SEEN = "encoder_camera_guide_seen"
        private const val PREFERENCES_NAME = "openjump_settings"
        private val MIGRATION_LOCK = Any()
    }
}
