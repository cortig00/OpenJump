package com.openjump.app.ui.selector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.openjump.app.R

data class TimelineMarker(
    val ptsUs: Long,
    val label: String,
    val color: Color,
)

/** Timeline temporal con snap al índice PTS y una capa extensible de eventos. */
@Composable
fun SportsTimeline(
    mapper: PtsTimelineMapper,
    selectedIndex: Int,
    enabled: Boolean,
    markers: List<TimelineMarker>,
    onPreviewFrame: (Int?) -> Unit,
    onScrubStart: () -> Unit,
    onScrubToFrame: (Int) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
    selectedTimeLabel: String = "--",
) {
    var isDragging by remember(mapper) { mutableStateOf(false) }
    var dragFraction by remember(mapper) { mutableStateOf(0f) }
    var lastDragIndex by remember(mapper) { mutableStateOf<Int?>(null) }
    val markerDescriptions = remember(markers) { markers.joinToString { it.label } }
    val timelineDescription = stringResource(R.string.selector_timeline_description) +
        if (markerDescriptions.isNotBlank()) {
            stringResource(R.string.selector_timeline_events, markerDescriptions)
        } else {
            ""
        }
    val sliderDescription = stringResource(
        R.string.selector_timeline_slider_description,
        selectedIndex + 1,
        mapper.frameCount,
        selectedTimeLabel,
    )
    val markerTopPx = with(LocalDensity.current) { 7.dp.toPx() }
    val markerBottomPx = with(LocalDensity.current) { 22.dp.toPx() }

    DisposableEffect(mapper) {
        onDispose {
            if (isDragging) onScrubEnd()
        }
    }

    Box(
        modifier = modifier.semantics { contentDescription = timelineDescription },
    ) {
        Canvas(
            Modifier
                .matchParentSize()
                .padding(horizontal = 10.dp),
        ) {
            markers.forEach { marker ->
                val fraction = mapper.fractionAtPts(marker.ptsUs)
                val x = fraction * size.width
                drawLine(
                    color = marker.color,
                    start = androidx.compose.ui.geometry.Offset(x, markerTopPx),
                    end = androidx.compose.ui.geometry.Offset(x, markerBottomPx),
                    strokeWidth = 4f,
                )
            }
        }
        Slider(
            value = if (isDragging) dragFraction else mapper.fractionAtIndex(selectedIndex),
            onValueChange = { fraction ->
                if (!isDragging) {
                    isDragging = true
                    onScrubStart()
                }
                dragFraction = fraction
                val frame = mapper.indexAtFraction(fraction)
                onPreviewFrame(frame)
                if (frame != lastDragIndex) {
                    lastDragIndex = frame
                    onScrubToFrame(frame)
                }
            },
            onValueChangeFinished = {
                if (isDragging) onScrubEnd()
                isDragging = false
                lastDragIndex = null
                onPreviewFrame(null)
            },
            enabled = enabled,
            valueRange = 0f..1f,
            modifier = Modifier
                .fillMaxWidth()
                // Keep Slider's native adjustable actions; only replace its
                // percentage-oriented announcement with the temporal context.
                .semantics {
                    contentDescription = timelineDescription
                    stateDescription = sliderDescription
                },
        )
    }
}
