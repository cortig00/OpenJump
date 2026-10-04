package com.openjump.app.ui.selector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.openjump.app.tracking.ImagePoint

@androidx.compose.runtime.Composable
fun MetricCalibrationOverlay(
    mapper: VideoCoordinateMapper,
    pointA: ImagePoint?,
    pointB: ImagePoint?,
    enabled: Boolean,
    onPoint: (ImagePoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier.pointerInput(mapper, enabled, pointA, pointB) {
            if (enabled) {
                detectTapGestures { offset ->
                    mapper.viewToVideo(ImagePoint(offset.x.toDouble(), offset.y.toDouble()))?.let(onPoint)
                }
            }
        },
    ) {
        val a = pointA?.let(mapper::videoToView)
        val b = pointB?.let(mapper::videoToView)
        if (a != null && b != null) {
            drawLine(
                Color(0xFF64B5F6),
                Offset(a.x.toFloat(), a.y.toFloat()),
                Offset(b.x.toFloat(), b.y.toFloat()),
                strokeWidth = 4f,
            )
        }
        listOfNotNull(a, b).forEachIndexed { index, point ->
            val center = Offset(point.x.toFloat(), point.y.toFloat())
            drawCircle(Color.Black.copy(alpha = 0.75f), 14f, center)
            drawCircle(if (index == 0) Color(0xFF64B5F6) else Color(0xFFFFB74D), 10f, center)
            drawCircle(Color.White, 10f, center, style = Stroke(2f))
        }
    }
}
