package com.openjump.app.ui.angles

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.ui.selector.VideoCoordinateMapper
import kotlin.math.sqrt

/** Etiqueta del contenido con la arena de encuadre (solo imagen+capa, nunca los HUD). */
internal const val ANGLE_WORKSPACE_FRAMING_ARENA_TAG = "angle-workspace-framing-arena"

/**
 * Arena de encuadre del workspace: **solo** dos o más dedos.
 *
 * Es un contenedor REAL ([content]) del contenido visual: la superficie de vídeo o el lienzo
 * congelado con su capa de geometría se componen **dentro** de este `Box`, no como hermanos
 * posteriores. Compose entrega el puntero al hermano más alto de la pila y no lo reenvía a los de
 * debajo, así que una arena hermana tapaba el overlay y ningún toque llegaba a la capa de edición.
 * Con el contenido dentro, el detector de esta arena es el **padre** del overlay y puede ver el
 * evento en el pase [PointerEventPass.Initial] (de padre a hijo) antes de que la capa lo procese en
 * `Main`. Los HUD, el transporte, la timeline y los botones siguen siendo hermanos FUERA de este
 * `Box`, de modo que un toque iniciado en ellos nunca entra aquí ni modifica la timeline.
 *
 * No se usa `sharePointerInputWithSiblings`: el reparto se resuelve por jerarquía y pase, no por un
 * parche de hit-test.
 *
 * Semántica:
 * - Con un solo puntero no consume nada: el overlay de edición (o el propio vídeo) conserva el gesto.
 * - Al llegar a dos punteros se activa el *latch*: se consume el stream completo **hasta que todos
 *   los punteros se levantan**, incluido el caso de que quede uno solo. Un dedo restante no coloca
 *   puntos, no arrastra asas y no encuadra.
 * - El último `UP` solo se consume si el latch estaba activo; un toque de un solo dedo nunca se
 *   consume aquí (si se consumiera, el overlay perdería taps y confirmaciones).
 * - El cambio de encuadre se calcula como zoom relativo al span y pan del centroide (misma aritmética
 *   del gesto que el encuadre existente) y se aplica con
 *   [VideoCoordinateMapper.stateAfterGesture]; no se añade ningún algoritmo de coordenadas nuevo.
 * - La clave del detector es la identidad del contenido ([interactionKey], estable: fuente +
 *   generación + fotograma), nunca el mapper de zoom, así que un gesto no se reinicia al cambiar el
 *   encuadre. Un cambio de escena cancela el gesto en curso.
 *
 * [onFramingGesture] recibe el centroide y deltas reales del gesto; el llamante decide el nuevo
 * encuadre con el mapper vigente. [onBusyChanged] avisa de que hay un encuadre multitáctil activo
 * para que el propietario inhiba OTRAS acciones (nunca la propia arena ni la capa de edición).
 */
@Composable
internal fun AngleWorkspaceFramingArena(
    mapperProvider: () -> VideoCoordinateMapper?,
    interactionKey: Any?,
    enabled: Boolean,
    onFramingGesture: (centroid: ImagePoint, zoomChange: Double, panDx: Double, panDy: Double) -> Unit,
    onBusyChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val latestMapperProvider by rememberUpdatedState(mapperProvider)
    val latestOnFramingGesture by rememberUpdatedState(onFramingGesture)
    val latestOnBusyChanged by rememberUpdatedState(onBusyChanged)
    DisposableEffect(interactionKey) {
        onDispose { latestOnBusyChanged(false) }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(ANGLE_WORKSPACE_FRAMING_ARENA_TAG)
            .pointerInput(interactionKey, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    // Pase Initial: el padre ve el DOWN antes que la capa de edición (que escucha en
                    // Main, de hijo a padre). Así el latch multitáctil puede consumir el stream
                    // completo antes de que el overlay coloque o confirme nada.
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    var previousCentroid = down.position
                    var previousSpan = 0.0
                    var previousCount = 1
                    var latched = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) {
                                // Solo el final de un gesto multitáctil se consume; el UP de un toque
                                // simple sigue siendo del overlay (selección/confirmación/tap).
                                if (latched) event.changes.forEach { it.consume() }
                                return@awaitEachGesture
                            }
                            val centroid = Offset(
                                pressed.sumOf { it.position.x.toDouble() }.toFloat() / pressed.size,
                                pressed.sumOf { it.position.y.toDouble() }.toFloat() / pressed.size,
                            )
                            if (pressed.size >= 2) {
                                latched = true
                                latestOnBusyChanged(true)
                                var sumDistanceSquared = 0.0
                                for (change in pressed) {
                                    val dx = change.position.x - centroid.x
                                    val dy = change.position.y - centroid.y
                                    sumDistanceSquared += dx * dx + dy * dy
                                }
                                val span = sqrt(sumDistanceSquared / pressed.size)
                                if (previousCount >= 2 && previousSpan > 0.0 && span.isFinite()) {
                                    val zoomChange = (span / previousSpan).coerceIn(0.01, 100.0)
                                    val panDx = centroid.x - previousCentroid.x
                                    val panDy = centroid.y - previousCentroid.y
                                    if (latestMapperProvider() != null) {
                                        latestOnFramingGesture(
                                            ImagePoint(
                                                previousCentroid.x.toDouble(),
                                                previousCentroid.y.toDouble(),
                                            ),
                                            zoomChange,
                                            panDx.toDouble(),
                                            panDy.toDouble(),
                                        )
                                    }
                                }
                                previousSpan = span
                            } else if (latched) {
                                // Queda un dedo del gesto multitáctil: se sigue consumiendo sin
                                // colocar ni arrastrar nada; el latch dura hasta que TODOS suben.
                                previousSpan = 0.0
                            }
                            previousCentroid = centroid
                            previousCount = pressed.size
                            // Un solo puntero NUNCA se consume aquí: el gesto pertenece a la capa
                            // de edición o al vídeo. Solo el latch multitáctil consume el stream.
                            if (latched) event.changes.forEach { it.consume() }
                        }
                    } finally {
                        latestOnBusyChanged(false)
                    }
                }
            },
    ) {
        content()
    }
}
