package com.openjump.app.settings

/**
 * Independent recording FPS preferences (Saltos vs Encoder).
 *
 * Pure, Android-free policy so the migration can be unit-tested on the JVM
 * without touching the production `openjump_settings` file. [CameraSettings]
 * is the only SharedPreferences owner and applies the computed maps in a
 * single editor (atomic snapshot, even when the first action is a write).
 */
enum class RecordingUse {
    JUMPS,
    ENCODER,
}

object RecordingFpsPolicy {
    const val LEGACY_KEY = "preferred_fps"
    const val JUMPS_KEY = "preferred_fps_jumps"
    const val ENCODER_KEY = "preferred_fps_encoder"
    const val SPLIT_DONE_KEY = "preferred_fps_split_done"

    /**
     * Raw snapshot of the prefs file. Null means the key is absent (fresh
     * install for legacy, or pre-migration for the independent keys).
     */
    data class Snapshot(
        val legacyRaw: Int?,
        val jumpsRaw: Int?,
        val encoderRaw: Int?,
        val splitDone: Boolean,
    )

    /** Active [AppSession.encoderDraft] distinguishes Encoder from Jump capture. */
    fun useFor(hasEncoderDraft: Boolean): RecordingUse =
        if (hasEncoderDraft) RecordingUse.ENCODER else RecordingUse.JUMPS

    fun keyFor(use: RecordingUse): String = when (use) {
        RecordingUse.JUMPS -> JUMPS_KEY
        RecordingUse.ENCODER -> ENCODER_KEY
    }

    /**
     * Atomic contents for the one-time split: the pre-existing legacy value
     * is copied to BOTH independent keys. Empty when already migrated.
     * The legacy key itself is never removed.
     */
    fun splitWrites(
        snapshot: Snapshot,
        legacyDefault: Int = CameraSettings.AUTO,
    ): Map<String, Any> {
        if (snapshot.splitDone) return emptyMap()
        val seed = snapshot.legacyRaw ?: legacyDefault
        return mapOf(
            JUMPS_KEY to seed,
            ENCODER_KEY to seed,
            SPLIT_DONE_KEY to true,
        )
    }

    /**
     * Atomic contents for writing one mode. When the split has not happened
     * yet, the legacy seed is snapshotted to both keys first and only the
     * selected use is then overwritten, all in the single returned map so the
     * caller can apply it with one preferences editor.
     */
    fun writeFor(
        snapshot: Snapshot,
        use: RecordingUse,
        newValue: Int,
        legacyDefault: Int = CameraSettings.AUTO,
    ): Map<String, Any> {
        if (snapshot.splitDone) return mapOf(keyFor(use) to newValue)
        val seed = snapshot.legacyRaw ?: legacyDefault
        return buildMap {
            put(JUMPS_KEY, seed)
            put(ENCODER_KEY, seed)
            put(SPLIT_DONE_KEY, true)
            put(keyFor(use), newValue)
        }
    }

    /**
     * Raw stored value for [use] before [CameraSettings.normalizePreferredFps].
     * Before the split the legacy value is the source for both uses; after the
     * split each mode reads only its own key and never falls back to the
     * mutable legacy key.
     */
    fun effectiveRaw(
        snapshot: Snapshot,
        use: RecordingUse,
        absentDefault: Int = CameraSettings.AUTO,
    ): Int {
        if (!snapshot.splitDone) return snapshot.legacyRaw ?: absentDefault
        return when (use) {
            RecordingUse.JUMPS -> snapshot.jumpsRaw ?: absentDefault
            RecordingUse.ENCODER -> snapshot.encoderRaw ?: absentDefault
        }
    }
}
