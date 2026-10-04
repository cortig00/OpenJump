package com.openjump.app.ui.selector

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.openjump.app.R
import com.openjump.app.ui.encoder.encoderToolIconColors
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.theme.Spacing
import com.openjump.app.ui.theme.ShapeTokens
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.roundToInt

internal const val MAX_PLATE_SNAPSHOT_SIDE = 1024

internal data class PlateSnapshotCrop(val left: Int, val top: Int, val width: Int, val height: Int)

/** PixelCopy captures the surface, not the Compose viewport. Strip FIT bars using measured bounds. */
internal fun plateSnapshotCrop(
    videoRect: ContentRect,
    surfaceRect: ContentRect,
    bitmapWidth: Int,
    bitmapHeight: Int,
): PlateSnapshotCrop? {
    if (surfaceRect.width <= 0 || surfaceRect.height <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0 ||
        videoRect.width <= 0 || videoRect.height <= 0 ||
        videoRect.left < surfaceRect.left - 1.0 || videoRect.top < surfaceRect.top - 1.0 ||
        videoRect.right > surfaceRect.right + 1.0 || videoRect.bottom > surfaceRect.bottom + 1.0) return null
    val left = ((videoRect.left - surfaceRect.left) * bitmapWidth / surfaceRect.width).roundToInt().coerceIn(0, bitmapWidth - 1)
    val top = ((videoRect.top - surfaceRect.top) * bitmapHeight / surfaceRect.height).roundToInt().coerceIn(0, bitmapHeight - 1)
    val right = ((videoRect.right - surfaceRect.left) * bitmapWidth / surfaceRect.width).roundToInt().coerceIn(left + 1, bitmapWidth)
    val bottom = ((videoRect.bottom - surfaceRect.top) * bitmapHeight / surfaceRect.height).roundToInt().coerceIn(top + 1, bitmapHeight)
    return PlateSnapshotCrop(left, top, right - left, bottom - top)
}

/** One local, cancellable snapshot of the already-rendered surface. No decoder or persistent file. */
internal suspend fun capturePlateSnapshot(surface: SurfaceView, viewport: View, mapper: VideoCoordinateMapper): Bitmap? =
    suspendCancellableCoroutine { continuation ->
        if (!surface.holder.surface.isValid || surface.width <= 0 || surface.height <= 0 ||
            viewport.width != mapper.viewport.width.toInt() || viewport.height != mapper.viewport.height.toInt()) {
            continuation.resume(null) { _, _, _ -> }
            return@suspendCancellableCoroutine
        }
        val origin = IntArray(2).also(surface::getLocationInWindow)
        val parentOrigin = IntArray(2).also(viewport::getLocationInWindow)
        val bounds = ContentRect(
            (origin[0] - parentOrigin[0]).toDouble(), (origin[1] - parentOrigin[1]).toDouble(),
            surface.width.toDouble(), surface.height.toDouble(),
        )
        val scale = minOf(1.0, MAX_PLATE_SNAPSHOT_SIDE.toDouble() / maxOf(surface.width, surface.height))
        val bitmap = Bitmap.createBitmap(
            (surface.width * scale).roundToInt().coerceAtLeast(1),
            (surface.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888,
        )
        val crop = plateSnapshotCrop(mapper.contentRect, bounds, bitmap.width, bitmap.height)
        if (crop == null) {
            bitmap.recycle()
            continuation.resume(null) { _, _, _ -> }
            return@suspendCancellableCoroutine
        }
        try {
            PixelCopy.request(surface, bitmap, { result ->
                val sameSurfaceBounds = surface.width == bounds.width.toInt() && surface.height == bounds.height.toInt() &&
                    surface.isAttachedToWindow && viewport.width == mapper.viewport.width.toInt() && viewport.height == mapper.viewport.height.toInt()
                if (result != PixelCopy.SUCCESS || !sameSurfaceBounds || !continuation.isActive) {
                    bitmap.recycle()
                    if (continuation.isActive) continuation.resume(null) { _, _, _ -> }
                } else {
                    val image = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width, crop.height)
                    if (image !== bitmap) bitmap.recycle()
                    continuation.resume(image) { _, cancelledImage, _ -> cancelledImage?.recycle() }
                }
            }, Handler(Looper.getMainLooper()))
        } catch (_: IllegalArgumentException) {
            bitmap.recycle()
            if (continuation.isActive) continuation.resume(null) { _, _, _ -> }
        }
    }

internal fun platePrecisionMapper(
    video: VideoPresentationGeometry,
    viewport: ViewportSize,
    focus: ImagePoint,
    zoom: Double,
): VideoCoordinateMapper {
    val centered = VideoCoordinateMapper(video, viewport, VideoScaleMode.CROP, zoom = zoom)
    val left = viewport.width / 2.0 - focus.x / (video.width - 1.0).coerceAtLeast(1.0) * centered.contentRect.width
    val top = viewport.height / 2.0 - focus.y / (video.height - 1.0).coerceAtLeast(1.0) * centered.contentRect.height
    return VideoCoordinateMapper(video, viewport, VideoScaleMode.CROP, CropAlignment(
        if (centered.cropOverflowX > 0) (-left / centered.cropOverflowX).coerceIn(0.0, 1.0) else 0.5,
        if (centered.cropOverflowY > 0) (-top / centered.cropOverflowY).coerceIn(0.0, 1.0) else 0.5,
    ), zoom)
}

/** Edits a local copy; only returning to the full FIT view publishes changed geometry. */
@Composable
internal fun PlateReferencePrecisionEditor(
    bitmap: Bitmap,
    proposal: PlateCalibrationProposal,
    onDismiss: () -> Unit,
    onApply: (ImagePoint, Double) -> Unit,
) {
    var local by remember(proposal.generation, bitmap) { mutableStateOf(proposal) }
    var zoom by remember { mutableStateOf(2.0) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var focus by remember { mutableStateOf(proposal.center) }
    val video = remember(proposal) { VideoPresentationGeometry(proposal.presentationWidth, proposal.presentationHeight) }
    val mapper = remember(viewport, zoom, focus, video) {
        if (viewport.width > 0 && viewport.height > 0)
            platePrecisionMapper(video, ViewportSize(viewport.width.toDouble(), viewport.height.toDouble()), focus, zoom) else null
    }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val edit: (ImagePoint, Double) -> Unit = { center, diameter ->
        boundedPlateProposalGeometry(center, diameter, video.width, video.height)?.let { (c, d) ->
            local = local.copy(center = c, diameterPx = d)
        }
    }
    // Keep native measurement enabled: Compose 1.7's non-default-width dialog
    // measures from screenHeightDp, including bars on API 35, and clips the CTA.
    // MATCH_PARENT gives full width while respecting the actual native usable height.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = true)) {
        val precisionView = LocalView.current
        val window = generateSequence(precisionView.parent) { it.parent }
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
                    title = stringResource(R.string.encoder_reference_precision_title), onNavigationClick = onDismiss,
                    navigationContentDescription = stringResource(R.string.common_cancel),
                )
            },
        ) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val wide = maxWidth >= 600.dp && (maxWidth > maxHeight || maxWidth >= 840.dp)
                val controlsWidth = (maxWidth * 0.40f).coerceIn(280.dp, 400.dp)
                val compactTools = maxHeight < 600.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.2f
                val controlsHeight = (maxHeight * if (compactTools) 0.56f else 0.48f).coerceAtMost(360.dp)
                val controls: @Composable (Modifier) -> Unit = { modifier ->
                    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md)
                        .testTag("reference-precision-pane")) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            // The title and previous review already establish the task. Avoid a
                            // repeated paragraph consuming the compact tool viewport at large fonts.
                            if (!compactTools) Text(stringResource(R.string.encoder_reference_review_hint), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            PlateReferenceFineControls(local, true, edit, compact = compactTools)
                        }
                        Spacer(Modifier.height(Spacing.md))
                        Button(onClick = {
                            if (local.center != proposal.center || local.diameterPx != proposal.diameterPx) onApply(local.center, local.diameterPx)
                            onDismiss()
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("reference-precision-return")) {
                            Text(stringResource(R.string.encoder_reference_precision_return))
                        }
                    }
                }
                val video: @Composable (Modifier) -> Unit = { modifier ->
                    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                        // Keep controls outside the image: a compact viewport must not hide the
                        // plate's upper edge underneath a zoom pill while calibrating.
                        Surface(modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = Spacing.xs),
                            color = MaterialTheme.colorScheme.surfaceContainer, shape = ShapeTokens.full) {
                            Row(Modifier.padding(horizontal = Spacing.sm), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                FilledTonalIconButton(modifier = Modifier.size(48.dp).semantics { contentDescription = zoomOutDescription },
                                    colors = encoderToolIconColors(), enabled = zoom > 1.0, onClick = { focus = local.center; zoom = (zoom / 1.5).coerceAtLeast(1.0) }) { StepSign(false) }
                                Text(stringResource(R.string.encoder_reference_zoom_value, zoom), style = MaterialTheme.typography.labelLarge,
                                    textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 48.dp))
                                FilledTonalIconButton(modifier = Modifier.size(48.dp).semantics { contentDescription = zoomInDescription },
                                    colors = encoderToolIconColors(), enabled = zoom < MAX_CROP_ZOOM, onClick = { focus = local.center; zoom = (zoom * 1.5).coerceAtMost(MAX_CROP_ZOOM) }) { StepSign(true) }
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black).clipToBounds()
                            .onSizeChanged { viewport = it }.testTag("reference-precision-video")) {
                            mapper?.let { m ->
                                Canvas(Modifier.fillMaxSize()) {
                                    drawImage(image,
                                        dstOffset = IntOffset(m.contentRect.left.roundToInt(), m.contentRect.top.roundToInt()),
                                        dstSize = IntSize(m.contentRect.width.roundToInt(), m.contentRect.height.roundToInt()),
                                    )
                                }
                                PlateCalibrationOverlay(m, local, edit, modifier = Modifier.fillMaxSize())
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
