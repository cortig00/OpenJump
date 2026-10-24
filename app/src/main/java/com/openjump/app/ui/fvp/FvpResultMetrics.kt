package com.openjump.app.ui.fvp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing

/**
 * Un valor YA formateado del modelo y la unidad con la que termina (`null` si no lleva unidad,
 * p. ej. R²).
 *
 * El texto completo se pinta en un UNICO nodo de texto: la MISMA cadena y las MISMAS cifras SI que
 * antes ("2554,7 N"), pero la unidad se compone subordinada (color y tamano de
 * [OpenJumpTypes.MetricUnit]). Asi no se duplica el formateo del modelo ni se reescribe ningun
 * valor en la capa de presentacion.
 */
internal data class FvpMetricEntry(
    val text: String,
    val unit: String?,
)

/**
 * Grupo de metricas del Resumen F–V–P, LOCAL a esta seccion: no usa el `MetricCard` compartido
 * (que aplica `primaryContainer` de hero y etiquetas en mayusculas a otras areas de la app).
 *
 * Composicion deliberadamente sobria: superficie neutra por celda, etiqueta en su caja natural (sin
 * `uppercase`, para no convertir unidades SI en titulares), cifra dominante y unidad subordinada.
 * Solo las glosas CRITICAS y breves acompanan a la cifra (extrapolado/no medido, maximo del
 * modelo, ajuste que no valida); las definiciones extensas y todas las notas anteriores viven en
 * [FvpMetricDefinitionsDialog], accesible por una accion de 48 dp.
 */
@Composable
internal fun FvpMetricGroup(
    f0: FvpMetricEntry,
    sfv: FvpMetricEntry,
    v0: FvpMetricEntry,
    pmax: FvpMetricEntry,
    r2: FvpMetricEntry,
    measuredRangeText: String,
) {
    var helpOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().testTag("fvp-metrics"),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        FvpMetricPair(first = { cellModifier ->
            FvpMetricCell(
                label = stringResource(R.string.fvp_metric_f0),
                entry = f0,
                modifier = cellModifier,
                rangeHint = measuredRangeText,
                gloss = stringResource(R.string.fvp_result_gloss_short_extrapolated),
                glossTag = "fvp-gloss-f0",
                valueTag = "fvp-metric-value-f0",
            )
        }, second = { cellModifier ->
            FvpMetricCell(
                label = stringResource(R.string.fvp_metric_v0),
                entry = v0,
                modifier = cellModifier,
                rangeHint = measuredRangeText,
                gloss = stringResource(R.string.fvp_result_gloss_short_extrapolated),
                glossTag = "fvp-gloss-v0",
                valueTag = "fvp-metric-value-v0",
            )
        })
        FvpMetricPair(first = { cellModifier ->
            // Sfv no lleva glosa critica propia: su definicion (cuanto cae la fuerza al moverse
            // mas rapido) es una DEFINICION, y se consulta completa en la ayuda.
            FvpMetricCell(
                label = stringResource(R.string.fvp_metric_sfv),
                entry = sfv,
                modifier = cellModifier,
                valueTag = "fvp-metric-value-sfv",
            )
        }, second = { cellModifier ->
            FvpMetricCell(
                label = stringResource(R.string.fvp_metric_pmax),
                entry = pmax,
                modifier = cellModifier,
                gloss = stringResource(R.string.fvp_result_gloss_short_pmax),
                glossTag = "fvp-gloss-pmax",
                valueTag = "fvp-metric-value-pmax",
            )
        })
        FvpMetricCell(
            label = stringResource(R.string.fvp_metric_r2),
            entry = r2,
            modifier = Modifier.fillMaxWidth(),
            gloss = stringResource(R.string.fvp_result_gloss_short_r2),
            glossTag = "fvp-gloss-r2",
            valueTag = "fvp-metric-value-r2",
        )
        // Accion EXPLICITA de 48 dp: las definiciones largas no se imponen como parrafo.
        TextButton(
            onClick = { helpOpen = true },
            modifier = Modifier.heightIn(min = 48.dp).testTag("fvp-metrics-help"),
        ) {
            Text(stringResource(R.string.fvp_result_metric_help_action))
        }
    }
    if (helpOpen) {
        FvpMetricDefinitionsDialog(onDismiss = { helpOpen = false })
    }
}

/** Con letra ampliada se apilan las cifras: ni numeros ni unidades se parten entre columnas. */
@Composable
private fun FvpMetricPair(
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    if (LocalDensity.current.fontScale >= 1.3f) {
        first(Modifier.fillMaxWidth())
        second(Modifier.fillMaxWidth())
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            first(Modifier.weight(1f))
            second(Modifier.weight(1f))
        }
    }
}

/**
 * Una cifra del grupo: etiqueta en su forma natural (SIN mayusculas forzadas), valor con la unidad
 * subordinada y, opcionalmente, el rango medido y la glosa critica breve.
 */
@Composable
private fun FvpMetricCell(
    label: String,
    entry: FvpMetricEntry,
    modifier: Modifier = Modifier,
    rangeHint: String? = null,
    gloss: String? = null,
    glossTag: String? = null,
    valueTag: String,
) {
    Surface(
        modifier = modifier,
        shape = ShapeTokens.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                label,
                style = OpenJumpTypes.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = fvpMetricAnnotatedValue(entry),
                style = OpenJumpTypes.MetricValue,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag(valueTag),
            )
            rangeHint?.let { hint ->
                Text(
                    hint,
                    style = OpenJumpTypes.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            gloss?.let { text ->
                Text(
                    text,
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (glossTag != null) Modifier.testTag(glossTag) else Modifier,
                )
            }
        }
    }
}

/**
 * Valor con la unidad subordinada SIN cambiar su contenido: el nodo de semantica expone la misma
 * cadena que antes. Si el texto no termina en la unidad esperada (p. ej. el marcador localizado de
 * valor indefinido) se pinta entero, sin recortar ni inventar una unidad.
 */
@Composable
private fun fvpMetricAnnotatedValue(entry: FvpMetricEntry): androidx.compose.ui.text.AnnotatedString {
    val unit = entry.unit
    val hasUnit = unit != null &&
        entry.text.length > unit.length &&
        entry.text.endsWith(unit) &&
        entry.text[entry.text.length - unit.length - 1] == ' '
    if (!hasUnit || unit == null) return buildAnnotatedString { append(entry.text) }
    val number = entry.text.dropLast(unit.length).trimEnd()
    val unitColor = MaterialTheme.colorScheme.onSurfaceVariant
    return buildAnnotatedString {
        append(number)
        append(" ")
        withStyle(SpanStyle(color = unitColor, fontSize = OpenJumpTypes.MetricUnit.fontSize)) {
            append(unit)
        }
    }
}

/**
 * Definiciones COMPLETAS de las cifras del Resumen, en una superficie modal bajo demanda.
 *
 * Incluye TODOS los recursos explicativos que antes vivian incrustados en el Resumen (las glosas
 * largas F0/Sfv/V0/Pmax/R², la nota de R², la de extrapolacion del rango, la de puntos estimados
 * frente a linea y la introduccion llana): nada se elimina, solo deja de ser un muro de texto
 * permanente. No anade diagnosticos ni prescripciones.
 */
@Composable
private fun FvpMetricDefinitionsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fvp_result_metric_help_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                FvpMetricDefinition(R.string.fvp_gloss_f0, "fvp-metric-help-f0")
                FvpMetricDefinition(R.string.fvp_gloss_sfv, "fvp-metric-help-sfv")
                FvpMetricDefinition(R.string.fvp_gloss_v0, "fvp-metric-help-v0")
                FvpMetricDefinition(R.string.fvp_gloss_pmax, "fvp-metric-help-pmax")
                FvpMetricDefinition(R.string.fvp_gloss_r2, "fvp-metric-help-r2")
                FvpMetricDefinition(R.string.fvp_r2_notice, "fvp-metric-help-r2-notice")
                FvpMetricDefinition(R.string.fvp_measured_range_note, "fvp-metric-help-range")
                FvpMetricDefinition(R.string.fvp_result_estimated_note, "fvp-metric-help-estimated")
                FvpMetricDefinition(R.string.fvp_result_fv_intro, "fvp-metric-help-intro")
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp).testTag("fvp-metric-help-close"),
            ) {
                Text(stringResource(R.string.common_back))
            }
        },
    )
}

@Composable
private fun FvpMetricDefinition(textRes: Int, tag: String) {
    Text(
        stringResource(textRes),
        style = OpenJumpTypes.Secondary,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(tag),
    )
}
