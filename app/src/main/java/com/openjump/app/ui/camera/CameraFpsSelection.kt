package com.openjump.app.ui.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.openjump.app.camera.CameraResolutionPreference
import com.openjump.app.settings.CameraSettings
import com.openjump.app.settings.RecordingUse

/**
 * Transient per-recording/visit FPS selection seeded from the saved Settings default.
 *
 * The returned state is initialized once per [settings]/[use] from
 * [CameraSettings.getPreferredFps] (normalized without device profiles) and never writes
 * back. Saved defaults change only through the Settings storage path
 * ([CameraSettings.setPreferredFps]); camera picks are temporary overrides for the current
 * visit/recording.
 */
@Composable
internal fun rememberCameraFpsSelection(
    settings: CameraSettings,
    use: RecordingUse,
): MutableIntState = remember(settings, use) {
    mutableIntStateOf(
        CameraSettings.normalizePreferredFps(settings.getPreferredFps(use), emptyList()),
    )
}

/**
 * Transient per-visit resolution selection, always AUTO on entry.
 *
 * Never reads or writes Settings/preferences: resolution is a temporary per-camera-visit
 * override. Re-seeded to AUTO whenever [use] changes; leaving the camera composition
 * drops the override so the next visit starts from AUTO again.
 */
@Composable
internal fun rememberCameraResolutionSelection(
    use: RecordingUse,
): MutableState<CameraResolutionPreference> = remember(use) {
    mutableStateOf(CameraResolutionPreference.AUTO)
}
