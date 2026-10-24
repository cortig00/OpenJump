package com.openjump.app.ui.camera

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.camera.CameraProductProfile
import com.openjump.app.camera.CameraResolutionPreference
import com.openjump.app.settings.CameraSettings
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing

/** UI-only, reset on each camera composition; never touches saved camera settings. */
@Composable
internal fun rememberCameraInformationVisibility(): androidx.compose.runtime.MutableState<Boolean> =
    remember { mutableStateOf(false) }

/** One camera-style quick control instead of a permanent row of FPS settings. */
@Composable
internal fun CameraQuickFpsSelector(
    selectedFps: Int,
    activeFps: Int?,
    availableFps: List<Int>,
    unavailableFps: Set<Int>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    scopedUnavailableFps: Set<Int> = emptySet(),
) {
    var expanded by remember { mutableStateOf(false) }
    val auto = stringResource(R.string.camera_fps_auto)
    val label = when {
        selectedFps == CameraSettings.AUTO && activeFps != null ->
            stringResource(R.string.camera_fps_quick_auto, auto, activeFps)
        selectedFps == CameraSettings.AUTO -> auto
        else -> stringResource(R.string.camera_fps_quick_fixed, selectedFps)
    }
    // Requested value semantics: the pill shows what was asked, not the file truth.
    val requestedDescription = stringResource(R.string.camera_requested_fps, label)
    Box(modifier) {
        Button(
            onClick = { expanded = true },
            modifier = Modifier
                .testTag("camera-fps-selector")
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics { contentDescription = requestedDescription },
            shape = ShapeTokens.full,
            border = BorderStroke(1.dp, CameraChrome.outline),
            contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
            colors = ButtonDefaults.buttonColors(
                containerColor = CameraChrome.surface,
                contentColor = CameraChrome.content,
            ),
        ) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                modifier = Modifier.padding(start = Spacing.xs).size(16.dp).rotate(90f))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(auto, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                onClick = {
                    expanded = false
                    onSelect(CameraSettings.AUTO)
                },
            )
            availableFps.forEach { fps ->
                val previewUnavailable = fps in unavailableFps
                val scopedUnavailable = fps in scopedUnavailableFps
                val unavailable = previewUnavailable || scopedUnavailable
                DropdownMenuItem(
                    text = {
                        Text(
                            when {
                                previewUnavailable -> stringResource(
                                    R.string.camera_fps_unavailable_chip,
                                    fps.toString(),
                                )
                                scopedUnavailable -> stringResource(
                                    R.string.camera_resolution_unavailable_chip,
                                    fps.toString(),
                                )
                                else -> stringResource(R.string.camera_fps_quick_fixed, fps)
                            },
                            color = if (unavailable) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    enabled = !unavailable,
                    onClick = {
                        expanded = false
                        onSelect(fps)
                    },
                )
            }
        }
    }
}

/** Grouped resolution pill next to the FPS pill; same anchor, tokens and 48dp target. */
@Composable
internal fun CameraQuickResolutionSelector(
    selected: CameraResolutionPreference,
    onSelect: (CameraResolutionPreference) -> Unit,
    isEnabled: (CameraResolutionPreference) -> Boolean = { true },
    activeResolutionShort: String? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = resolutionLabel(selected)
    // Con Auto seleccionado, la píldora muestra la resolución efectiva (p. ej.
    // "Auto · 720p") para distinguirse a simple vista de la píldora de FPS.
    // Ver F-006.
    val pillLabel = if (selected == CameraResolutionPreference.AUTO && activeResolutionShort != null) {
        stringResource(R.string.camera_resolution_quick_auto, activeResolutionShort)
    } else {
        label
    }
    val requestedDescription = stringResource(R.string.camera_requested_resolution, label)
    Box(modifier) {
        Button(
            onClick = { expanded = true },
            modifier = Modifier
                .testTag("camera-resolution-selector")
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics { contentDescription = requestedDescription },
            shape = ShapeTokens.full,
            border = BorderStroke(1.dp, CameraChrome.outline),
            contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
            colors = ButtonDefaults.buttonColors(
                containerColor = CameraChrome.surface,
                contentColor = CameraChrome.content,
            ),
        ) {
            Text(pillLabel, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                modifier = Modifier.padding(start = Spacing.xs).size(16.dp).rotate(90f))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CameraResolutionPreference.entries.forEach { option ->
                val enabled = isEnabled(option)
                DropdownMenuItem(
                    text = {
                        Text(
                            // Las opciones no disponibles explican el motivo y suben el
                            // contraste respecto al gris por defecto. Ver F-020.
                            text = if (enabled) resolutionLabel(option)
                                else stringResource(
                                    R.string.camera_option_unavailable,
                                    resolutionLabel(option),
                                ),
                            color = if (enabled) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    enabled = enabled,
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/** Compact top-row controls. Profile details live in the on-demand information panel. */
@Composable
internal fun CameraCaptureControlsGroup(
    selectedFps: Int,
    activeFps: Int?,
    fpsChoices: List<Int>,
    previewUnavailableFps: Set<Int>,
    scopedUnavailableFps: Set<Int>,
    selectedResolution: CameraResolutionPreference,
    isResolutionEnabled: (CameraResolutionPreference) -> Boolean,
    activeProfile: CameraProductProfile?,
    onFpsSelect: (Int) -> Unit,
    onResolutionSelect: (CameraResolutionPreference) -> Unit,
    onHintClick: () -> Unit,
    hintDescriptionRes: Int,
    modifier: Modifier = Modifier,
    includeInformationButton: Boolean = true,
    informationExpanded: Boolean = false,
) {
    val boundDescription = activeProfile?.let {
        stringResource(R.string.camera_active_profile,
            stringResource(R.string.camera_profile_label, it.height, it.fps))
    }
    Row(
        modifier = modifier.fillMaxWidth().semantics {
            boundDescription?.let { stateDescription = it }
        },
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CameraQuickFpsSelector(
            selectedFps = selectedFps, activeFps = activeFps,
            availableFps = fpsChoices, unavailableFps = previewUnavailableFps,
            scopedUnavailableFps = scopedUnavailableFps, onSelect = onFpsSelect,
            modifier = Modifier.weight(1.3f),
        )
        CameraQuickResolutionSelector(
            selected = selectedResolution, onSelect = onResolutionSelect,
            isEnabled = isResolutionEnabled,
            activeResolutionShort = activeProfile?.let { profile ->
                when (profile.height) {
                    720 -> stringResource(R.string.camera_resolution_720p)
                    1080 -> stringResource(R.string.camera_resolution_1080p)
                    else -> null
                }
            },
            modifier = Modifier.weight(1f),
        )
        if (includeInformationButton) {
            CameraHintButton(
                onClick = onHintClick, descriptionRes = hintDescriptionRes,
                expanded = informationExpanded,
            )
        }
    }
}

/** Discrete bound-profile caption; never claims the requested profile is the file truth. */
@Composable
internal fun CameraActiveProfileCaption(
    profile: CameraProductProfile?,
    modifier: Modifier = Modifier,
) {
    if (profile == null) return
    val profileLabel = stringResource(
        R.string.camera_profile_label,
        profile.height,
        profile.fps,
    )
    val text = stringResource(R.string.camera_active_profile, profileLabel)
    Text(
        text = text,
        modifier = modifier
            .testTag("camera-active-profile")
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = CameraChrome.content,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun resolutionLabel(resolution: CameraResolutionPreference): String = when (resolution) {
    CameraResolutionPreference.AUTO -> stringResource(R.string.camera_resolution_auto)
    CameraResolutionPreference.HD_720P -> stringResource(R.string.camera_resolution_720p)
    CameraResolutionPreference.FHD_1080P -> stringResource(R.string.camera_resolution_1080p)
}

@Composable
internal fun CameraHintButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    descriptionRes: Int = R.string.camera_capture_tips,
    expanded: Boolean = false,
) {
    val description = stringResource(descriptionRes)
    Surface(
        modifier = modifier.size(48.dp),
        shape = ShapeTokens.circle,
        color = CameraChrome.surface,
        border = BorderStroke(1.dp, if (expanded) CameraChrome.accent else CameraChrome.outline),
    ) {
        IconToggleButton(
            checked = expanded,
            onCheckedChange = { onClick() },
            modifier = Modifier.semantics { contentDescription = description; role = Role.Button },
        ) {
            Icon(
                painterResource(R.drawable.ic_camera_reference),
                contentDescription = null,
                tint = if (expanded) CameraChrome.accent else CameraChrome.content,
            )
        }
    }
}

/** Scrollable, dismissible information only; never a permanent viewfinder card. */
@Composable
internal fun CameraHintOverlay(
    guidance: String,
    progress: String?,
    warnings: List<String>,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
    profile: CameraProductProfile? = null,
    additionalContent: @Composable () -> Unit = {},
) {
    Surface(
        modifier = modifier,
        shape = ShapeTokens.large,
        color = CameraChrome.surface,
        contentColor = CameraChrome.content,
        border = BorderStroke(1.dp, CameraChrome.outline),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_camera_reference), null,
                    tint = CameraChrome.accent, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.camera_capture_tips),
                    modifier = Modifier.weight(1f).padding(start = Spacing.sm),
                    style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_close),
                        stringResource(R.string.video_source_close))
                }
            }
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
                    .padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (guidance.isNotBlank()) Text(guidance, style = MaterialTheme.typography.bodySmall)
                progress?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                CameraActiveProfileCaption(profile)
                additionalContent()
            }
        }
    }
}

/** Camera-specific semantic colors: fixed dark chrome stays legible over either scene/theme. */
internal object CameraChrome {
    val surface = Color(0xFF141916).copy(alpha = 0.82f)
    val content = Color.White
    val outline = Color.White.copy(alpha = 0.22f)
    val accent = com.openjump.app.ui.theme.DarkPrimary
    val recording = Color(0xFFE53935)
}
