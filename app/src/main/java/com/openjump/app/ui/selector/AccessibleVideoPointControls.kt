package com.openjump.app.ui.selector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.openjump.app.R
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.video.VideoFrameIndex
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing

@Composable
internal fun AccessibleVideoPointOverlay(
    mapper: VideoCoordinateMapper,
    cursor: ImagePoint,
) {
    val cursorDescription = stringResource(R.string.selector_cursor_position, cursor.x, cursor.y)
    val cursorColor = MaterialTheme.colorScheme.onSurface
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(3f)
            .semantics {
                contentDescription = cursorDescription
                stateDescription = cursorDescription
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        val viewPoint = mapper.videoToView(cursor)
        drawCircle(cursorColor, radius = 14f, center = androidx.compose.ui.geometry.Offset(viewPoint.x.toFloat(), viewPoint.y.toFloat()), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
        drawLine(cursorColor, androidx.compose.ui.geometry.Offset(viewPoint.x.toFloat() - 20f, viewPoint.y.toFloat()), androidx.compose.ui.geometry.Offset(viewPoint.x.toFloat() + 20f, viewPoint.y.toFloat()), 2f)
        drawLine(cursorColor, androidx.compose.ui.geometry.Offset(viewPoint.x.toFloat(), viewPoint.y.toFloat() - 20f), androidx.compose.ui.geometry.Offset(viewPoint.x.toFloat(), viewPoint.y.toFloat() + 20f), 2f)
    }
}

/** Optional non-touch placement shared by Encoder and spatial jump points. */
@Composable
internal fun AccessibleVideoPointControls(
    index: VideoFrameIndex?,
    initial: ImagePoint?,
    cursor: ImagePoint?,
    enabled: Boolean,
    onCursorChanged: (ImagePoint?) -> Unit,
    onConfirm: (ImagePoint) -> Boolean,
) {
    val encoded = index ?: return
    val bounds = VideoPresentationGeometry.fromEncoded(encoded.width, encoded.height, encoded.rotationDegrees)
    var expanded by remember(encoded, initial) { mutableStateOf(false) }
    val selected = cursor ?: initial ?: ImagePoint(bounds.width / 2.0, bounds.height / 2.0)
    val step = (minOf(bounds.width, bounds.height) * 0.02).coerceAtLeast(4.0)
    val cursorPositionDescription = stringResource(R.string.selector_cursor_position, selected.x, selected.y)
    val leftDescription = stringResource(R.string.selector_cursor_left)
    val upDescription = stringResource(R.string.selector_cursor_up)
    val downDescription = stringResource(R.string.selector_cursor_down)
    val rightDescription = stringResource(R.string.selector_cursor_right)
    val showCursorDescription = stringResource(R.string.selector_show_accessible_cursor)
    val hideCursorDescription = stringResource(R.string.selector_hide_accessible_cursor)
    fun move(dx: Double, dy: Double) {
        onCursorChanged(
            ImagePoint(
                (selected.x + dx).coerceIn(0.0, bounds.width - 1.0),
                (selected.y + dy).coerceIn(0.0, bounds.height - 1.0),
            ),
        )
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = ShapeTokens.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs), horizontalAlignment = Alignment.CenterHorizontally) {
            TextButton(
                onClick = {
                    val opening = !expanded
                    expanded = opening
                    onCursorChanged(if (opening) selected else null)
                },
                modifier = Modifier.fillMaxWidth().semantics {
                    stateDescription = if (expanded) hideCursorDescription else showCursorDescription
                },
            ) {
                Text(stringResource(if (expanded) R.string.selector_hide_accessible_cursor else R.string.selector_show_accessible_cursor))
            }
            if (expanded) {
                Text(
                    stringResource(R.string.selector_cursor_position, selected.x, selected.y),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.semantics {
                        contentDescription = cursorPositionDescription
                        stateDescription = cursorPositionDescription
                        liveRegion = LiveRegionMode.Polite
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    TextButton(enabled = enabled, onClick = { move(-step, 0.0) }, modifier = Modifier.semantics { contentDescription = leftDescription }) { Text("←") }
                    TextButton(enabled = enabled, onClick = { move(0.0, -step) }, modifier = Modifier.semantics { contentDescription = upDescription }) { Text("↑") }
                    TextButton(enabled = enabled, onClick = { move(0.0, step) }, modifier = Modifier.semantics { contentDescription = downDescription }) { Text("↓") }
                    TextButton(enabled = enabled, onClick = { move(step, 0.0) }, modifier = Modifier.semantics { contentDescription = rightDescription }) { Text("→") }
                }
                Button(
                    enabled = enabled,
                    onClick = {
                        if (onConfirm(selected)) {
                            expanded = false
                            onCursorChanged(null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.selector_cursor_confirm))
                }
            }
        }
    }
}
