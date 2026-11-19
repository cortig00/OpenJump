package com.openjump.app.ui.angles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.openjump.app.R
import com.openjump.app.angles.AngleAccessibleCursor
import com.openjump.app.angles.AngleId
import com.openjump.app.angles.AngleMarker
import com.openjump.app.angles.AnglePoint
import com.openjump.app.angles.Angle
import com.openjump.app.angles.AngleMarkers
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing

/**
 * Objetivo de los controles accesibles de puntos del editor de ángulos.
 *
 * La pantalla decide el objetivo a partir del estado real del editor; este archivo no inventa
 * selección ni coloca nada por su cuenta.
 */
internal sealed interface AngleAccessibleTarget {

    /** Colocación del siguiente punto del borrador (A → B → C). */
    data class Draft(val marker: AngleMarker) : AngleAccessibleTarget

    /**
     * Ajuste de una marca A/B **ya colocada** de un borrador pendiente.
     *
     * Es el target que usa el inspector para corregir A o B antes de colocar C sin descartar el
     * borrador ni inventar un `AngleId`: el borrador no es un ángulo guardado y no tiene id.
     */
    data class ExistingDraft(
        val marker: AngleMarker,
        val point: AnglePoint,
    ) : AngleAccessibleTarget

    /** Ajuste de una de las tres marcas de un ángulo existente y visible. */
    data class ExistingAngle(
        val angleId: AngleId,
        val angleName: String,
        val marker: AngleMarker,
        val point: AnglePoint,
    ) : AngleAccessibleTarget

    /** Hay ángulos, pero ninguno seleccionado: primero hay que elegir uno en la lista. */
    data object PickAngle : AngleAccessibleTarget
}

/**
 * Deriva el objetivo de los controles accesibles a partir del modo de la capa y del estado.
 *
 * - [AngleOverlayMode.PLACEMENT]: se coloca el siguiente punto del borrador.
 * - [AngleOverlayMode.EDIT] con un ángulo visible seleccionado: se ajusta la marca elegida.
 * - [AngleOverlayMode.EDIT] sin selección: se pide elegir un ángulo (nunca se elige por su cuenta).
 * - [AngleOverlayMode.FRAMING]: `null`; el encuadre no ofrece controles de puntos.
 */
internal fun angleAccessibleTarget(
    mode: AngleOverlayMode,
    draftPoints: List<AnglePoint>,
    selectedAngle: Angle?,
    marker: AngleMarker,
): AngleAccessibleTarget? = when {
    mode == AngleOverlayMode.PLACEMENT ->
        AngleAccessibleTarget.Draft(nextDraftMarker(draftPoints.size))
    mode != AngleOverlayMode.EDIT -> null
    selectedAngle == null || !selectedAngle.visible -> AngleAccessibleTarget.PickAngle
    else -> AngleAccessibleTarget.ExistingAngle(
        angleId = selectedAngle.id,
        angleName = selectedAngle.name,
        marker = marker,
        point = selectedAngle.markers.pointFor(marker),
    )
}

/**
 * Índice del punto del borrador que corresponde a [marker] **si ya está colocado**.
 *
 * Solo A y B son ajustables mientras el borrador está pendiente: C cierra la terna y no se ofrece
 * como ajuste de borrador (para eso está el ángulo ya medido).
 */
private fun angleInspectorDraftIndex(marker: AngleMarker, placed: Int): Int? = when (marker) {
    AngleMarker.A -> 0.takeIf { placed >= 1 }
    AngleMarker.B -> 1.takeIf { placed >= 2 }
    AngleMarker.C -> null
}

/**
 * Objetivo de los controles del inspector: colocar el **siguiente** punto del borrador o ajustar una
 * marca A/B **ya colocada** del mismo borrador, sin pasar por un `AngleId` ficticio.
 *
 * Es una variante explícita del target legacy ([angleAccessibleTarget], que no cambia):
 * - [adjustDraft] `true` con A/B ya colocados ajusta **esa** marca explícita (A o B, la que pida el
 *   usuario); si la marca pedida aún no existe ofrece el siguiente punto sin descartar el borrador;
 * - [placementIntent] `true` (o un borrador pendiente sin ángulo elegido) ofrece el siguiente punto
 *   y, sin ningún punto, el primer A;
 * - en el resto de casos se delega en el target legacy, de modo que elegir un ángulo existente con
 *   A/B pendientes **conserva** el borrador y permite inspeccionar el ángulo.
 *
 * No decide algoritmos ni muta nada.
 */
internal fun angleInspectorPointTarget(
    draftPoints: List<AnglePoint>,
    selectedAngle: Angle?,
    marker: AngleMarker,
    adjustDraft: Boolean,
    placementIntent: Boolean = false,
): AngleAccessibleTarget? {
    val placed = draftPoints.size
    if (adjustDraft && placed > 0) {
        val index = angleInspectorDraftIndex(marker, placed)
        if (index != null) return AngleAccessibleTarget.ExistingDraft(marker, draftPoints[index])
        return AngleAccessibleTarget.Draft(nextDraftMarker(placed))
    }
    if (placementIntent) return AngleAccessibleTarget.Draft(nextDraftMarker(placed))
    if (placed > 0 && selectedAngle == null) return AngleAccessibleTarget.Draft(nextDraftMarker(placed))
    return angleAccessibleTarget(
        mode = AngleOverlayMode.EDIT,
        draftPoints = draftPoints,
        selectedAngle = selectedAngle,
        marker = marker,
    )
}

/** Siguiente marca del borrador según los puntos ya colocados (A → B → C). */
internal fun nextDraftMarker(placedPoints: Int): AngleMarker = when {
    placedPoints <= 0 -> AngleMarker.A
    placedPoints == 1 -> AngleMarker.B
    else -> AngleMarker.C
}

/** Punto de [this] correspondiente a [marker]; B es el vértice. */
internal fun AngleMarkers.pointFor(marker: AngleMarker): AnglePoint = when (marker) {
    AngleMarker.A -> a
    AngleMarker.B -> b
    AngleMarker.C -> c
}

internal const val ANGLE_ACCESSIBLE_TOGGLE_TAG = "angle-accessible-toggle"
internal const val ANGLE_ACCESSIBLE_CONFIRM_TAG = "angle-accessible-confirm"
internal const val ANGLE_ACCESSIBLE_POSITION_TAG = "angle-accessible-position"

/** Etiqueta de prueba estable de la opción de marca A/B/C. */
internal fun angleAccessibleMarkerTag(marker: AngleMarker): String =
    "angle-accessible-marker-${marker.name}"

/** Etiqueta de prueba estable de los cuatro pasos del cursor. */
internal fun angleAccessibleStepTag(direction: String): String =
    "angle-accessible-step-$direction"

/**
 * Controles semánticos para colocar A/B/C del borrador y ajustar A/B/C de un ángulo existente sin
 * tocar el vídeo (TalkBack/Switch Access).
 *
 * Es la contrapartida accesible de los gestos de [AngleOverlay]: el dedo coloca y arrastra sobre el
 * Canvas, y aquí se mueve un cursor con pasos y se confirma explícitamente. No duplica reglas de
 * negocio:
 *
 * - el punto confirmado va en coordenadas de imagen al mismo mapper y a las mismas guardas
 *   (frame/PTS/generación) que un toque, porque el llamante las aplica antes de mutar;
 * - [onConfirm] devuelve `true` solo si el cambio se aplicó de verdad. Este panel **no** avanza,
 *   no colapsa ni anuncia progreso por su cuenta: si el llamante rechaza el punto (frame cambiado,
 *   reproducción reanudada, terna no medible), la pantalla sigue mostrando el estado real;
 * - [enabled] apaga los botones cuando no hay frame confirmado o la geometría no corresponde al
 *   frame renderizado, y en ese caso se explica el motivo en texto localizado.
 *
 * Todos los objetivos táctiles son de 48dp como mínimo y cada botón tiene etiqueta propia: el
 * glifo de la flecha nunca es la única información.
 *
 * @param target qué se está colocando o ajustando; el panel solo dibuja lo que el estado permite.
 * @param geometry geometría de presentación (rotación ya aplicada); fija el espacio del cursor.
 * @param marker marca elegida por el usuario cuando se ajusta un ángulo existente.
 * @param enabled `false` cuando no hay frame confirmado o la geometría es de otro frame.
 * @param expanded estado de plegado del panel; lo controla el llamante para poder abrirlo desde la
 *   acción personalizada de la capa.
 * @param cursor posición pendiente del cursor, o `null` para partir de la posición real del punto.
 * @param onConfirm intención de colocar/ajustar; devuelve si el editor la aplicó.
 * @param showToggle conserva la fila plegable del editor (por defecto). El inspector modal la apaga
 *   y fija `expanded = true`: un solo gesto para llegar a los controles, sin segundo clic de más.
 */
@Composable
internal fun AngleAccessiblePointControls(
    target: AngleAccessibleTarget,
    geometry: VideoPresentationGeometry,
    marker: AngleMarker,
    enabled: Boolean,
    expanded: Boolean,
    cursor: AnglePoint?,
    onExpandedChange: (Boolean) -> Unit,
    onMarkerChange: (AngleMarker) -> Unit,
    onCursorChange: (AnglePoint?) -> Unit,
    onConfirm: (AngleMarker, AnglePoint) -> Boolean,
    /** `false` oculta la fila «Mostrar/Ocultar» y deja el panel ya desplegado (inspector modal). */
    showToggle: Boolean = true,
) {
    val activeMarker: AngleMarker? = when (target) {
        is AngleAccessibleTarget.Draft -> target.marker
        is AngleAccessibleTarget.ExistingDraft -> target.marker
        is AngleAccessibleTarget.ExistingAngle -> target.marker
        AngleAccessibleTarget.PickAngle -> null
    }
    val existingPoint: AnglePoint? = when (target) {
        is AngleAccessibleTarget.ExistingDraft -> target.point
        is AngleAccessibleTarget.ExistingAngle -> target.point
        else -> null
    }
    val position = cursor ?: existingPoint ?: AngleAccessibleCursor.centered(geometry)
    val step = AngleAccessibleCursor.step(geometry)
    val positionText = stringResource(R.string.angle_accessible_position, position.x, position.y)
    val showLabel = stringResource(R.string.angle_accessible_show)
    val hideLabel = stringResource(R.string.angle_accessible_hide)
    val blockedText = stringResource(R.string.angle_accessible_blocked)
    val leftLabel = stringResource(R.string.angle_accessible_left)
    val upLabel = stringResource(R.string.angle_accessible_up)
    val downLabel = stringResource(R.string.angle_accessible_down)
    val rightLabel = stringResource(R.string.angle_accessible_right)

    fun move(dx: Double, dy: Double) {
        onCursorChange(AngleAccessibleCursor.moved(geometry, position, dx, dy))
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = ShapeTokens.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (showToggle) {
                TextButton(
                    onClick = { onExpandedChange(!expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Spacing.xxxl)
                        .testTag(ANGLE_ACCESSIBLE_TOGGLE_TAG)
                        .semantics {
                            stateDescription = if (expanded) hideLabel else showLabel
                        },
                ) {
                    Text(if (expanded) hideLabel else showLabel)
                }
            }
            if (!expanded) return@Column

            when (target) {
                AngleAccessibleTarget.PickAngle -> Text(
                    text = stringResource(R.string.angle_accessible_pick_angle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is AngleAccessibleTarget.Draft,
                is AngleAccessibleTarget.ExistingDraft,
                is AngleAccessibleTarget.ExistingAngle,
                -> {
                    val targetLabel = when (target) {
                        is AngleAccessibleTarget.Draft, is AngleAccessibleTarget.ExistingDraft ->
                            stringResource(R.string.angle_accessible_draft_target)

                        is AngleAccessibleTarget.ExistingAngle -> target.angleName
                        AngleAccessibleTarget.PickAngle -> ""
                    }
                    Text(
                        text = stringResource(R.string.angle_accessible_target, targetLabel),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (target is AngleAccessibleTarget.ExistingAngle ||
                        target is AngleAccessibleTarget.ExistingDraft
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            AngleMarker.entries.forEach { option ->
                                val optionLabel = stringResource(
                                    R.string.angle_accessible_marker,
                                    option.name,
                                )
                                FilterChip(
                                    selected = marker == option,
                                    onClick = { onMarkerChange(option) },
                                    enabled = enabled,
                                    label = { Text(option.name) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = Spacing.xxxl)
                                        .testTag(angleAccessibleMarkerTag(option))
                                        .semantics { contentDescription = optionLabel },
                                )
                            }
                        }
                    }
                    Text(
                        text = positionText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ANGLE_ACCESSIBLE_POSITION_TAG)
                            .semantics {
                                contentDescription = positionText
                                stateDescription = positionText
                                liveRegion = LiveRegionMode.Polite
                            },
                    )
                    if (!enabled) {
                        Text(
                            text = blockedText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        OutlinedButton(
                            enabled = enabled,
                            onClick = { move(-step, 0.0) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = Spacing.xxxl)
                                .testTag(angleAccessibleStepTag("left"))
                                .semantics { contentDescription = leftLabel },
                        ) { Text("←") }
                        OutlinedButton(
                            enabled = enabled,
                            onClick = { move(0.0, -step) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = Spacing.xxxl)
                                .testTag(angleAccessibleStepTag("up"))
                                .semantics { contentDescription = upLabel },
                        ) { Text("↑") }
                        OutlinedButton(
                            enabled = enabled,
                            onClick = { move(0.0, step) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = Spacing.xxxl)
                                .testTag(angleAccessibleStepTag("down"))
                                .semantics { contentDescription = downLabel },
                        ) { Text("↓") }
                        OutlinedButton(
                            enabled = enabled,
                            onClick = { move(step, 0.0) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = Spacing.xxxl)
                                .testTag(angleAccessibleStepTag("right"))
                                .semantics { contentDescription = rightLabel },
                        ) { Text("→") }
                    }
                    val marker = activeMarker
                    if (marker != null) {
                        Button(
                            enabled = enabled,
                            onClick = { onConfirm(marker, position) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Spacing.xxxl)
                                .testTag(ANGLE_ACCESSIBLE_CONFIRM_TAG),
                        ) {
                            Text(
                                stringResource(
                                    if (existingPoint == null) {
                                        R.string.angle_accessible_place
                                    } else {
                                        R.string.angle_accessible_adjust
                                    },
                                    marker.name,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
