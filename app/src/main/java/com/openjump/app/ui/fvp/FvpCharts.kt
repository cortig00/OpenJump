package com.openjump.app.ui.fvp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private val CHART_HEIGHT = 190.dp

/**
 * MINIMO de la columna de numeros de los ejes Y. El ancho real es el INTRINSECO del texto (crece con
 * la escala de fuente del sistema) con este suelo: un valor de 7 cifras no se parte en dos lineas a
 * 150% de fontscale ni se recorta; la grafica se queda con el ancho restante.
 */
private val AXIS_LABEL_WIDTH = 62.dp

/**
 * Grafica F-V de los puntos por condicion con la recta ajustada.
 *
 * Reglas vinculantes:
 * - la recta SOLO es solida dentro de `[Vmin,Vmax]`; hacia V0 es DISCONTINUA (extrapolacion) y hay
 *   leyenda explicita (`fvp_chart_measured` / `fvp_chart_extrapolation`).
 * - nunca se dibuja un punto NaN/Infinito.
 * - etiquetas de eje como `Text` hermanas al `Canvas` (no pintadas en el lienzo).
 * - `contentDescription` con `fvp_chart_summary` en el orden documentado de tokens
 *   (1 protocolo, 2 numero de puntos, 3 Vmin, 4 Vmax, 5 F0, 6 V0).
 * - sin puntos plottables se muestra el estado vacio (`fvp_chart_empty`), nunca ejes desnudos
 *   (Muse P2-10), y la leyenda distingue cada entrada por FORMA (trazo solido / discontinuo /
 *   marcador), no por color (Muse P1-4).
 * - los numeros del eje Y nunca se parten ni se recortan: la columna toma el ancho intrinseco del
 *   texto (fuente escalada incluida) con el minimo de `AXIS_LABEL_WIDTH`.
 */
@Composable
fun FvpForceVelocityChart(
    points: List<FvpPointUi>,
    f0N: Double,
    sfvNsPerM: Double,
    vMinMps: Double,
    vMaxMps: Double,
    v0Mps: Double?,
    protocolLabel: String,
    modifier: Modifier = Modifier,
) {
    val locale = currentAppLocale()
    val plottable = remember(points) { points.filter { it.forceN.isFinite() && it.velocityMps.isFinite() } }
    val summary = chartSummary(protocolLabel, plottable.size, vMinMps, vMaxMps, f0N, v0Mps, locale)
    var selectedIndex by rememberSaveable { mutableIntStateOf(-1) }
    val lineColor = OpenJumpTheme.colors.dataBlue
    val pointColor = OpenJumpTheme.colors.positive
    val invalidColor = MaterialTheme.colorScheme.onSurfaceVariant
    val selectedColor = OpenJumpTheme.colors.warning
    val axisColor = MaterialTheme.colorScheme.outlineVariant

    val maxV = maxOf(
        vMaxMps.takeIf { it.isFinite() } ?: 0.0,
        v0Mps?.takeIf { it.isFinite() } ?: 0.0,
        plottable.maxOfOrNull { it.velocityMps } ?: 0.0,
    ).takeIf { it > 0.0 } ?: 1.0
    val maxF = max(
        f0N.takeIf { it.isFinite() } ?: 0.0,
        plottable.maxOfOrNull { it.forceN } ?: 0.0,
    ).takeIf { it > 0.0 } ?: 1.0
    // La recta se dibuja solo con F0/Sfv finitos y Sfv > 0; la extrapolacion DISCONTINUA exige
    // ademas V0 finito por encima de Vmax. La leyenda de extrapolacion se muestra bajo la MISMA
    // condicion que el trazo (P2: nunca una leyenda de una linea que no se pinta).
    val canDrawLine = f0N.isFinite() && sfvNsPerM.isFinite() && sfvNsPerM > 0.0
    val drawsExtrapolation = canDrawLine && vMinMps.isFinite() && vMaxMps.isFinite() &&
        v0Mps?.let { it.isFinite() && it > vMaxMps } == true

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = ShapeTokens.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(stringResource(R.string.fvp_chart_fv), style = OpenJumpTypes.SectionTitle)
            if (plottable.isEmpty()) {
                // Muse P2-10: sin puntos plottables no se dejan ejes desnudos: se explica el estado.
                FvpChartEmptyState(tag = "fvp-chart-empty-fv")
            } else {
                // Columna de numeros dimensionada por el ancho INTRINSECO del texto (fuente
                // escalada incluida) con el minimo AXIS_LABEL_WIDTH; el lienzo conserva el resto.
                Row(verticalAlignment = Alignment.Top) {
                    Column(
                        modifier = Modifier
                            .widthIn(min = AXIS_LABEL_WIDTH)
                            .width(IntrinsicSize.Max)
                            .height(CHART_HEIGHT),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.End,
                    ) {
                        Text(
                            format(maxF, locale),
                            style = OpenJumpTypes.Label,
                            // Igualar nodo y parrafo al ancho real evita que Compose conserve un
                            // parrafo mas ancho que el nodo al aplicar el suelo de 62 dp.
                            modifier = Modifier.fillMaxWidth().testTag("fvp-chart-axis-max-fv"),
                            textAlign = TextAlign.End,
                        )
                        Text("F (N)", style = OpenJumpTypes.Label)
                        Text(format(0.0, locale), style = OpenJumpTypes.Label)
                    }
                    // Las etiquetas del eje V son hermanas del lienzo DENTRO de esta columna: su
                    // sangria es el ancho real de la columna de numeros, no una constante corta.
                    Column(modifier = Modifier.weight(1f)) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(CHART_HEIGHT)
                                .testTag("fvp-chart-canvas-fv")
                                .pointerInput(plottable) {
                                    detectTapGestures { tap ->
                                        selectedIndex = nearestPoint(
                                            plottable,
                                            maxV,
                                            maxF,
                                            size.width,
                                            size.height,
                                            tap,
                                        )
                                    }
                                }
                                .semantics { contentDescription = summary },
                        ) {
                            val left = 8.dp.toPx()
                            val right = size.width - 8.dp.toPx()
                            val top = 10.dp.toPx()
                            val bottom = size.height - 10.dp.toPx()
                            val width = (right - left).coerceAtLeast(1f)
                            val height = (bottom - top).coerceAtLeast(1f)
                            fun x(v: Double): Float = left + (v / maxV).toFloat().coerceIn(0f, 1f) * width
                            fun y(f: Double): Float = bottom - (f / maxF).toFloat().coerceIn(0f, 1f) * height

                            drawLine(axisColor, Offset(left, top), Offset(left, bottom), 1.dp.toPx())
                            drawLine(axisColor, Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())

                            val slope = sfvNsPerM
                            if (canDrawLine && vMinMps.isFinite() && vMaxMps.isFinite()) {
                                val solid = Path()
                                solid.moveTo(x(vMinMps), y(f0N - slope * vMinMps))
                                solid.lineTo(x(vMaxMps), y(f0N - slope * vMaxMps))
                                drawPath(solid, lineColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
                                if (drawsExtrapolation) {
                                    val dashed = Path()
                                    dashed.moveTo(x(vMaxMps), y(f0N - slope * vMaxMps))
                                    dashed.lineTo(x(v0Mps!!), y(0.0))
                                    drawPath(
                                        dashed,
                                        lineColor,
                                        style = Stroke(
                                            width = 2.dp.toPx(),
                                            cap = StrokeCap.Round,
                                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 12f)),
                                        ),
                                    )
                                }
                                // Vmin..Vmax highlight: the measured band is always visible as text too.
                                drawLine(
                                    lineColor.copy(alpha = 0.25f),
                                    Offset(x(vMinMps), bottom),
                                    Offset(x(vMaxMps), bottom),
                                    4.dp.toPx(),
                                )
                            }
                            plottable.forEachIndexed { index, point ->
                                val center = Offset(x(point.velocityMps), y(point.forceN))
                                val color = when {
                                    !point.valid -> invalidColor
                                    index == selectedIndex -> selectedColor
                                    else -> pointColor
                                }
                                val radius = if (index == selectedIndex) 7.dp.toPx() else 4.dp.toPx()
                                if (point.valid) {
                                    drawCircle(color, radius, center)
                                } else {
                                    drawCircle(color, radius, center, style = Stroke(width = 2.dp.toPx()))
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("0", style = OpenJumpTypes.Label)
                            Text("V (m/s)", style = OpenJumpTypes.Label)
                            Text(format(maxV, locale), style = OpenJumpTypes.Label)
                        }
                    }
                }
                val selected = plottable.getOrNull(selectedIndex)
                if (selected != null) {
                    Text(
                        "${selected.label}: ${format(selected.forceN, locale)} N · " +
                            "${format(selected.velocityMps, locale)} m/s",
                        style = OpenJumpTypes.Secondary,
                    )
                }
                // Muse P1-4: cada entrada lleva su propia muestra (trazo solido / discontinuo /
                // marcador), no el mismo punto azul para todas.
                LegendRow(
                    entries = buildList {
                        add(LegendSample.SOLID_LINE to stringResource(R.string.fvp_chart_measured))
                        if (drawsExtrapolation) {
                            add(LegendSample.DASHED_LINE to stringResource(R.string.fvp_chart_extrapolation))
                        }
                    },
                )
                // Muse P2-10: una linea de "como leerlo" en lenguaje llano.
                Text(
                    stringResource(R.string.fvp_chart_read_fv),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("fvp-chart-read-fv"),
                )
                Text(
                    stringResource(R.string.fvp_a11y_chart_hint),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Grafica P-V con la curva del modelo `P(V) = (F0 - Sfv·V)·V` y el marcador de Pmax en `V0/2`.
 *
 * La curva solo se dibuja cuando V0 y Pmax son finitos (perfil fisico); con `NON_PHYSICAL` no hay
 * curva y los puntos se siguen mostrando como datos. Nada de NaN en el lienzo. Sin puntos
 * plottables se muestra el estado vacio (`fvp_chart_empty`) y la leyenda distingue trazo solido y
 * marcador de Pmax por forma (Muse P1-4/P2-10).
 *
 * Los numeros del eje Y nunca se parten ni se recortan: la columna toma el ancho intrinseco del
 * texto (fuente escalada incluida) con el minimo de `AXIS_LABEL_WIDTH`.
 */
@Composable
fun FvpPowerVelocityChart(
    points: List<FvpPointUi>,
    f0N: Double,
    sfvNsPerM: Double,
    pmaxW: Double?,
    v0Mps: Double?,
    vMinMps: Double,
    vMaxMps: Double,
    protocolLabel: String,
    modifier: Modifier = Modifier,
) {
    val locale = currentAppLocale()
    val plottable = remember(points) { points.filter { it.velocityMps.isFinite() && it.powerW.isFinite() } }
    val summary = chartSummary(
        protocolLabel,
        plottable.size,
        vMinMps,
        vMaxMps,
        f0N,
        v0Mps,
        locale,
    )
    var selectedIndex by rememberSaveable { mutableIntStateOf(-1) }
    val curveColor = OpenJumpTheme.colors.dataBlue
    val pointColor = OpenJumpTheme.colors.positive
    val invalidColor = MaterialTheme.colorScheme.onSurfaceVariant
    val markerColor = OpenJumpTheme.colors.warning
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val drawable = v0Mps != null && v0Mps.isFinite() && v0Mps > 0.0 &&
        pmaxW != null && pmaxW.isFinite() && pmaxW > 0.0 && f0N.isFinite() && sfvNsPerM.isFinite()
    val maxV = maxOf(
        if (drawable) v0Mps!! else 0.0,
        plottable.maxOfOrNull { it.velocityMps } ?: 0.0,
        vMaxMps.takeIf { it.isFinite() } ?: 0.0,
    ).takeIf { it > 0.0 } ?: 1.0
    val maxP = maxOf(
        if (drawable) pmaxW!! else 0.0,
        plottable.maxOfOrNull { it.powerW } ?: 0.0,
    ).takeIf { it > 0.0 } ?: 1.0

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = ShapeTokens.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(stringResource(R.string.fvp_chart_pv), style = OpenJumpTypes.SectionTitle)
            if (plottable.isEmpty()) {
                // Muse P2-10: sin puntos plottables no se dejan ejes desnudos: se explica el estado.
                FvpChartEmptyState(tag = "fvp-chart-empty-pv")
            } else {
                // Columna de numeros dimensionada por el ancho INTRINSECO del texto (fuente
                // escalada incluida) con el minimo AXIS_LABEL_WIDTH; el lienzo conserva el resto.
                Row(verticalAlignment = Alignment.Top) {
                    Column(
                        modifier = Modifier
                            .widthIn(min = AXIS_LABEL_WIDTH)
                            .width(IntrinsicSize.Max)
                            .height(CHART_HEIGHT),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.End,
                    ) {
                        Text(
                            format(maxP, locale),
                            style = OpenJumpTypes.Label,
                            modifier = Modifier.fillMaxWidth().testTag("fvp-chart-axis-max-pv"),
                            textAlign = TextAlign.End,
                        )
                        Text("P (W)", style = OpenJumpTypes.Label)
                        Text(format(0.0, locale), style = OpenJumpTypes.Label)
                    }
                    // Las etiquetas del eje V son hermanas del lienzo DENTRO de esta columna: su
                    // sangria es el ancho real de la columna de numeros, no una constante corta.
                    Column(modifier = Modifier.weight(1f)) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(CHART_HEIGHT)
                                .testTag("fvp-chart-canvas-pv")
                                .pointerInput(plottable) {
                                    detectTapGestures { tap ->
                                        selectedIndex = nearestPowerPoint(
                                            plottable,
                                            maxV,
                                            maxP,
                                            size.width,
                                            size.height,
                                            tap,
                                        )
                                    }
                                }
                                .semantics { contentDescription = summary },
                        ) {
                            val left = 8.dp.toPx()
                            val right = size.width - 8.dp.toPx()
                            val top = 10.dp.toPx()
                            val bottom = size.height - 10.dp.toPx()
                            val width = (right - left).coerceAtLeast(1f)
                            val height = (bottom - top).coerceAtLeast(1f)
                            fun x(v: Double): Float = left + (v / maxV).toFloat().coerceIn(0f, 1f) * width
                            fun y(p: Double): Float = bottom - (p / maxP).toFloat().coerceIn(0f, 1f) * height

                            drawLine(axisColor, Offset(left, top), Offset(left, bottom), 1.dp.toPx())
                            drawLine(axisColor, Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())

                            if (drawable) {
                                val v0 = v0Mps!!
                                val curve = Path()
                                val samples = 48
                                for (i in 0..samples) {
                                    val v = v0 * i / samples
                                    val p = (f0N - sfvNsPerM * v) * v
                                    if (!p.isFinite()) continue
                                    val point = Offset(x(v), y(p))
                                    if (i == 0) curve.moveTo(point.x, point.y) else curve.lineTo(point.x, point.y)
                                }
                                drawPath(curve, curveColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
                                val markerV = v0 / 2.0
                                drawLine(
                                    markerColor.copy(alpha = 0.7f),
                                    Offset(x(markerV), y(0.0)),
                                    Offset(x(markerV), y(pmaxW!!)),
                                    2.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                                )
                                drawCircle(markerColor, 6.dp.toPx(), Offset(x(markerV), y(pmaxW!!)))
                            }
                            plottable.forEachIndexed { index, point ->
                                val center = Offset(x(point.velocityMps), y(point.powerW))
                                val color = when {
                                    !point.valid -> invalidColor
                                    index == selectedIndex -> markerColor
                                    else -> pointColor
                                }
                                val radius = if (index == selectedIndex) 7.dp.toPx() else 4.dp.toPx()
                                if (point.valid) {
                                    drawCircle(color, radius, center)
                                } else {
                                    drawCircle(color, radius, center, style = Stroke(width = 2.dp.toPx()))
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("0", style = OpenJumpTypes.Label)
                            Text("V (m/s)", style = OpenJumpTypes.Label)
                            Text(format(maxV, locale), style = OpenJumpTypes.Label)
                        }
                    }
                }
                val selected = plottable.getOrNull(selectedIndex)
                if (selected != null) {
                    Text(
                        "${selected.label}: ${format(selected.powerW, locale)} W · " +
                            "${format(selected.velocityMps, locale)} m/s",
                        style = OpenJumpTypes.Secondary,
                    )
                }
                // Muse P1-4: muestra propia por entrada (trazo solido / marcador de Pmax).
                LegendRow(
                    entries = buildList {
                        add(LegendSample.SOLID_LINE to stringResource(R.string.fvp_chart_measured))
                        if (drawable) {
                            add(LegendSample.MARKER to stringResource(R.string.fvp_chart_pmax_at_v0_half))
                        }
                    },
                )
                if (!drawable) {
                    Text(
                        stringResource(R.string.fvp_warning_non_physical),
                        style = OpenJumpTypes.Secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Muse P2-10: "como leerlo" sin prometer una curva que no existe (NON_PHYSICAL).
                Text(
                    stringResource(
                        if (drawable) R.string.fvp_chart_read_pv else R.string.fvp_chart_read_pv_no_curve,
                    ),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("fvp-chart-read-pv"),
                )
            }
        }
    }
}

/** Texto del estado vacio de una grafica sin puntos plottables (Muse P2-10). */
@Composable
private fun FvpChartEmptyState(tag: String) {
    Text(
        stringResource(R.string.fvp_chart_empty),
        style = OpenJumpTypes.Secondary,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = Spacing.sm).testTag(tag),
    )
}

/**
 * Muestra visual de una entrada de leyenda. La FORMA (trazo solido, trazo discontinuo, marcador)
 * distingue las series sin depender del color y coincide con lo que pinta cada grafica.
 */
private enum class LegendSample { SOLID_LINE, DASHED_LINE, MARKER }

@Composable
private fun LegendRow(entries: List<Pair<LegendSample, String>>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        entries.forEach { (sample, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                LegendSwatch(sample)
                Text(label, style = OpenJumpTypes.Secondary)
            }
        }
    }
}

/** Muestra de leyenda con el MISMO color y trazo que la grafica correspondiente. */
@Composable
private fun LegendSwatch(sample: LegendSample) {
    val lineColor = OpenJumpTheme.colors.dataBlue
    val markerColor = OpenJumpTheme.colors.warning
    Canvas(modifier = Modifier.size(width = 18.dp, height = 10.dp)) {
        val centerY = size.height / 2f
        when (sample) {
            LegendSample.SOLID_LINE -> drawLine(
                lineColor,
                Offset(0f, centerY),
                Offset(size.width, centerY),
                2.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
            LegendSample.DASHED_LINE -> drawLine(
                lineColor,
                Offset(0f, centerY),
                Offset(size.width, centerY),
                2.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f)),
            )
            LegendSample.MARKER -> drawCircle(
                markerColor,
                size.minDimension / 2f,
                Offset(size.width / 2f, centerY),
            )
        }
    }
}

/**
 * Resumen accesible de la grafica. Orden de tokens documentado:
 * 1 protocolo, 2 numero de puntos, 3 Vmin, 4 Vmax, 5 F0, 6 V0 (indefinido cuando V0 no existe).
 */
@Composable
private fun chartSummary(
    protocolLabel: String,
    pointCount: Int,
    vMinMps: Double,
    vMaxMps: Double,
    f0N: Double,
    v0Mps: Double?,
    locale: Locale,
): String = stringResource(
    R.string.fvp_chart_summary,
    protocolLabel,
    pointCount.toString(),
    format(vMinMps, locale),
    format(vMaxMps, locale),
    format(f0N, locale),
    v0Mps?.let { format(it, locale) } ?: stringResource(R.string.fvp_undefined_value),
)

private fun format(value: Double, locale: Locale): String =
    if (value.isFinite()) String.format(locale, "%.2f", value) else "—"

private fun nearestPoint(
    points: List<FvpPointUi>,
    maxV: Double,
    maxF: Double,
    widthPx: Int,
    heightPx: Int,
    tap: Offset,
): Int {
    if (points.isEmpty()) return -1
    val left = 8f
    val right = (widthPx - 8f).coerceAtLeast(left + 1f)
    val top = 10f
    val bottom = (heightPx - 10f).coerceAtLeast(top + 1f)
    val spanX = (right - left).coerceAtLeast(1f)
    val spanY = (bottom - top).coerceAtLeast(1f)
    return points.indices.minByOrNull { index ->
        val point = points[index]
        val px = left + (point.velocityMps / maxV).toFloat().coerceIn(0f, 1f) * spanX
        val py = bottom - (point.forceN / maxF).toFloat().coerceIn(0f, 1f) * spanY
        abs(tap.x - px) + abs(tap.y - py)
    } ?: -1
}

private fun nearestPowerPoint(
    points: List<FvpPointUi>,
    maxV: Double,
    maxP: Double,
    widthPx: Int,
    heightPx: Int,
    tap: Offset,
): Int {
    if (points.isEmpty()) return -1
    val left = 8f
    val right = (widthPx - 8f).coerceAtLeast(left + 1f)
    val top = 10f
    val bottom = (heightPx - 10f).coerceAtLeast(top + 1f)
    val spanX = (right - left).coerceAtLeast(1f)
    val spanY = (bottom - top).coerceAtLeast(1f)
    return points.indices.minByOrNull { index ->
        val point = points[index]
        val px = left + (point.velocityMps / maxV).toFloat().coerceIn(0f, 1f) * spanX
        val py = bottom - (point.powerW / maxP).toFloat().coerceIn(0f, 1f) * spanY
        abs(tap.x - px) + abs(tap.y - py)
    } ?: -1
}
