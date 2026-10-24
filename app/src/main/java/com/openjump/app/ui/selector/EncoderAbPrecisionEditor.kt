package com.openjump.app.ui.selector

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.openjump.app.R
import com.openjump.app.encoder.VideoTimingDecision
import com.openjump.app.protocol.VideoSource
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.encoder.encoderToolIconColors
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import kotlin.math.hypot

/**
 * Anchors an A/B enlarge session to the exact frame it was captured from. Any
 * drift (seek, source/method change, lifecycle stop) invalidates the dialog
 * instead of publishing stale points. The snapshot bitmap itself is owned by the
 * caller, which recycles it on dismiss exactly like the plate route.
 */
internal data class AbEditorAnchor(
    val sessionKey: String,
    val videoUri: String?,
    val renderedIndex: Int,
    val renderedPtsUs: Long,
    val videoWidth: Double,
    val videoHeight: Double,
    val liveA: ImagePoint?,
    val liveB: ImagePoint?,
    val requestedIndex: Int = renderedIndex,
    val timing: VideoTimingDecision? = null,
    val source: VideoSource? = null,
)

/**
 * Pure drift policy for the anchored A/B dialog. Every field pinned at capture
 * must still match; any seek (including seek-away-and-back, which already
 * closed the dialog via the LaunchedEffect), source/timing/geometry/pair change,
 * calibration confirmation, or lifecycle/canMark loss invalidates. Canonical PTS
 * must equal the stored actual PTS so a same-index/different-PTS re-index slips
 * through nowhere. Unit-testable; the Screen wires live state into it.
 */
internal fun isAbAnchorStillValid(
    anchor: AbEditorAnchor,
    sessionKey: String?,
    timing: VideoTimingDecision?,
    calibrationPresent: Boolean,
    liveA: ImagePoint?,
    liveB: ImagePoint?,
    videoUri: String?,
    source: VideoSource?,
    renderedIndex: Int?,
    renderedPtsUs: Long?,
    requestedIndex: Int,
    canMark: Boolean,
    phaseIsManual: Boolean,
    videoWidth: Double?,
    videoHeight: Double?,
    canonicalPtsUs: Long?,
): Boolean {
    if (!phaseIsManual) return false
    if (!canMark) return false
    if (calibrationPresent) return false
    if (sessionKey != anchor.sessionKey) return false
    // Timing is the physical authority: it must be present and exactly equal.
    if (timing == null || anchor.timing == null || timing != anchor.timing) return false
    if (liveA != anchor.liveA || liveB != anchor.liveB) return false
    if (videoUri != anchor.videoUri) return false
    if (source != anchor.source) return false
    if (renderedIndex != anchor.renderedIndex) return false
    if (renderedPtsUs != anchor.renderedPtsUs) return false
    if (requestedIndex != anchor.requestedIndex) return false
    if (videoWidth != anchor.videoWidth || videoHeight != anchor.videoHeight) return false
    // Stored actual PTS must be the canonical PTS for that index.
    if (canonicalPtsUs != anchor.renderedPtsUs) return false
    return true
}

/**
 * Ordered publish plan using only the existing callbacks (no VM/AppSession edits).
 *
 * [markCalibrationPoint] alternates (empty -> A, A-only -> B, A+B -> reset to A),
 * so a locally edited pair cannot be published with naive taps when a point
 * already exists: with a partial live A, marking a changed A would land on B.
 * When live already holds any point, the plan resets first via the existing
 * [useManualEncoderSelection] path (which clears A/B but never confirms scale
 * and never touches the explicit distance input) and then marks the local pair
 * plainly. Empty and already-equal cases publish nothing, Cancel never reaches
 * this planner, and a B-only local state is never published as A.
 */
internal data class AbPublishPlan(val resetFirst: Boolean, val marks: List<ImagePoint>)

internal fun planAbPublish(
    liveA: ImagePoint?,
    liveB: ImagePoint?,
    newA: ImagePoint?,
    newB: ImagePoint?,
): AbPublishPlan {
    if (newA == null) return AbPublishPlan(false, emptyList())
    if (liveA == newA && liveB == newB) return AbPublishPlan(false, emptyList())
    val marks = listOfNotNull(newA, newB)
    if (liveA == null) return AbPublishPlan(false, marks)
    return AbPublishPlan(true, marks)
}

/**
 * Local zoom/pan view over a full-frame FIT snapshot. The shared FIT mapper is
 * never modified (it ignores zoom by design); this pure helper applies the
 * editor-local affine transform on top of FIT coordinates instead. At zoom 1x
 * with zero pan the whole frame is visible: no CROP masquerading as 1x.
 */
internal data class AbEditorView(
    val viewportWidth: Double,
    val viewportHeight: Double,
    val bitmapWidth: Int,
    val bitmapHeight: Int,
    val videoWidth: Double,
    val videoHeight: Double,
    val zoom: Double,
    val panX: Double,
    val panY: Double,
)

internal const val MIN_AB_ZOOM = 1.0
internal const val MAX_AB_ZOOM = 4.0

internal fun abEditorFitScale(view: AbEditorView): Double =
    minOf(view.viewportWidth / view.bitmapWidth, view.viewportHeight / view.bitmapHeight)

internal fun abEditorContentSize(view: AbEditorView): Pair<Double, Double> {
    val scale = abEditorFitScale(view)
    return view.bitmapWidth * scale to view.bitmapHeight * scale
}

/** Touch in viewport px -> original presentation coordinates. Null on FIT bars or outside the viewport. */
internal fun abEditorViewToVideo(view: AbEditorView, x: Double, y: Double): ImagePoint? {
    if (!view.zoom.isFinite() || view.zoom < MIN_AB_ZOOM || view.zoom > MAX_AB_ZOOM ||
        !x.isFinite() || !y.isFinite()
    ) return null
    // Match the shared mapper: touches outside the viewport never map.
    if (x < 0.0 || y < 0.0 || x > view.viewportWidth || y > view.viewportHeight) return null
    val scale = abEditorFitScale(view)
    val centerX = view.viewportWidth / 2.0
    val centerY = view.viewportHeight / 2.0
    val fitX = centerX + (x - view.panX - centerX) / view.zoom
    val fitY = centerY + (y - view.panY - centerY) / view.zoom
    val (contentWidth, contentHeight) = abEditorContentSize(view)
    val left = (view.viewportWidth - contentWidth) / 2.0
    val top = (view.viewportHeight - contentHeight) / 2.0
    val bitmapX = (fitX - left) / scale
    val bitmapY = (fitY - top) / scale
    if (bitmapX < 0.0 || bitmapX > view.bitmapWidth || bitmapY < 0.0 || bitmapY > view.bitmapHeight) return null
    return ImagePoint(
        x = bitmapX / view.bitmapWidth * (view.videoWidth - 1.0),
        y = bitmapY / view.bitmapHeight * (view.videoHeight - 1.0),
    )
}

/** Original presentation coordinates -> viewport px for drawing the local pair. */
internal fun abEditorVideoToView(view: AbEditorView, point: ImagePoint): ImagePoint {
    val scale = abEditorFitScale(view)
    val (contentWidth, contentHeight) = abEditorContentSize(view)
    val left = (view.viewportWidth - contentWidth) / 2.0
    val top = (view.viewportHeight - contentHeight) / 2.0
    val bitmapX = point.x / (view.videoWidth - 1.0).coerceAtLeast(1.0) * view.bitmapWidth
    val bitmapY = point.y / (view.videoHeight - 1.0).coerceAtLeast(1.0) * view.bitmapHeight
    val fitX = left + bitmapX * scale
    val fitY = top + bitmapY * scale
    val centerX = view.viewportWidth / 2.0
    val centerY = view.viewportHeight / 2.0
    return ImagePoint(
        x = centerX + (fitX - centerX) * view.zoom + view.panX,
        y = centerY + (fitY - centerY) * view.zoom + view.panY,
    )
}

/** Clamps panning so the zoomed frame keeps covering the viewport. */
internal fun clampAbPan(view: AbEditorView, panX: Double, panY: Double): Pair<Double, Double> {
    val (contentWidth, contentHeight) = abEditorContentSize(view)
    val overflowX = ((contentWidth * view.zoom - view.viewportWidth) / 2.0).coerceAtLeast(0.0)
    val overflowY = ((contentHeight * view.zoom - view.viewportHeight) / 2.0).coerceAtLeast(0.0)
    return panX.coerceIn(-overflowX, overflowX) to panY.coerceIn(-overflowY, overflowY)
}

/**
 * Edits a local copy of the manual A/B pair over an anchored full-frame
 * snapshot. Cancel discards everything; returning publishes the local pair via
 * the caller's existing callbacks only. Never confirms scale, never touches the
 * distance input, never writes Room.
 */
@Composable
internal fun EncoderAbPrecisionEditor(
    bitmap: Bitmap,
    anchor: AbEditorAnchor,
    initialA: ImagePoint?,
    initialB: ImagePoint?,
    onDismiss: () -> Unit,
    onReturn: (ImagePoint?, ImagePoint?) -> Unit,
) {
    var localA by remember(anchor) { mutableStateOf(initialA) }
    var localB by remember(anchor) { mutableStateOf(initialB) }
    var zoom by remember(anchor) { mutableStateOf(MIN_AB_ZOOM) }
    val panState = remember(anchor) { mutableStateOf(0.0 to 0.0) }
    var pan by panState
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val emphasis = OpenJumpTheme.colors.positive
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
    // The dialog creates its own platform locals; retain the caller's language/font/density
    // scoped to this editor only (mirrors the P3 metrics sheet pattern). No globals.
    val callerContext = LocalContext.current
    val callerConfiguration = LocalConfiguration.current
    val callerDensity = LocalDensity.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = true)) {
        CompositionLocalProvider(
            LocalContext provides callerContext,
            LocalConfiguration provides callerConfiguration,
            LocalDensity provides callerDensity,
        ) {
        val editorView = LocalView.current
        val window = generateSequence(editorView.parent) { it.parent }
            .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
        SideEffect {
            window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val zoomOutDescription = stringResource(R.string.selector_zoom_out_description)
        val zoomInDescription = stringResource(R.string.selector_zoom_in_description)
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                OpenJumpTopAppBar(
                    title = stringResource(R.string.encoder_reference_ab_editor_title),
                    onNavigationClick = onDismiss,
                    navigationContentDescription = stringResource(R.string.common_cancel),
                )
            },
        ) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val wide = maxWidth >= 600.dp && (maxWidth > maxHeight || maxWidth >= 840.dp)
                val controlsWidth = (maxWidth * 0.40f).coerceIn(280.dp, 400.dp)
                val compactTools = maxHeight < 600.dp || LocalDensity.current.fontScale > 1.2f
                val controlsHeight = (maxHeight * if (compactTools) 0.56f else 0.48f).coerceAtMost(360.dp)
                val controls: @Composable (Modifier) -> Unit = { modifier ->
                    Column(
                        modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md)
                            .testTag("ab-precision-pane"),
                    ) {
                        // Scrollable body with a pinned Return: narrow + fontScale 1.5
                        // keeps the CTA displayed and clickable instead of clipping.
                        Column(
                            Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            Text(
                                stringResource(R.string.encoder_reference_ab_editor_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val status = when {
                                localA == null -> stringResource(R.string.selector_touch_reference_a)
                                localB == null -> stringResource(R.string.selector_touch_reference_b)
                                else -> stringResource(R.string.selector_enter_reference_length)
                            }
                            Text(status, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(Spacing.md))
                        Button(
                            onClick = {
                                val a = localA
                                val b = localB
                                onDismiss()
                                // Dismiss first so a drifted frame cannot publish: the
                                // caller re-anchors before running any callback.
                                if (a != null || b != null) onReturn(a, b)
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("ab-precision-return"),
                        ) {
                            Text(stringResource(R.string.encoder_reference_precision_return))
                        }
                    }
                }
                val video: @Composable (Modifier) -> Unit = { modifier ->
                    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Surface(
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = Spacing.xs),
                            color = MaterialTheme.colorScheme.surfaceContainer, shape = ShapeTokens.full,
                        ) {
                            Row(
                                Modifier.padding(horizontal = Spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                            ) {
                                FilledTonalIconButton(
                                    modifier = Modifier.size(48.dp).semantics { contentDescription = zoomOutDescription },
                                    colors = encoderToolIconColors(), enabled = zoom > MIN_AB_ZOOM,
                                    onClick = { zoom = (zoom / 1.5).coerceAtLeast(MIN_AB_ZOOM) },
                                ) { StepSign(false) }
                                Text(
                                    stringResource(R.string.encoder_reference_zoom_value, zoom),
                                    style = MaterialTheme.typography.labelLarge,
                                    textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 48.dp),
                                )
                                FilledTonalIconButton(
                                    modifier = Modifier.size(48.dp).semantics { contentDescription = zoomInDescription },
                                    colors = encoderToolIconColors(), enabled = zoom < MAX_AB_ZOOM,
                                    onClick = { zoom = (zoom * 1.5).coerceAtMost(MAX_AB_ZOOM) },
                                ) { StepSign(true) }
                            }
                        }
                        Box(
                            Modifier.weight(1f).fillMaxWidth().background(Color.Black).clipToBounds()
                                .onSizeChanged { viewport = it }.testTag("ab-precision-video"),
                        ) {
                            val viewState = if (viewport.width > 0 && viewport.height > 0) AbEditorView(
                                viewportWidth = viewport.width.toDouble(),
                                viewportHeight = viewport.height.toDouble(),
                                bitmapWidth = bitmap.width,
                                bitmapHeight = bitmap.height,
                                videoWidth = anchor.videoWidth,
                                videoHeight = anchor.videoHeight,
                                zoom = zoom,
                                panX = pan.first,
                                panY = pan.second,
                            ) else null
                            // Stable gesture keys (geometry + zoom only): pan must never
                            // restart the drag. Read pan's state holder directly:
                            // several motion events can arrive before recomposition,
                            // so rememberUpdatedState(pan) would lose accumulated deltas.
                            val latestPanState = panState
                            val latestLocalAState = rememberUpdatedState(localA)
                            val latestLocalBState = rememberUpdatedState(localB)
                            // Clamp/reset pan on zoom or viewport change; 1x recenters
                            // to centered FIT (overflow 0 forces pan 0,0).
                            LaunchedEffect(zoom, viewport.width, viewport.height) {
                                if (viewport.width > 0 && viewport.height > 0) {
                                    val cur = latestPanState.value
                                    val clampView = AbEditorView(
                                        viewportWidth = viewport.width.toDouble(),
                                        viewportHeight = viewport.height.toDouble(),
                                        bitmapWidth = bitmap.width,
                                        bitmapHeight = bitmap.height,
                                        videoWidth = anchor.videoWidth,
                                        videoHeight = anchor.videoHeight,
                                        zoom = zoom,
                                        panX = cur.first,
                                        panY = cur.second,
                                    )
                                    val clamped = clampAbPan(clampView, cur.first, cur.second)
                                    if (clamped != pan) pan = clamped
                                }
                            }
                            Canvas(
                                Modifier.fillMaxSize()
                                    .pointerInput(viewport.width, viewport.height, bitmap.width, bitmap.height, anchor.videoWidth, anchor.videoHeight, zoom) {
                                        if (viewport.width <= 0 || viewport.height <= 0) return@pointerInput
                                        detectTapGestures { offset ->
                                            val curPan = latestPanState.value
                                            val tapView = AbEditorView(
                                                viewportWidth = viewport.width.toDouble(),
                                                viewportHeight = viewport.height.toDouble(),
                                                bitmapWidth = bitmap.width,
                                                bitmapHeight = bitmap.height,
                                                videoWidth = anchor.videoWidth,
                                                videoHeight = anchor.videoHeight,
                                                zoom = zoom,
                                                panX = curPan.first,
                                                panY = curPan.second,
                                            )
                                            abEditorViewToVideo(tapView, offset.x.toDouble(), offset.y.toDouble())
                                                ?.let { tapped ->
                                                    val curA = latestLocalAState.value
                                                    val curB = latestLocalBState.value
                                                    when {
                                                        curA == null -> localA = tapped
                                                        curB == null -> localB = tapped
                                                        else -> {
                                                            val distA = hypot(tapped.x - curA.x, tapped.y - curA.y)
                                                            val distB = hypot(tapped.x - curB.x, tapped.y - curB.y)
                                                            if (distA <= distB) localA = tapped else localB = tapped
                                                        }
                                                    }
                                                }
                                        }
                                    }
                                    .pointerInput(viewport.width, viewport.height, bitmap.width, bitmap.height, anchor.videoWidth, anchor.videoHeight, zoom) {
                                        if (viewport.width <= 0 || viewport.height <= 0) return@pointerInput
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            val cur = latestPanState.value
                                            val dragView = AbEditorView(
                                                viewportWidth = viewport.width.toDouble(),
                                                viewportHeight = viewport.height.toDouble(),
                                                bitmapWidth = bitmap.width,
                                                bitmapHeight = bitmap.height,
                                                videoWidth = anchor.videoWidth,
                                                videoHeight = anchor.videoHeight,
                                                zoom = zoom,
                                                panX = cur.first,
                                                panY = cur.second,
                                            )
                                            pan = clampAbPan(
                                                dragView,
                                                cur.first + dragAmount.x,
                                                cur.second + dragAmount.y,
                                            )
                                        }
                                    },
                            ) {
                                if (viewState != null) {
                                    val (contentWidth, contentHeight) = abEditorContentSize(viewState)
                                    val scale = abEditorFitScale(viewState)
                                    val left = (viewState.viewportWidth - contentWidth) / 2.0
                                    val top = (viewState.viewportHeight - contentHeight) / 2.0
                                    val centerX = viewState.viewportWidth / 2.0
                                    val centerY = viewState.viewportHeight / 2.0
                                    val dstLeft = centerX + (left - centerX) * viewState.zoom + viewState.panX
                                    val dstTop = centerY + (top - centerY) * viewState.zoom + viewState.panY
                                    drawImage(
                                        image,
                                        dstOffset = IntOffset(dstLeft.toInt(), dstTop.toInt()),
                                        dstSize = IntSize(
                                            (contentWidth * viewState.zoom).toInt(),
                                            (contentHeight * viewState.zoom).toInt(),
                                        ),
                                    )
                                    // Green A/B mirrors the Encoder overlay; labels carry a
                                    // halo so they stay legible over bright frames.
                                    // The shared FIT mapper ignores zoom by design, so this
                                    // local affine transform stays the only zoom path here.
                                    // Full-frame green connector between the local pair.
                                    val aViewPoint = localA?.let { abEditorVideoToView(viewState, it) }
                                    val bViewPoint = localB?.let { abEditorVideoToView(viewState, it) }
                                    if (aViewPoint != null && bViewPoint != null) {
                                        drawLine(
                                            emphasis,
                                            Offset(aViewPoint.x.toFloat(), aViewPoint.y.toFloat()),
                                            Offset(bViewPoint.x.toFloat(), bViewPoint.y.toFloat()),
                                            strokeWidth = 4f,
                                        )
                                    }
                                    listOfNotNull(
                                        localA?.let { it to "A" },
                                        localB?.let { it to "B" },
                                    ).forEach { (source, label) ->
                                        val viewPoint = abEditorVideoToView(viewState, source)
                                        val center = Offset(viewPoint.x.toFloat(), viewPoint.y.toFloat())
                                        drawCircle(Color.Black.copy(alpha = 0.75f), 14f, center)
                                        drawCircle(emphasis, 10f, center)
                                        drawCircle(Color.White, 10f, center, style = Stroke(2f))
                                        val labelAt = Offset(center.x, center.y - 30f)
                                        drawContext.canvas.nativeCanvas.drawText(label, labelAt.x, labelAt.y, labelHalo)
                                        drawContext.canvas.nativeCanvas.drawText(label, labelAt.x, labelAt.y, labelFill)
                                    }
                                }
                            }
                        }
                    }
                }
                if (wide) Row(Modifier.fillMaxSize()) {
                    video(Modifier.weight(1f).fillMaxHeight())
                    controls(Modifier.width(controlsWidth).fillMaxHeight())
                } else Column(Modifier.fillMaxSize()) {
                    video(Modifier.weight(1f).fillMaxWidth())
                    controls(Modifier.fillMaxWidth().height(controlsHeight))
                }
            }
        }
        }
    }
}
