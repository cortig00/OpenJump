package com.openjump.app.settings

import android.content.Context

/**
 * Preferencias de cámara persistidas (SharedPreferences). Simple y suficiente
 * para el FPS preferido y las ayudas independientes de cámara.
 */
class CameraSettings(context: Context) {

    private val prefs = context.getSharedPreferences("openjump_settings", Context.MODE_PRIVATE)

    /** Keep the existing key so users who already dismissed the Jump guide do not see it again. */
    var hasSeenJumpCameraGuide: Boolean
        get() = prefs.getBoolean(KEY_JUMP_RECORDING_GUIDE_SEEN, false)
        set(value) = prefs.edit().putBoolean(KEY_JUMP_RECORDING_GUIDE_SEEN, value).apply()

    /** Reserved for a future, independent Encoder camera guide; no Encoder UI yet. */
    var hasSeenEncoderCameraGuide: Boolean
        get() = prefs.getBoolean(KEY_ENCODER_CAMERA_GUIDE_SEEN, false)
        set(value) = prefs.edit().putBoolean(KEY_ENCODER_CAMERA_GUIDE_SEEN, value).apply()

    /** FPS preferido de grabación. 0 = AUTO (mejor modo disponible del dispositivo). */
    var preferredFps: Int
        get() = prefs.getInt(KEY_PREFERRED_FPS, AUTO)
        set(value) = prefs.edit().putInt(KEY_PREFERRED_FPS, value).apply()

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

        private const val KEY_PREFERRED_FPS = "preferred_fps"
        private const val KEY_JUMP_RECORDING_GUIDE_SEEN = "jump_recording_guide_seen"
        private const val KEY_ENCODER_CAMERA_GUIDE_SEEN = "encoder_camera_guide_seen"
    }
}
