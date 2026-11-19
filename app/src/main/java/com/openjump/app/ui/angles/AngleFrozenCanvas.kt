package com.openjump.app.ui.angles

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import com.openjump.app.R
import com.openjump.app.angles.AngleEditorState
import com.openjump.app.angles.AngleId
import com.openjump.app.angles.AngleMarker
import com.openjump.app.angles.AnglePoint
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.ui.selector.CropFramingOverlay
import com.openjump.app.ui.selector.VideoCoordinateMapper
import com.openjump.app.ui.theme.Spacing

/** Etiqueta única de la imagen congelada compartida por el visor legacy y el workspace nuevo. */
internal const val ANGLE_FROZEN_CANVAS_IMAGE_TAG = "angle-frozen-canvas-image"

/** Qué texto explica la ausencia de píxeles en el lienzo; el llamante conoce el estado real. */
internal enum class AngleFrozenCanvasPlaceholder {
    /** Hay imagen: no se pinta ningún aviso. */
    None,

    /** No hay fotograma activo en la sesión. */
    NoActiveFrame,

    /** El fotograma activo se está capturando. */
    Capturing,

    /** El fotograma activo existe pero aún no tiene imagen retenida. */
    ImagePending,
}

/**
 * Lienzo de un fotograma **congelado**: pinta el bitmap exacto del lease, la capa de geometría y
 * (solo en modo encuadre) la capa de gestos, con las mismas coordenadas que el mapper.
 *
 * Es el único sitio donde se combinan píxeles y marcas de una imagen congelada, y lo usan tanto el
 * visor multi-fotograma legacy (`AngleMultiFrameEditorScreen`) como el workspace nuevo
 * ([AngleEditorWorkspace]). No adquiere ni cierra el lease: el llamante es dueño de la instancia
 * exacta y la cierra en el `onDispose` de su efecto. No copia el bitmap, no lee la geometría del
 * vídeo (usa [mapper], construido por el llamante con `lease.imageGeometry` y `FIT`) y no interpreta
 * la sesión.
 *
 * [enabled] deshabilita gestos y puntos sin ocultar la imagen (por ejemplo durante un guardado);
 * [showsGeometry] decide si la capa dibuja las marcas del ancla actual. Los callbacks devuelven el
 * resultado real de la operación, de modo que el lienzo nunca anuncia un punto que el ViewModel
 * rechazó.
 */
@Composable
internal fun AngleFrozenCanvas(
    lease: AngleMultiFrameBitmapLease?,
    frameIndex: Int?,
    placeholder: AngleFrozenCanvasPlaceholder,
    editor: AngleEditorState?,
    mapper: VideoCoordinateMapper?,
    mode: AngleOverlayMode,
    enabled: Boolean,
    showsGeometry: Boolean,
    accessibilityDescription: String?,
    formatDegrees: (Double) -> String,
    accessibleControlsLabel: String?,
    onAccessibleControlsRequested: (() -> Unit)?,
    framingDescription: String,
    onFramingGesture: (ImagePoint, Double, Double, Double) -> Unit,
    onSelectAngle: (AngleId?) -> Unit,
    onPlacePoint: (AnglePoint) -> Unit,
    onMoveMarker: (AngleId, AngleMarker, AnglePoint) -> Unit,
    onMoveDraftMarker: (AngleMarker, AnglePoint) -> Unit,
    interactionKey: Any? = null,
    deferredGestureCommit: Boolean = false,
    previewConfirmation: com.openjump.app.angles.AngleFrameConfirmation? = null,
    onPreviewActiveChanged: ((Boolean) -> Unit)? = null,
    abortPlacementOnMultitouch: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (lease != null) {
            val imageLabel = stringResource(
                R.string.angle_multi_frame_frame_label,
                (frameIndex ?: 0) + 1,
            )
            Image(
                bitmap = lease.bitmap.asImageBitmap(),
                contentDescription = imageLabel,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(ANGLE_FROZEN_CANVAS_IMAGE_TAG)
                    .graphicsLayer {
                        // Identidad en FIT 1x; con zoom, la imagen sigue exactamente la misma
                        // escala y traslación nativa que el mapper usa para los puntos.
                        scaleX = mapper?.nativeScale?.toFloat() ?: 1f
                        scaleY = mapper?.nativeScale?.toFloat() ?: 1f
                        translationX = mapper?.contentTranslation?.x?.toFloat() ?: 0f
                        translationY = mapper?.contentTranslation?.y?.toFloat() ?: 0f
                    },
            )
        }
        if (editor != null && mapper != null && lease != null) {
            AngleOverlay(
                mapper = mapper,
                state = editor,
                formatLabel = { _, degrees -> formatDegrees(degrees) },
                modifier = Modifier.fillMaxSize(),
                enabled = enabled,
                mode = mode,
                showsGeometry = showsGeometry,
                accessibilityDescription = accessibilityDescription,
                markerLabel = { it.name },
                onSelectAngle = onSelectAngle,
                onPlacePoint = onPlacePoint,
                onMoveMarker = onMoveMarker,
                onMoveDraftMarker = onMoveDraftMarker,
                accessibleControlsLabel = accessibleControlsLabel,
                onAccessibleControlsRequested = onAccessibleControlsRequested,
                interactionKey = interactionKey,
                deferredGestureCommit = deferredGestureCommit,
                previewConfirmation = previewConfirmation,
                onPreviewActiveChanged = onPreviewActiveChanged,
                abortPlacementOnMultitouch = abortPlacementOnMultitouch,
            )
        }
        if (mode == AngleOverlayMode.FRAMING && mapper != null && lease != null) {
            CropFramingOverlay(
                onGesture = { centroid, zoomChange, panDelta ->
                    onFramingGesture(
                        ImagePoint(centroid.x.toDouble(), centroid.y.toDouble()),
                        zoomChange.toDouble(),
                        panDelta.x.toDouble(),
                        panDelta.y.toDouble(),
                    )
                },
                description = framingDescription,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1f),
            )
        }
        when (placeholder) {
            AngleFrozenCanvasPlaceholder.None -> Unit
            AngleFrozenCanvasPlaceholder.NoActiveFrame -> CanvasNotice(
                stringResource(R.string.angle_multi_frame_no_active),
            )

            AngleFrozenCanvasPlaceholder.Capturing -> CanvasNotice(
                stringResource(R.string.angle_multi_frame_frame_capturing),
            )

            AngleFrozenCanvasPlaceholder.ImagePending -> CanvasNotice(
                stringResource(R.string.angle_multi_frame_image_pending),
            )
        }
    }
}

@Composable
private fun CanvasNotice(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White,
        modifier = Modifier.padding(Spacing.md),
    )
}
