package com.openjump.app.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.ui.Formatting
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing

/**
 * Fila de la familia Ángulos en HISTORY: icono genérico de ángulo, datos REALES ya existentes
 * (recuento de marcas, atleta, fecha) y el borrado detrás de un menú secundario.
 *
 * No lee miniaturas ni imágenes, no proyecta grados y no inventa recuentos de fotogramas: un
 * análisis guardado no persiste ningún valor derivado y la lista no lo recalcula. La fila navega
 * entera; el menú `⋯` es la única vía de borrado y sólo existe con [onDelete] no nulo.
 *
 * El estado del menú se recuerda por la identidad persistida de la fila (fuente + id) y el callback
 * se lee siempre del último valor compuesto: un menú abierto no puede quedar aplicable a otra
 * entrada ni ejecutar un borrado que ya no corresponde.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AngleHistoryEntryRow(
    entry: AngleAnalysisHistoryEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = false,
    onDelete: (() -> Unit)? = null,
    deleting: Boolean = false,
) {
    val rowTag = angleHistoryRowTag(entry)
    var menuExpanded by remember(rowTag) { mutableStateOf(false) }
    val currentOnDelete by rememberUpdatedState(onDelete)
    val currentOnClick by rememberUpdatedState(onClick)
    val optionsLabel = stringResource(R.string.angle_history_options)

    // Sin callback de borrado no hay menú que ofrecer; con un borrado en vuelo el menú se cierra en
    // vez de quedar abierto sobre una acción ya reclamada.
    LaunchedEffect(onDelete == null, deleting) {
        if (onDelete == null || deleting) menuExpanded = false
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeTokens.large)
                .clickable(role = Role.Button, onClick = { currentOnClick() })
                .testTag(rowTag),
            shape = ShapeTokens.large,
            // Contenedor bajo sobre la superficie de la lista: separa la fila sin convertirla en
            // una tarjeta por métrica.
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                    .heightIn(min = 72.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_angle_history),
                    // Decorativo: el recuento, el atleta y la fecha ya son texto; repetir el icono en
                    // TalkBack sólo añadiría ruido.
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Spacing.xxxl),
                )
                Column(modifier = Modifier.weight(1f)) {
                    // Título = tipo de análisis, en el tono PRIMARIO de la familia: la fila se
                    // identifica por lo que es, no por una etiqueta secundaria encima del recuento.
                    Text(
                        stringResource(R.string.history_type_angle),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        entry.athleteName ?: stringResource(R.string.athlete_unassigned),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = OpenJumpTypes.Secondary,
                    )
                    // Metadatos compactos en UNA fila que envuelve sin truncar: recuento de marcas
                    // persistidas y fecha, cada uno en su propio texto. No se inventa recuento de
                    // fotogramas ni se proyectan grados: no hay valor derivado guardado.
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Text(
                            pluralStringResource(
                                R.plurals.history_angle_count,
                                entry.markCount,
                                entry.markCount,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = OpenJumpTypes.Secondary,
                        )
                        Text(
                            Formatting.dateTimeToText(entry.dateTime),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = OpenJumpTypes.Secondary,
                        )
                    }
                }
                if (onDelete != null) {
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            enabled = !deleting,
                            modifier = Modifier
                                .size(Spacing.xxxl)
                                .testTag(angleHistoryOptionsTag(entry))
                                .semantics { contentDescription = optionsLabel },
                        ) {
                            AngleOverflowGlyph(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DropdownMenu(
                            // Este menú sólo existe dentro de la rama con callback: sin autoridad de
                            // borrado no queda ninguna acción ofrecible.
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                modifier = Modifier
                                    .heightIn(min = Spacing.xxxl)
                                    .testTag(angleHistoryDeleteOptionTag(entry)),
                                // Un borrado en vuelo deshabilita la acción: no hay segundo toque.
                                enabled = !deleting,
                                text = {
                                    Text(
                                        stringResource(R.string.result_delete_action),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    // Cerrar primero y ejecutar el callback vigente: la acción no
                                    // puede quedar abierta apuntando a otra entrada.
                                    menuExpanded = false
                                    currentOnDelete?.invoke()
                                },
                            )
                        }
                    }
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = Spacing.lg),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/**
 * Puntos verticales del menú secundario.
 *
 * Se dibujan en vez de añadirse como recurso porque el vector de desbordamiento no existe en el set
 * actual y este slice no puede introducir recursos fuera de su lista. Es decorativo: la descripción
 * accesible la pone el propio [IconButton].
 */
@Composable
private fun AngleOverflowGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(width = Spacing.xs, height = Spacing.lg)) {
        val radius = size.width / 2f
        val centerX = size.width / 2f
        drawCircle(color = color, radius = radius, center = Offset(centerX, radius))
        drawCircle(color = color, radius = radius, center = Offset(centerX, size.height / 2f))
        drawCircle(color = color, radius = radius, center = Offset(centerX, size.height - radius))
    }
}

/**
 * Etiquetas estables de fila, menú y acción: contienen fuente persistida + id, no son texto visible
 * y sirven a las pruebas de UI para distinguir dos raíces distintas que comparten número.
 */
internal fun angleHistoryRowTag(entry: AngleAnalysisHistoryEntry): String =
    "angle-history-row-${entry.historyTagKey()}"

internal fun angleHistoryOptionsTag(entry: AngleAnalysisHistoryEntry): String =
    "angle-history-options-${entry.historyTagKey()}"

internal fun angleHistoryDeleteOptionTag(entry: AngleAnalysisHistoryEntry): String =
    "angle-history-delete-${entry.historyTagKey()}"

private fun AngleAnalysisHistoryEntry.historyTagKey(): String = when (source) {
    AngleHistorySource.LEGACY -> "legacy-$id"
    AngleHistorySource.MULTI_FRAME -> "v2-$id"
}
