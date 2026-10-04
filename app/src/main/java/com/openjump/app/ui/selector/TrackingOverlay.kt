package com.openjump.app.ui.selector

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.encoder.PlateDetection
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.TrackingFrameResult
import com.openjump.app.tracking.TrackingStatus
import com.openjump.app.ui.theme.OpenJumpTheme

@Composable
fun TrackingOverlay(
    mapper: VideoCoordinateMapper,
    current: TrackingFrameResult?,
    recent: List<TrackingFrameResult>,
    trackingPhase: TrackingPhase,
    plateDetection: PlateDetection? = null,
    selectionEnabled: Boolean,
    onTargetSelected: (ImagePoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = current?.result?.sample?.status
    val statusLabel = stringResource(
        when (status) {
            TrackingStatus.TRACKING -> R.string.selector_tracking_status_tracking
            TrackingStatus.UNCERTAIN -> R.string.selector_tracking_status_uncertain
            TrackingStatus.LOST -> R.string.selector_tracking_status_lost
            null -> when (trackingPhase) {
                TrackingPhase.IDLE -> R.string.selector_tracking_status_ready
                TrackingPhase.INITIALIZING -> R.string.selector_tracking_status_initializing
                TrackingPhase.READY -> R.string.selector_tracking_status_ready
                TrackingPhase.PROCESSING -> R.string.selector_tracking_status_processing
                TrackingPhase.COMPLETED -> R.string.selector_tracking_status_completed
                TrackingPhase.LOST -> R.string.selector_tracking_status_lost
                TrackingPhase.ERROR -> R.string.selector_tracking_status_error
            }
        },
    )
    val color = when (status) {
        TrackingStatus.TRACKING -> OpenJumpTheme.colors.positive
        TrackingStatus.UNCERTAIN -> OpenJumpTheme.colors.warning
        TrackingStatus.LOST -> MaterialTheme.colorScheme.error
        null -> when (trackingPhase) {
            TrackingPhase.LOST, TrackingPhase.ERROR -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    val validPointCount = recent.count { it.result.sample.point != null }
    val trajectoryDescription = if (validPointCount == 0) {
        stringResource(R.string.selector_tracking_trajectory_empty)
    } else {
        stringResource(R.string.selector_tracking_trajectory_description, validPointCount)
    }
    Box(
        modifier = modifier
            .semantics {
                contentDescription = "$statusLabel. $trajectoryDescription"
            }
            .pointerInput(mapper, selectionEnabled) {
                if (selectionEnabled) {
                    detectTapGestures { offset ->
                        mapper.viewToVideo(ImagePoint(offset.x.toDouble(), offset.y.toDouble()))
                            ?.let(onTargetSelected)
                    }
                }
            },
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val validPoints = recent.mapNotNull { it.result.sample.point }.takeLast(60)
            if (validPoints.size >= 2) {
                val path = Path()
                validPoints.forEachIndexed { index, point ->
                    val mapped = mapper.videoToView(point)
                    if (index == 0) path.moveTo(mapped.x.toFloat(), mapped.y.toFloat())
                    else path.lineTo(mapped.x.toFloat(), mapped.y.toFloat())
                }
                drawPath(path, color.copy(alpha = 0.65f), style = Stroke(width = 3f))
            }
            validPoints.forEach { point ->
                val mapped = mapper.videoToView(point)
                drawCircle(color.copy(alpha = 0.55f), radius = 3f, center = mapped.offset())
            }

            plateDetection?.let { plate ->
                val center = mapper.videoToView(plate.center).offset()
                val majorEdge = mapper.videoToView(
                    ImagePoint(
                        plate.center.x + kotlin.math.cos(plate.ellipseAngleRadians) * plate.ellipseMajorAxisPx / 2.0,
                        plate.center.y + kotlin.math.sin(plate.ellipseAngleRadians) * plate.ellipseMajorAxisPx / 2.0,
                    ),
                ).offset()
                val minorAngle = plate.ellipseAngleRadians + kotlin.math.PI / 2.0
                val minorEdge = mapper.videoToView(
                    ImagePoint(
                        plate.center.x + kotlin.math.cos(minorAngle) * plate.ellipseMinorAxisPx / 2.0,
                        plate.center.y + kotlin.math.sin(minorAngle) * plate.ellipseMinorAxisPx / 2.0,
                    ),
                ).offset()
                val majorRadius = (majorEdge - center).getDistance()
                val minorRadius = (minorEdge - center).getDistance()
                val angleDegrees = kotlin.math.atan2(
                    (majorEdge.y - center.y).toDouble(),
                    (majorEdge.x - center.x).toDouble(),
                ).toFloat() * 180f / kotlin.math.PI.toFloat()
                rotate(angleDegrees, center) {
                    drawOval(
                        color = color.copy(alpha = 0.92f),
                        topLeft = Offset(center.x - majorRadius, center.y - minorRadius),
                        size = Size(majorRadius * 2f, minorRadius * 2f),
                        style = Stroke(width = 4f),
                    )
                }
            }

            current?.result?.region?.let { region ->
                val topLeft = mapper.videoToView(ImagePoint(region.left, region.top))
                val bottomRight = mapper.videoToView(ImagePoint(region.right, region.bottom))
                drawRect(
                    color = color,
                    topLeft = topLeft.offset(),
                    size = Size(
                        (bottomRight.x - topLeft.x).toFloat(),
                        (bottomRight.y - topLeft.y).toFloat(),
                    ),
                    style = Stroke(width = 3f),
                )
            }
            current?.result?.featurePoints?.forEach { feature ->
                drawCircle(color.copy(alpha = 0.75f), radius = 2.5f, center = mapper.videoToView(feature).offset())
            }
            current?.result?.sample?.point?.let { point ->
                val mapped = mapper.videoToView(point).offset()
                drawCircle(Color.Black.copy(alpha = 0.75f), radius = 13f, center = mapped)
                drawCircle(color, radius = 9f, center = mapped)
                drawCircle(Color.White, radius = 9f, center = mapped, style = Stroke(width = 2f))
            }
        }

        if (trackingPhase != TrackingPhase.IDLE) {
            Text(
                text = statusLabel,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                color = color,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private fun ImagePoint.offset() = Offset(x.toFloat(), y.toFloat())
