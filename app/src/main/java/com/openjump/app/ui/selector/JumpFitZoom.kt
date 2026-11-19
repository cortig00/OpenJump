package com.openjump.app.ui.selector

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import kotlin.math.sqrt

/** Etiqueta del contenido con la arena de encuadre del zoom FIT de Saltos. */
internal const val JUMP_FIT_ZOOM_ARENA_TAG = "jump-fit-zoom-arena"
internal const val JUMP_FIT_ZOOM_OVERLAY_TAG = "jump-fit-zoom-overlay"

/** Paso aditivo de los botones − / + (el mismo que usa Ángulos). */
internal const val JUMP_FIT_ZOOM_STEP: Double = 0.5

/**
 * Política del zoom FIT del selector temporal de Saltos (1.3).
 *
 * Decisión aislada y comprobable: solo un borrador **temporal** explícito de Saltos adopta el zoom
 * local sobre la imagen completa. Sin borrador, sin eventos temporales, en Horizontal y en Encoder
 * (referencia/revisión/A-B) se conserva el flujo heredado FIT/CROP con sus controles, guardas y
 * revisión de placa sin ningún cambio. [temporalMarkingEventCount] son los eventos de marcado
 * temporales del borrador vigente; `null`/0 significa que no hay nada que marcar y por tanto no se
 * habilita el zoom.
 */
internal fun isJumpFitZoomEligible(
    protocolId: ProtocolId?,
    temporalMarkingEventCount: Int,
    encoderDraftPresent: Boolean,
): Boolean = protocolId != null &&
    protocolId != ProtocolId.HORIZONTAL &&
    !encoderDraftPresent &&
    temporalMarkingEventCount > 0

/**
 * Mapper vigente para el estado visual [state], reconstruido con la geometría y el viewport
 * actuales.
 *
 * Se crea con `allowFitZoom = true` y FIT: 1x es el fotograma completo centrado (letterbox), subir
 * el zoom no conmuta a CROP y el eje que no desborda queda centrado. Devuelve `null` si la fuente
 * o el viewport todavía no son válidos, de modo que ningún gesto o botón aplica un estado inválido.
 */
internal fun jumpFitZoomMapper(
    geometry: VideoPresentationGeometry?,
    viewportWidth: Double,
    viewportHeight: Double,
    state: CropViewportState,
): VideoCoordinateMapper? {
    if (geometry == null || geometry.width <= 0 || geometry.height <= 0) return null
    if (!viewportWidth.isFinite() || !viewportHeight.isFinite()) return null
    if (viewportWidth <= 0.0 || viewportHeight <= 0.0) return null
    return VideoCoordinateMapper(
        video = geometry,
        viewport = ViewportSize(viewportWidth, viewportHeight),
        scaleMode = VideoScaleMode.FIT,
        cropAlignment = state.alignment,
        zoom = state.zoom,
        allowFitZoom = true,
    )
}

/**
 * Reajusta la alineación a la geometría vigente.
 *
 * Un eje que a este zoom ya no desborda se recentra: el desplazamiento guardado de un tamaño
 * anterior no puede desplazar una imagen completa. Sin fuente o viewport válidos el estado no
 * cambia: nunca se aplica un encuadre sobre una geometría que aún no existe.
 */
internal fun jumpFitZoomNormalized(
    state: CropViewportState,
    geometry: VideoPresentationGeometry?,
    viewportWidth: Double,
    viewportHeight: Double,
): CropViewportState {
    val mapper = jumpFitZoomMapper(geometry, viewportWidth, viewportHeight, state)
        ?: return state
    if (mapper.cropOverflowX > 0.0 && mapper.cropOverflowY > 0.0) return state
    return CropViewportState(
        zoom = state.zoom,
        alignment = CropAlignment(
            x = if (mapper.cropOverflowX > 0.0) state.alignment.x else 0.5,
            y = if (mapper.cropOverflowY > 0.0) state.alignment.y else 0.5,
        ),
    )
}

/**
 * Estado siguiente tras un delta de pinza/arrastre.
 *
 * El mapper se reconstruye en el instante del evento con la geometría, el viewport y **el estado
 * acumulado** (no con el mapper de la composición previa), así que varios deltas antes de
 * recomponer se suman sobre lo ya aplicado. Se reutiliza `stateAfterGesture`, la misma aritmética
 * que el encuadre existente, de modo que la imagen y las coordenadas comparten transformación y el
 * punto bajo el centroide se conserva cuando los límites lo permiten. Entradas no finitas, un
 * factor de zoom no positivo o un centroide fuera de la imagen dejan el estado como estaba.
 */
internal fun jumpFitZoomStateAfterGesture(
    geometry: VideoPresentationGeometry?,
    viewportWidth: Double,
    viewportHeight: Double,
    state: CropViewportState,
    centroid: ImagePoint,
    zoomChange: Double,
    panDx: Double,
    panDy: Double,
): CropViewportState {
    val mapper = jumpFitZoomMapper(geometry, viewportWidth, viewportHeight, state)
        ?: return state
    if (!centroid.x.isFinite() || !centroid.y.isFinite()) return state
    if (!zoomChange.isFinite() || !panDx.isFinite() || !panDy.isFinite()) return state
    // Un factor de zoom no positivo no es una transformación válida: se ignora entero.
    if (zoomChange <= 0.0) return state
    val next = mapper.stateAfterGesture(
        centroid = centroid,
        zoomChange = zoomChange,
        panDeltaX = panDx,
        panDeltaY = panDy,
    )
    return jumpFitZoomNormalized(next, geometry, viewportWidth, viewportHeight)
}

/**
 * Estado siguiente de un paso aditivo de botón ([JUMP_FIT_ZOOM_STEP]).
 *
 * El paso se acota a 1–4x y se aplica como cambio relativo anclado en el centro del visor: se sube
 * exactamente 0.5x desde el zoom **acumulado** y, al llegar a 1x, el encuadre queda centrado.
 */
internal fun jumpFitZoomStateAfterStep(
    geometry: VideoPresentationGeometry?,
    viewportWidth: Double,
    viewportHeight: Double,
    state: CropViewportState,
    delta: Double,
): CropViewportState {
    if (!delta.isFinite()) return state
    val target = (state.zoom + delta).coerceIn(MIN_CROP_ZOOM, MAX_CROP_ZOOM)
    if (target == state.zoom) return state
    val mapper = jumpFitZoomMapper(geometry, viewportWidth, viewportHeight, state) ?: return state
    if (target == MIN_CROP_ZOOM) return CropViewportState()
    val next = mapper.stateAfterGesture(
        centroid = ImagePoint(mapper.viewport.width / 2.0, mapper.viewport.height / 2.0),
        zoomChange = target / state.zoom,
    )
    return jumpFitZoomNormalized(next, geometry, viewportWidth, viewportHeight)
}

/**
 * Arena de encuadre del zoom FIT de Saltos: **solo** el contenido de vídeo y **solo** dos o más
 * dedos.
 *
 * [content] (la superficie Media3 compartida) se compone **dentro** de este `Box`, de modo que el
 * detector es el padre del vídeo y ve el evento en el pase [PointerEventPass.Initial] antes que el
 * hijo. La timeline, los botones, el HUD y la etiqueta de fotograma son hermanos fuera de la arena:
 * un toque iniciado en ellos nunca llega aquí. No se sustituye la superficie por ninguna imagen.
 *
 * Semántica (misma que el encuadre de Ángulos):
 * - Con un dedo no se consume nada: el scroll, el marcado y los taps conservan su acción.
 * - Con dos dedos se activa el *latch* y se consume el resto del stream hasta que **todos** los
 *   punteros se levantan, incluido el dedo que queda solo, así que una pinza nunca marca, no
 *   desplaza la timeline y no activa un control al soltar.
 * - El último UP solo se consume si hubo latch: un toque simple sigue siendo del vídeo.
 * - Zoom y pan relativos al span/centroide se entregan al llamante, que aplica el cambio con
 *   [jumpFitZoomStateAfterGesture]; aquí no se calcula ninguna transformación distinta.
 *
 * La referencia del gesto son los **punteros concretos** ([PointerId]): cualquier cambio en el
 * conjunto de dedos (incluida una sustitución 2→2 en el mismo evento) reinicia centroide y span sin
 * aplicar ningún delta, así que un dedo sustituido nunca se interpreta como la misma pinza. El
 * latch, la cancelación y el consumo se conservan: antes del latch no se consume nada.
 *
 * La clave del detector es la identidad de la fuente ([interactionKey], p. ej. `videoUri`), no el
 * mapper de zoom: un cambio de encuadre no reinicia el gesto, y un cambio de fuente (o una
 * cancelación) lo cancela y descarta el latch porque todo su estado es local a [awaitEachGesture].
 */
@Composable
internal fun JumpFitZoomGestureArena(
    mapperProvider: () -> VideoCoordinateMapper?,
    interactionKey: Any?,
    enabled: Boolean,
    onGesture: (centroid: ImagePoint, zoomChange: Double, panDx: Double, panDy: Double) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val latestMapperProvider by rememberUpdatedState(mapperProvider)
    val latestOnGesture by rememberUpdatedState(onGesture)
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(JUMP_FIT_ZOOM_ARENA_TAG)
            .pointerInput(interactionKey, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    var previousCentroid = down.position
                    var previousSpan = 0.0
                    // Identidad real de los punteros, no sólo su número: una sustitución 2→2 (un
                    // dedo sube y otro baja en el mismo evento) reinicia la referencia sin
                    // aplicar ningún delta.
                    var previousIds: Set<PointerId> = setOf(down.id)
                    var latched = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            // Solo el final de un gesto multitáctil se consume; el UP de un toque
                            // simple sigue siendo del vídeo y de sus acciones normales.
                            if (latched) event.changes.forEach { it.consume() }
                            return@awaitEachGesture
                        }
                        val ids = pressed.mapTo(mutableSetOf()) { it.id }
                        val centroid = Offset(
                            pressed.sumOf { it.position.x.toDouble() }.toFloat() / pressed.size,
                            pressed.sumOf { it.position.y.toDouble() }.toFloat() / pressed.size,
                        )
                        if (pressed.size >= 2) {
                            latched = true
                            var sumDistanceSquared = 0.0
                            for (change in pressed) {
                                val dx = change.position.x - centroid.x
                                val dy = change.position.y - centroid.y
                                sumDistanceSquared += dx * dx + dy * dy
                            }
                            val span = sqrt(sumDistanceSquared / pressed.size)
                            if (ids == previousIds && previousSpan > 0.0 && span.isFinite()) {
                                val zoomChange = (span / previousSpan).coerceIn(0.01, 100.0)
                                if (latestMapperProvider() != null) {
                                    latestOnGesture(
                                        ImagePoint(
                                            previousCentroid.x.toDouble(),
                                            previousCentroid.y.toDouble(),
                                        ),
                                        zoomChange,
                                        (centroid.x - previousCentroid.x).toDouble(),
                                        (centroid.y - previousCentroid.y).toDouble(),
                                    )
                                }
                            }
                            previousSpan = span
                        } else if (latched) {
                            // Queda un dedo del gesto multitáctil: se consume sin marcar ni
                            // arrastrar nada hasta que TODOS los punteros suben.
                            previousSpan = 0.0
                        }
                        previousCentroid = centroid
                        previousIds = ids
                        if (latched) event.changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        content()
    }
}
