package com.openjump.app.ui.selector

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.ui.theme.OpenJumpTheme

/**
 * Manual A/B reference overlay. The default presentation stays the shared Saltos
 * look (blue A / orange B); [encoderEmphasis] switches only the Encoder route to
 * the approved green A/B with labels and halo. No scale or tracking change.
 */
@Composable
fun MetricCalibrationOverlay(
    mapper: VideoCoordinateMapper,
    pointA: ImagePoint?,
    pointB: ImagePoint?,
    enabled: Boolean,
    onPoint: (ImagePoint) -> Unit,
    modifier: Modifier = Modifier,
    encoderEmphasis: Boolean = false,
    contentLabel: String? = null,
) {
    val emphasisColor = OpenJumpTheme.colors.positive
    val pointColorA = if (encoderEmphasis) emphasisColor else Color(0xFF64B5F6)
    val pointColorB = if (encoderEmphasis) emphasisColor else Color(0xFFFFB74D)
    val density = LocalDensity.current
    val labelTextSize = with(density) { 14.sp.toPx() }
    val labelFill = remember(labelTextSize) {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = labelTextSize
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
    }
    val labelHalo = remember(labelTextSize) {
        Paint().apply {
            color = android.graphics.Color.argb(220, 0, 0, 0)
            textSize = labelTextSize
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            style = Paint.Style.STROKE
            strokeWidth = labelTextSize * 0.28f
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }
    }
    Canvas(
        modifier = modifier.semantics { if (contentLabel != null) contentDescription = contentLabel }
            .pointerInput(mapper, enabled, pointA, pointB) {
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
                if (encoderEmphasis) emphasisColor else Color(0xFF64B5F6),
                Offset(a.x.toFloat(), a.y.toFloat()),
                Offset(b.x.toFloat(), b.y.toFloat()),
                strokeWidth = 4f,
            )
        }
        listOfNotNull(a, b).forEachIndexed { index, point ->
            val center = Offset(point.x.toFloat(), point.y.toFloat())
            drawCircle(Color.Black.copy(alpha = 0.75f), 14f, center)
            drawCircle(if (index == 0) pointColorA else pointColorB, 10f, center)
            drawCircle(Color.White, 10f, center, style = Stroke(2f))
            if (encoderEmphasis) {
                val label = if (index == 0) "A" else "B"
                val labelOffset = Offset(center.x, center.y - 30f)
                drawContext.canvas.nativeCanvas.drawText(label, labelOffset.x, labelOffset.y, labelHalo)
                drawContext.canvas.nativeCanvas.drawText(label, labelOffset.x, labelOffset.y, labelFill)
            }
        }
    }
}
