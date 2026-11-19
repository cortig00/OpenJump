package com.openjump.app.ui.angles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.openjump.app.R
import com.openjump.app.ui.Formatting
import com.openjump.app.ui.components.EmptyState
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.history.HistoryDeleteDialogs
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.Spacing

/**
 * Número de fotograma que se muestra al usuario para el `frameIndex` persistido.
 *
 * `frameIndex` es un índice interno de base cero (el que guarda el análisis y el que viajan JSON y
 * CSV); la interfaz habla en base uno, igual que el editor (`angle_frame_anchor`), el detalle de
 * resultados y el selector de fotogramas. Por eso este detalle aplica la misma conversión que el
 * resto: sin ella el mismo análisis aparecía como "Frame 10" en el detalle y "Analysis frame: 11"
 * al editarlo. La conversión es sólo de presentación: no altera el dato persistido ni el formato de
 * exportación.
 */
internal fun angleSavedFrameDisplayNumber(frameIndex: Int): Int = frameIndex + 1

/**
 * Detalle compacto y de SOLO LECTURA de un análisis de ángulos guardado (familia legacy).
 *
 * Consultar el resultado no abre el vídeo: los valores persistidos (nombre, visibilidad, puntos y
 * grados recalculados) siguen visibles aunque la URI falte o ya no sea accesible.
 *
 * Por el contrato de producto (D2), un análisis preexistente SIN imagen anotada se consulta y no
 * ofrece editar ni reabrir el vídeo: se muestra un aviso de imagen no disponible y no se intenta
 * generar una imagen ni abrir el vídeo original. Borrar reutiliza la confirmación ya existente de
 * HISTORY, de modo que aquí no se crea un segundo camino de borrado ni un datastore/export paralelo.
 *
 * No dibuja geometría sobre el vídeo: sin editor abierto no hay frame confirmado y, por tanto, esta
 * pantalla nunca superpone marcas a un vídeo erróneo.
 */
@Composable
fun AngleSavedDetailScreen(
    analysisId: Long,
    viewModel: AngleSavedDetailViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val deleting by viewModel.deleting.collectAsState()
    val deleteFailed by viewModel.deleteFailed.collectAsState()
    var pendingDelete by rememberSaveable { mutableStateOf(false) }
    // El objetivo no es serializable; se reconstruye desde el id de la entrada (la confirmación
    // pendiente sí sobrevive a la recreación mediante el booleano guardable).
    val target = remember(analysisId) { angleSavedDeleteTarget(analysisId) }
    // La carga viaja con el id de la ruta: recrear la pantalla no relee si el VM ya tiene el dato,
    // y un id distinto (nunca debería ocurrir en esta entrada) sí fuerza una lectura coherente.
    LaunchedEffect(analysisId) { viewModel.load(analysisId) }
    // Al volver del editor (u otra pantalla) se relee la raíz sin vaciar lo ya visible: el detalle
    // no reabre vídeo ni pierde el contenido si la relectura falla.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Scaffold(
        modifier = modifier,
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(R.string.angle_saved_title),
                onNavigationClick = onBack,
                navigationContentDescription = stringResource(R.string.common_back),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            when (val current = state) {
                AngleSavedDetailState.Loading -> Text(
                    stringResource(R.string.angle_saved_loading),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                AngleSavedDetailState.Missing -> {
                    EmptyState(
                        stringResource(R.string.angle_saved_missing_title),
                        stringResource(R.string.angle_saved_missing_body),
                    )
                    BackButton(onBack)
                }

                AngleSavedDetailState.Error -> {
                    EmptyState(
                        stringResource(R.string.angle_saved_error_title),
                        stringResource(R.string.angle_saved_error_body),
                    )
                    Button(
                        onClick = viewModel::retry,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.common_retry))
                    }
                    BackButton(onBack)
                }

                is AngleSavedDetailState.Loaded -> {
                    val content = current.content
                    Text(
                        Formatting.dateTimeToText(content.createdAt),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(
                            R.string.angle_saved_frame,
                            angleSavedFrameDisplayNumber(content.frameIndex),
                        ),
                        style = OpenJumpTypes.Secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // D2: los análisis preexistentes no tienen imagen anotada. Se avisa y NO se
                    // ofrece editar ni reabrir el vídeo: no se genera una imagen automáticamente ni
                    // se intenta abrir el vídeo original. Las mediciones siguen consultables.
                    Text(
                        stringResource(R.string.angle_saved_no_image),
                        style = OpenJumpTypes.Secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        stringResource(
                            R.string.angle_saved_marks_title,
                            content.visibleMarkCount,
                            content.marks.size,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (content.marks.isEmpty()) {
                        Text(
                            stringResource(R.string.angle_saved_marks_empty),
                            style = OpenJumpTypes.Secondary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        content.marks.forEach { mark -> SavedMarkCard(mark) }
                    }
                    Text(
                        stringResource(R.string.angle_saved_read_only_note),
                        style = OpenJumpTypes.Secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { pendingDelete = true },
                        enabled = !deleting,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(
                            stringResource(R.string.result_delete_action),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    // Confirmación ya existente de HISTORY (result_delete_*), con la familia ANGLE explícita: el
    // borrado sólo puede apuntar a la raíz del análisis guardado y nunca a un id suelto.
    HistoryDeleteDialogs(
        pending = target.takeIf { pendingDelete },
        failed = target.takeIf { deleteFailed },
        onConfirm = {
            pendingDelete = false
            // Volver al origen sólo con la fila borrada: HISTORY refresca al reanudar.
            viewModel.delete(onDeleted = onBack)
        },
        onCancelPending = { pendingDelete = false },
        onRetry = { viewModel.delete(onDeleted = onBack) },
        onDismissFailure = viewModel::acknowledgeDeleteFailure,
    )
}

@Composable
internal fun SavedMarkCard(mark: AngleSavedDetailMark) {
    val locale = currentAppLocale()
    val degreesFormat = stringResource(R.string.angle_degrees_value)
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    mark.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val degrees = mark.degrees
                Text(
                    if (degrees != null) {
                        degreesFormat.format(formatAngleSavedDegrees(degrees, locale))
                    } else {
                        stringResource(R.string.angle_saved_value_invalid)
                    },
                    style = OpenJumpTypes.MetricValue,
                )
            }
            if (!mark.visible) {
                Text(
                    stringResource(R.string.angle_hidden),
                    style = OpenJumpTypes.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(
                    R.string.angle_saved_points,
                    formatAngleSavedPoint(mark.pointA.x, locale),
                    formatAngleSavedPoint(mark.pointA.y, locale),
                    formatAngleSavedPoint(mark.pointB.x, locale),
                    formatAngleSavedPoint(mark.pointB.y, locale),
                    formatAngleSavedPoint(mark.pointC.x, locale),
                    formatAngleSavedPoint(mark.pointC.y, locale),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.common_back))
    }
}
