package com.openjump.app.ui.angles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.openjump.app.R
import com.openjump.app.angles.Angle
import com.openjump.app.angles.AngleEditorState
import com.openjump.app.angles.AngleMeasurement
import com.openjump.app.angles.AngleId
import com.openjump.app.angles.AngleMarker
import com.openjump.app.angles.AnglePoint
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.ui.selector.CropViewportState
import com.openjump.app.ui.theme.Spacing

internal const val ANGLE_INSPECTOR_TAG = "angle-inspector"
internal const val ANGLE_INSPECTOR_CLOSE_TAG = "angle-inspector-close"
internal const val ANGLE_INSPECTOR_ADD_ANGLE_TAG = "angle-inspector-add-angle"
internal const val ANGLE_INSPECTOR_ADJUST_DRAFT_TAG = "angle-inspector-adjust-draft"
internal const val ANGLE_INSPECTOR_DISCARD_DRAFT_TAG = "angle-inspector-discard-draft"
internal const val ANGLE_INSPECTOR_ZOOM_IN_TAG = "angle-inspector-zoom-in"
internal const val ANGLE_INSPECTOR_ZOOM_OUT_TAG = "angle-inspector-zoom-out"
internal const val ANGLE_INSPECTOR_ZOOM_RESET_TAG = "angle-inspector-zoom-reset"

/** Etiqueta estable de la fila de un ángulo real de la lista del inspector. */
internal fun angleInspectorRowTag(angleId: AngleId): String = "angle-inspector-row-${angleId.value}"

/**
 * Inspector de alta precisión del workspace ([AngleEditorWorkspace]).
 *
 * Es un panel **modal** (no un panel permanente que le quite sitio al vídeo) con:
 *
 * - los controles accesibles de puntos ya existentes ([AngleAccessiblePointControls]) con
 *   `showToggle = false` y `expanded = true`: un solo gesto para llegar al cursor;
 * - el objetivo derivado de la función pura ya existente ([angleInspectorPointTarget]): colocar el
 *   siguiente punto del borrador, ajustar A o B **ya colocados** sin descartar el borrador, o
 *   inspeccionar las marcas de un ángulo existente conservando A/B pendientes;
 * - la lista de ángulos **reales** del fotograma con su nombre, grados del locale, visibilidad,
 *   renombrado, color y borrado con confirmación, reutilizando los diálogos ya existentes
 *   ([RenameAngleDialog], [AngleColorDialog]) y el límite compartido de nombre;
 * - una acción **explícita** de descarte del borrador A/B (nunca se descarta por elegir otro
 *   ángulo ni por seleccionar otro fotograma).
 *
 * Este composable **no** muta el editor ni inventa ids: todas las intenciones suben al llamante con
 * el fotograma ya fijado (y sus guardas de frame/fuente/generación) y cada callback devuelve si la
 * operación se aplicó de verdad. El cursor solo se limpia cuando el llamante confirma un cambio
 * real: un rechazo no dibuja un avance falso.
 *
 * @param editor estado canónico del fotograma activo (o `null` si aún no hay editor).
 * @param geometry geometría de presentación del bitmap congelado; sin ella no hay cursor (el resto
 *   del panel sigue disponible).
 * @param frameKey identidad del fotograma dibujado (id + fuente + generación). Reinicia el cursor y
 *   la marca elegida: el cursor de otro fotograma no se reutiliza.
 * @param placementIntent intención explícita de colocar un A nuevo («Otro ángulo»).
 * @param enabled `false` durante un guardado o sin permiso de edición: no se ofrece avanzar.
 * @param onConfirm actions devuelven `true` solo si el ViewModel aplicó el cambio.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AngleWorkspaceInspector(
    editor: AngleEditorState?,
    geometry: VideoPresentationGeometry?,
    crop: CropViewportState? = null,
    onZoomIn: (() -> Unit)? = null,
    onZoomOut: (() -> Unit)? = null,
    onResetCrop: (() -> Unit)? = null,
    frameKey: String?,
    placementIntent: Boolean,
    enabled: Boolean,
    formatDegrees: (Double) -> String,
    onDismiss: () -> Unit,
    onStartAnotherAngle: () -> Unit,
    onSelectAngle: (AngleId) -> Unit,
    onPlacePoint: (AnglePoint) -> Boolean,
    onMoveMarker: (AngleId, AngleMarker, AnglePoint) -> Boolean,
    onMoveDraftMarker: (AngleMarker, AnglePoint) -> Boolean,
    onCancelDraft: () -> Boolean,
    onRename: (AngleId, String) -> Boolean,
    onSetColor: (AngleId, Int) -> Boolean,
    onSetVisible: (AngleId, Boolean) -> Boolean,
    onDelete: (AngleId) -> Boolean,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val angles = editor?.angles.orEmpty()
    val draftPoints = editor?.draft?.points.orEmpty()
    val selectedAngle: Angle? = editor?.selectedAngleId?.let { id ->
        angles.firstOrNull { angle -> angle.id == id }
    }
    // La marca elegida solo vale para ESTE fotograma y ESTE ángulo: al cambiar de fotograma o de
    // ángulo inspeccionado se vuelve a A. Un cambio de marca nunca mueve la anterior.
    var marker by remember(frameKey, selectedAngle?.id) { mutableStateOf(AngleMarker.A) }
    // Ajustar A/B es una intención explícita del usuario; colocarse el siguiente punto es el
    // comportamiento por defecto y no requiere descartar nada.
    var adjustDraft by remember(frameKey) { mutableStateOf(false) }
    val draftAdjustable = draftPoints.isNotEmpty()
    // Fase vacía de colocación: sin borrador, sin ángulo elegido y sin ángulos medidos, el
    // inspector ofrece el primer A (nunca «elige un ángulo» de una lista vacía).
    val emptyPlacement = draftPoints.isEmpty() && selectedAngle == null &&
        editor?.angles.isNullOrEmpty()
    val target = angleInspectorPointTarget(
        draftPoints = draftPoints,
        selectedAngle = selectedAngle,
        marker = marker,
        adjustDraft = adjustDraft && draftAdjustable,
        placementIntent = placementIntent || emptyPlacement,
    )
    val targetKind = when (target) {
        is AngleAccessibleTarget.Draft -> "draft"
        is AngleAccessibleTarget.ExistingDraft -> "existing-draft"
        is AngleAccessibleTarget.ExistingAngle -> "existing-angle"
        AngleAccessibleTarget.PickAngle, null -> "pick"
    }
    // El cursor no sobrevive a un cambio de fotograma/ángulo/marca/tipo de objetivo: era el bug
    // histórico de mover B con la posición de A.
    val cursorKey = "$frameKey|${selectedAngle?.id?.value}|${marker.name}|$targetKind"
    var cursor by remember(cursorKey) { mutableStateOf<AnglePoint?>(null) }
    var renameTarget by remember(frameKey) { mutableStateOf<Angle?>(null) }
    var colorTarget by remember(frameKey) { mutableStateOf<Angle?>(null) }
    var deleteTarget by remember(frameKey) { mutableStateOf<Angle?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(ANGLE_INSPECTOR_TAG),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.angle_workspace_precision),
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .heightIn(min = Spacing.xxxl)
                        .testTag(ANGLE_INSPECTOR_CLOSE_TAG),
                ) {
                    Text(stringResource(R.string.angle_workspace_inspector_close))
                }
            }
            if (geometry != null) {
                AngleAccessiblePointControls(
                    target = target ?: AngleAccessibleTarget.Draft(AngleMarker.A),
                    geometry = geometry,
                    marker = marker,
                    enabled = enabled,
                    expanded = true,
                    cursor = cursor,
                    onExpandedChange = {},
                    onMarkerChange = { option -> marker = option },
                    onCursorChange = { position -> cursor = position },
                    onConfirm = { confirmMarker, point ->
                        val current = target
                        val accepted = when (current) {
                            is AngleAccessibleTarget.Draft -> onPlacePoint(point)
                            is AngleAccessibleTarget.ExistingDraft ->
                                onMoveDraftMarker(current.marker, point)

                            is AngleAccessibleTarget.ExistingAngle ->
                                onMoveMarker(current.angleId, confirmMarker, point)

                            AngleAccessibleTarget.PickAngle, null -> false
                        }
                        if (accepted) cursor = null
                        accepted
                    },
                    showToggle = false,
                )
            }
            // Encuadre real de la FOTO en el inspector: zoom relativo ± y reset 1x, mismo estado
            // hoisted que el mapper del bitmap/overlay. Objetivos 48dp.
            if (geometry != null && crop != null &&
                onZoomIn != null && onZoomOut != null && onResetCrop != null
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    TextButton(
                        onClick = onZoomOut,
                        enabled = enabled && crop.zoom > com.openjump.app.ui.selector.MIN_CROP_ZOOM,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Spacing.xxxl)
                            .testTag(ANGLE_INSPECTOR_ZOOM_OUT_TAG),
                    ) {
                        Text(stringResource(R.string.angle_workspace_zoom_out))
                    }
                    TextButton(
                        onClick = onZoomIn,
                        enabled = enabled && crop.zoom < com.openjump.app.ui.selector.MAX_CROP_ZOOM,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Spacing.xxxl)
                            .testTag(ANGLE_INSPECTOR_ZOOM_IN_TAG),
                    ) {
                        Text(stringResource(R.string.angle_workspace_zoom_in))
                    }
                    TextButton(
                        onClick = onResetCrop,
                        enabled = enabled,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Spacing.xxxl)
                            .testTag(ANGLE_INSPECTOR_ZOOM_RESET_TAG),
                    ) {
                        Text(stringResource(R.string.angle_workspace_zoom_reset))
                    }
                }
            }
            if (draftAdjustable) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    TextButton(
                        onClick = {
                            // Ajustar A/B debe elegir un marcador YA colocado y válido (A por
                            // defecto) y limpiar el cursor. Si el usuario venía de C (p. ej. una C
                            // rechazada), el target deja de apuntar a C y reaparecen los chips.
                            adjustDraft = true
                            marker = AngleMarker.A
                            cursor = null
                        },
                        enabled = enabled,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Spacing.xxxl)
                            .testTag(ANGLE_INSPECTOR_ADJUST_DRAFT_TAG),
                    ) {
                        Text(stringResource(R.string.angle_workspace_inspector_adjust_draft))
                    }
                    TextButton(
                        onClick = {
                            if (onCancelDraft()) {
                                adjustDraft = false
                                cursor = null
                            }
                        },
                        enabled = enabled,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Spacing.xxxl)
                            .testTag(ANGLE_INSPECTOR_DISCARD_DRAFT_TAG),
                    ) {
                        Text(stringResource(R.string.angle_workspace_inspector_discard_draft))
                    }
                }
            } else if (angles.isNotEmpty()) {
                TextButton(
                    onClick = onStartAnotherAngle,
                    enabled = enabled,
                    modifier = Modifier
                        .heightIn(min = Spacing.xxxl)
                        .testTag(ANGLE_INSPECTOR_ADD_ANGLE_TAG),
                ) {
                    Text(stringResource(R.string.angle_workspace_add_angle))
                }
            }
            Text(
                text = stringResource(R.string.angle_workspace_inspector_angles),
                style = MaterialTheme.typography.titleMedium,
            )
            if (angles.isEmpty()) {
                Text(
                    text = stringResource(R.string.angle_list_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            angles.forEach { angle ->
                // Envoltorio estable para tests: la etiqueta declarada angleInspectorRowTag(id)
                // nunca se adjuntó en producción y los tests no encontraban la fila. El Box no
                // intercepta el clic (sin clickable): el selectable del AngleRow sigue dueño.
                Box(
                    modifier = Modifier.testTag(angleInspectorRowTag(angle.id)),
                ) {
                    AngleRow(
                        angle = angle,
                        selected = angle.id == editor?.selectedAngleId,
                        degreesText = formatDegrees((angle.measurement as AngleMeasurement.Measured).degrees),
                        hiddenLabel = stringResource(R.string.angle_hidden),
                        onSelect = {
                            // Inspeccionar un ángulo conserva el borrador A/B pendiente; solo cambia
                            // qué marcas se ajustan.
                            marker = AngleMarker.A
                            adjustDraft = false
                            onSelectAngle(angle.id)
                        },
                        onSetVisible = { visible -> onSetVisible(angle.id, visible) },
                        onRename = { renameTarget = angle },
                        onColor = { colorTarget = angle },
                        onDelete = { deleteTarget = angle },
                    )
                }
            }
        }
    }

    renameTarget?.let { angle ->
        RenameAngleDialog(
            angle = angle,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                // El ViewModel valida otra vez; un nombre inválido no se anuncia como aplicado.
                onRename(angle.id, name)
                renameTarget = null
            },
        )
    }
    colorTarget?.let { angle ->
        AngleColorDialog(
            angle = angle,
            onDismiss = { colorTarget = null },
            onConfirm = { color ->
                onSetColor(angle.id, color)
                colorTarget = null
            },
        )
    }
    deleteTarget?.let { angle ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.angle_delete_title)) },
            text = { Text(stringResource(R.string.angle_delete_body, angle.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(angle.id)
                        deleteTarget = null
                    },
                    modifier = Modifier.heightIn(min = Spacing.xxxl),
                ) {
                    Text(stringResource(R.string.angle_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteTarget = null },
                    modifier = Modifier.heightIn(min = Spacing.xxxl),
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}
