package com.openjump.app.ui.selector

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.openjump.app.R
import kotlin.math.sqrt

/**
 * Exclusive gesture layer used while framing CROP. It consumes the complete
 * pointer stream so a drag cannot scroll the page or select a tracking target.
 */
@Composable
fun CropFramingOverlay(
    onGesture: (centroid: Offset, zoomChange: Float, panDelta: Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnGesture by rememberUpdatedState(onGesture)
    val cropModeDescription = stringResource(R.string.selector_crop_mode_description)

    Box(
        modifier = modifier
            .semantics { contentDescription = cropModeDescription }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var previousCentroid = down.position
                    var previousSpan = 0f
                    var previousPointerCount = 1
                    val previousPointerIds = LongArray(20)
                    previousPointerIds[0] = down.id.value
                    val currentPointerIds = LongArray(20)
                    while (true) {
                        val event = awaitPointerEvent()
                        var pointerCount = 0
                        var sumX = 0f
                        var sumY = 0f
                        var sumDistanceSquared = 0.0
                        for (change in event.changes) {
                            if (change.pressed && pointerCount < currentPointerIds.size) {
                                currentPointerIds[pointerCount] = change.id.value
                                pointerCount++
                                sumX += change.position.x
                                sumY += change.position.y
                            }
                        }
                        if (pointerCount == 0) {
                            event.changes.forEach { it.consume() }
                            break
                        }
                        val centroid = Offset(sumX / pointerCount, sumY / pointerCount)
                        if (pointerCount >= 2) {
                            for (change in event.changes) {
                                if (change.pressed) {
                                    val dx = change.position.x - centroid.x
                                    val dy = change.position.y - centroid.y
                                    sumDistanceSquared += dx * dx + dy * dy
                                }
                            }
                        }
                        var samePointerSet = pointerCount == previousPointerCount
                        if (samePointerSet) {
                            for (currentIndex in 0 until pointerCount) {
                                var found = false
                                for (previousIndex in 0 until previousPointerCount) {
                                    if (currentPointerIds[currentIndex] == previousPointerIds[previousIndex]) {
                                        found = true
                                        break
                                    }
                                }
                                if (!found) {
                                    samePointerSet = false
                                    break
                                }
                            }
                        }
                        val span = if (pointerCount >= 2) {
                            sqrt(sumDistanceSquared / pointerCount).toFloat()
                        } else {
                            0f
                        }
                        if (!samePointerSet) {
                            previousCentroid = centroid
                            previousSpan = span
                        } else if (pointerCount >= 2) {
                            if (previousPointerCount >= 2 && previousSpan > 0f && span.isFinite()) {
                                currentOnGesture(
                                    previousCentroid,
                                    (span / previousSpan).coerceIn(0.01f, 100f),
                                    centroid - previousCentroid,
                                )
                            }
                            previousSpan = span
                        } else if (previousPointerCount == 1) {
                            val delta = centroid - previousCentroid
                            if (delta != Offset.Zero) {
                                currentOnGesture(previousCentroid, 1f, delta)
                            }
                            previousSpan = 0f
                        }
                        for (i in 0 until pointerCount) previousPointerIds[i] = currentPointerIds[i]
                        previousCentroid = centroid
                        previousPointerCount = pointerCount
                        event.changes.forEach { it.consume() }
                    }
                }
            },
    )
}
