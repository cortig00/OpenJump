package com.openjump.app.ui.result

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.openjump.app.R
import com.openjump.app.encoder.EncoderAnalysis
import com.openjump.app.encoder.EncoderMetricKey
import com.openjump.app.protocol.EventMark
import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.MetricValue
import com.openjump.app.protocol.StoredEvent
import com.openjump.app.settings.UnitProfile
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ShareCardAspect(val widthPx: Int, val heightPx: Int) {
    PORTRAIT_4_5(1080, 1350),
    STORY_9_16(1080, 1920),
}

data class ShareCardFrameRequest(
    val label: String,
    val ptsUs: Long,
)

data class ShareCardPhotoFrame(
    val label: String,
    val bitmap: Bitmap,
)

data class ShareCardRepetitionBar(
    val repNumber: Int,
    val value: Double,
    val displayValue: String,
)

data class ShareCardOptions(
    val aspect: ShareCardAspect = ShareCardAspect.PORTRAIT_4_5,
    val lightTheme: Boolean = false,
    val includePhotos: Boolean = true,
    val includeRepetitionChart: Boolean = true,
    val includeSecondaryMetrics: Boolean = true,
    val includeEstimatedPower: Boolean = true,
    val includeAthleteName: Boolean = false,
    val includeBranding: Boolean = true,
)

data class JumpResultShareMetric(
    val label: String,
    val value: String,
)

data class JumpResultShareCardData(
    val protocolTitle: String,
    val methodLabel: String,
    val primaryLabel: String,
    val primaryValue: String,
    val secondaryMetrics: List<JumpResultShareMetric>,
    val dateLabel: String?,
    val athleteName: String?,
    val footerLabel: String,
    val estimatedPowerMetrics: List<JumpResultShareMetric> = emptyList(),
    val estimatedPowerNote: String? = null,
    val repetitionChartTitle: String? = null,
    val repetitionBars: List<ShareCardRepetitionBar> = emptyList(),
    val repetitionChartLegendBest: String? = null,
    val repetitionChartLegendFatigue: String? = null,
) {
    /** Returns a copy honoring the user's privacy choice regarding the athlete's name. */
    fun withPrivacy(includeAthleteName: Boolean): JumpResultShareCardData =
        if (includeAthleteName) this else copy(athleteName = null)

    /** Returns a copy honoring all share-studio customization toggles. */
    fun applyOptions(options: ShareCardOptions): JumpResultShareCardData = copy(
        athleteName = if (options.includeAthleteName) athleteName else null,
        secondaryMetrics = if (options.includeSecondaryMetrics) secondaryMetrics else emptyList(),
        estimatedPowerMetrics = if (options.includeEstimatedPower) estimatedPowerMetrics else emptyList(),
        estimatedPowerNote = if (options.includeEstimatedPower) estimatedPowerNote else null,
        repetitionBars = if (options.includeRepetitionChart) repetitionBars else emptyList(),
    )
}

/**
 * Extracts only the two headline Sayers estimated power metrics (peak W and relative W/kg)
 * when available for a jump with athlete body mass configured.
 */
internal fun extractJumpEstimatedPowerMetrics(
    estimatedMetrics: List<MetricValue>,
    peakPowerLabel: String,
    relativePowerLabel: String,
    locale: Locale,
    unitSystem: UnitProfile,
): List<JumpResultShareMetric> {
    val peak = estimatedMetrics.firstOrNull { it.key == MetricKey.ESTIMATED_PEAK_POWER_SAYERS_W }
    val relative = estimatedMetrics.firstOrNull { it.key == MetricKey.ESTIMATED_RELATIVE_PEAK_POWER_SAYERS_W_PER_KG }
    return buildList(2) {
        peak?.let {
            add(JumpResultShareMetric(peakPowerLabel, ResultText.formatMetric(it, locale, unitSystem)))
        }
        relative?.let {
            add(JumpResultShareMetric(relativePowerLabel, ResultText.formatMetric(it, locale, unitSystem)))
        }
    }
}

/** Selects the first and last distinct marked frames for jump cards (e.g. Takeoff + Landing). */
internal fun selectJumpShareFrameRequests(
    marks: List<EventMark>,
    labelFor: (EventMark) -> String,
): List<ShareCardFrameRequest> {
    if (marks.isEmpty()) return emptyList()
    val first = marks.first()
    val last = marks.last()
    return if (first.ptsUs == last.ptsUs) {
        listOf(ShareCardFrameRequest(labelFor(first), first.ptsUs))
    } else {
        listOf(
            ShareCardFrameRequest(labelFor(first), first.ptsUs),
            ShareCardFrameRequest(labelFor(last), last.ptsUs),
        )
    }
}

/** Selects the first and last distinct stored event frames for persisted jump cards. */
internal fun selectStoredJumpShareFrameRequests(
    events: List<StoredEvent>,
    labelFor: (StoredEvent) -> String,
): List<ShareCardFrameRequest> {
    val ordered = events.sortedBy { it.ptsUs }
    if (ordered.isEmpty()) return emptyList()
    val first = ordered.first()
    val last = ordered.last()
    return if (first.ptsUs == last.ptsUs) {
        listOf(ShareCardFrameRequest(labelFor(first), first.ptsUs))
    } else {
        listOf(
            ShareCardFrameRequest(labelFor(first), first.ptsUs),
            ShareCardFrameRequest(labelFor(last), last.ptsUs),
        )
    }
}

/** Selects start and lockout frames of the best valid repetition (or first repetition) for encoder cards. */
internal fun selectEncoderShareFrameRequests(
    analysis: EncoderAnalysis,
    startLabel: String,
    endLabel: String,
): List<ShareCardFrameRequest> {
    val samples = analysis.samples
    if (samples.isEmpty()) return emptyList()
    val bestRep = analysis.validRepetitions
        .maxByOrNull { it.metric(EncoderMetricKey.MCV)?.value ?: Double.NEGATIVE_INFINITY }
        ?: analysis.repetitions.firstOrNull()
        ?: return emptyList()
    val startIdx = bestRep.concentricPhase.startSample.coerceIn(0, samples.lastIndex)
    val endIdx = bestRep.concentricPhase.endSample.coerceIn(0, samples.lastIndex)
    val startPts = samples[startIdx].sourcePtsUs
    val endPts = samples[endIdx].sourcePtsUs
    return if (startPts == endPts) {
        listOf(ShareCardFrameRequest(startLabel, startPts))
    } else {
        listOf(
            ShareCardFrameRequest(startLabel, startPts),
            ShareCardFrameRequest(endLabel, endPts),
        )
    }
}

object ShareCardFrameExtractor {

    private const val MAX_EXTRACTED_SIDE_PX = 640

    suspend fun extract(
        context: Context,
        videoUriString: String?,
        requests: List<ShareCardFrameRequest>,
    ): List<ShareCardPhotoFrame> {
        if (videoUriString.isNullOrBlank() || requests.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            val extracted = mutableListOf<ShareCardPhotoFrame>()
            try {
                val uri = Uri.parse(videoUriString)
                if (uri.scheme == "file") {
                    val path = uri.path ?: return@withContext emptyList()
                    if (!File(path).isFile) return@withContext emptyList()
                    retriever.setDataSource(path)
                } else {
                    retriever.setDataSource(context, uri)
                }
                for (request in requests.take(2)) {
                    val bmp = extractScaledFrame(retriever, request.ptsUs.coerceAtLeast(0L))
                    if (bmp != null) {
                        extracted += ShareCardPhotoFrame(request.label, bmp)
                    }
                }
                extracted
            } catch (_: Exception) {
                extracted.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
                emptyList()
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    private fun extractScaledFrame(retriever: MediaMetadataRetriever, ptsUs: Long): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val scaled = runCatching {
                retriever.getScaledFrameAtTime(
                    ptsUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    MAX_EXTRACTED_SIDE_PX,
                    MAX_EXTRACTED_SIDE_PX,
                )
            }.getOrNull()
            if (scaled != null) return scaled
        }
        val raw = runCatching {
            retriever.getFrameAtTime(ptsUs, MediaMetadataRetriever.OPTION_CLOSEST)
        }.getOrNull() ?: return null
        val maxDim = max(raw.width, raw.height)
        if (maxDim <= MAX_EXTRACTED_SIDE_PX) return raw
        val scale = MAX_EXTRACTED_SIDE_PX.toFloat() / maxDim.toFloat()
        val resized = Bitmap.createScaledBitmap(
            raw,
            (raw.width * scale).roundToInt().coerceAtLeast(1),
            (raw.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        if (resized !== raw) raw.recycle()
        return resized
    }
}

private data class CardPalette(
    val bgTop: Int,
    val bgMid: Int,
    val bgBottom: Int,
    val accentStart: Int,
    val accentEnd: Int,
    val brand: Int,
    val title: Int,
    val muted: Int,
    val heroFill: Int,
    val heroStroke: Int,
    val heroLabel: Int,
    val heroValue: Int,
    val cardFill: Int,
    val cardStroke: Int,
    val cardValue: Int,
    val divider: Int,
    val gridLine: Int,
    val refLine: Int,
    val barBest: Int,
    val barGood: Int,
    val barMid: Int,
    val barFatigue: Int,
    val barValueBest: Int,
    val barValueNormal: Int,
    val photoBorder: Int,
)

private val DarkCardPalette = CardPalette(
    bgTop = 0xFF0B1412.toInt(),
    bgMid = 0xFF11211C.toInt(),
    bgBottom = 0xFF09100E.toInt(),
    accentStart = 0xFF00A88A.toInt(),
    accentEnd = 0xFF67D1B8.toInt(),
    brand = 0xFF67D1B8.toInt(),
    title = 0xFFF1F7F4.toInt(),
    muted = 0xFF98ADA4.toInt(),
    heroFill = 0xFF162923.toInt(),
    heroStroke = 0xFF2B5246.toInt(),
    heroLabel = 0xFF67D1B8.toInt(),
    heroValue = 0xFFFFFFFF.toInt(),
    cardFill = 0xFF13201C.toInt(),
    cardStroke = 0xFF243A32.toInt(),
    cardValue = 0xFFF1F7F4.toInt(),
    divider = 0xFF243A32.toInt(),
    gridLine = 0x96263E35.toInt(),
    refLine = 0x7867D1B8.toInt(),
    barBest = 0xFF67D1B8.toInt(),
    barGood = 0xFF2A947C.toInt(),
    barMid = 0xFF40B094.toInt(),
    barFatigue = 0xFFF2C76E.toInt(),
    barValueBest = 0xFFFFFFFF.toInt(),
    barValueNormal = 0xFFD2E0DA.toInt(),
    photoBorder = 0xFF2D4A3F.toInt(),
)

private val LightCardPalette = CardPalette(
    bgTop = 0xFFF6FAF8.toInt(),
    bgMid = 0xFFECF4F0.toInt(),
    bgBottom = 0xFFF2F7F4.toInt(),
    accentStart = 0xFF006B5A.toInt(),
    accentEnd = 0xFF19A98B.toInt(),
    brand = 0xFF006B5A.toInt(),
    title = 0xFF141E1A.toInt(),
    muted = 0xFF4B5D56.toInt(),
    heroFill = 0xFFDFF4EC.toInt(),
    heroStroke = 0xFF9ED4C2.toInt(),
    heroLabel = 0xFF005A4B.toInt(),
    heroValue = 0xFF0C241E.toInt(),
    cardFill = 0xFFFFFFFF.toInt(),
    cardStroke = 0xFFD0DFD8.toInt(),
    cardValue = 0xFF141E1A.toInt(),
    divider = 0xFFCBDAD3.toInt(),
    gridLine = 0xA0CEDED7.toInt(),
    refLine = 0x8C006B5A.toInt(),
    barBest = 0xFF006B5A.toInt(),
    barGood = 0xFF2B8E7A.toInt(),
    barMid = 0xFF4DA895.toInt(),
    barFatigue = 0xFFC87A08.toInt(),
    barValueBest = 0xFF004D40.toInt(),
    barValueNormal = 0xFF2B3D36.toInt(),
    photoBorder = 0xFFB8CEC4.toInt(),
)

object JumpResultShareCardRenderer {

    const val CARD_WIDTH = 1080
    const val CARD_HEIGHT = 1350
    private const val MAX_SECONDARY_METRICS = 4
    private const val MAX_ESTIMATED_POWER_METRICS = 2

    fun renderPngBytes(
        data: JumpResultShareCardData,
        options: ShareCardOptions = ShareCardOptions(includePhotos = false),
        photos: List<ShareCardPhotoFrame> = emptyList(),
        logoBitmap: Bitmap? = null,
    ): ByteArray {
        val bitmap = renderBitmap(
            data = data,
            options = options,
            photos = photos,
            logoBitmap = logoBitmap,
            scale = 1f,
        )
        try {
            val out = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "Unable to compress result share card PNG."
            }
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    fun renderPreviewBitmap(
        data: JumpResultShareCardData,
        options: ShareCardOptions,
        photos: List<ShareCardPhotoFrame>,
        previewWidthPx: Int = 540,
        logoBitmap: Bitmap? = null,
    ): Bitmap {
        val scale = (previewWidthPx.toFloat() / options.aspect.widthPx.toFloat()).coerceIn(0.25f, 1f)
        return renderBitmap(
            data = data,
            options = options,
            photos = photos,
            logoBitmap = logoBitmap,
            scale = scale,
        )
    }

    internal fun splitValueAndUnit(formatted: String): Pair<String, String?> {
        val trimmed = formatted.trim()
        val spaceIdx = trimmed.indexOf(' ')
        if (spaceIdx <= 0 || spaceIdx >= trimmed.lastIndex) return trimmed to null
        val numberPart = trimmed.substring(0, spaceIdx)
        val unitPart = trimmed.substring(spaceIdx + 1).trim()
        val looksNumeric = numberPart.any { it.isDigit() } &&
            numberPart.all { it.isDigit() || it == '.' || it == ',' || it == '-' || it == '+' }
        return if (looksNumeric && unitPart.isNotEmpty() && unitPart.length <= 6) {
            numberPart to unitPart
        } else {
            trimmed to null
        }
    }

    private fun renderBitmap(
        data: JumpResultShareCardData,
        options: ShareCardOptions,
        photos: List<ShareCardPhotoFrame>,
        logoBitmap: Bitmap?,
        scale: Float,
    ): Bitmap {
        val widthPx = (options.aspect.widthPx * scale).roundToInt().coerceAtLeast(1)
        val heightPx = (options.aspect.heightPx * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (scale != 1f) canvas.scale(scale, scale)
        val effectiveData = data.applyOptions(options)
        val effectivePhotos = if (options.includePhotos) photos.take(2) else emptyList()
        drawCard(canvas, effectiveData, options, effectivePhotos, logoBitmap)
        return bitmap
    }

    private fun drawCard(
        canvas: Canvas,
        data: JumpResultShareCardData,
        options: ShareCardOptions,
        photos: List<ShareCardPhotoFrame>,
        logoBitmap: Bitmap?,
    ) {
        val palette = if (options.lightTheme) LightCardPalette else DarkCardPalette
        val w = options.aspect.widthPx.toFloat()
        val h = options.aspect.heightPx.toFloat()
        val isStory = options.aspect == ShareCardAspect.STORY_9_16
        val hasPhotos = photos.isNotEmpty()
        val hasChart = data.repetitionBars.isNotEmpty()
        val shownEstimated = data.estimatedPowerMetrics.take(MAX_ESTIMATED_POWER_METRICS)
        val shownMetrics = data.secondaryMetrics.take(MAX_SECONDARY_METRICS) + shownEstimated
        val estimatedNote = data.estimatedPowerNote?.takeIf { shownEstimated.isNotEmpty() && it.isNotBlank() }

        // Background vertical gradient
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, w * 0.45f, h,
                intArrayOf(palette.bgTop, palette.bgMid, palette.bgBottom),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Top accent stripe
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, w, 0f,
                palette.accentStart,
                palette.accentEnd,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w, 14f, accentPaint)

        val margin = 68f
        val contentWidth = w - 2 * margin
        var cursorY = if (isStory) 118f else 88f

        // Header: OpenJump official icon + brand mark (optional) + date
        if (options.includeBranding || !data.dateLabel.isNullOrBlank()) {
            if (options.includeBranding) {
                var brandX = margin
                if (logoBitmap != null && !logoBitmap.isRecycled) {
                    val iconSize = 44f
                    val iconTop = cursorY - 34f
                    val iconRect = RectF(brandX, iconTop, brandX + iconSize, iconTop + iconSize)
                    val clip = Path().apply {
                        addRoundRect(iconRect, 12f, 12f, Path.Direction.CW)
                    }
                    canvas.save()
                    canvas.clipPath(clip)
                    canvas.drawBitmap(
                        logoBitmap,
                        Rect(0, 0, logoBitmap.width, logoBitmap.height),
                        iconRect,
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                    )
                    canvas.restore()
                    brandX += iconSize + 14f
                }
                val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.brand
                    textSize = 34f
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                    letterSpacing = 0.08f
                }
                canvas.drawText("OPENJUMP", brandX, cursorY, brandPaint)
            }
            data.dateLabel?.takeIf { it.isNotBlank() }?.let { date ->
                val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.muted
                    textSize = 28f
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                    textAlign = Paint.Align.RIGHT
                }
                canvas.drawText(date, w - margin, cursorY - 2f, datePaint)
            }
            cursorY += if (isStory) 88f else 74f
        } else {
            cursorY += 28f
        }

        // Title (auto-fit long protocol names cleanly before ellipsizing) & method/athlete subtitle
        val protocolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.title
            textSize = if (isStory) 62f else 56f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        fitTextSize(data.protocolTitle, protocolPaint, contentWidth, minSize = 38f)
        canvas.drawText(ellipsize(data.protocolTitle, protocolPaint, contentWidth), margin, cursorY, protocolPaint)
        cursorY += 48f

        val subtitleText = buildString {
            append(data.methodLabel)
            data.athleteName?.takeIf { it.isNotBlank() }?.let {
                append("  ·  ")
                append(it)
            }
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.muted
            textSize = 30f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        canvas.drawText(ellipsize(subtitleText, subtitlePaint, contentWidth), margin, cursorY, subtitlePaint)
        cursorY += if (isStory) 48f else 36f

        val footerReserve = if (options.includeBranding) 128f else 64f
        val availableHeight = (h - footerReserve - cursorY).coerceAtLeast(360f)
        val gap = 24f

        val hasVisualBlock = hasPhotos || hasChart
        val metricRows = if (shownMetrics.isEmpty()) 0 else (shownMetrics.size + 1) / 2
        val metricGap = if (metricRows >= 3) 18f else gap
        val metricRowH = when {
            metricRows == 0 -> 0f
            metricRows >= 3 && hasVisualBlock && !isStory -> 114f
            metricRows >= 3 -> 152f
            hasVisualBlock && !isStory -> 146f
            isStory -> 184f
            else -> 188f
        }
        val estimatedNoteH = if (estimatedNote != null) 34f else 0f
        val metricsBlockH = if (metricRows == 0) {
            0f
        } else {
            metricRows * metricRowH + (metricRows - 1) * metricGap + estimatedNoteH
        }

        val heroHeight = when {
            hasVisualBlock && !isStory && metricRows >= 3 -> 196f
            hasVisualBlock && !isStory -> if (metricRows == 2) 216f else 256f
            hasVisualBlock && isStory -> 286f
            isStory -> 360f
            else -> 320f
        }

        val photoHeight = if (hasPhotos) {
            val extraGaps = gap + if (metricRows > 0) gap else 0f
            (availableHeight - heroHeight - metricsBlockH - extraGaps).coerceIn(220f, if (isStory) 680f else 420f)
        } else {
            0f
        }

        val chartHeight = if (hasChart) {
            val extraGap = if (metricRows > 0) gap else 0f
            (availableHeight - heroHeight - gap - metricsBlockH - extraGap)
                .coerceIn(260f, if (isStory) 660f else 420f)
        } else {
            0f
        }

        // Compute total body height and optically center vertically when extra space exists (e.g. 9:16 Story)
        var totalBodyH = heroHeight
        if (hasPhotos) totalBodyH += photoHeight + gap
        if (hasChart) totalBodyH += chartHeight + gap
        if (metricRows > 0) totalBodyH += metricsBlockH + gap
        val verticalSlack = (availableHeight - totalBodyH).coerceAtLeast(0f)
        if (verticalSlack > 12f) {
            cursorY += verticalSlack * 0.42f
        }

        // Layout order:
        // - For Jumps with key-frame photos: Photos -> Hero -> Secondary metrics
        // - For Encoder with repetition chart: Hero -> Repetition Bar Chart -> Secondary metrics
        if (hasPhotos) {
            drawPhotoStrip(
                canvas = canvas,
                photos = photos,
                palette = palette,
                left = margin,
                top = cursorY,
                width = contentWidth,
                height = photoHeight,
                gap = gap,
            )
            cursorY += photoHeight + gap
        }

        // Hero metric surface
        drawHeroMetric(
            canvas = canvas,
            data = data,
            palette = palette,
            left = margin,
            top = cursorY,
            width = contentWidth,
            height = heroHeight,
        )
        cursorY += heroHeight + gap

        // Encoder VBT repetition velocity bar chart
        if (hasChart) {
            drawRepetitionChart(
                canvas = canvas,
                title = data.repetitionChartTitle.orEmpty(),
                legendBest = data.repetitionChartLegendBest,
                legendFatigue = data.repetitionChartLegendFatigue,
                bars = data.repetitionBars,
                palette = palette,
                left = margin,
                top = cursorY,
                width = contentWidth,
                height = chartHeight,
            )
            cursorY += chartHeight + gap
        }

        // Secondary metrics centered grid (odd trailing card spans full width for symmetry)
        if (shownMetrics.isNotEmpty()) {
            val halfWidth = (contentWidth - gap) / 2f
            val cellFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.cardFill }
            val cellStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.cardStroke
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
            val compactCell = metricRowH < 160f
            val ultraCompactCell = metricRowH < 126f
            val metricLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.muted
                textSize = when {
                    ultraCompactCell -> 22f
                    compactCell -> 25f
                    else -> 28f
                }
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                textAlign = Paint.Align.CENTER
            }
            val metricValueSize = when {
                ultraCompactCell -> 38f
                compactCell -> 46f
                else -> 54f
            }
            val metricValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.cardValue
                textSize = metricValueSize
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val isOddCount = shownMetrics.size % 2 == 1
            shownMetrics.forEachIndexed { index, metric ->
                val isFullWidthLast = isOddCount && index == shownMetrics.lastIndex
                val col = if (isFullWidthLast) 0 else index % 2
                val row = index / 2
                val cellWidth = if (isFullWidthLast) contentWidth else halfWidth
                val left = margin + col * (halfWidth + gap)
                val top = cursorY + row * (metricRowH + metricGap)
                val rect = RectF(left, top, left + cellWidth, top + metricRowH)
                canvas.drawRoundRect(rect, 26f, 26f, cellFill)
                canvas.drawRoundRect(rect, 26f, 26f, cellStroke)

                val centerX = rect.centerX()
                canvas.drawText(
                    ellipsize(metric.label, metricLabelPaint, cellWidth - 48f),
                    centerX,
                    top + metricRowH * 0.37f,
                    metricLabelPaint,
                )
                metricValuePaint.textSize = metricValueSize
                fitTextSize(metric.value, metricValuePaint, cellWidth - 48f, minSize = 28f)
                canvas.drawText(
                    metric.value,
                    centerX,
                    top + metricRowH * 0.77f,
                    metricValuePaint,
                )
            }
            if (estimatedNote != null) {
                val noteY = cursorY + metricRows * metricRowH + (metricRows - 1) * metricGap + 28f
                val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.muted
                    textSize = 22f
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText(
                    ellipsize(estimatedNote, notePaint, contentWidth),
                    w / 2f,
                    noteY,
                    notePaint,
                )
            }
        }

        // Optional footer branding
        if (options.includeBranding) {
            val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.divider
                strokeWidth = 2f
            }
            canvas.drawLine(margin, h - 108f, w - margin, h - 108f, dividerPaint)

            val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.muted
                textSize = 26f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(
                ellipsize(data.footerLabel, footerPaint, contentWidth),
                w / 2f,
                h - 54f,
                footerPaint,
            )
        }
    }

    private fun drawHeroMetric(
        canvas: Canvas,
        data: JumpResultShareCardData,
        palette: CardPalette,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
    ) {
        val heroRect = RectF(left, top, left + width, top + height)
        val heroFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                heroRect.left,
                heroRect.top,
                heroRect.right,
                heroRect.bottom,
                palette.heroFill,
                palette.cardFill,
                Shader.TileMode.CLAMP,
            )
        }
        val heroStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.heroStroke
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRoundRect(heroRect, 34f, 34f, heroFill)
        canvas.drawRoundRect(heroRect, 34f, 34f, heroStroke)

        val heroLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.heroLabel
            textSize = if (height < 250f) 28f else 32f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.06f
        }
        canvas.drawText(
            ellipsize(data.primaryLabel.uppercase(), heroLabelPaint, heroRect.width() - 64f),
            heroRect.centerX(),
            heroRect.top + height * 0.30f,
            heroLabelPaint,
        )

        val (numPart, unitPart) = splitValueAndUnit(data.primaryValue)
        val baselineY = heroRect.top + height * 0.77f
        val maxValW = heroRect.width() - 80f

        if (unitPart != null) {
            val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.heroValue
                textSize = if (height < 250f) 110f else 138f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.heroLabel
                textSize = numPaint.textSize * 0.56f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val unitText = " $unitPart"
            while (numPaint.textSize > 62f && (numPaint.measureText(numPart) + unitPaint.measureText(unitText)) > maxValW) {
                numPaint.textSize -= 4f
                unitPaint.textSize = numPaint.textSize * 0.56f
            }
            val numW = numPaint.measureText(numPart)
            val totalW = numW + unitPaint.measureText(unitText)
            val startX = heroRect.centerX() - totalW / 2f
            canvas.drawText(numPart, startX, baselineY, numPaint)
            canvas.drawText(unitText, startX + numW, baselineY, unitPaint)
        } else {
            val heroValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.heroValue
                textSize = if (height < 250f) 104f else 134f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            fitTextSize(data.primaryValue, heroValuePaint, maxValW, minSize = 62f)
            canvas.drawText(data.primaryValue, heroRect.centerX(), baselineY, heroValuePaint)
        }
    }

    private fun drawRepetitionChart(
        canvas: Canvas,
        title: String,
        legendBest: String?,
        legendFatigue: String?,
        bars: List<ShareCardRepetitionBar>,
        palette: CardPalette,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
    ) {
        if (bars.isEmpty()) return
        val cardRect = RectF(left, top, left + width, top + height)
        val cardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.cardFill }
        val cardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.cardStroke
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRoundRect(cardRect, 30f, 30f, cardFill)
        canvas.drawRoundRect(cardRect, 30f, 30f, cardStroke)

        val headerY = top + 48f
        var legendReservedW = 0f
        if (!legendBest.isNullOrBlank() || !legendFatigue.isNullOrBlank()) {
            val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.muted
                textSize = 21f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                textAlign = Paint.Align.RIGHT
            }
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            var rx = left + width - 32f
            val dotR = 7f
            val dotY = headerY - 7f

            if (!legendFatigue.isNullOrBlank()) {
                canvas.drawText(legendFatigue, rx, headerY, legendPaint)
                val tw = legendPaint.measureText(legendFatigue)
                rx -= tw + 10f
                dotPaint.color = palette.barFatigue
                canvas.drawCircle(rx - dotR, dotY, dotR, dotPaint)
                rx -= dotR * 2f + 22f
            }
            if (!legendBest.isNullOrBlank()) {
                canvas.drawText(legendBest, rx, headerY, legendPaint)
                val tw = legendPaint.measureText(legendBest)
                rx -= tw + 10f
                dotPaint.color = palette.barBest
                canvas.drawCircle(rx - dotR, dotY, dotR, dotPaint)
                rx -= dotR * 2f
            }
            legendReservedW = (left + width - rx) + 18f
        }

        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.muted
            textSize = 24f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.05f
        }
        if (title.isNotBlank()) {
            canvas.drawText(
                ellipsize(title.uppercase(), headerPaint, (width - 64f - legendReservedW).coerceAtLeast(160f)),
                left + 32f,
                headerY,
                headerPaint,
            )
        }

        val plotLeft = left + 36f
        val plotRight = left + width - 36f
        val plotTop = top + 96f
        val plotBottom = top + height - 56f
        val plotW = plotRight - plotLeft
        val plotH = (plotBottom - plotTop).coerceAtLeast(80f)

        val bestValue = bars.maxOf { it.value }.coerceAtLeast(0.01)
        val bestIdx = bars.indexOfFirst { it.value == bestValue }.coerceAtLeast(0)
        val scaleMax = bestValue * 1.14

        // Horizontal subtle guide lines
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.gridLine
            strokeWidth = 2f
        }
        for (frac in floatArrayOf(0.25f, 0.5f, 0.75f, 1f)) {
            val gy = plotBottom - plotH * frac
            canvas.drawLine(plotLeft, gy, plotRight, gy, gridPaint)
        }

        // Dashed peak reference line
        val refY = plotBottom - plotH * (bestValue / scaleMax).toFloat()
        val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.refLine
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
        }
        canvas.drawLine(plotLeft, refY, plotRight, refY, refPaint)

        val n = bars.size
        val barGap = when {
            n <= 5 -> 24f
            n <= 10 -> 14f
            else -> 8f
        }
        val barW = min(112f, ((plotW - barGap * (n - 1)) / n).coerceAtLeast(10f))
        val totalBarsW = n * barW + (n - 1) * barGap
        val startX = plotLeft + (plotW - totalBarsW) / 2f

        val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = when {
                n <= 6 -> 26f
                n <= 10 -> 21f
                else -> 17f
            }
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val repPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.muted
            textSize = if (n <= 10) 22f else 18f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        bars.forEachIndexed { index, bar ->
            val bh = (plotH * (bar.value / scaleMax).toFloat()).coerceAtLeast(16f)
            val bx1 = startX + index * (barW + barGap)
            val bx2 = bx1 + barW
            val by1 = plotBottom - bh
            val by2 = plotBottom
            val lossPct = ((bestValue - bar.value) / bestValue) * 100.0

            barPaint.color = when {
                index == bestIdx -> palette.barBest
                lossPct >= 25.0 -> palette.barFatigue
                lossPct >= 15.0 -> palette.barMid
                else -> palette.barGood
            }
            val radius = min(14f, barW / 3f)
            canvas.drawRoundRect(RectF(bx1, by1, bx2, by2), radius, radius, barPaint)

            val showValue = n <= 10 || index == 0 || index == bestIdx || index == n - 1 || index % 2 == 0
            if (showValue) {
                valPaint.color = if (index == bestIdx) palette.barValueBest else palette.barValueNormal
                canvas.drawText(bar.displayValue, bx1 + barW / 2f, by1 - 12f, valPaint)
            }
            val showLabel = n <= 15 || index == 0 || index == n - 1 || (index + 1) % 2 == 0
            if (showLabel) {
                val repLabel = if (n <= 8) "R${bar.repNumber}" else "${bar.repNumber}"
                canvas.drawText(repLabel, bx1 + barW / 2f, plotBottom + 34f, repPaint)
            }
        }
    }

    private fun drawPhotoStrip(
        canvas: Canvas,
        photos: List<ShareCardPhotoFrame>,
        palette: CardPalette,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        gap: Float,
    ) {
        val count = photos.size.coerceAtMost(2)
        if (count == 0) return
        val tileW = if (count == 1) width else (width - gap) / 2f
        val radius = 26f
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.photoBorder
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(195, 9, 16, 14)
        }
        val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#67D1B8")
            textSize = 24f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.05f
        }
        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        photos.take(count).forEachIndexed { index, frame ->
            val tileLeft = left + index * (tileW + gap)
            val rect = RectF(tileLeft, top, tileLeft + tileW, top + height)
            val clipPath = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }

            canvas.save()
            canvas.clipPath(clipPath)
            if (!frame.bitmap.isRecycled && frame.bitmap.width > 0 && frame.bitmap.height > 0) {
                val src = computeCenterCropRect(frame.bitmap.width, frame.bitmap.height, tileW / height)
                canvas.drawBitmap(frame.bitmap, src, rect, bitmapPaint)
            }
            val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, rect.bottom - 96f, 0f, rect.bottom,
                    Color.TRANSPARENT,
                    Color.argb(170, 9, 16, 14),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(rect.left, rect.bottom - 96f, rect.right, rect.bottom, scrimPaint)

            val badgeText = ellipsize(frame.label.uppercase(), pillTextPaint, tileW - 56f)
            val textW = pillTextPaint.measureText(badgeText)
            val pillRect = RectF(
                rect.left + 16f,
                rect.bottom - 58f,
                (rect.left + 16f + textW + 32f).coerceAtMost(rect.right - 16f),
                rect.bottom - 16f,
            )
            canvas.drawRoundRect(pillRect, 14f, 14f, pillBgPaint)
            canvas.drawText(badgeText, pillRect.left + 16f, pillRect.bottom - 12f, pillTextPaint)
            canvas.restore()

            canvas.drawRoundRect(rect, radius, radius, borderPaint)
        }
    }

    internal fun computeCenterCropRect(srcWidth: Int, srcHeight: Int, targetAspect: Float): Rect {
        val srcAspect = srcWidth.toFloat() / srcHeight.toFloat()
        return if (srcAspect > targetAspect) {
            val cropW = (srcHeight * targetAspect).roundToInt().coerceIn(1, srcWidth)
            val offsetX = (srcWidth - cropW) / 2
            Rect(offsetX, 0, offsetX + cropW, srcHeight)
        } else {
            val cropH = (srcWidth / targetAspect).roundToInt().coerceIn(1, srcHeight)
            val offsetY = (srcHeight - cropH) / 2
            Rect(0, offsetY, srcWidth, offsetY + cropH)
        }
    }

    private fun fitTextSize(text: String, paint: Paint, maxWidth: Float, minSize: Float) {
        while (paint.textSize > minSize && paint.measureText(text) > maxWidth) {
            paint.textSize -= 4f
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

object JumpResultShareStager {

    private const val SHARE_DIR = "shared-jump-cards"
    private const val FILE_PREFIX = "openjump_card_"
    private const val MAX_CACHED_CARDS = 5
    private const val MIME_PNG = "image/png"

    suspend fun share(
        context: Context,
        data: JumpResultShareCardData,
        chooserTitle: String,
        options: ShareCardOptions = ShareCardOptions(includePhotos = false),
        photos: List<ShareCardPhotoFrame> = emptyList(),
    ): Boolean {
        val file = withContext(Dispatchers.IO) {
            val logo = if (options.includeBranding) {
                runCatching { BitmapFactory.decodeResource(context.resources, R.drawable.ic_openjump_logo) }.getOrNull()
            } else {
                null
            }
            val bytes = try {
                JumpResultShareCardRenderer.renderPngBytes(data, options, photos, logo)
            } finally {
                logo?.let { if (!it.isRecycled) it.recycle() }
            }
            val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
            pruneOldCards(dir)
            val out = File(dir, "$FILE_PREFIX${System.currentTimeMillis()}.png")
            out.writeBytes(bytes)
            out
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = MIME_PNG
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

    private fun pruneOldCards(dir: File) {
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(FILE_PREFIX) }
            .sortedByDescending(File::lastModified)
            .drop(MAX_CACHED_CARDS - 1)
            .forEach(File::delete)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ResultShareStudioSheet(
    cardData: JumpResultShareCardData,
    videoUri: String?,
    frameRequests: List<ShareCardFrameRequest>,
    sharing: Boolean,
    shareError: Boolean,
    onConfirmShare: (ShareCardOptions, List<ShareCardPhotoFrame>) -> Unit,
    onDismiss: () -> Unit,
    videoExportSource: VideoExportSource? = null,
) {
    val context = LocalContext.current
    val initialLightTheme = !OpenJumpTheme.isDark
    var exportModeVideo by rememberSaveable(videoExportSource != null) { mutableStateOf(false) }
    var videoExportBusy by remember { mutableStateOf(false) }
    var aspect by rememberSaveable { mutableStateOf(ShareCardAspect.PORTRAIT_4_5) }
    var lightTheme by rememberSaveable { mutableStateOf(initialLightTheme) }
    var includePhotos by rememberSaveable { mutableStateOf(true) }
    var includeRepetitionChart by rememberSaveable { mutableStateOf(true) }
    var includeSecondaryMetrics by rememberSaveable { mutableStateOf(true) }
    var includeEstimatedPower by rememberSaveable { mutableStateOf(true) }
    var includeAthleteName by rememberSaveable { mutableStateOf(false) }
    var includeBranding by rememberSaveable { mutableStateOf(true) }

    var loadingPhotos by remember(videoUri, frameRequests) {
        mutableStateOf(!videoUri.isNullOrBlank() && frameRequests.isNotEmpty())
    }
    var extractedPhotos by remember(videoUri, frameRequests) {
        mutableStateOf<List<ShareCardPhotoFrame>>(emptyList())
    }

    LaunchedEffect(videoUri, frameRequests) {
        if (!videoUri.isNullOrBlank() && frameRequests.isNotEmpty()) {
            loadingPhotos = true
            extractedPhotos = ShareCardFrameExtractor.extract(context, videoUri, frameRequests)
            loadingPhotos = false
        } else {
            loadingPhotos = false
            extractedPhotos = emptyList()
        }
    }
    DisposableEffect(extractedPhotos) {
        val current = extractedPhotos
        onDispose {
            current.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        }
    }

    val options = ShareCardOptions(
        aspect = aspect,
        lightTheme = lightTheme,
        includePhotos = includePhotos && extractedPhotos.isNotEmpty(),
        includeRepetitionChart = includeRepetitionChart && cardData.repetitionBars.isNotEmpty(),
        includeSecondaryMetrics = includeSecondaryMetrics,
        includeEstimatedPower = includeEstimatedPower && cardData.estimatedPowerMetrics.isNotEmpty(),
        includeAthleteName = includeAthleteName,
        includeBranding = includeBranding,
    )

    val cardLogoBitmap = remember(context) {
        runCatching { BitmapFactory.decodeResource(context.resources, R.drawable.ic_openjump_logo) }.getOrNull()
    }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(cardData, options, extractedPhotos, exportModeVideo) {
        if (exportModeVideo) return@LaunchedEffect
        val rendered = withContext(Dispatchers.Default) {
            runCatching {
                JumpResultShareCardRenderer.renderPreviewBitmap(
                    data = cardData,
                    options = options,
                    photos = extractedPhotos,
                    previewWidthPx = 720,
                    logoBitmap = cardLogoBitmap,
                )
            }.getOrNull()
        }
        val old = previewBitmap
        previewBitmap = rendered
        if (old != null && old !== rendered && !old.isRecycled) old.recycle()
    }
    DisposableEffect(Unit) {
        onDispose {
            previewBitmap?.let { if (!it.isRecycled) it.recycle() }
            cardLogoBitmap?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target -> target != SheetValue.Hidden },
    )
    ModalBottomSheet(
        onDismissRequest = { if (!sharing && !videoExportBusy) onDismiss() },
        sheetState = sheetState,
        dragHandle = null,
        modifier = Modifier
            .statusBarsPadding()
            .padding(top = Spacing.sm),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenHorizontal)
                .padding(top = Spacing.md, bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.result_share_dialog_title),
                    style = OpenJumpTypes.ScreenTitle,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { if (!sharing && !videoExportBusy) onDismiss() },
                    enabled = !sharing && !videoExportBusy,
                    modifier = Modifier.testTag("share-studio-close"),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.common_cancel),
                    )
                }
            }

            if (videoExportSource != null) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
                ) {
                    FilterChip(
                        selected = !exportModeVideo,
                        onClick = { exportModeVideo = false },
                        enabled = !sharing && !videoExportBusy,
                        label = { Text(stringResource(R.string.share_studio_tab_image)) },
                        modifier = Modifier.testTag("share-studio-tab-image"),
                    )
                    FilterChip(
                        selected = exportModeVideo,
                        onClick = { exportModeVideo = true },
                        enabled = !sharing && !videoExportBusy,
                        label = { Text(stringResource(R.string.share_studio_tab_video)) },
                        modifier = Modifier.testTag("share-studio-tab-video"),
                    )
                }
            }

            if (exportModeVideo && videoExportSource != null) {
                VideoExportStudioSection(
                    source = videoExportSource,
                    lightTheme = lightTheme,
                    onLightThemeChange = { lightTheme = it },
                    onBusyChange = { videoExportBusy = it },
                )
                return@Column
            }

            // Live rendered preview card (full-width for 4:5 Post, adaptive height for 9:16 Story)
            val previewRatio = aspect.widthPx.toFloat() / aspect.heightPx.toFloat()
            val storyPreviewHeight = (LocalConfiguration.current.screenHeightDp * 0.48f).dp.coerceIn(280.dp, 430.dp)
            val previewSurfaceModifier = if (aspect == ShareCardAspect.PORTRAIT_4_5) {
                Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .aspectRatio(previewRatio)
                    .testTag("share-studio-preview")
            } else {
                Modifier
                    .height(storyPreviewHeight)
                    .aspectRatio(previewRatio, matchHeightConstraintsFirst = true)
                    .testTag("share-studio-preview")
            }
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = previewSurfaceModifier,
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
                        val bmp = previewBitmap
                        if (bmp != null && !bmp.isRecycled) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = stringResource(R.string.result_share_dialog_title),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(ShapeTokens.large),
                            )
                        }
                        if (loadingPhotos) {
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

            // Unified customization card (Format, Theme, and content toggles)
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
                    // Format row (4:5 Post vs 9:16 Story)
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        FilterChip(
                            selected = aspect == ShareCardAspect.PORTRAIT_4_5,
                            onClick = { aspect = ShareCardAspect.PORTRAIT_4_5 },
                            enabled = !sharing,
                            label = { Text(stringResource(R.string.share_studio_format_feed)) },
                            modifier = Modifier.testTag("share-studio-format-4-5"),
                        )
                        FilterChip(
                            selected = aspect == ShareCardAspect.STORY_9_16,
                            onClick = { aspect = ShareCardAspect.STORY_9_16 },
                            enabled = !sharing,
                            label = { Text(stringResource(R.string.share_studio_format_story)) },
                            modifier = Modifier.testTag("share-studio-format-9-16"),
                        )
                    }
                    ShareStudioDivider()
                    // Theme row (Dark vs Light)
                    ShareStudioThemeRow(
                        lightTheme = lightTheme,
                        enabled = !sharing,
                        onLightThemeChange = { lightTheme = it },
                        tagPrefix = "share-studio-theme",
                    )
                    if (extractedPhotos.isNotEmpty()) {
                        ShareStudioDivider()
                        ShareStudioSwitchRow(
                            label = stringResource(R.string.share_studio_include_photos),
                            checked = includePhotos,
                            enabled = !sharing,
                            onCheckedChange = { includePhotos = it },
                            modifier = Modifier.testTag("share-studio-toggle-photos"),
                        )
                    }
                    if (cardData.repetitionBars.isNotEmpty()) {
                        ShareStudioDivider()
                        ShareStudioSwitchRow(
                            label = stringResource(R.string.share_studio_include_chart),
                            checked = includeRepetitionChart,
                            enabled = !sharing,
                            onCheckedChange = { includeRepetitionChart = it },
                            modifier = Modifier.testTag("share-studio-toggle-chart"),
                        )
                    }
                    if (cardData.secondaryMetrics.isNotEmpty()) {
                        ShareStudioDivider()
                        ShareStudioSwitchRow(
                            label = stringResource(R.string.share_studio_include_metrics),
                            checked = includeSecondaryMetrics,
                            enabled = !sharing,
                            onCheckedChange = { includeSecondaryMetrics = it },
                            modifier = Modifier.testTag("share-studio-toggle-metrics"),
                        )
                    }
                    if (cardData.estimatedPowerMetrics.isNotEmpty()) {
                        ShareStudioDivider()
                        ShareStudioSwitchRow(
                            label = stringResource(R.string.share_studio_include_estimated_power),
                            checked = includeEstimatedPower,
                            enabled = !sharing,
                            onCheckedChange = { includeEstimatedPower = it },
                            modifier = Modifier.testTag("share-studio-toggle-estimated-power"),
                        )
                    }
                    if (!cardData.athleteName.isNullOrBlank()) {
                        ShareStudioDivider()
                        ShareStudioSwitchRow(
                            label = stringResource(R.string.result_share_include_athlete),
                            checked = includeAthleteName,
                            enabled = !sharing,
                            onCheckedChange = { includeAthleteName = it },
                            modifier = Modifier.testTag("share-studio-toggle-athlete"),
                        )
                    }
                    ShareStudioDivider()
                    ShareStudioSwitchRow(
                        label = stringResource(R.string.share_studio_include_brand),
                        checked = includeBranding,
                        enabled = !sharing,
                        onCheckedChange = { includeBranding = it },
                        modifier = Modifier.testTag("share-studio-toggle-brand"),
                    )
                }
            }

            if (shareError) {
                Text(
                    stringResource(R.string.result_share_error),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = { onConfirmShare(options, extractedPhotos) },
                enabled = !sharing && !loadingPhotos,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("share-studio-confirm"),
            ) {
                Text(stringResource(R.string.result_share_confirm))
            }
        }
    }
}

@Composable
internal fun ShareStudioSwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = OpenJumpTypes.Body,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
    }
}

@Composable
internal fun ShareStudioThemeRow(
    lightTheme: Boolean,
    enabled: Boolean,
    onLightThemeChange: (Boolean) -> Unit,
    tagPrefix: String,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.settings_theme_title),
            style = OpenJumpTypes.Body,
            modifier = Modifier.weight(1f),
        )
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            FilterChip(
                selected = !lightTheme,
                onClick = { onLightThemeChange(false) },
                enabled = enabled,
                label = { Text(stringResource(R.string.settings_theme_dark)) },
                modifier = Modifier.testTag("$tagPrefix-dark"),
            )
            FilterChip(
                selected = lightTheme,
                onClick = { onLightThemeChange(true) },
                enabled = enabled,
                label = { Text(stringResource(R.string.settings_theme_light)) },
                modifier = Modifier.testTag("$tagPrefix-light"),
            )
        }
    }
}

@Composable
internal fun ShareStudioDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = Spacing.lg),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
    )
}
