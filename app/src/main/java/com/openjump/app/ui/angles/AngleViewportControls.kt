package com.openjump.app.ui.angles

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.ui.selector.CropAlignment
import com.openjump.app.ui.selector.CropViewportState
import com.openjump.app.ui.selector.MAX_CROP_ZOOM
import com.openjump.app.ui.selector.MIN_CROP_ZOOM
import com.openjump.app.ui.selector.VideoCoordinateMapper
import com.openjump.app.ui.selector.VideoScaleMode
import com.openjump.app.ui.selector.ViewportSize
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing

/**
 * Geometría del visor de ángulos, separada de la composición para poder fijarla en pruebas JVM.
 *
 * El visor es el protagonista de la pantalla (design system §13), así que su alto **no** puede ser
 * un 16:9 fijo: un vídeo vertical dentro de una caja apaisada queda diminuto y con franjas negras
 * enormes, que es justo el defecto que corrige este slice. El alto se deriva del aspecto real
 * (dimensiones post-rotación del índice o de la imagen congelada) y queda acotado:
 *
 * - el modo normal no pasa de [ANGLE_VIEWPORT_NORMAL_MAX_FRACTION] del espacio disponible;
 * - el modo ampliado puede llegar a todo el espacio disponible;
 * - nunca se sale del espacio disponible ni baja del mínimo útil.
 *
 * Funciones puras (`Float` en dp): no leen estado, no dependen de Compose y no crean players.
 */

/** Aspecto de reserva mientras no hay geometría publicada (equivalente al 16:9 anterior). */
internal const val ANGLE_VIEWPORT_FALLBACK_ASPECT: Float = 16f / 9f

/** Alto mínimo útil del visor. */
internal const val ANGLE_VIEWPORT_MIN_PANE_DP: Float = 160f

/** Fracción máxima del espacio disponible con el visor sin ampliar. */
internal const val ANGLE_VIEWPORT_NORMAL_MAX_FRACTION: Float = 0.60f

/** Fracción máxima del espacio disponible con el visor ampliado. */
internal const val ANGLE_VIEWPORT_EXPANDED_MAX_FRACTION: Float = 1f

/** Ganancia mínima (dp) que se considera mejora real al ampliar; medida para pruebas y presupuesto. */
internal const val ANGLE_VIEWPORT_EXPANSION_MIN_GAIN_DP: Float = 1f

/** Paso de zoom de los botones − / + (mismo en visor en vivo e imagen congelada). */
internal const val ANGLE_ZOOM_STEP: Double = 0.5

/** Alto reservado para la barra compacta de controles del visor (48 dp + padding). */
internal const val ANGLE_VIEWPORT_CONTROLS_BAR_DP: Float = 64f

/** Alto mínimo del panel secundario que nunca se lleva el visor. */
internal const val ANGLE_VIEWPORT_MIN_PANEL_DP: Float = 96f

/**
 * Espacio máximo para el visor tras reservar la barra de controles y un panel mínimo.
 *
 * El visor es el protagonista, pero no puede comerse la barra compacta (la única vía de vuelta
 * cuando está ampliado) ni dejar el panel secundario sin espacio: el alto final se acota con este
 * presupuesto. Cualquier entrada no finita o no positiva se normaliza y el resultado siempre es
 * finito y positivo (en pantallas absurdamente bajas queda 1 dp; el panel manda).
 */
internal fun angleVideoPaneBudgetDp(availableHeightDp: Float): Float {
    val available = availableHeightDp.takeIf { it.isFinite() && it > 0f } ?: 1f
    return (available - ANGLE_VIEWPORT_CONTROLS_BAR_DP - ANGLE_VIEWPORT_MIN_PANEL_DP)
        .coerceAtLeast(1f)
}

/** Aspecto de presentación (ancho/alto) de la geometría ya rotada; `null` si no hay. */
internal fun angleVideoAspect(geometry: VideoPresentationGeometry?): Float? {
    if (geometry == null) return null
    if (geometry.width <= 0 || geometry.height <= 0) return null
    val aspect = geometry.width.toFloat() / geometry.height.toFloat()
    return aspect.takeIf { it.isFinite() && it > 0f }
}

/**
 * Alto del visor para un ancho y un espacio disponibles.
 *
 * Con aspecto conocido se intenta mostrar el vídeo a ancho completo; eso da alturas muy distintas
 * según el clip (un vertical pide mucho más alto que un 16:9) y por eso se acota con una fracción
 * del espacio disponible en lugar de con un 16:9 fijo. Cualquier entrada no finita o no positiva se
 * normaliza antes de calcular: la función nunca devuelve `NaN`, negativos ni un alto mayor que el
 * espacio disponible.
 */
internal fun angleVideoPaneHeightDp(
    paneWidthDp: Float,
    availableHeightDp: Float,
    aspect: Float?,
    expanded: Boolean,
): Float {
    val width = paneWidthDp.takeIf { it.isFinite() && it > 0f } ?: 1f
    val available = availableHeightDp.takeIf { it.isFinite() && it > 0f } ?: 1f
    val safeAspect = aspect?.takeIf { it.isFinite() && it > 0f } ?: ANGLE_VIEWPORT_FALLBACK_ASPECT
    val desired = width / safeAspect
    val fraction = if (expanded) {
        ANGLE_VIEWPORT_EXPANDED_MAX_FRACTION
    } else {
        ANGLE_VIEWPORT_NORMAL_MAX_FRACTION
    }
    val ceiling = (available * fraction).coerceAtMost(available).coerceAtLeast(1f)
    // El mínimo nunca puede superar el techo: en pantallas muy bajas manda el techo, no el mínimo.
    val floor = ANGLE_VIEWPORT_MIN_PANE_DP.coerceAtMost(ceiling)
    return desired.coerceIn(floor, ceiling)
}

/**
 * Alto FINAL del visor: el mismo que aplica la pantalla, ya acotado por [angleVideoPaneBudgetDp].
 *
 * Es lo que se mide para decidir si ampliar cambia algo de verdad: comparar alturas sin el
 * presupuesto daría por buena una ampliación que el layout luego no puede conceder.
 */
internal fun angleVideoFinalPaneHeightDp(
    paneWidthDp: Float,
    availableHeightDp: Float,
    aspect: Float?,
    expanded: Boolean,
): Float {
    val pane = angleVideoPaneHeightDp(paneWidthDp, availableHeightDp, aspect, expanded)
    return pane.coerceAtMost(angleVideoPaneBudgetDp(availableHeightDp)).coerceAtLeast(1f)
}

/**
 * Alto real de imagen VISIBLE dentro del visor con ajuste FIT (sin recorte).
 *
 * Es la magnitud que percibe quien mira la pantalla: ampliar solo aporta si este valor crece. El
 * alto visible nunca supera el del panel ni el que pide el ancho disponible para el aspecto real.
 */
internal fun angleVideoRenderedHeightDp(
    paneWidthDp: Float,
    availableHeightDp: Float,
    aspect: Float?,
    expanded: Boolean,
): Float {
    val width = paneWidthDp.takeIf { it.isFinite() && it > 0f } ?: 1f
    val safeAspect = aspect?.takeIf { it.isFinite() && it > 0f } ?: ANGLE_VIEWPORT_FALLBACK_ASPECT
    val pane = angleVideoFinalPaneHeightDp(paneWidthDp, availableHeightDp, safeAspect, expanded)
    return minOf(pane, width / safeAspect)
}

/**
 * `true` si ampliar el visor aumenta de forma apreciable la imagen visible de verdad.
 *
 * Compara los dos altos FINALES (ya acotados por el presupuesto de panel) y la parte FIT realmente
 * visible: un clip apaisado a ancho completo ya está en su máximo en modo normal, así que el botón
 * no se ofrece encendido para una ampliación sin efecto. Reducir desde ampliado siempre es posible
 * ([AngleViewportControls] mantiene el botón activo cuando `expanded`).
 */
internal fun angleViewportCanExpand(
    paneWidthDp: Float,
    availableHeightDp: Float,
    aspect: Float?,
): Boolean {
    val normal = angleVideoRenderedHeightDp(paneWidthDp, availableHeightDp, aspect, expanded = false)
    val expanded = angleVideoRenderedHeightDp(paneWidthDp, availableHeightDp, aspect, expanded = true)
    return expanded - normal >= ANGLE_VIEWPORT_EXPANSION_MIN_GAIN_DP
}

/** Un delta de encuadre tal como lo entrega `CropFramingOverlay` (centroide, zoom relativo y pan). */
internal data class AngleCropGesture(
    val centroid: ImagePoint,
    val zoomChange: Double,
    val panDeltaX: Double,
    val panDeltaY: Double,
)

/**
 * Mapper del encuadre construido con la geometría y el tamaño REALES del visor.
 *
 * Los gestos pueden encadenarse varias veces antes de que Compose recomponga: si el gesto reutiliza
 * el mapper del frame composicionado, cada delta parte del estado viejo y el último gana. Por eso el
 * gesto lo reconstruye con el estado del instante, y este helper concentra ese cálculo (mismo FIT
 * con opt-in de zoom que el mapper publicado y que la imagen congelada).
 */
internal fun angleCropMapper(
    geometry: VideoPresentationGeometry?,
    viewportWidth: Int,
    viewportHeight: Int,
    zoom: Double,
    alignment: CropAlignment,
): VideoCoordinateMapper? {
    if (geometry == null || viewportWidth <= 0 || viewportHeight <= 0) return null
    return VideoCoordinateMapper(
        video = geometry,
        viewport = ViewportSize(viewportWidth.toDouble(), viewportHeight.toDouble()),
        scaleMode = VideoScaleMode.FIT,
        cropAlignment = alignment,
        zoom = zoom,
        allowFitZoom = true,
    )
}

/**
 * Estado del encuadre tras UN gesto, partiendo del estado REAL en el instante del evento.
 *
 * Función pura: aplicada en secuencia a los deltas que llegan antes de recomponer, los acumula
 * (zoom relativo y pan sobre el resultado anterior). Sin geometría o viewport todavía medido no hay
 * gesto posible y el estado se conserva.
 */
internal fun angleCropStateAfterGesture(
    geometry: VideoPresentationGeometry?,
    viewportWidth: Int,
    viewportHeight: Int,
    state: CropViewportState,
    gesture: AngleCropGesture,
): CropViewportState {
    val mapper = angleCropMapper(
        geometry = geometry,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        zoom = state.zoom,
        alignment = state.alignment,
    ) ?: return state
    return mapper.stateAfterGesture(
        centroid = gesture.centroid,
        zoomChange = gesture.zoomChange,
        panDeltaX = gesture.panDeltaX,
        panDeltaY = gesture.panDeltaY,
    )
}

/** Etiquetas de prueba de los controles del visor (pruebas de UI instrumentadas). */
internal const val ANGLE_VIDEO_PANE_TAG = "angle-video-pane"
internal const val ANGLE_VIEWPORT_ZOOM_OUT_TAG = "angle-viewport-zoom-out"
internal const val ANGLE_VIEWPORT_ZOOM_IN_TAG = "angle-viewport-zoom-in"
internal const val ANGLE_VIEWPORT_ZOOM_RESET_TAG = "angle-viewport-zoom-reset"
internal const val ANGLE_VIEWPORT_EXPAND_TAG = "angle-viewport-expand"

/**
 * Controles compactos del visor, agrupados sobre el vídeo.
 *
 * El zoom (+) / (−) / reinicio son **independientes** del botón de ampliar: el primero cambia la
 * escala de la imagen (1x = imagen completa) y el segundo solo el tamaño del visor, sin reorientar la
 * Activity, sin recrear la superficie y sin tocar el player. Todos los objetivos son de 48 dp y cada
 * icono lleva descripción propia; el grupo de zoom y la ampliación van separados por un hueco y por
 * una línea divisoria para que no se confundan.
 */
@Composable
internal fun AngleViewportControls(
    zoom: Double,
    zoomPercentText: String?,
    expanded: Boolean,
    canExpand: Boolean,
    canReset: Boolean,
    zoomEnabled: Boolean,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onReset: () -> Unit,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zoomOutDescription = stringResource(R.string.angle_zoom_out)
    val zoomInDescription = stringResource(R.string.angle_zoom_in)
    val resetDescription = stringResource(R.string.angle_zoom_reset)
    val expandDescription = stringResource(
        if (expanded) R.string.angle_viewport_collapse else R.string.angle_viewport_expand,
    )
    val groupDescription = stringResource(R.string.angle_viewport_controls_description)
    val expandedStateDescription = stringResource(
        if (expanded) R.string.angle_viewport_state_expanded else R.string.angle_viewport_state_normal,
    )
    Surface(
        color = Color.Black.copy(alpha = 0.72f),
        contentColor = Color.White,
        shape = ShapeTokens.small,
        modifier = modifier.semantics { contentDescription = groupDescription },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewportSymbolButton(
                symbol = "−",
                description = zoomOutDescription,
                enabled = zoomEnabled && zoom > MIN_CROP_ZOOM,
                onClick = onZoomOut,
                modifier = Modifier.testTag(ANGLE_VIEWPORT_ZOOM_OUT_TAG),
            )
            ViewportSymbolButton(
                symbol = "+",
                description = zoomInDescription,
                enabled = zoomEnabled && zoom < MAX_CROP_ZOOM,
                onClick = onZoomIn,
                modifier = Modifier.testTag(ANGLE_VIEWPORT_ZOOM_IN_TAG),
            )
            ViewportSymbolButton(
                symbol = "↺",
                description = resetDescription,
                enabled = zoomEnabled && canReset,
                onClick = onReset,
                modifier = Modifier.testTag(ANGLE_VIEWPORT_ZOOM_RESET_TAG),
            )
            if (zoomPercentText != null) {
                Text(
                    text = zoomPercentText,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            }
            // Separación explícita del grupo de ampliación: no es otro paso de zoom.
            Text(
                text = "|",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.5f),
            )
            Button(
                onClick = onToggleExpanded,
                enabled = canExpand || expanded,
                colors = viewportButtonColors(),
                border = viewportButtonBorder(canExpand || expanded),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                modifier = Modifier
                    .size(Spacing.xxxl)
                    .semantics {
                        contentDescription = expandDescription
                        stateDescription = expandedStateDescription
                    }
                    .testTag(ANGLE_VIEWPORT_EXPAND_TAG),
            ) {
                Icon(
                    painter = painterResource(
                        if (expanded) R.drawable.ic_angle_collapse else R.drawable.ic_angle_expand,
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(Spacing.xl),
                )
            }
        }
    }
}

/** Botón cuadrado de 48 dp con símbolo y descripción: no depende de texto visible para TalkBack. */
@Composable
private fun ViewportSymbolButton(
    symbol: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = viewportButtonColors(),
        border = viewportButtonBorder(enabled),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        modifier = modifier
            .size(Spacing.xxxl)
            .semantics { contentDescription = description },
    ) {
        Text(symbol, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun viewportButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Color.Black.copy(alpha = 0.78f),
    contentColor = Color.White,
    disabledContainerColor = Color.Black.copy(alpha = 0.48f),
    disabledContentColor = Color.White.copy(alpha = 0.62f),
)

@Composable
private fun viewportButtonBorder(enabled: Boolean) = BorderStroke(
    width = 1.dp,
    color = Color.White.copy(alpha = if (enabled) 0.9f else 0.45f),
)
