package com.openjump.app.ui.result

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.openjump.app.R
import com.openjump.app.encoder.EncoderAnalysis
import com.openjump.app.encoder.EncoderMetricKey
import com.openjump.app.encoder.EncoderSample
import com.openjump.app.encoder.MetricValidity
import com.openjump.app.encoder.ObservationKind
import com.openjump.app.settings.UnitProfile
import com.openjump.app.tracking.TrackingStatus
import com.openjump.app.tracking.TrackingVisualStyle
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import com.openjump.app.video.VideoFrameIndex
import com.openjump.app.video.VideoFrameIndexer
import com.openjump.app.video.export.TrajectoryTimeline
import com.openjump.app.video.export.TrajectoryVideoExporter
import com.openjump.app.video.export.VideoExportOptions
import com.openjump.app.video.export.VideoExportValidator
import com.openjump.app.video.export.VideoHudCanvasRenderer
import com.openjump.app.video.export.VideoHudEncoderRepSegment
import com.openjump.app.video.export.VideoHudEncoderVelocitySample
import com.openjump.app.video.export.VideoHudJumpTimeline
import com.openjump.app.video.export.VideoHudMetric
import com.openjump.app.video.export.VideoHudRepTelemetryPoint
import com.openjump.app.video.export.VideoHudSpec
import com.openjump.app.video.export.VideoOverlayCorner
import com.openjump.app.video.export.VideoTrajectoryMode
import com.openjump.app.video.export.resolveVideoHudFrameState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Input bundle describing everything needed to preview & export an annotated MP4 from a result screen.
 */
internal data class VideoExportSource(
    val videoUri: String,
    val videoIndex: VideoFrameIndex? = null,
    val hudSpec: VideoHudSpec,
    val previewTimeUs: Long? = null,
    val encoderSamples: List<EncoderSample> = emptyList(),
    val suggestedFileName: String = "openjump_video.mp4",
) {
    val hasTrajectory: Boolean get() = encoderSamples.any { it.rawPixelPoint != null }
}

private enum class VideoStudioPhase {
    IDLE,
    INDEXING,
    EXPORTING,
    VALIDATING,
    READY,
    SAVING,
    SAVED,
    ERROR,
}

private data class VideoStudioExportState(
    val phase: VideoStudioPhase = VideoStudioPhase.IDLE,
    val progressPercent: Int? = null,
    val message: String? = null,
    val file: File? = null,
) {
    val isBusy: Boolean
        get() = phase == VideoStudioPhase.INDEXING ||
            phase == VideoStudioPhase.EXPORTING ||
            phase == VideoStudioPhase.VALIDATING ||
            phase == VideoStudioPhase.SAVING
}

internal data class ExtractedVideoPreviewFrame(
    val bitmap: Bitmap,
    val sourceGeometry: VideoPresentationGeometry?,
)

internal fun computePreviewTrajectoryScale(
    previewWidth: Int,
    previewHeight: Int,
    sourceGeometry: VideoPresentationGeometry?,
): Pair<Float, Float>? {
    if (previewWidth <= 0 || previewHeight <= 0 || sourceGeometry == null) return null
    if (sourceGeometry.width <= 0 || sourceGeometry.height <= 0) return null
    return (previewWidth.toFloat() / sourceGeometry.width.toFloat()) to
        (previewHeight.toFloat() / sourceGeometry.height.toFloat())
}

internal object VideoExportShareHelper {

    fun shareVideo(context: Context, file: File, chooserTitle: String): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, chooserTitle, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, chooserTitle).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(chooser)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    suspend fun saveVideo(context: Context, sourceFile: File, destination: Uri): Boolean =
        withContext(Dispatchers.IO) {
            if (!sourceFile.isFile || sourceFile.length() == 0L) return@withContext false
            runCatching {
                context.contentResolver.openOutputStream(destination)?.use { output ->
                    sourceFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                } ?: error("OutputStream unavailable")
                true
            }.getOrDefault(false)
        }

    suspend fun extractPreviewFrame(
        context: Context,
        videoUriString: String,
        targetTimeUs: Long?,
        maxDimensionPx: Int = 960,
    ): ExtractedVideoPreviewFrame? = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(videoUriString) }.getOrNull() ?: return@withContext null
        val retriever = MediaMetadataRetriever()
        try {
            if (uri.scheme == "file") {
                val path = uri.path ?: return@withContext null
                if (!File(path).isFile) return@withContext null
                retriever.setDataSource(path)
            } else {
                retriever.setDataSource(context, uri)
            }
            val metaWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val metaHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val metaRotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val geometry = if (metaWidth != null && metaHeight != null && metaWidth > 0 && metaHeight > 0) {
                runCatching { VideoPresentationGeometry.fromEncoded(metaWidth, metaHeight, metaRotation) }.getOrNull()
            } else {
                null
            }

            val timeUs = (targetTimeUs ?: 0L).coerceAtLeast(0L)
            val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    maxDimensionPx,
                    maxDimensionPx,
                ) ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

            raw?.let { frame ->
                val software = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && frame.config == Bitmap.Config.HARDWARE) {
                    val copied = frame.copy(Bitmap.Config.ARGB_8888, false)
                    frame.recycle()
                    copied
                } else {
                    frame
                }
                ExtractedVideoPreviewFrame(
                    bitmap = scaleDownIfNeeded(software, maxDimensionPx),
                    sourceGeometry = geometry,
                )
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val scale = maxDim.toFloat() / longest.toFloat()
        val dstW = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val dstH = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, dstW, dstH, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    fun renderPreviewComposite(
        baseFrame: ExtractedVideoPreviewFrame,
        source: VideoExportSource,
        options: VideoExportOptions,
        logoBitmap: Bitmap?,
    ): Bitmap {
        val out = baseFrame.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)

        // Draw preview trajectory path scaled accurately from authoritative video presentation geometry.
        if (options.showTrajectory && source.hasTrajectory) {
            val authoritativeGeometry = source.videoIndex?.let {
                VideoPresentationGeometry.fromEncoded(it.width, it.height, it.rotationDegrees)
            } ?: baseFrame.sourceGeometry
            drawPreviewTrajectory(canvas, out.width, out.height, source, options.trajectoryMode, authoritativeGeometry)
        }

        val firstPtsUs = source.videoIndex?.frameTimesUs?.firstOrNull()
            ?: source.encoderSamples.firstOrNull()?.sourcePtsUs
            ?: 0L
        val previewPtsUs = source.previewTimeUs ?: firstPtsUs
        val relPtsUs = (previewPtsUs - firstPtsUs).coerceAtLeast(0L)
        val frameState = resolveVideoHudFrameState(source.hudSpec, relPtsUs, firstPtsUs)
        VideoHudCanvasRenderer.draw(
            canvas = canvas,
            videoWidth = out.width,
            videoHeight = out.height,
            spec = source.hudSpec,
            options = options,
            frameState = frameState,
            logoBitmap = logoBitmap,
        )
        return out
    }

    private fun drawPreviewTrajectory(
        canvas: Canvas,
        previewW: Int,
        previewH: Int,
        source: VideoExportSource,
        trajectoryMode: VideoTrajectoryMode,
        sourceGeometry: VideoPresentationGeometry?,
    ) {
        val (scaleX, scaleY) = computePreviewTrajectoryScale(previewW, previewH, sourceGeometry) ?: return
        val samples = source.encoderSamples
        val strokeScale = (min(previewW, previewH) / 720f).coerceAtLeast(0.6f)

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 3f * strokeScale
            alpha = 215
        }
        var prevX: Float? = null
        var prevY: Float? = null
        var prevFrameIndex: Int? = null
        var lastX: Float? = null
        var lastY: Float? = null
        var lastStatus = TrackingStatus.TRACKING
        val cutoffPts = source.previewTimeUs ?: Long.MAX_VALUE
        val minStartPts = if (trajectoryMode == VideoTrajectoryMode.PER_REP && source.hudSpec.encoderReps.size >= 2) {
            source.hudSpec.encoderReps.lastOrNull { it.startPtsUs <= cutoffPts }?.startPtsUs ?: Long.MIN_VALUE
        } else {
            Long.MIN_VALUE
        }

        for (sample in samples) {
            if (sample.sourcePtsUs < minStartPts) continue
            if (sample.sourcePtsUs > cutoffPts) break
            val pt = sample.rawPixelPoint
            val unusable = pt == null ||
                sample.trackingStatus == TrackingStatus.LOST ||
                sample.observationKind == ObservationKind.MISSING
            if (unusable) {
                prevX = null
                prevY = null
                prevFrameIndex = null
                continue
            }
            val x = pt!!.x.toFloat() * scaleX
            val y = pt.y.toFloat() * scaleY
            val continuous = prevFrameIndex != null && sample.frameIndex == prevFrameIndex + 1
            if (continuous && prevX != null && prevY != null) {
                linePaint.color = TrackingVisualStyle.colorFor(sample.trackingStatus).toInt()
                linePaint.alpha = 215
                canvas.drawLine(prevX, prevY, x, y, linePaint)
            }
            prevX = x
            prevY = y
            prevFrameIndex = sample.frameIndex
            lastX = x
            lastY = y
            lastStatus = sample.trackingStatus
        }

        if (lastX != null && lastY != null) {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            fill.color = TrackingVisualStyle.OUTLINE_ARGB.toInt()
            fill.alpha = 190
            canvas.drawCircle(lastX, lastY, 13f * strokeScale, fill)
            fill.color = TrackingVisualStyle.colorFor(lastStatus).toInt()
            fill.alpha = 255
            canvas.drawCircle(lastX, lastY, 9f * strokeScale, fill)
        }
    }
}

/**
 * Helper to build a Jump [VideoExportSource] from live or persisted jump results.
 */
internal fun buildJumpVideoExportSource(
    videoUri: String?,
    videoIndex: VideoFrameIndex?,
    cardData: JumpResultShareCardData,
    takeoffPtsUs: Long?,
    landingPtsUs: Long?,
    readyLabel: String,
    inFlightLabel: String,
    landedLabel: String,
): VideoExportSource? {
    if (videoUri.isNullOrBlank()) return null
    val previewPts = when {
        takeoffPtsUs != null && landingPtsUs != null && landingPtsUs > takeoffPtsUs ->
            takeoffPtsUs + (landingPtsUs - takeoffPtsUs) / 2L
        takeoffPtsUs != null -> takeoffPtsUs
        landingPtsUs != null -> landingPtsUs
        else -> null
    }
    return VideoExportSource(
        videoUri = videoUri,
        videoIndex = videoIndex,
        hudSpec = VideoHudSpec(
            title = cardData.protocolTitle,
            subtitle = cardData.methodLabel,
            primaryLabel = cardData.primaryLabel,
            primaryValue = cardData.primaryValue,
            secondaryMetrics = cardData.secondaryMetrics.take(3).map { VideoHudMetric(it.label, it.value) },
            estimatedPowerMetrics = cardData.estimatedPowerMetrics.take(2).map { VideoHudMetric(it.label, it.value) },
            estimatedPowerNote = cardData.estimatedPowerNote,
            athleteName = cardData.athleteName,
            dateLabel = cardData.dateLabel,
            jumpTimeline = if (takeoffPtsUs != null) {
                VideoHudJumpTimeline(
                    takeoffPtsUs = takeoffPtsUs,
                    landingPtsUs = landingPtsUs,
                    readyLabel = readyLabel,
                    inFlightLabel = inFlightLabel,
                    landedLabel = landedLabel,
                )
            } else {
                null
            },
        ),
        previewTimeUs = previewPts,
        suggestedFileName = "openjump_jump_${System.currentTimeMillis()}.mp4",
    )
}

/**
 * Helper to build an Encoder [VideoExportSource] with live per-rep progression, velocity readout,
 * and barbell trajectory overlay.
 */
internal fun buildEncoderVideoExportSource(
    videoUri: String?,
    videoIndex: VideoFrameIndex?,
    cardData: JumpResultShareCardData,
    analysis: EncoderAnalysis,
    unitSystem: UnitProfile,
    locale: Locale,
    repPrefix: String,
    peakVelocityLabel: String,
    velocityLossLabel: String,
    romLabel: String,
): VideoExportSource? {
    if (videoUri.isNullOrBlank()) return null
    val samples = analysis.samples
    val validReps = analysis.validRepetitions

    // Compute fixed velocity & position bounds across all valid repetitions so fatigue is comparable visually.
    val repSampleSliceValues = validReps.flatMap { rep ->
        val lo = rep.startSample.coerceIn(0, samples.lastIndex.coerceAtLeast(0))
        val hi = rep.endSample.coerceIn(lo, samples.lastIndex.coerceAtLeast(0))
        if (samples.isEmpty()) emptyList() else samples.subList(lo, hi + 1)
    }
    val allVelocities = repSampleSliceValues.mapNotNull { it.verticalVelocityMps?.takeIf(Double::isFinite) }
    val allPositions = repSampleSliceValues.mapNotNull { it.verticalPositionM?.takeIf(Double::isFinite) }
    val bestPeakVel = validReps.mapNotNull { it.metric(EncoderMetricKey.PEAK_VELOCITY)?.value?.takeIf(Double::isFinite) }.maxOrNull()
        ?: allVelocities.maxOrNull()?.coerceAtLeast(0.0)
        ?: 0.0
    val rawMaxVel = maxOf(allVelocities.maxOrNull() ?: 0.0, bestPeakVel, 0.25)
    val rawMinVel = minOf(allVelocities.minOrNull() ?: 0.0, -0.15)
    val chartMaxVel = rawMaxVel * 1.12
    val chartMinVel = rawMinVel * 1.12
    val minPos = allPositions.minOrNull() ?: 0.0
    val posSpan = ((allPositions.maxOrNull() ?: 0.0) - minPos).coerceAtLeast(0.05)

    val repSegments = validReps.mapNotNull { rep ->
        val startSample = samples.getOrNull(rep.startSample) ?: return@mapNotNull null
        val endSample = samples.getOrNull(rep.endSample) ?: return@mapNotNull null
        val mcv = rep.metric(EncoderMetricKey.MCV)?.value
        val mcvLabel = mcv?.let {
            MeasurementFormatting.format(it, MeasurementQuantity.SPEED_MPS, unitSystem, locale)
        } ?: cardData.primaryValue
        val vlossMetric = rep.metric(EncoderMetricKey.VELOCITY_LOSS)
        val vlossLabel = if (vlossMetric != null && vlossMetric.validity != MetricValidity.INVALID && vlossMetric.value != null && validReps.size >= 2) {
            String.format(locale, "%.0f%%", -kotlin.math.abs(vlossMetric.value))
        } else {
            null
        }
        val repSecondary = buildList {
            rep.metric(EncoderMetricKey.PEAK_VELOCITY)?.value?.let { pv ->
                add(
                    VideoHudMetric(
                        label = peakVelocityLabel,
                        value = MeasurementFormatting.format(pv, MeasurementQuantity.SPEED_MPS, unitSystem, locale),
                    ),
                )
            }
            if (vlossMetric != null && vlossMetric.validity != MetricValidity.INVALID && vlossMetric.value != null && validReps.size >= 2) {
                add(
                    VideoHudMetric(
                        label = velocityLossLabel,
                        value = String.format(locale, "%.1f %%", vlossMetric.value),
                    ),
                )
            }
            rep.metric(EncoderMetricKey.ROM)?.value?.let { rom ->
                add(
                    VideoHudMetric(
                        label = romLabel,
                        value = MeasurementFormatting.format(rom, MeasurementQuantity.DISPLACEMENT_M, unitSystem, locale),
                    ),
                )
            }
        }
        val repDurationUs = (endSample.sourcePtsUs - startSample.sourcePtsUs).coerceAtLeast(1L)
        val rawRepPoints = (rep.startSample..rep.endSample).mapNotNull { idx ->
            val s = samples.getOrNull(idx) ?: return@mapNotNull null
            val v = s.verticalVelocityMps?.takeIf(Double::isFinite) ?: return@mapNotNull null
            val p = s.verticalPositionM?.takeIf(Double::isFinite)
            val progress = ((s.sourcePtsUs - startSample.sourcePtsUs).toFloat() / repDurationUs.toFloat()).coerceIn(0f, 1f)
            val normPos = if (p != null) ((p - minPos) / posSpan).toFloat().coerceIn(0f, 1f) else 0.5f
            VideoHudRepTelemetryPoint(progress = progress, velocityMps = v, normalizedPosition = normPos)
        }
        val telemetryPoints = downsampleTelemetryPoints(rawRepPoints, maxPoints = 72)

        VideoHudEncoderRepSegment(
            repNumber = rep.ordinal + 1,
            startPtsUs = startSample.sourcePtsUs,
            endPtsUs = endSample.sourcePtsUs,
            mcvLabel = mcvLabel,
            velocityLossLabel = vlossLabel,
            secondaryMetrics = repSecondary,
            telemetryPoints = telemetryPoints,
        )
    }

    val velocitySamples = samples.mapNotNull { sample ->
        val v = sample.verticalVelocityMps ?: return@mapNotNull null
        if (!v.isFinite()) return@mapNotNull null
        VideoHudEncoderVelocitySample(
            ptsUs = sample.sourcePtsUs,
            speedLabel = MeasurementFormatting.format(kotlin.math.abs(v), MeasurementQuantity.SPEED_MPS, unitSystem, locale),
            verticalVelocityMps = v,
        )
    }

    val previewPts = repSegments.firstOrNull()?.let { firstRep ->
        firstRep.startPtsUs + ((firstRep.endPtsUs - firstRep.startPtsUs) * 0.75).toLong()
    } ?: samples.getOrNull(samples.size / 2)?.sourcePtsUs

    return VideoExportSource(
        videoUri = videoUri,
        videoIndex = videoIndex,
        hudSpec = VideoHudSpec(
            title = cardData.protocolTitle,
            subtitle = cardData.methodLabel,
            primaryLabel = cardData.primaryLabel,
            primaryValue = cardData.primaryValue,
            secondaryMetrics = cardData.secondaryMetrics.take(3).map { VideoHudMetric(it.label, it.value) },
            estimatedPowerMetrics = cardData.estimatedPowerMetrics.take(2).map { VideoHudMetric(it.label, it.value) },
            estimatedPowerNote = cardData.estimatedPowerNote,
            athleteName = cardData.athleteName,
            dateLabel = cardData.dateLabel,
            encoderReps = repSegments,
            encoderVelocitySamples = velocitySamples,
            repPrefix = repPrefix,
            totalReps = validReps.size,
            chartMaxVelMps = chartMaxVel,
            chartMinVelMps = chartMinVel,
            chartBestPeakVelMps = bestPeakVel,
        ),
        previewTimeUs = previewPts,
        encoderSamples = samples,
        suggestedFileName = "openjump_encoder_${System.currentTimeMillis()}.mp4",
    )
}

private fun downsampleTelemetryPoints(
    points: List<VideoHudRepTelemetryPoint>,
    maxPoints: Int,
): List<VideoHudRepTelemetryPoint> {
    if (points.size <= maxPoints || maxPoints < 2) return points
    val step = (points.size - 1).toDouble() / (maxPoints - 1).toDouble()
    return List(maxPoints) { i ->
        val index = (i * step).roundToInt().coerceIn(0, points.lastIndex)
        points[index]
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun VideoExportStudioSection(
    source: VideoExportSource,
    lightTheme: Boolean,
    onLightThemeChange: (Boolean) -> Unit,
    onBusyChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showLogo by rememberSaveable { mutableStateOf(true) }
    var logoCorner by rememberSaveable { mutableStateOf(VideoOverlayCorner.TOP_LEFT) }
    var showHud by rememberSaveable { mutableStateOf(true) }
    var hudCorner by rememberSaveable { mutableStateOf(VideoOverlayCorner.BOTTOM_LEFT) }
    var showLiveChart by rememberSaveable { mutableStateOf(true) }
    var includeEstimatedPower by rememberSaveable { mutableStateOf(true) }
    var showTrajectory by rememberSaveable { mutableStateOf(source.hasTrajectory) }
    var trajectoryMode by rememberSaveable { mutableStateOf(VideoTrajectoryMode.PER_REP) }
    var includeAthleteName by rememberSaveable { mutableStateOf(false) }

    val options = VideoExportOptions(
        showLogo = showLogo,
        logoCorner = logoCorner,
        showHud = showHud,
        hudCorner = hudCorner,
        showTrajectory = showTrajectory && source.hasTrajectory,
        trajectoryMode = trajectoryMode,
        showLiveChart = showLiveChart && showHud && source.hudSpec.hasLiveTelemetryChart,
        includeEstimatedPower = includeEstimatedPower && showHud && source.hudSpec.hasEstimatedPower,
        includeAthleteName = includeAthleteName,
        lightTheme = lightTheme,
    )
    val hasAnyLayer = options.showLogo || options.showHud || (options.showTrajectory && source.hasTrajectory)

    var exportState by remember { mutableStateOf(VideoStudioExportState()) }
    LaunchedEffect(exportState.isBusy) {
        onBusyChange(exportState.isBusy)
    }

    val exporter = remember(context) { TrajectoryVideoExporter(context) }
    var activeExportJob by remember { mutableStateOf<Job?>(null) }
    var cachedVideoIndex by remember(source.videoUri, source.videoIndex) {
        mutableStateOf(source.videoIndex)
    }
    val logoBitmap = remember(context) {
        runCatching { BitmapFactory.decodeResource(context.resources, R.drawable.ic_openjump_logo) }.getOrNull()
    }
    var baseFrame by remember(source.videoUri, source.previewTimeUs) { mutableStateOf<ExtractedVideoPreviewFrame?>(null) }
    var loadingFrame by remember(source.videoUri, source.previewTimeUs) { mutableStateOf(true) }
    var frameUnavailable by remember(source.videoUri, source.previewTimeUs) { mutableStateOf(false) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(source.videoUri, source.previewTimeUs) {
        loadingFrame = true
        frameUnavailable = false
        val extracted = VideoExportShareHelper.extractPreviewFrame(
            context = context,
            videoUriString = source.videoUri,
            targetTimeUs = source.previewTimeUs,
        )
        val old = baseFrame
        baseFrame = extracted
        if (old != null && old.bitmap !== extracted?.bitmap && !old.bitmap.isRecycled) old.bitmap.recycle()
        frameUnavailable = extracted == null
        loadingFrame = false
    }

    LaunchedEffect(baseFrame, options, source) {
        val frame = baseFrame ?: return@LaunchedEffect
        val rendered = withContext(Dispatchers.Default) {
            runCatching {
                VideoExportShareHelper.renderPreviewComposite(frame, source, options, logoBitmap)
            }.getOrNull()
        }
        val old = previewBitmap
        previewBitmap = rendered
        if (old != null && old !== rendered && !old.isRecycled) old.recycle()
    }

    // If options change after an export completed, let the user re-export seamlessly.
    LaunchedEffect(options) {
        if (exportState.phase in setOf(VideoStudioPhase.READY, VideoStudioPhase.SAVED)) {
            exportState = VideoStudioExportState()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activeExportJob?.cancel()
            activeExportJob = null
            exporter.cancel(notifyListener = false)
            onBusyChange(false)
            previewBitmap?.let { if (!it.isRecycled) it.recycle() }
            baseFrame?.bitmap?.let { if (!it.isRecycled) it.recycle() }
            logoBitmap?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    val shareTitle = stringResource(R.string.video_export_share_title)
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("video/mp4"),
    ) { destination ->
        val readyFile = exportState.file ?: return@rememberLauncherForActivityResult
        if (destination == null) return@rememberLauncherForActivityResult
        exportState = exportState.copy(phase = VideoStudioPhase.SAVING)
        scope.launch {
            val saved = VideoExportShareHelper.saveVideo(context, readyFile, destination)
            exportState = if (saved) {
                exportState.copy(
                    phase = VideoStudioPhase.SAVED,
                    message = context.getString(R.string.video_export_saved_ok),
                )
            } else {
                exportState.copy(
                    phase = VideoStudioPhase.READY,
                    message = context.getString(R.string.video_export_save_error),
                )
            }
        }
    }

    fun cancelActiveExport() {
        activeExportJob?.cancel()
        activeExportJob = null
        if (exporter.isExporting) {
            exporter.cancel(notifyListener = true)
        } else if (exportState.isBusy) {
            exportState = VideoStudioExportState(
                phase = VideoStudioPhase.IDLE,
                message = context.getString(R.string.selector_export_cancelled),
            )
        }
    }

    fun startExport() {
        if (exportState.isBusy || !hasAnyLayer) return
        activeExportJob?.cancel()
        exportState.file?.delete()
        exportState = VideoStudioExportState(
            phase = VideoStudioPhase.INDEXING,
            progressPercent = null,
            message = context.getString(R.string.selector_preparing_encoder),
        )
        activeExportJob = scope.launch {
            val uri = runCatching { Uri.parse(source.videoUri) }.getOrNull()
            if (uri == null) {
                exportState = VideoStudioExportState(
                    phase = VideoStudioPhase.ERROR,
                    message = context.getString(R.string.video_export_unavailable),
                )
                return@launch
            }
            val index = cachedVideoIndex ?: runCatching {
                withContext(Dispatchers.IO) {
                    val jobCtx = coroutineContext
                    VideoFrameIndexer.indexFromUri(context, uri) { jobCtx.ensureActive() }
                }
            }.onFailure { if (it is CancellationException) throw it }
                .getOrNull()
                ?.also { cachedVideoIndex = it }

            if (index == null) {
                exportState = VideoStudioExportState(
                    phase = VideoStudioPhase.ERROR,
                    message = context.getString(R.string.video_export_unavailable),
                )
                return@launch
            }

            val timeline = if (options.showTrajectory && source.hasTrajectory) {
                TrajectoryTimeline.fromEncoderSamples(index, source.encoderSamples)
            } else {
                null
            }

            exportState = VideoStudioExportState(
                phase = VideoStudioPhase.EXPORTING,
                progressPercent = 0,
                message = context.getString(R.string.selector_encoding_progress, 0),
            )
            try {
                exporter.startWithOverlays(
                    sourceUri = uri,
                    sourceIndex = index,
                    timeline = timeline,
                    hudSpec = source.hudSpec,
                    exportOptions = options,
                    logoBitmap = logoBitmap,
                    listener = object : TrajectoryVideoExporter.Listener {
                        override fun onProgress(percent: Int?) {
                            exportState = VideoStudioExportState(
                                phase = VideoStudioPhase.EXPORTING,
                                progressPercent = percent,
                                message = percent?.let {
                                    context.getString(R.string.selector_encoding_progress, it)
                                } ?: context.getString(R.string.selector_encoding),
                            )
                        }

                        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
                        override fun onCompleted(file: File, result: androidx.media3.transformer.ExportResult) {
                            exportState = VideoStudioExportState(
                                phase = VideoStudioPhase.VALIDATING,
                                progressPercent = 100,
                                message = context.getString(R.string.selector_export_validating),
                            )
                            activeExportJob = scope.launch {
                                try {
                                    val validation = withContext(Dispatchers.IO) {
                                        val jobCtx = coroutineContext
                                        val outIndex = VideoFrameIndexer.indexFromPath(file.absolutePath) {
                                            jobCtx.ensureActive()
                                        }
                                        VideoExportValidator.validate(index, outIndex)
                                    }
                                    val sizeMb = file.length().toDouble() / (1024.0 * 1024.0)
                                    exportState = VideoStudioExportState(
                                        phase = VideoStudioPhase.READY,
                                        file = file,
                                        message = context.getString(
                                            R.string.selector_export_verified,
                                            validation.presentationGeometry.width,
                                            validation.presentationGeometry.height,
                                            validation.detectedFps,
                                            sizeMb,
                                        ),
                                    )
                                } catch (_: CancellationException) {
                                    file.delete()
                                } catch (_: Exception) {
                                    file.delete()
                                    exportState = VideoStudioExportState(
                                        phase = VideoStudioPhase.ERROR,
                                        message = context.getString(R.string.selector_export_invalid_copy),
                                    )
                                }
                            }
                        }

                        override fun onCancelled() {
                            exportState = VideoStudioExportState(
                                phase = VideoStudioPhase.IDLE,
                                message = context.getString(R.string.selector_export_cancelled),
                            )
                        }

                        override fun onError(message: String, cause: Throwable?) {
                            exportState = VideoStudioExportState(
                                phase = VideoStudioPhase.ERROR,
                                message = context.getString(R.string.selector_export_start_error),
                            )
                        }
                    },
                )
            } catch (_: Exception) {
                exportState = VideoStudioExportState(
                    phase = VideoStudioPhase.ERROR,
                    message = context.getString(R.string.selector_export_start_error),
                )
            }
        }
    }

    // 1. Live video overlay preview
    val previewBmp = previewBitmap
    val frameAspect = if (previewBmp != null && previewBmp.height > 0) {
        (previewBmp.width.toFloat() / previewBmp.height.toFloat()).coerceIn(0.45f, 2.2f)
    } else {
        9f / 16f
    }

    val verticalPreviewHeight = (LocalConfiguration.current.screenHeightDp * 0.48f).dp.coerceIn(280.dp, 420.dp)
    val previewModifier = if (frameAspect >= 1f) {
        Modifier
            .fillMaxWidth()
            .aspectRatio(frameAspect)
            .testTag("video-studio-preview")
    } else {
        Modifier
            .height(verticalPreviewHeight)
            .aspectRatio(frameAspect, matchHeightConstraintsFirst = true)
            .testTag("video-studio-preview")
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = previewModifier,
            shape = ShapeTokens.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
            ),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (previewBmp != null && !previewBmp.isRecycled) {
                    Image(
                        bitmap = previewBmp.asImageBitmap(),
                        contentDescription = stringResource(R.string.video_export_preview_cd),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(ShapeTokens.large),
                    )
                } else if (frameUnavailable) {
                    Text(
                        text = stringResource(R.string.video_export_unavailable),
                        style = OpenJumpTypes.Secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.md),
                    )
                }
                if (loadingFrame) {
                    Box(
                        modifier = Modifier
                            .clip(ShapeTokens.full)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                            .padding(Spacing.sm),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
        }
    }

    // 2. Unified overlay toggles, theme & corner pickers card
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column {
            ShareStudioThemeRow(
                lightTheme = lightTheme,
                enabled = !exportState.isBusy,
                onLightThemeChange = onLightThemeChange,
                tagPrefix = "video-studio-theme",
            )
            ShareStudioDivider()
            ShareStudioSwitchRow(
                label = stringResource(R.string.video_export_show_logo),
                checked = showLogo,
                enabled = !exportState.isBusy,
                onCheckedChange = { showLogo = it },
                modifier = Modifier.testTag("video-studio-toggle-logo"),
            )
            if (showLogo) {
                OverlayCornerPickerRow(
                    label = stringResource(R.string.video_export_logo_position),
                    selected = logoCorner,
                    enabled = !exportState.isBusy,
                    onSelect = { logoCorner = it },
                    tagPrefix = "video-studio-logo",
                )
            }
            ShareStudioDivider()
            ShareStudioSwitchRow(
                label = stringResource(R.string.video_export_show_hud),
                checked = showHud,
                enabled = !exportState.isBusy,
                onCheckedChange = { showHud = it },
                modifier = Modifier.testTag("video-studio-toggle-hud"),
            )
            if (showHud) {
                OverlayCornerPickerRow(
                    label = stringResource(R.string.video_export_hud_position),
                    selected = hudCorner,
                    enabled = !exportState.isBusy,
                    onSelect = { hudCorner = it },
                    tagPrefix = "video-studio-hud",
                )
                if (source.hudSpec.hasLiveTelemetryChart) {
                    ShareStudioDivider()
                    ShareStudioSwitchRow(
                        label = stringResource(R.string.video_export_show_live_chart),
                        checked = showLiveChart,
                        enabled = !exportState.isBusy,
                        onCheckedChange = { showLiveChart = it },
                        modifier = Modifier.testTag("video-studio-toggle-live-chart"),
                    )
                }
                if (source.hudSpec.hasEstimatedPower) {
                    ShareStudioDivider()
                    ShareStudioSwitchRow(
                        label = stringResource(R.string.share_studio_include_estimated_power),
                        checked = includeEstimatedPower,
                        enabled = !exportState.isBusy,
                        onCheckedChange = { includeEstimatedPower = it },
                        modifier = Modifier.testTag("video-studio-toggle-estimated-power"),
                    )
                }
            }
            if (source.hasTrajectory) {
                ShareStudioDivider()
                ShareStudioSwitchRow(
                    label = stringResource(R.string.video_export_show_trajectory),
                    checked = showTrajectory,
                    enabled = !exportState.isBusy,
                    onCheckedChange = { showTrajectory = it },
                    modifier = Modifier.testTag("video-studio-toggle-trajectory"),
                )
                if (showTrajectory && source.hudSpec.encoderReps.size >= 2) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        FilterChip(
                            selected = trajectoryMode == VideoTrajectoryMode.PER_REP,
                            onClick = { trajectoryMode = VideoTrajectoryMode.PER_REP },
                            enabled = !exportState.isBusy,
                            label = { Text(stringResource(R.string.video_export_trajectory_per_rep)) },
                            modifier = Modifier.testTag("video-studio-trajectory-per-rep"),
                        )
                        FilterChip(
                            selected = trajectoryMode == VideoTrajectoryMode.FULL_SET,
                            onClick = { trajectoryMode = VideoTrajectoryMode.FULL_SET },
                            enabled = !exportState.isBusy,
                            label = { Text(stringResource(R.string.video_export_trajectory_full_set)) },
                            modifier = Modifier.testTag("video-studio-trajectory-full-set"),
                        )
                    }
                }
            }
            if (!source.hudSpec.athleteName.isNullOrBlank()) {
                ShareStudioDivider()
                ShareStudioSwitchRow(
                    label = stringResource(R.string.result_share_include_athlete),
                    checked = includeAthleteName,
                    enabled = !exportState.isBusy,
                    onCheckedChange = { includeAthleteName = it },
                    modifier = Modifier.testTag("video-studio-toggle-athlete"),
                )
            }
        }
    }

    // 4. Export progress / actions
    when (exportState.phase) {
        VideoStudioPhase.IDLE, VideoStudioPhase.ERROR -> {
            exportState.message?.let { msg ->
                Text(
                    text = msg,
                    style = OpenJumpTypes.Secondary,
                    color = if (exportState.phase == VideoStudioPhase.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Button(
                onClick = ::startExport,
                enabled = hasAnyLayer && !frameUnavailable && !loadingFrame,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("video-studio-export-btn"),
            ) {
                Text(stringResource(R.string.video_export_action))
            }
        }

        VideoStudioPhase.INDEXING, VideoStudioPhase.EXPORTING, VideoStudioPhase.VALIDATING -> {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text = exportState.message.orEmpty(),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val progress = exportState.progressPercent
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                OutlinedButton(
                    onClick = ::cancelActiveExport,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("video-studio-cancel-btn"),
                ) {
                    Text(stringResource(R.string.selector_cancel_export))
                }
            }
        }

        VideoStudioPhase.READY, VideoStudioPhase.SAVING, VideoStudioPhase.SAVED -> {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                exportState.message?.let { msg ->
                    Text(
                        text = msg,
                        style = OpenJumpTypes.Secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = {
                        exportState.file?.let { file ->
                            val launched = VideoExportShareHelper.shareVideo(context, file, shareTitle)
                            if (!launched) {
                                exportState = exportState.copy(
                                    message = context.getString(R.string.result_share_error),
                                )
                            }
                        }
                    },
                    enabled = exportState.file != null && exportState.phase != VideoStudioPhase.SAVING,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag("video-studio-share-btn"),
                ) {
                    Text(stringResource(R.string.video_export_share_action))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    OutlinedButton(
                        onClick = { saveLauncher.launch(source.suggestedFileName) },
                        enabled = exportState.file != null && exportState.phase != VideoStudioPhase.SAVING,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("video-studio-save-btn"),
                    ) {
                        Text(
                            stringResource(
                                if (exportState.phase == VideoStudioPhase.SAVING) {
                                    R.string.common_saving
                                } else {
                                    R.string.selector_save_mp4
                                },
                            ),
                        )
                    }
                    TextButton(
                        onClick = ::startExport,
                        enabled = exportState.phase != VideoStudioPhase.SAVING,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.selector_regenerate))
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayCornerPickerRow(
    label: String,
    selected: VideoOverlayCorner,
    enabled: Boolean,
    onSelect: (VideoOverlayCorner) -> Unit,
    tagPrefix: String,
) {
    val corners = listOf(
        VideoOverlayCorner.TOP_LEFT to Pair("↖", R.string.video_export_corner_top_left),
        VideoOverlayCorner.TOP_RIGHT to Pair("↗", R.string.video_export_corner_top_right),
        VideoOverlayCorner.BOTTOM_LEFT to Pair("↙", R.string.video_export_corner_bottom_left),
        VideoOverlayCorner.BOTTOM_RIGHT to Pair("↘", R.string.video_export_corner_bottom_right),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = OpenJumpTypes.Secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            corners.forEach { (corner, pair) ->
                val (glyph, labelRes) = pair
                val cd = stringResource(labelRes)
                FilterChip(
                    selected = selected == corner,
                    onClick = { onSelect(corner) },
                    enabled = enabled,
                    label = { Text(glyph) },
                    modifier = Modifier
                        .testTag("$tagPrefix-${corner.name.lowercase()}")
                        .semantics { contentDescription = cd },
                )
            }
        }
    }
}
