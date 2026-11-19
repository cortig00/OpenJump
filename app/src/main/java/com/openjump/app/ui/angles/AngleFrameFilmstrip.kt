package com.openjump.app.ui.angles

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.angles.AngleFrameId
import com.openjump.app.ui.theme.Spacing

/** Contenedor de la tira de miniaturas (vídeo + fotogramas), único por pantalla. */
internal const val ANGLE_WORKSPACE_FILMSTRIP_TAG = "angle-workspace-filmstrip"

/** Prefijo del tag de cada miniatura; el sufijo es el id estable del fotograma, nunca su posición. */
internal const val ANGLE_WORKSPACE_THUMB_TAG_PREFIX = "angle-workspace-thumb-"

/** Tag estable de la miniatura de [frameId]. */
internal fun angleWorkspaceThumbTag(frameId: AngleFrameId): String =
    ANGLE_WORKSPACE_THUMB_TAG_PREFIX + frameId.value

/** Etiqueta corta del instante del fotograma (PTS de la fuente), para su descripción accesible. */
internal fun angleFrameTimestampLabel(sourcePtsUs: Long): String {
    val totalSeconds = (sourcePtsUs / 1_000_000L).coerceAtLeast(0L)
    val seconds = (totalSeconds % 60L).toString().padStart(2, '0')
    return "${totalSeconds / 60L}:$seconds"
}

/**
 * `true` solo si la miniatura puede mostrar la marca de «medición completa»: imagen editable, al
 * menos un ángulo completo y **ningún** borrador A/B pendiente.
 *
 * No afirma que el análisis esté guardado: solo que ese fotograma ya tiene una terna cerrada y que
 * no queda trabajo a medias. Un borrador pendiente (aunque haya ángulos) no se presenta como
 * completo.
 */
internal val AngleMultiFrameItem.hasCompleteMeasurement: Boolean
    get() = canEdit && editor.angles.isNotEmpty() && editor.draft.points.isEmpty()

/**
 * Tira horizontal de fotogramas: la baldosa de vídeo y, a continuación, una miniatura por fotograma
 * con **sus píxeles reales**.
 *
 * - Las claves de la lista son el [AngleFrameId], no la posición ni el índice de la fuente: renombrar
 *   o borrar otro fotograma no renombra las claves ajenas.
 * - Cada miniatura visible pide **su propio** lease al ViewModel y lo cierra en `onDispose` la
 *   instancia exacta que adquirió. El efecto se keyea solo por `frameId` + `canEdit` (nunca por la
 *   versión del estado ni por `leaseCount`, que cambiarían al abrir el propio lease y provocarían un
 *   bucle de adquisición/cierre).
 * - La imagen es el bitmap del lease con `asImageBitmap()`: sin `Bitmap.copy`, sin reescalado y sin
 *   caché en ficheros. `contentDescription` es `null` porque el botón que la contiene ya describe el
 *   fotograma completo (ordinal, instante y estado) para accesibilidad.
 * - Es presentación pura: no captura, no guarda y no muta la sesión. Sin vídeo no adquiere nada.
 */
@Composable
internal fun AngleFrameFilmstrip(
    viewModel: AngleEditorViewModel,
    session: AngleMultiFrameSessionState?,
    activeFrameId: AngleFrameId?,
    enabled: Boolean,
    onSelectVideo: () -> Unit,
    onSelectFrame: (AngleFrameId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val frames = session?.frames.orEmpty()
    // Época de la sesión que se está pintando: el mismo id de fotograma puede reaparecer en otra
    // copia del vídeo o en otro render, así que el lease de la miniatura se invalida también por
    // fuente y generación, no solo por id.
    val sourceIdentity = session?.sourceIdentity
    val generation = session?.generation
    LazyRow(
        modifier = modifier.testTag(ANGLE_WORKSPACE_FILMSTRIP_TAG),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        contentPadding = PaddingValues(
            horizontal = Spacing.screenHorizontal,
            vertical = Spacing.xs,
        ),
    ) {
        item(key = "video", contentType = "video") {
            FilmstripVideoTile(
                selected = activeFrameId == null,
                enabled = enabled,
                onClick = onSelectVideo,
            )
        }
        items(items = frames, key = { it.frameId.value }, contentType = { "frame" }) { frame ->
            FilmstripThumbnail(
                frameId = frame.frameId,
                sourceIdentity = sourceIdentity,
                generation = generation,
                ordinal = frame.anchor.frameIndex + 1,
                sourcePtsUs = frame.anchor.sourcePtsUs,
                capture = frame.capture,
                hasCompleteMeasurement = frame.hasCompleteMeasurement,
                selected = frame.frameId == activeFrameId,
                enabled = enabled,
                viewModel = viewModel,
                onClick = { onSelectFrame(frame.frameId) },
            )
        }
    }
}

@Composable
private fun FilmstripVideoTile(
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(R.string.angle_workspace_video_tile)
    val description = if (selected) {
        stringResource(R.string.angle_workspace_thumb_selected, label)
    } else {
        label
    }
    Box(
        modifier = Modifier
            .width(72.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(Spacing.sm))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (selected) {
                    Modifier.border(
                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                        RoundedCornerShape(Spacing.sm),
                    )
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(ANGLE_WORKSPACE_VIDEO_TILE_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun FilmstripThumbnail(
    frameId: AngleFrameId,
    sourceIdentity: String?,
    generation: Long?,
    ordinal: Int,
    sourcePtsUs: Long,
    capture: AngleDraftCapturePhase,
    hasCompleteMeasurement: Boolean,
    selected: Boolean,
    enabled: Boolean,
    viewModel: AngleEditorViewModel,
    onClick: () -> Unit,
) {
    val canEdit = capture is AngleDraftCapturePhase.Ready
    // Lease LOCAL de esta miniatura: se cierra exactamente esta instancia en onDispose. Las claves
    // incluyen la época (fuente + generación) para no dibujar los píxeles de un lease viejo si el
    // id de fotograma se reutiliza en otra sesión. Nunca se usa `version`/`leaseCount` como clave:
    // cambian por motivos ajenos al píxel y no identifican la imagen.
    var thumbLease by remember(frameId, sourceIdentity, generation) {
        mutableStateOf<AngleMultiFrameBitmapLease?>(null)
    }
    DisposableEffect(frameId, canEdit, sourceIdentity, generation) {
        var acquired: AngleMultiFrameBitmapLease? = null
        if (canEdit) {
            when (val result = viewModel.acquireMultiFrameBitmap(frameId)) {
                is AngleMultiFrameBitmapResult.Granted -> {
                    acquired = result.lease
                    thumbLease = result.lease
                }

                is AngleMultiFrameBitmapResult.Rejected -> thumbLease = null
            }
        } else {
            thumbLease = null
        }
        onDispose {
            if (thumbLease === acquired) thumbLease = null
            acquired?.close()
        }
    }
    val statusRes = when (capture) {
        is AngleDraftCapturePhase.Ready -> R.string.angle_multi_frame_frame_ready
        is AngleDraftCapturePhase.Capturing -> R.string.angle_multi_frame_frame_capturing
        is AngleDraftCapturePhase.Failed -> R.string.angle_multi_frame_frame_failed
        is AngleDraftCapturePhase.NotCaptured -> R.string.angle_multi_frame_frame_not_captured
    }
    val status = stringResource(statusRes)
    val base = stringResource(R.string.angle_workspace_thumb_description, ordinal, status)
    val description = if (selected) {
        stringResource(R.string.angle_workspace_thumb_selected, base)
    } else {
        base
    }
    val lease = thumbLease?.takeIf { it.isOpen }
    val timestamp = angleFrameTimestampLabel(sourcePtsUs)
    Box(
        modifier = Modifier
            .width(88.dp)
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(Spacing.sm))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (selected) {
                    Modifier.border(
                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                        RoundedCornerShape(Spacing.sm),
                    )
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(angleWorkspaceThumbTag(frameId)),
        contentAlignment = Alignment.Center,
    ) {
        if (lease != null) {
            // Píxeles exactos del lease: sin copia, sin escalado a disco y sin caché.
            Image(
                bitmap = lease.bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(Spacing.xs),
            ) {
                Text(text = status, style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(
            text = timestamp,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 2.dp),
        )
        if (hasCompleteMeasurement) {
            // Marca de medición completa; no afirma que esté guardado.
            Text(
                text = "✓",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp),
            )
        }
    }
}
