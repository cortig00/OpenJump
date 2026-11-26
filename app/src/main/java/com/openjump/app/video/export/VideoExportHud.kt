package com.openjump.app.video.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class VideoOverlayCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}

enum class VideoTrajectoryMode {
    PER_REP,
    FULL_SET,
}

data class VideoExportOptions(
    val showLogo: Boolean = true,
    val logoCorner: VideoOverlayCorner = VideoOverlayCorner.TOP_LEFT,
    val showHud: Boolean = true,
    val hudCorner: VideoOverlayCorner = VideoOverlayCorner.BOTTOM_LEFT,
    val showTrajectory: Boolean = true,
    val trajectoryMode: VideoTrajectoryMode = VideoTrajectoryMode.PER_REP,
    val showLiveChart: Boolean = true,
    val includeEstimatedPower: Boolean = true,
    val includeAthleteName: Boolean = false,
    val lightTheme: Boolean = false,
) {
    val hasAnyCanvasHud: Boolean get() = showLogo || showHud
}

data class VideoHudMetric(
    val label: String,
    val value: String,
)

data class VideoHudJumpTimeline(
    /** Source PTS (µs) of takeoff event, if marked. */
    val takeoffPtsUs: Long?,
    /** Source PTS (µs) of landing event, if marked. */
    val landingPtsUs: Long?,
    val readyLabel: String,
    val inFlightLabel: String,
    val landedLabel: String,
)

data class VideoHudRepTelemetryPoint(
    /** Normalized time within the repetition [0f..1f]. */
    val progress: Float,
    /** Signed vertical velocity in m/s (negative eccentric, positive concentric). */
    val velocityMps: Double,
    /** Normalized vertical position [0f..1f] relative to set range. */
    val normalizedPosition: Float,
)

data class VideoHudEncoderRepSegment(
    val repNumber: Int,
    /** Source PTS (µs) of repetition start. */
    val startPtsUs: Long,
    /** Source PTS (µs) of repetition end. */
    val endPtsUs: Long,
    val mcvLabel: String,
    val velocityLossLabel: String? = null,
    /** Optional per-repetition secondary metrics (e.g. PV, velocity loss, ROM) shown live during this rep. */
    val secondaryMetrics: List<VideoHudMetric> = emptyList(),
    /** Optional per-repetition velocity & position curve points rendered live inside the HUD. */
    val telemetryPoints: List<VideoHudRepTelemetryPoint> = emptyList(),
)

data class VideoHudEncoderVelocitySample(
    /** Source PTS (µs). */
    val ptsUs: Long,
    /** Formatted instantaneous speed (e.g. "0.74 m/s"). */
    val speedLabel: String,
    /** Signed speed in m/s for concentric/eccentric accent cues. */
    val verticalVelocityMps: Double,
)

data class VideoHudSpec(
    val title: String,
    val subtitle: String,
    val primaryLabel: String,
    val primaryValue: String,
    val secondaryMetrics: List<VideoHudMetric> = emptyList(),
    val estimatedPowerMetrics: List<VideoHudMetric> = emptyList(),
    val estimatedPowerNote: String? = null,
    val athleteName: String? = null,
    val dateLabel: String? = null,
    val jumpTimeline: VideoHudJumpTimeline? = null,
    val encoderReps: List<VideoHudEncoderRepSegment> = emptyList(),
    val encoderVelocitySamples: List<VideoHudEncoderVelocitySample> = emptyList(),
    val repPrefix: String = "REP",
    val totalReps: Int = 0,
    val brandTitle: String = "OpenJump",
    /** Fixed vertical velocity scale across the whole set so fatigue is visible per rep. */
    val chartMaxVelMps: Double = 0.0,
    val chartMinVelMps: Double = 0.0,
    val chartBestPeakVelMps: Double = 0.0,
) {
    val hasLiveTelemetryChart: Boolean
        get() = encoderReps.any { it.telemetryPoints.size >= 2 } && chartMaxVelMps > chartMinVelMps

    val hasEstimatedPower: Boolean
        get() = estimatedPowerMetrics.isNotEmpty()
}

enum class VideoHudPhaseTone { NEUTRAL, ACTIVE, COMPLETE }

/** Snapshot of dynamic HUD state for one video PTS. Used to skip redundant canvas redraws. */
data class VideoHudFrameState(
    val statusBadge: String?,
    val statusTone: VideoHudPhaseTone,
    val primaryLabel: String,
    val primaryValue: String,
    val activeRepNumber: Int?,
    val totalReps: Int,
    val liveDetail: String?,
    val secondaryMetrics: List<VideoHudMetric> = emptyList(),
    val chartRepNumber: Int? = null,
    val chartPoints: List<VideoHudRepTelemetryPoint> = emptyList(),
    /** Quantized [0..CHART_PROGRESS_BUCKETS] playhead position inside the active repetition. */
    val chartProgressBucket: Int = -1,
)

internal const val CHART_PROGRESS_BUCKETS = 60

data class VideoHudRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun overlaps(other: VideoHudRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}

data class VideoOverlayLayout(
    val logoRect: VideoHudRect?,
    val hudRect: VideoHudRect?,
)

/** Pure JVM-safe ARGB palette for video overlays (no android.graphics.Color calls at init). */
internal data class VideoHudPalette(
    val pillBg: Int,
    val pillBorder: Int,
    val cardBgTop: Int,
    val cardBgBottom: Int,
    val cardBorder: Int,
    val accent: Int,
    val accentBg: Int,
    val activeDot: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val textMuted: Int,
    val divider: Int,
    val repDotIdle: Int,
    val repDotDone: Int,
    val repDotActive: Int,
    val chartBg: Int,
    val chartBorder: Int,
    val chartGrid: Int,
    val chartZeroLine: Int,
    val chartRefLine: Int,
    val chartVelLine: Int,
    val chartVelFill: Int,
    val chartPosLine: Int,
    val chartDotHalo: Int,
)

internal val DarkVideoHudPalette = VideoHudPalette(
    pillBg = 0xCC0A1412.toInt(),
    pillBorder = 0x555AD2B8.toInt(),
    cardBgTop = 0xEB0B1714.toInt(),
    cardBgBottom = 0xF008110F.toInt(),
    cardBorder = 0x665AD2B8.toInt(),
    accent = 0xFF67D1B8.toInt(),
    accentBg = 0x3367D1B8.toInt(),
    activeDot = 0xFF2EE59D.toInt(),
    textPrimary = 0xFFF2FAF7.toInt(),
    textSecondary = 0xFFC5DDD6.toInt(),
    textMuted = 0xFF8EAFA6.toInt(),
    divider = 0x2EFFFFFF.toInt(),
    repDotIdle = 0x44FFFFFF.toInt(),
    repDotDone = 0xFF3E9C87.toInt(),
    repDotActive = 0xFF67D1B8.toInt(),
    chartBg = 0xF7060D0B.toInt(),
    chartBorder = 0x665AD2B8.toInt(),
    chartGrid = 0x1AFFFFFF.toInt(),
    chartZeroLine = 0x66FFFFFF.toInt(),
    chartRefLine = 0x99F2C66D.toInt(),
    chartVelLine = 0xFF2EE59D.toInt(),
    chartVelFill = 0x382EE59D.toInt(),
    chartPosLine = 0xDC60C2FF.toInt(),
    chartDotHalo = 0xFFFFFFFF.toInt(),
)

internal val LightVideoHudPalette = VideoHudPalette(
    pillBg = 0xF0F6FBFA.toInt(),
    pillBorder = 0x660F6E5D.toInt(),
    cardBgTop = 0xF5F8FCFB.toInt(),
    cardBgBottom = 0xF7EAF4F1.toInt(),
    cardBorder = 0x660F6E5D.toInt(),
    accent = 0xFF0E6C5B.toInt(),
    accentBg = 0x220E6C5B.toInt(),
    activeDot = 0xFF0C8A6E.toInt(),
    textPrimary = 0xFF0D1D1A.toInt(),
    textSecondary = 0xFF344C46.toInt(),
    textMuted = 0xFF567069.toInt(),
    divider = 0x240E6C5B.toInt(),
    repDotIdle = 0x330E6C5B.toInt(),
    repDotDone = 0xFF52A896.toInt(),
    repDotActive = 0xFF0E6C5B.toInt(),
    chartBg = 0xFCFFFFFF.toInt(),
    chartBorder = 0x550F6E5D.toInt(),
    chartGrid = 0x1A0F6E5D.toInt(),
    chartZeroLine = 0x660F6E5D.toInt(),
    chartRefLine = 0x99C87A08.toInt(),
    chartVelLine = 0xFF0B8A6E.toInt(),
    chartVelFill = 0x300B8A6E.toInt(),
    chartPosLine = 0xD81E70B9.toInt(),
    chartDotHalo = 0xCC0D1D1A.toInt(),
)

internal fun videoHudPalette(lightTheme: Boolean): VideoHudPalette =
    if (lightTheme) LightVideoHudPalette else DarkVideoHudPalette

/**
 * Computes collision-free logo and HUD overlay bounds in video presentation space.
 * Pure Kotlin so it is directly unit-tested on JVM.
 */
internal fun computeVideoOverlayLayout(
    videoWidth: Int,
    videoHeight: Int,
    options: VideoExportOptions,
    hasSecondaryMetrics: Boolean,
    hasAthleteRow: Boolean,
    hasRepDots: Boolean,
    hasLiveChart: Boolean = false,
    hasEstimatedPowerRow: Boolean = false,
): VideoOverlayLayout {
    val w = videoWidth.coerceAtLeast(1).toFloat()
    val h = videoHeight.coerceAtLeast(1).toFloat()
    val shortSide = min(w, h)
    val scale = (shortSide / 720f).coerceIn(0.55f, 3.2f)
    val margin = (26f * scale).coerceAtLeast(12f)
    val stackGap = (12f * scale).coerceAtLeast(6f)

    val logoRect = if (options.showLogo) {
        val logoW = min(w - margin * 2f, 212f * scale)
        val logoH = 52f * scale
        placeRectInCorner(w, h, margin, logoW, logoH, options.logoCorner)
    } else {
        null
    }

    val hudRect = if (options.showHud) {
        val maxHudW = if (w >= h) w * 0.46f else w - margin * 2f
        val baseHudW = if (hasLiveChart) 412f * scale else 380f * scale
        val hudW = min(maxHudW, baseHudW).coerceAtLeast(min(w - margin * 2f, 220f * scale))
        var hudH = 114f * scale
        if (hasSecondaryMetrics) hudH += 56f * scale
        if (hasEstimatedPowerRow) hudH += 54f * scale
        if (hasRepDots) hudH += 22f * scale
        if (hasLiveChart) hudH += 102f * scale
        if (hasAthleteRow) hudH += 24f * scale
        val maxHeightFrac = if (hasLiveChart) 0.58f else 0.48f
        hudH = min(hudH, h * maxHeightFrac)

        var rect = placeRectInCorner(w, h, margin, hudW, hudH, options.hudCorner)
        if (logoRect != null && options.logoCorner == options.hudCorner) {
            rect = when (options.hudCorner) {
                VideoOverlayCorner.TOP_LEFT, VideoOverlayCorner.TOP_RIGHT -> {
                    val shiftedTop = (logoRect.bottom + stackGap).coerceAtMost((h - margin - hudH).coerceAtLeast(margin))
                    VideoHudRect(rect.left, shiftedTop, rect.right, shiftedTop + hudH)
                }
                VideoOverlayCorner.BOTTOM_LEFT, VideoOverlayCorner.BOTTOM_RIGHT -> {
                    val shiftedBottom = (logoRect.top - stackGap).coerceAtLeast(margin + hudH)
                    VideoHudRect(rect.left, shiftedBottom - hudH, rect.right, shiftedBottom)
                }
            }
        }
        rect
    } else {
        null
    }

    return VideoOverlayLayout(logoRect = logoRect, hudRect = hudRect)
}

private fun placeRectInCorner(
    videoW: Float,
    videoH: Float,
    margin: Float,
    boxW: Float,
    boxH: Float,
    corner: VideoOverlayCorner,
): VideoHudRect {
    val left = when (corner) {
        VideoOverlayCorner.TOP_LEFT, VideoOverlayCorner.BOTTOM_LEFT -> margin
        VideoOverlayCorner.TOP_RIGHT, VideoOverlayCorner.BOTTOM_RIGHT -> (videoW - margin - boxW).coerceAtLeast(margin)
    }
    val top = when (corner) {
        VideoOverlayCorner.TOP_LEFT, VideoOverlayCorner.TOP_RIGHT -> margin
        VideoOverlayCorner.BOTTOM_LEFT, VideoOverlayCorner.BOTTOM_RIGHT -> (videoH - margin - boxH).coerceAtLeast(margin)
    }
    return VideoHudRect(left = left, top = top, right = left + boxW, bottom = top + boxH)
}

/**
 * Resolves the HUD display state for a zero-based Transformer [presentationTimeUs].
 * Pure Kotlin so jump phase transitions and encoder rep progression are JVM-tested.
 */
internal fun resolveVideoHudFrameState(
    spec: VideoHudSpec,
    presentationTimeUs: Long,
    firstSourcePtsUs: Long,
): VideoHudFrameState {
    val sourcePtsUs = presentationTimeUs + firstSourcePtsUs

    val jump = spec.jumpTimeline
    if (jump != null && jump.takeoffPtsUs != null) {
        val takeoff = jump.takeoffPtsUs
        val landing = jump.landingPtsUs
        return when {
            sourcePtsUs < takeoff -> VideoHudFrameState(
                statusBadge = jump.readyLabel,
                statusTone = VideoHudPhaseTone.NEUTRAL,
                primaryLabel = spec.primaryLabel,
                primaryValue = spec.primaryValue,
                activeRepNumber = null,
                totalReps = 0,
                liveDetail = null,
            )
            landing == null || sourcePtsUs < landing -> {
                val elapsedSec = ((sourcePtsUs - takeoff).coerceAtLeast(0L)) / 1_000_000.0
                val quantized = (elapsedSec * 100.0).roundToInt() / 100.0
                VideoHudFrameState(
                    statusBadge = jump.inFlightLabel,
                    statusTone = VideoHudPhaseTone.ACTIVE,
                    primaryLabel = spec.primaryLabel,
                    primaryValue = spec.primaryValue,
                    activeRepNumber = null,
                    totalReps = 0,
                    liveDetail = String.format(java.util.Locale.US, "%.2f s", quantized),
                )
            }
            else -> VideoHudFrameState(
                statusBadge = jump.landedLabel,
                statusTone = VideoHudPhaseTone.COMPLETE,
                primaryLabel = spec.primaryLabel,
                primaryValue = spec.primaryValue,
                activeRepNumber = null,
                totalReps = 0,
                liveDetail = null,
            )
        }
    }

    if (spec.encoderReps.isNotEmpty()) {
        val activeRep = spec.encoderReps.lastOrNull { sourcePtsUs >= it.startPtsUs && sourcePtsUs <= it.endPtsUs }
        val lastCompletedRep = spec.encoderReps.lastOrNull { sourcePtsUs > it.endPtsUs }
        val displayRep = activeRep ?: lastCompletedRep
        val total = max(spec.totalReps, spec.encoderReps.size)

        val liveSample = binarySearchVelocitySample(spec.encoderVelocitySamples, sourcePtsUs)
        val isMovingConcentric = liveSample != null && liveSample.verticalVelocityMps > 0.08

        val badge = when {
            activeRep != null -> "${spec.repPrefix} ${activeRep.repNumber}/$total"
            lastCompletedRep != null -> "${spec.repPrefix} ${lastCompletedRep.repNumber}/$total"
            else -> "${spec.repPrefix} 0/$total"
        }
        val tone = when {
            activeRep != null && isMovingConcentric -> VideoHudPhaseTone.ACTIVE
            activeRep != null -> VideoHudPhaseTone.ACTIVE
            lastCompletedRep != null -> VideoHudPhaseTone.COMPLETE
            else -> VideoHudPhaseTone.NEUTRAL
        }
        val primaryVal = displayRep?.mcvLabel ?: spec.primaryValue
        val liveDetail = when {
            activeRep != null && liveSample != null && abs(liveSample.verticalVelocityMps) >= 0.05 -> liveSample.speedLabel
            displayRep?.velocityLossLabel != null -> displayRep.velocityLossLabel
            else -> null
        }

        val activeSecondary = displayRep?.secondaryMetrics
            ?.takeIf { it.isNotEmpty() }
            ?: spec.secondaryMetrics

        val chartPoints = displayRep?.telemetryPoints.orEmpty()
        val chartBucket = when {
            chartPoints.size < 2 -> -1
            activeRep != null -> {
                val span = (activeRep.endPtsUs - activeRep.startPtsUs).coerceAtLeast(1L)
                val frac = ((sourcePtsUs - activeRep.startPtsUs).toDouble() / span.toDouble()).coerceIn(0.0, 1.0)
                (frac * CHART_PROGRESS_BUCKETS).roundToInt().coerceIn(0, CHART_PROGRESS_BUCKETS)
            }
            lastCompletedRep != null -> CHART_PROGRESS_BUCKETS
            else -> -1
        }

        return VideoHudFrameState(
            statusBadge = badge,
            statusTone = tone,
            primaryLabel = spec.primaryLabel,
            primaryValue = primaryVal,
            activeRepNumber = displayRep?.repNumber,
            totalReps = total,
            liveDetail = liveDetail,
            secondaryMetrics = activeSecondary,
            chartRepNumber = displayRep?.repNumber,
            chartPoints = chartPoints,
            chartProgressBucket = chartBucket,
        )
    }

    return VideoHudFrameState(
        statusBadge = null,
        statusTone = VideoHudPhaseTone.NEUTRAL,
        primaryLabel = spec.primaryLabel,
        primaryValue = spec.primaryValue,
        activeRepNumber = null,
        totalReps = 0,
        liveDetail = null,
        secondaryMetrics = spec.secondaryMetrics,
    )
}

private fun binarySearchVelocitySample(
    samples: List<VideoHudEncoderVelocitySample>,
    sourcePtsUs: Long,
): VideoHudEncoderVelocitySample? {
    if (samples.isEmpty()) return null
    var low = 0
    var high = samples.lastIndex
    var answer = -1
    while (low <= high) {
        val mid = (low + high).ushr(1)
        if (samples[mid].ptsUs <= sourcePtsUs) {
            answer = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return answer.takeIf { it >= 0 }?.let(samples::get)
}

/**
 * Shared Canvas renderer used both by Media3's [VideoHudCanvasOverlay] and by the live preview
 * in the Video Export Studio so what you preview matches the exported MP4 pixel-for-pixel.
 */
object VideoHudCanvasRenderer {

    private val sansNormal: Typeface by lazy { Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }
    private val sansBold: Typeface by lazy { Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }

    fun draw(
        canvas: Canvas,
        videoWidth: Int,
        videoHeight: Int,
        spec: VideoHudSpec,
        options: VideoExportOptions,
        frameState: VideoHudFrameState,
        logoBitmap: Bitmap? = null,
    ) {
        if (!options.hasAnyCanvasHud) return
        val palette = videoHudPalette(options.lightTheme)
        val scale = (min(videoWidth, videoHeight) / 720f).coerceIn(0.55f, 3.2f)
        val secondary = frameState.secondaryMetrics.ifEmpty { spec.secondaryMetrics }.take(3)
        val estimatedPower = if (options.includeEstimatedPower) spec.estimatedPowerMetrics.take(2) else emptyList()
        val estimatedNote = spec.estimatedPowerNote?.takeIf { estimatedPower.isNotEmpty() && it.isNotBlank() }
        val athleteText = spec.athleteName?.takeIf { options.includeAthleteName && it.isNotBlank() }
        val showRepDots = frameState.totalReps in 2..12
        val showLiveChart = options.showLiveChart && spec.hasLiveTelemetryChart

        val layout = computeVideoOverlayLayout(
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            options = options,
            hasSecondaryMetrics = secondary.isNotEmpty(),
            hasAthleteRow = athleteText != null || estimatedNote != null,
            hasRepDots = showRepDots,
            hasLiveChart = showLiveChart,
            hasEstimatedPowerRow = estimatedPower.isNotEmpty(),
        )

        layout.logoRect?.let { rect ->
            drawLogoBadge(canvas, rect, spec.brandTitle, palette, scale, logoBitmap)
        }
        layout.hudRect?.let { rect ->
            drawHudCard(
                canvas = canvas,
                rect = rect,
                spec = spec,
                frameState = frameState,
                secondary = secondary,
                estimatedPower = estimatedPower,
                estimatedNote = estimatedNote,
                athleteText = athleteText,
                showRepDots = showRepDots,
                showLiveChart = showLiveChart,
                palette = palette,
                scale = scale,
            )
        }
    }

    private fun drawLogoBadge(
        canvas: Canvas,
        rect: VideoHudRect,
        brandTitle: String,
        palette: VideoHudPalette,
        scale: Float,
        logoBitmap: Bitmap?,
    ) {
        val rf = RectF(rect.left, rect.top, rect.right, rect.bottom)
        val radius = rf.height() / 2f

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.pillBg
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.pillBorder
            style = Paint.Style.STROKE
            strokeWidth = (1.6f * scale).coerceAtLeast(1f)
        }
        canvas.drawRoundRect(rf, radius, radius, bgPaint)
        canvas.drawRoundRect(rf, radius, radius, borderPaint)

        val padX = 12f * scale
        val iconSize = rf.height() * 0.64f
        val iconLeft = rf.left + padX
        val iconTop = rf.centerY() - iconSize / 2f
        val iconRect = RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)

        if (logoBitmap != null && !logoBitmap.isRecycled) {
            val clipPath = Path().apply {
                addRoundRect(iconRect, iconSize * 0.25f, iconSize * 0.25f, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(clipPath)
            val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(
                logoBitmap,
                Rect(0, 0, logoBitmap.width, logoBitmap.height),
                iconRect,
                bmpPaint,
            )
            canvas.restore()
        } else {
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accent }
            canvas.drawCircle(iconRect.centerX(), iconRect.centerY(), iconSize * 0.38f, dotPaint)
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textPrimary
            textSize = 21f * scale
            typeface = sansBold
            letterSpacing = 0.02f
        }
        val textX = iconRect.right + 10f * scale
        val maxTextW = (rf.right - padX - textX).coerceAtLeast(24f)
        val fitted = ellipsize(brandTitle, textPaint, maxTextW)
        val fm = textPaint.fontMetrics
        val baseline = rf.centerY() - (fm.ascent + fm.descent) / 2f
        canvas.drawText(fitted, textX, baseline, textPaint)
    }

    private fun drawHudCard(
        canvas: Canvas,
        rect: VideoHudRect,
        spec: VideoHudSpec,
        frameState: VideoHudFrameState,
        secondary: List<VideoHudMetric>,
        estimatedPower: List<VideoHudMetric>,
        estimatedNote: String?,
        athleteText: String?,
        showRepDots: Boolean,
        showLiveChart: Boolean,
        palette: VideoHudPalette,
        scale: Float,
    ) {
        val rf = RectF(rect.left, rect.top, rect.right, rect.bottom)
        val radius = 20f * scale
        val padX = 18f * scale
        val padY = 14f * scale

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                rf.left,
                rf.top,
                rf.left,
                rf.bottom,
                palette.cardBgTop,
                palette.cardBgBottom,
                Shader.TileMode.CLAMP,
            )
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.cardBorder
            style = Paint.Style.STROKE
            strokeWidth = (1.6f * scale).coerceAtLeast(1f)
        }
        canvas.drawRoundRect(rf, radius, radius, bgPaint)
        canvas.drawRoundRect(rf, radius, radius, borderPaint)

        // Left accent stripe inside card
        val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (frameState.statusTone == VideoHudPhaseTone.ACTIVE) palette.activeDot else palette.accent
        }
        val stripeW = 4f * scale
        canvas.drawRoundRect(
            RectF(rf.left + 8f * scale, rf.top + 14f * scale, rf.left + 8f * scale + stripeW, rf.bottom - 14f * scale),
            stripeW / 2f,
            stripeW / 2f,
            stripePaint,
        )

        val contentLeft = rf.left + padX + 4f * scale
        val contentRight = rf.right - padX
        val contentW = (contentRight - contentLeft).coerceAtLeast(40f)
        var cursorY = rf.top + padY

        // Row 1: Protocol / Exercise title + optional live status pill
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            textSize = 15.5f * scale
            typeface = sansBold
            letterSpacing = 0.04f
        }
        val badgeText = frameState.statusBadge?.uppercase()
        var badgeW = 0f
        if (badgeText != null) {
            val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (frameState.statusTone == VideoHudPhaseTone.ACTIVE) palette.activeDot else palette.accent
                textSize = 13.5f * scale
                typeface = sansBold
                letterSpacing = 0.04f
            }
            val textW = badgePaint.measureText(badgeText)
            val dotGap = if (frameState.statusTone == VideoHudPhaseTone.ACTIVE) 12f * scale else 0f
            badgeW = textW + 18f * scale + dotGap
            val badgeH = 24f * scale
            val badgeRect = RectF(
                (contentRight - badgeW).coerceAtLeast(contentLeft + contentW * 0.45f),
                cursorY,
                contentRight,
                cursorY + badgeH,
            )
            val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accentBg }
            canvas.drawRoundRect(badgeRect, badgeH / 2f, badgeH / 2f, pillPaint)

            var badgeTextX = badgeRect.left + 9f * scale
            if (frameState.statusTone == VideoHudPhaseTone.ACTIVE) {
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.activeDot }
                canvas.drawCircle(badgeRect.left + 11f * scale, badgeRect.centerY(), 3.6f * scale, dotPaint)
                badgeTextX += dotGap
            }
            val bfm = badgePaint.fontMetrics
            val bBase = badgeRect.centerY() - (bfm.ascent + bfm.descent) / 2f
            canvas.drawText(
                ellipsize(badgeText, badgePaint, (badgeRect.right - badgeTextX - 6f * scale).coerceAtLeast(16f)),
                badgeTextX,
                bBase,
                badgePaint,
            )
        }

        val headerText = if (spec.subtitle.isNotBlank()) "${spec.title} · ${spec.subtitle}" else spec.title
        val titleMaxW = (contentW - badgeW - if (badgeW > 0f) 10f * scale else 0f).coerceAtLeast(36f)
        val tfm = titlePaint.fontMetrics
        val titleBaseline = cursorY + 12f * scale - (tfm.ascent + tfm.descent) / 2f
        canvas.drawText(ellipsize(headerText.uppercase(), titlePaint, titleMaxW), contentLeft, titleBaseline, titlePaint)
        cursorY += 28f * scale

        // Row 2: Hero primary metric + optional live readout (flight timer / live speed)
        val primaryLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textMuted
            textSize = 13.5f * scale
            typeface = sansNormal
        }
        canvas.drawText(ellipsize(frameState.primaryLabel, primaryLabelPaint, contentW), contentLeft, cursorY + 12f * scale, primaryLabelPaint)
        cursorY += 18f * scale

        val heroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textPrimary
            textSize = 38f * scale
            typeface = sansBold
        }
        val liveDetail = frameState.liveDetail
        var liveDetailW = 0f
        if (liveDetail != null) {
            val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.accent
                textSize = 20f * scale
                typeface = sansBold
                textAlign = Paint.Align.RIGHT
            }
            liveDetailW = livePaint.measureText(liveDetail) + 12f * scale
            canvas.drawText(liveDetail, contentRight, cursorY + 34f * scale, livePaint)
        }

        val heroMaxW = (contentW - liveDetailW).coerceAtLeast(60f)
        fitTextSize(frameState.primaryValue, heroPaint, heroMaxW, 20f * scale)
        canvas.drawText(frameState.primaryValue, contentLeft, cursorY + 36f * scale, heroPaint)
        cursorY += 48f * scale

        // Optional rep progress dots for Encoder sets
        if (showRepDots) {
            val activeRep = frameState.activeRepNumber ?: 0
            val count = frameState.totalReps
            val dotGap = 6f * scale
            val dotW = ((contentW - dotGap * (count - 1)) / count).coerceAtMost(28f * scale)
            val dotH = 5.5f * scale
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (i in 1..count) {
                dotPaint.color = when {
                    i == activeRep -> palette.repDotActive
                    i < activeRep -> palette.repDotDone
                    else -> palette.repDotIdle
                }
                val dx = contentLeft + (i - 1) * (dotW + dotGap)
                canvas.drawRoundRect(
                    RectF(dx, cursorY, dx + dotW, cursorY + dotH),
                    dotH / 2f,
                    dotH / 2f,
                    dotPaint,
                )
            }
            cursorY += 16f * scale
        }

        // Optional high-contrast per-repetition live velocity + position chart
        if (showLiveChart) {
            val reserveBelow = (if (secondary.isNotEmpty()) 54f * scale else 0f) +
                (if (athleteText != null) 22f * scale else 0f) + 12f * scale
            val chartH = min(90f * scale, (rf.bottom - reserveBelow - cursorY).coerceAtLeast(56f * scale))
            drawPerRepTelemetryChart(
                canvas = canvas,
                left = contentLeft,
                top = cursorY,
                right = contentRight,
                bottom = cursorY + chartH,
                spec = spec,
                frameState = frameState,
                palette = palette,
                scale = scale,
            )
            cursorY += chartH + 10f * scale
        }

        // Row 3: Secondary metrics strip (up to 3)
        if (secondary.isNotEmpty()) {
            val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.divider
                strokeWidth = (1.2f * scale).coerceAtLeast(1f)
            }
            canvas.drawLine(contentLeft, cursorY, contentRight, cursorY, divPaint)
            cursorY += 10f * scale

            val colW = contentW / secondary.size
            val sLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.textMuted
                textSize = 12.5f * scale
                typeface = sansNormal
            }
            val sValPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.textPrimary
                textSize = 17.5f * scale
                typeface = sansBold
            }
            secondary.forEachIndexed { idx, metric ->
                val x = contentLeft + idx * colW
                val maxColW = (colW - 8f * scale).coerceAtLeast(30f)
                canvas.drawText(ellipsize(metric.label, sLabelPaint, maxColW), x, cursorY + 12f * scale, sLabelPaint)
                canvas.drawText(ellipsize(metric.value, sValPaint, maxColW), x, cursorY + 33f * scale, sValPaint)
            }
            cursorY += 42f * scale
        }

        // Row 4: Optional estimated power strip (Sayers peak + relative power when athlete mass is set)
        if (estimatedPower.isNotEmpty()) {
            val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.divider
                strokeWidth = (1.2f * scale).coerceAtLeast(1f)
            }
            canvas.drawLine(contentLeft, cursorY, contentRight, cursorY, divPaint)
            cursorY += 10f * scale

            val colW = contentW / estimatedPower.size
            val eLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.textMuted
                textSize = 12.5f * scale
                typeface = sansNormal
            }
            val eValPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.textPrimary
                textSize = 17.5f * scale
                typeface = sansBold
            }
            estimatedPower.forEachIndexed { idx, metric ->
                val x = contentLeft + idx * colW
                val maxColW = (colW - 8f * scale).coerceAtLeast(30f)
                canvas.drawText(ellipsize(metric.label, eLabelPaint, maxColW), x, cursorY + 12f * scale, eLabelPaint)
                canvas.drawText(ellipsize(metric.value, eValPaint, maxColW), x, cursorY + 33f * scale, eValPaint)
            }
            cursorY += 42f * scale
        }

        // Optional bottom row: Athlete name (left) and/or discrete Sayers estimate note (right)
        if (athleteText != null || estimatedNote != null) {
            val rowBaseline = min(cursorY + 12f * scale, rf.bottom - 10f * scale)
            var noteReservedW = 0f
            if (estimatedNote != null) {
                val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.textMuted
                    textSize = 11.5f * scale
                    typeface = sansNormal
                    textAlign = Paint.Align.RIGHT
                }
                val fittedNote = ellipsize(estimatedNote, notePaint, contentW * 0.6f)
                canvas.drawText(fittedNote, contentRight, rowBaseline, notePaint)
                noteReservedW = notePaint.measureText(fittedNote) + 10f * scale
            }
            if (athleteText != null) {
                val athletePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.textSecondary
                    textSize = 13.5f * scale
                    typeface = sansNormal
                }
                val maxAthleteW = (contentW - noteReservedW).coerceAtLeast(36f)
                canvas.drawText(
                    ellipsize(athleteText, athletePaint, maxAthleteW),
                    contentLeft,
                    rowBaseline,
                    athletePaint,
                )
            }
        }
    }

    private fun drawPerRepTelemetryChart(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        spec: VideoHudSpec,
        frameState: VideoHudFrameState,
        palette: VideoHudPalette,
        scale: Float,
    ) {
        val box = RectF(left, top, right, bottom)
        val radius = 12f * scale
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.chartBg
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.chartBorder
            style = Paint.Style.STROKE
            strokeWidth = (1.4f * scale).coerceAtLeast(1f)
        }
        canvas.drawRoundRect(box, radius, radius, bgPaint)
        canvas.drawRoundRect(box, radius, radius, borderPaint)

        val padX = 10f * scale
        val plotLeft = left + padX
        val plotRight = right - padX
        val plotTop = top + 22f * scale
        val plotBottom = bottom - 8f * scale
        val plotW = (plotRight - plotLeft).coerceAtLeast(24f)
        val plotH = (plotBottom - plotTop).coerceAtLeast(20f)

        // Header legend: ● VEL  ● POS (left) and active REP X (right)
        val dotR = 3.5f * scale
        val legendY = top + 12f * scale
        val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            textSize = 10.5f * scale
            typeface = sansBold
        }
        val lfm = legendPaint.fontMetrics
        val legendBase = legendY - (lfm.ascent + lfm.descent) / 2f
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        dotPaint.color = palette.chartVelLine
        canvas.drawCircle(plotLeft + dotR, legendY, dotR, dotPaint)
        val velTextX = plotLeft + dotR * 2f + 4f * scale
        canvas.drawText("VEL", velTextX, legendBase, legendPaint)

        val posDotX = velTextX + legendPaint.measureText("VEL") + 12f * scale + dotR
        dotPaint.color = palette.chartPosLine
        canvas.drawCircle(posDotX, legendY, dotR, dotPaint)
        canvas.drawText("POS", posDotX + dotR + 4f * scale, legendBase, legendPaint)

        frameState.chartRepNumber?.let { repNum ->
            val repPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.textMuted
                textSize = 10.5f * scale
                typeface = sansBold
                textAlign = Paint.Align.RIGHT
            }
            canvas.drawText("${spec.repPrefix} $repNum", plotRight, legendBase, repPaint)
        }

        // Fixed set velocity bounds so fatigue across reps is clearly visible
        val maxVel = spec.chartMaxVelMps.coerceAtLeast(0.2)
        val minVel = spec.chartMinVelMps.coerceAtMost(-0.1)
        val velSpan = (maxVel - minVel).coerceAtLeast(0.3)
        val zeroY = plotTop + ((maxVel / velSpan).toFloat().coerceIn(0f, 1f)) * plotH

        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.chartGrid
            strokeWidth = (1f * scale).coerceAtLeast(1f)
        }
        canvas.drawLine(plotLeft, plotTop, plotRight, plotTop, gridPaint)
        canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, gridPaint)

        val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.chartZeroLine
            style = Paint.Style.STROKE
            strokeWidth = (1.1f * scale).coerceAtLeast(1f)
            pathEffect = DashPathEffect(floatArrayOf(6f * scale, 5f * scale), 0f)
        }
        canvas.drawLine(plotLeft, zeroY, plotRight, zeroY, zeroPaint)

        if (spec.chartBestPeakVelMps > 0.05) {
            val refY = plotTop + (((maxVel - spec.chartBestPeakVelMps) / velSpan).toFloat().coerceIn(0f, 1f)) * plotH
            val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.chartRefLine
                style = Paint.Style.STROKE
                strokeWidth = (1.2f * scale).coerceAtLeast(1f)
                pathEffect = DashPathEffect(floatArrayOf(8f * scale, 5f * scale), 0f)
            }
            canvas.drawLine(plotLeft, refY, plotRight, refY, refPaint)
        }

        val zeroLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textMuted
            textSize = 9.5f * scale
            typeface = sansNormal
        }
        canvas.drawText("0", plotLeft + 2f * scale, zeroY - 3f * scale, zeroLabelPaint)

        val points = frameState.chartPoints
        if (points.size < 2 || frameState.chartProgressBucket < 0) return
        val maxProgress = frameState.chartProgressBucket.toFloat() / CHART_PROGRESS_BUCKETS.toFloat()
        val visible = points.filter { it.progress <= maxProgress + 0.005f }
            .ifEmpty { listOf(points.first()) }
        if (visible.isEmpty()) return

        fun xFor(p: VideoHudRepTelemetryPoint): Float = plotLeft + p.progress.coerceIn(0f, 1f) * plotW
        fun velYFor(p: VideoHudRepTelemetryPoint): Float =
            plotTop + (((maxVel - p.velocityMps) / velSpan).toFloat().coerceIn(0f, 1f)) * plotH
        fun posYFor(p: VideoHudRepTelemetryPoint): Float =
            plotBottom - p.normalizedPosition.coerceIn(0f, 1f) * plotH

        val velPath = Path()
        val fillPath = Path()
        val posPath = Path()

        val first = visible.first()
        val firstX = xFor(first)
        val firstVy = velYFor(first)
        val firstPy = posYFor(first)
        velPath.moveTo(firstX, firstVy)
        posPath.moveTo(firstX, firstPy)
        fillPath.moveTo(firstX, zeroY)
        fillPath.lineTo(firstX, firstVy)

        for (i in 1 until visible.size) {
            val pt = visible[i]
            val x = xFor(pt)
            val vy = velYFor(pt)
            val py = posYFor(pt)
            velPath.lineTo(x, vy)
            posPath.lineTo(x, py)
            fillPath.lineTo(x, vy)
        }
        val last = visible.last()
        val lastX = xFor(last)
        val lastVy = velYFor(last)
        val lastPy = posYFor(last)
        fillPath.lineTo(lastX, zeroY)
        fillPath.close()

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.chartVelFill
            style = Paint.Style.FILL
        }
        canvas.drawPath(fillPath, fillPaint)

        val posPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.chartPosLine
            style = Paint.Style.STROKE
            strokeWidth = (2.0f * scale).coerceAtLeast(1.4f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(posPath, posPaint)

        val velPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.chartVelLine
            style = Paint.Style.STROKE
            strokeWidth = (2.8f * scale).coerceAtLeast(2f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(velPath, velPaint)

        // Vertical playhead & live endpoint dots
        val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.chartZeroLine
            strokeWidth = (1f * scale).coerceAtLeast(1f)
        }
        canvas.drawLine(lastX, plotTop, lastX, plotBottom, playheadPaint)

        dotPaint.color = palette.chartPosLine
        canvas.drawCircle(lastX, lastPy, 3.6f * scale, dotPaint)

        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.chartDotHalo
            style = Paint.Style.STROKE
            strokeWidth = (1.6f * scale).coerceAtLeast(1f)
        }
        dotPaint.color = palette.chartVelLine
        canvas.drawCircle(lastX, lastVy, 4.8f * scale, dotPaint)
        canvas.drawCircle(lastX, lastVy, 4.8f * scale, outlinePaint)
    }

    private fun fitTextSize(text: String, paint: Paint, maxWidth: Float, minSize: Float) {
        while (paint.textSize > minSize && paint.measureText(text) > maxWidth) {
            paint.textSize -= 2f
        }
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return text.substring(0, end).trimEnd() + "…"
    }
}
