package com.openjump.app.ui.selector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.ui.theme.OpenJumpTheme
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** One effective outline. Editing never commits scale or starts tracking. */
@Composable
fun PlateCalibrationOverlay(
    mapper: VideoCoordinateMapper,
    proposal: PlateCalibrationProposal,
    onGeometryChanged: (ImagePoint, Double) -> Unit,
    interactive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.selector_plate_review_overlay_description)
    val color = OpenJumpTheme.colors.warning
    val density = LocalDensity.current
    val hitRadius = with(density) { 24.dp.toPx() }
    val center = mapper.videoToView(proposal.center)
    // A consistent lower-right handle is easier to find than an ellipse-fit angle.
    val diagonal = proposal.diameterPx / (2.0 * sqrt(2.0))
    val handle = mapper.videoToView(ImagePoint(proposal.center.x + diagonal, proposal.center.y + diagonal))
    val screenRadius = hypot(handle.x - center.x, handle.y - center.y)
    val latestProposal = rememberUpdatedState(proposal)
    val latestCenter = rememberUpdatedState(center)
    val latestHandle = rememberUpdatedState(handle)
    val latestRadius = rememberUpdatedState(screenRadius)
    val latestChange = rememberUpdatedState(onGeometryChanged)
    val directions = listOf(
        Triple(stringResource(R.string.selector_cursor_left), -1.0, 0.0),
        Triple(stringResource(R.string.selector_cursor_right), 1.0, 0.0),
        Triple(stringResource(R.string.selector_cursor_up), 0.0, -1.0),
        Triple(stringResource(R.string.selector_cursor_down), 0.0, 1.0),
    )
    Box(
        modifier.semantics {
            contentDescription = description
            if (interactive) customActions = directions.map { (label, dx, dy) ->
                CustomAccessibilityAction(label) {
                    val current = latestProposal.value
                    latestChange.value(ImagePoint(current.center.x + dx, current.center.y + dy), current.diameterPx)
                    true
                }
            }
        }.pointerInput(mapper, interactive) {
            if (!interactive) return@pointerInput
            var mode: PlateDragMode? = null
            detectDragGestures(
                onDragStart = { start ->
                    mode = plateDragModeFor(
                        ImagePoint(start.x.toDouble(), start.y.toDouble()), latestCenter.value, latestHandle.value,
                        hitRadius.toDouble(), latestRadius.value,
                    )
                },
                onDragEnd = { mode = null }, onDragCancel = { mode = null },
            ) { change, delta ->
                val active = mode ?: return@detectDragGestures
                change.consume()
                val source = mapper.viewToVideo(ImagePoint(change.position.x.toDouble(), change.position.y.toDouble()))
                    ?: return@detectDragGestures
                val previous = mapper.viewToVideo(ImagePoint(
                    (change.position.x - delta.x).toDouble(), (change.position.y - delta.y).toDouble(),
                )) ?: return@detectDragGestures
                val current = latestProposal.value
                if (active == PlateDragMode.HANDLE) {
                    // Relative radius avoids a jump when grabbing anywhere in the 48dp target.
                    val changeInRadius = hypot(source.x - current.center.x, source.y - current.center.y) -
                        hypot(previous.x - current.center.x, previous.y - current.center.y)
                    latestChange.value(current.center, max(8.0, current.diameterPx + 2.0 * changeInRadius))
                } else {
                    latestChange.value(ImagePoint(
                        current.center.x + source.x - previous.x, current.center.y + source.y - previous.y,
                    ), current.diameterPx)
                }
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(center.x.toFloat(), center.y.toFloat())
            val h = Offset(handle.x.toFloat(), handle.y.toFloat())
            drawCircle(Color.Black.copy(alpha = 0.8f), screenRadius.toFloat(), c, style = Stroke(4.dp.toPx()))
            drawCircle(color, screenRadius.toFloat(), c, style = Stroke(2.dp.toPx()))
            drawCircle(Color.Black.copy(alpha = 0.8f), 10.dp.toPx(), h)
            drawCircle(Color.White, 8.dp.toPx(), h)
            drawCircle(color, 5.dp.toPx(), h)
            drawCircle(Color.Black.copy(alpha = 0.8f), 5.dp.toPx(), c)
            drawCircle(Color.White, 3.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
        }
    }
}

internal enum class PlateDragMode { CENTER, HANDLE }

internal fun plateDragModeFor(
    start: ImagePoint,
    center: ImagePoint,
    handle: ImagePoint,
    hitRadiusPx: Double,
    circleRadiusPx: Double = 0.0,
): PlateDragMode? {
    if (!hitRadiusPx.isFinite() || hitRadiusPx < 0.0 || !circleRadiusPx.isFinite() || circleRadiusPx < 0.0) return null
    val handleDistance = hypot(start.x - handle.x, start.y - handle.y)
    val centerDistance = hypot(start.x - center.x, start.y - center.y)
    return when {
        handleDistance <= hitRadiusPx && handleDistance < centerDistance -> PlateDragMode.HANDLE
        centerDistance <= max(hitRadiusPx, circleRadiusPx) -> PlateDragMode.CENTER
        handleDistance <= hitRadiusPx -> PlateDragMode.HANDLE
        else -> null
    }
}
