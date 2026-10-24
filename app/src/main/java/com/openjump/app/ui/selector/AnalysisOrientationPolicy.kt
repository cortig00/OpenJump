package com.openjump.app.ui.selector

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo

/**
 * Scoped orientation ownership for video analysis/marking (F034).
 *
 * The manifest keeps `screenOrientation="portrait"` with
 * `configChanges="orientation|screenSize|keyboardHidden"`, so rotation never
 * recreates the activity: player, frame index, marks, calibration and
 * bilateral drafts stay in the retained ViewModel plus `rememberSaveable`
 * inputs. This policy only overrides the requested orientation while the
 * selector is in the foreground, mirroring the camera pattern
 * (`CameraOrientationPolicy` + `CameraScreen` SideEffect/dispose) without
 * touching the manifest, the player index, the coordinate mapper, PTS
 * handling or the tracking engine.
 */
enum class AnalysisOrientationMode {
    PORTRAIT,
    FULL_SENSOR,
}

object AnalysisOrientationPolicy {
    /** Scoped sensor orientation while analysis/marking is visible. */
    fun onEnter(): AnalysisOrientationMode = AnalysisOrientationMode.FULL_SENSOR

    /** Restore portrait once analysis/marking leaves the foreground. */
    fun onExit(): AnalysisOrientationMode = AnalysisOrientationMode.PORTRAIT
}

internal fun Context.findAnalysisActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findAnalysisActivity()
    else -> null
}

internal fun Activity.applyAnalysisOrientation(mode: AnalysisOrientationMode) {
    val requested = when (mode) {
        AnalysisOrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        AnalysisOrientationMode.FULL_SENSOR -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }
    if (requestedOrientation != requested) requestedOrientation = requested
}
