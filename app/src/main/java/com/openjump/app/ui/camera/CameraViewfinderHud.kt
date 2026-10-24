package com.openjump.app.ui.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.openjump.app.ui.theme.Spacing

/** Presentation slots only. The caller retains all capture, permission and profile policies. */
@Composable
internal fun CameraViewfinderHud(
    showControls: Boolean,
    onBack: () -> Unit,
    onInformation: () -> Unit,
    informationDescriptionRes: Int,
    informationExpanded: Boolean,
    controls: @Composable (includeInformationButton: Boolean) -> Unit,
    information: (@Composable () -> Unit)?,
    record: (@Composable () -> Unit)?,
    status: (@Composable () -> Unit)?,
    blockingError: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val landscape = maxWidth > maxHeight
        // On narrow windows / large type, keep every complete value and 48dp target
        // above the preview rather than shrinking text or moving controls to the bottom.
        val stackedToolbar = maxWidth < 360.dp || (maxWidth < 480.dp && LocalDensity.current.fontScale > 1.3f)
        val informationWidth = if (landscape) maxWidth * 0.42f else 360.dp
        var toolbarHeightPx by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current
        val informationHeight = if (landscape) maxHeight * 0.30f else
            (maxHeight * 0.44f - with(density) { toolbarHeightPx.toDp() } - Spacing.lg)
                .coerceIn(48.dp, 176.dp)
        Column(
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                .testTag("camera-top-toolbar"),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().onSizeChanged { toolbarHeightPx = it.height },
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                CameraBackControl(onBack)
                if (showControls && !stackedToolbar) {
                    if (landscape) {
                        Spacer(Modifier.weight(1f))
                        val controlsWidth = if (LocalDensity.current.fontScale > 1.3f) 380.dp else 320.dp
                        Box(Modifier.widthIn(max = controlsWidth)) { controls(true) }
                    } else {
                        Box(Modifier.weight(1f)) { controls(true) }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                    if (showControls) {
                        CameraHintButton(
                            onClick = onInformation,
                            descriptionRes = informationDescriptionRes,
                            expanded = informationExpanded,
                        )
                    }
                }
            }
            if (showControls && stackedToolbar) controls(false)
            status?.let { content ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { content() }
            }
            }
            information?.let { content ->
                Box(
                    Modifier.widthIn(max = informationWidth)
                        .heightIn(max = informationHeight)
                        .testTag("camera-information-panel"),
                ) { content() }
            }
        }
        record?.let { content ->
            Box(
                modifier = Modifier
                    .align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter)
                    .padding(if (landscape) Spacing.lg else Spacing.md)
                    .size(88.dp)
                    .testTag("camera-record-position"),
                contentAlignment = Alignment.Center,
            ) { content() }
        }
        blockingError?.let { content ->
            // El error ya no se centra sobre el encuadre: se ancla arriba del control de
            // grabación (88dp + paddings) para que el visor siga viéndose. Ver F-036.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = if (landscape) 112.dp else Spacing.lg,
                        end = if (landscape) 112.dp else Spacing.lg,
                        bottom = if (landscape) Spacing.lg else 112.dp,
                    )
                    .widthIn(max = 420.dp)
                    .heightIn(max = maxHeight * 0.48f),
            ) { content() }
        }
    }
}
