package com.openjump.app.ui.angles

import com.openjump.app.angles.AngleFrameId
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.ui.selector.CropViewportState
import com.openjump.app.ui.selector.MAX_CROP_ZOOM
import com.openjump.app.ui.selector.MIN_CROP_ZOOM
import com.openjump.app.ui.selector.ViewportSize
import com.openjump.app.ui.selector.VideoCoordinateMapper
import com.openjump.app.ui.selector.VideoScaleMode

/** Orientación real del espacio disponible para el workspace (por constraints, no por sensor). */
internal enum class AngleWorkspacePresentationOrientation { PORTRAIT, LANDSCAPE }

/** Orientación que el workspace pide mientras es dueño de la ventana. */
internal enum class AngleWorkspaceOrientationTarget { FOLLOW_USER, LANDSCAPE }

/**
 * Plan de presentación **puro** del workspace, derivado de constraints reales.
 *
 * - Portrait: el vídeo/foto domina y los controles van debajo.
 * - Apaisado: los controles pasan a un panel lateral compacto para no apilar filas bajo la imagen.
 * - Fullscreen: oculta header/controles pesados/tira, no la salida ni la acción esencial.
 * - La tira solo aparece cuando existe al menos un fotograma (sin fotos no hay tira de solo Vídeo).
 */
internal data class AngleWorkspaceLayoutPlan(
    val orientation: AngleWorkspacePresentationOrientation,
    val controlsBesideMedia: Boolean,
    val showFilmstrip: Boolean,
    val fullscreen: Boolean,
    val showHeader: Boolean,
    val showStatus: Boolean,
    val mediaMinHeightDp: Int,
    /**
     * Tope de altura REAL de la zona de controles en portrait. Es lo que impide que el texto grande
     * (fontScale 2) o un motivo de error largo consuman la etapa: con scroll interno los controles
     * siguen alcanzables, pero nunca por encima de este tope.
     */
    val controlsMaxHeightDp: Int,
    val lateralPanelMaxWidthDp: Int = 280,
    val minTargetDp: Int = 48,
)

/** Alto real de la tira de miniaturas (56dp) más su padding vertical: se reserva aparte. */
private const val PORTRAIT_FILMSTRIP_RESERVE_DP = 64

/** Orientación de presentación a partir de los constraints: más ancho que alto es apaisado. */
internal fun angleWorkspacePresentationOrientation(
    widthDp: Int,
    heightDp: Int,
): AngleWorkspacePresentationOrientation =
    if (widthDp > heightDp) {
        AngleWorkspacePresentationOrientation.LANDSCAPE
    } else {
        AngleWorkspacePresentationOrientation.PORTRAIT
    }

/**
 * Plan de layout. No asume que la petición de orientación haya surtido efecto (Android 16 puede
 * ignorarla en ventanas grandes): todo se decide con el ancho/alto que realmente hay.
 *
 * `mediaMinHeightDp` es siempre positivo y `mediaMinHeightDp + controlsMaxHeightDp` nunca supera la
 * altura disponible (con la tira reservada aparte si existe), así que no se puede construir un
 * mínimo mayor que el máximo. El scroll de los controles, no el tamaño de la imagen, absorbe el
 * texto grande.
 */
internal fun angleWorkspaceLayoutPlan(
    widthDp: Int,
    heightDp: Int,
    framesExist: Boolean,
    fullscreen: Boolean,
    fontScale: Float = 1f,
): AngleWorkspaceLayoutPlan {
    val orientation = angleWorkspacePresentationOrientation(widthDp, heightDp)
    val available = heightDp.coerceAtLeast(1)
    val fraction = if (fontScale >= 2f) 0.55f else 0.65f
    val minTarget = 48
    // Reserva REAL de la etapa: nunca mayor que el alto disponible (sin mínimos imposibles).
    val mediaMin = (available * fraction).toInt().coerceIn(1, (available - minTarget).coerceAtLeast(1))
    // Los controles solo pueden quedarse con lo que sobre después de la etapa y la tira.
    val filmstripReserve = if (framesExist) PORTRAIT_FILMSTRIP_RESERVE_DP else 0
    val controlsMax = (available - mediaMin - filmstripReserve).coerceIn(1, available)
    return AngleWorkspaceLayoutPlan(
        orientation = orientation,
        controlsBesideMedia = orientation == AngleWorkspacePresentationOrientation.LANDSCAPE &&
            !fullscreen,
        showFilmstrip = framesExist && !fullscreen,
        fullscreen = fullscreen,
        showHeader = !fullscreen,
        // En fullscreen solo queda la salida y la acción esencial: el estado detallado no se
        // apila encima de la imagen.
        showStatus = !fullscreen,
        mediaMinHeightDp = mediaMin,
        controlsMaxHeightDp = controlsMax,
    )
}

/**
 * Objetivo de orientación **scoped** derivado de la orientación de la **media**, no de la ventana.
 *
 * La ventana y la media no tienen por qué coincidir: en un teléfono en portrait el workspace ocupa
 * una ventana vertical aunque el vídeo sea horizontal, y es justo ahí donde el fullscreen debe
 * poder girar. Por eso la decisión usa la geometría de la media:
 *
 * - fuera de fullscreen: `FULL_USER` (se respeta la rotación del usuario);
 * - fullscreen sin geometría: `FULL_USER` (no se inventa un giro sin fuente);
 * - fullscreen con media horizontal (`width > height`, ya postrotación): `LANDSCAPE` explícito,
 *   aunque la ventana siga en portrait;
 * - fullscreen con media vertical o cuadrada: `FULL_USER` (forzar landscape empeoraría un vídeo
 *   vertical ya limitado por el ancho físico).
 */
internal fun angleWorkspaceOrientationTarget(
    fullscreen: Boolean,
    mediaGeometry: VideoPresentationGeometry?,
): AngleWorkspaceOrientationTarget = when {
    !fullscreen -> AngleWorkspaceOrientationTarget.FOLLOW_USER
    mediaGeometry == null -> AngleWorkspaceOrientationTarget.FOLLOW_USER
    mediaGeometry.width > mediaGeometry.height -> AngleWorkspaceOrientationTarget.LANDSCAPE
    else -> AngleWorkspaceOrientationTarget.FOLLOW_USER
}

/** Siguiente zoom de encuadre para un paso real del control ±. Sin cambios de modo. */
internal fun angleWorkspaceNextCrop(
    current: CropViewportState,
    zoomIn: Boolean,
): CropViewportState {
    val step = 1.25
    val next = if (zoomIn) current.zoom * step else current.zoom / step
    return current.copy(zoom = next.coerceIn(MIN_CROP_ZOOM, MAX_CROP_ZOOM))
}

/** Estado de encuadre al pulsar Reset: FIT 1x centrado, sin tocar la fuente ni el fotograma. */
internal val angleWorkspaceResetCrop: CropViewportState = CropViewportState()

/** Encuadre por fuente (vídeo) y por fotograma, hoisted fuera de las ramas portrait/apaisado. */
internal data class AngleWorkspaceCrops(
    val source: CropViewportState = angleWorkspaceResetCrop,
    val byFrame: Map<AngleFrameId, CropViewportState> = emptyMap(),
) {
    /** Encuadre aplicable al fotograma activo: el suyo si existe; si no, el de la fuente. */
    fun forFrame(frameId: AngleFrameId?): CropViewportState =
        frameId?.let { byFrame[it] } ?: source

    /** Cambia el encuadre del objetivo actual (fotograma si hay; fuente si no) sin tocar otros. */
    fun withCrop(frameId: AngleFrameId?, crop: CropViewportState): AngleWorkspaceCrops =
        if (frameId == null) copy(source = crop) else copy(byFrame = byFrame + (frameId to crop))
}

/**
 * Registra el encuadre de una **nueva** captura copiando el de la fuente.
 *
 * [alreadyTracked] (o un id ya presente) NUNCA sobrescribe el encuadre previo del fotograma: el
 * usuario pudo ajustarlo y volver a pulsar «Medir» sobre el mismo instante.
 */
internal fun angleWorkspaceRegisterCapture(
    crops: AngleWorkspaceCrops,
    frameId: AngleFrameId,
    alreadyTracked: Boolean,
): AngleWorkspaceCrops = if (alreadyTracked || crops.byFrame.containsKey(frameId)) {
    crops
} else {
    crops.copy(byFrame = crops.byFrame + (frameId to crops.source))
}

/**
 * Mapper único para bitmap/vídeo y overlay: mismo encuadre, misma escala y traslación. `FIT` con
 * opt-in de zoom relativo (1x = imagen completa) y sin recorte automático.
 */
internal fun angleWorkspaceMapper(
    geometry: VideoPresentationGeometry?,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    crop: CropViewportState,
): VideoCoordinateMapper? {
    if (geometry == null || viewportWidthPx <= 0 || viewportHeightPx <= 0) return null
    if (geometry.width <= 0 || geometry.height <= 0) return null
    return VideoCoordinateMapper(
        video = geometry,
        viewport = ViewportSize(viewportWidthPx.toDouble(), viewportHeightPx.toDouble()),
        scaleMode = VideoScaleMode.FIT,
        cropAlignment = crop.alignment,
        zoom = crop.zoom,
        allowFitZoom = true,
    )
}
