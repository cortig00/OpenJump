package com.openjump.app.ui.fvp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing

/**
 * Primer dato PENDIENTE del paso 1, para el pie ACCIONABLE (plan `fvp-guidance-ux.md`, slice A).
 *
 * Cada valor de este enum corresponde a un bloqueo que YA existe en `canContinue`; el enum no
 * decide elegibilidad ni cambia ningun dato, solo dice A QUE CAMPO llevar al usuario y con que
 * frase corta. Orden = orden de los campos en pantalla: el "primer motivo" es el bloqueo mas
 * arriba del formulario, de modo que la accion nunca salta por encima de otro pendiente.
 *
 * `expandsOptionalComponents` marca los campos que viven dentro del plegable de componentes: la
 * accion los despliega ANTES de desplazarse (un campo oculto no se puede revisar).
 */
internal enum class FvpSetupPending(
    val reasonRes: Int,
    val expandsOptionalComponents: Boolean = false,
) {
    ATHLETE(R.string.fvp_setup_pending_athlete),
    BODY_MASS(R.string.fvp_setup_pending_mass),
    PUSH_OFF(R.string.fvp_setup_pending_push_off),
    PUSH_OFF_METADATA(R.string.fvp_setup_pending_metadata),
    PUSH_OFF_PROCEDURE(R.string.fvp_setup_pending_procedure),
    ACQUISITION_CONTEXT_KEY(R.string.fvp_setup_pending_context_key),
    OPTIONAL_CROUCH(R.string.fvp_setup_pending_optional, expandsOptionalComponents = true),
    OPTIONAL_EXTENDED(R.string.fvp_setup_pending_optional, expandsOptionalComponents = true),
}

/**
 * Primer pendiente segun los MISMOS booleanos que forman `canContinue` (el llamador los pasa ya
 * resueltos: no se recalcula ningun gate aqui). `null` = no hay un campo concreto que revisar
 * (p. ej. mientras cargan los atletas), y entonces el pie conserva el motivo generico de siempre.
 */
internal fun fvpFirstSetupPending(
    athleteSelected: Boolean,
    massNeedsReview: Boolean,
    bodyMassInvalid: Boolean,
    pushOffNeedsReview: Boolean,
    pushOffOutOfDomain: Boolean,
    pushOffMissing: Boolean,
    pushOffMetadataMissing: Boolean,
    landmarkMissing: Boolean,
    acquisitionContextKeyMissing: Boolean,
    crouchNeedsReview: Boolean,
    extendedNeedsReview: Boolean,
): FvpSetupPending? = when {
    !athleteSelected -> FvpSetupPending.ATHLETE
    massNeedsReview || bodyMassInvalid -> FvpSetupPending.BODY_MASS
    pushOffNeedsReview || pushOffOutOfDomain || pushOffMissing -> FvpSetupPending.PUSH_OFF
    pushOffMetadataMissing && landmarkMissing -> FvpSetupPending.PUSH_OFF_METADATA
    pushOffMetadataMissing -> FvpSetupPending.PUSH_OFF_PROCEDURE
    crouchNeedsReview -> FvpSetupPending.OPTIONAL_CROUCH
    extendedNeedsReview -> FvpSetupPending.OPTIONAL_EXTENDED
    acquisitionContextKeyMissing -> FvpSetupPending.ACQUISITION_CONTEXT_KEY
    else -> null
}

/**
 * Ayuda visual de h_po: esquema de las DOS POSICIONES APOYADAS (sentadilla y extension de
 * puntillas) con el MISMO hito en las dos y una flecha VERTICAL entre sus alturas.
 *
 * Decisiones vinculantes (gate cientifico):
 * - las dos figuras comparten una linea de suelo: son posiciones apoyadas, no hay vuelo libre;
 * - no se dibuja escala numerica, ni estatura, ni centro de masas: solo el desplazamiento vertical
 *   del mismo punto anatomico;
 * - el color no es la unica informacion: el `Canvas` lleva la descripcion textual completa
 *   ([R.string.fvp_push_off_diagram_description]) como `contentDescription` y debajo se repite el
 *   resumen visible ([R.string.fvp_push_off_diagram_caption]) y las dos etiquetas de posicion.
 *
 * Los colores salen del tema (claro/oscuro) y el minimo del bloque supera 48 dp.
 */
@Composable
internal fun FvpPushOffLandmarkDiagram(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.fvp_push_off_diagram_description)
    val caption = stringResource(R.string.fvp_push_off_diagram_caption)
    val landmarkColor = MaterialTheme.colorScheme.primary
    val bodyColor = MaterialTheme.colorScheme.onSurfaceVariant
    val groundColor = MaterialTheme.colorScheme.outline
    Column(
        modifier = modifier.fillMaxWidth().testTag("fvp-setup-diagram"),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                // Equivalente textual: el esquema no depende de ver el dibujo ni de distinguir
                // colores (48 dp de alto como minimo, no es un control interactivo).
                .semantics { contentDescription = description }
                .testTag("fvp-setup-diagram-canvas"),
        ) {
            val baselineWidth = 2.dp.toPx()
            val bodyStroke = 3.dp.toPx()
            val headRadius = 5.dp.toPx()
            val markRadius = 4.dp.toPx()
            val groundY = size.height - 8.dp.toPx()
            // Centros alineados con las dos etiquetas inferiores; flecha entre las figuras.
            val crouchX = size.width * 0.25f
            val extendedX = size.width * 0.75f
            val arrowX = size.width * 0.50f
            val crouchMarkY = groundY - 35.dp.toPx()
            val extendedMarkY = groundY - 64.dp.toPx()

            // Suelo COMUN: las dos posiciones estan apoyadas; no se dibuja ninguna trayectoria de
            // vuelo ni una silueta en el aire.
            drawLine(
                color = groundColor,
                start = Offset(0f, groundY),
                end = Offset(size.width, groundY),
                strokeWidth = baselineWidth,
                cap = StrokeCap.Round,
            )

            fun drawSupportedPosition(x: Float, markY: Float, crouched: Boolean) {
                val hip = Offset(x, markY)
                val shoulder = Offset(x - if (crouched) 12.dp.toPx() else 0f, markY - 28.dp.toPx())
                val knee = Offset(x + if (crouched) 20.dp.toPx() else 0f, groundY - if (crouched) 22.dp.toPx() else 44.dp.toPx())
                val ankle = Offset(x + 2.dp.toPx(), groundY - if (crouched) 3.dp.toPx() else 10.dp.toPx())
                val toe = Offset(x + 12.dp.toPx(), groundY)
                val heel = Offset(x - 5.dp.toPx(), if (crouched) groundY else groundY - 8.dp.toPx())
                // Rodilla flexionada y pie plano vs pierna extendida y talon elevado. Ambos
                // conservan apoyo: la figura nunca representa el salto ni una trayectoria de vuelo.
                listOf(
                    shoulder to hip, hip to knee, knee to ankle, heel to toe,
                    ankle to toe, shoulder to Offset(x + 13.dp.toPx(), markY - 9.dp.toPx()),
                ).forEach { (from, to) ->
                    drawLine(bodyColor, from, to, bodyStroke, cap = StrokeCap.Round)
                }
                drawCircle(
                    color = bodyColor,
                    radius = headRadius,
                    center = shoulder - Offset(0f, 10.dp.toPx()),
                    style = Stroke(width = baselineWidth),
                )
                // Mismo hito de ejemplo (cadera), no un marcador del centro de masas.
                drawCircle(color = landmarkColor, radius = markRadius, center = hip)
                drawLine(
                    color = landmarkColor,
                    start = hip - Offset(7.dp.toPx(), 0f),
                    end = hip + Offset(7.dp.toPx(), 0f),
                    strokeWidth = bodyStroke,
                    cap = StrokeCap.Round,
                )
            }
            drawSupportedPosition(crouchX, crouchMarkY, crouched = true)
            drawSupportedPosition(extendedX, extendedMarkY, crouched = false)

            // Guias horizontales: las dos ALTURAS del mismo hito.
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            drawLine(
                color = landmarkColor,
                start = Offset(crouchX + 8.dp.toPx(), crouchMarkY),
                end = Offset(arrowX - 8.dp.toPx(), crouchMarkY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = dash,
            )
            drawLine(
                color = landmarkColor,
                start = Offset(extendedX + 8.dp.toPx(), extendedMarkY),
                end = Offset(arrowX - 8.dp.toPx(), extendedMarkY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = dash,
            )

            // Flecha VERTICAL entre las dos alturas: el desplazamiento del hito es h_po. Sin cifras,
            // sin escala y sin calculo automatico de ningun centro de masas.
            drawLine(
                color = landmarkColor,
                start = Offset(arrowX, crouchMarkY),
                end = Offset(arrowX, extendedMarkY),
                strokeWidth = baselineWidth,
                cap = StrokeCap.Round,
            )
            val head = 4.dp.toPx()
            listOf(
                Offset(arrowX, crouchMarkY) to Offset(arrowX - head, crouchMarkY - head),
                Offset(arrowX, crouchMarkY) to Offset(arrowX + head, crouchMarkY - head),
                Offset(arrowX, extendedMarkY) to Offset(arrowX - head, extendedMarkY + head),
                Offset(arrowX, extendedMarkY) to Offset(arrowX + head, extendedMarkY + head),
            ).forEach { (from, to) ->
                drawLine(
                    color = landmarkColor,
                    start = from,
                    end = to,
                    strokeWidth = baselineWidth,
                    cap = StrokeCap.Round,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                stringResource(R.string.fvp_push_off_diagram_crouch),
                style = OpenJumpTypes.Label,
                color = bodyColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).testTag("fvp-setup-diagram-crouch"),
            )
            Text(
                stringResource(R.string.fvp_push_off_diagram_extended),
                style = OpenJumpTypes.Label,
                color = bodyColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).testTag("fvp-setup-diagram-extended"),
            )
        }
        Text(
            caption,
            style = OpenJumpTypes.Secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("fvp-setup-diagram-caption"),
        )
    }
}

/**
 * Ayuda de h_po BAJO DEMANDA: un unico control "Como se mide (detalle)" abre el esquema accesible
 * y el detalle largo a la vez. Nada de esto se compone mientras esta plegado.
 *
 * El aviso de aproximacion de campo ([R.string.fvp_push_off_field_approximation]) NO vive aqui:
 * queda siempre visible fuera de este plegable, junto al campo. Plegar/desplegar la ayuda no toca
 * ningun valor, ni la fecha, ni la elegibilidad: el estado es solo de UI (`rememberSaveable`).
 */
@Composable
internal fun FvpPushOffGuidance(modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val stateLabel = stringResource(
        if (expanded) R.string.result_temporal_sensitivity_expanded
        else R.string.result_temporal_sensitivity_collapsed,
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = ShapeTokens.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Fila clicable de 48 dp minimos, con estado anunciado (no solo el simbolo).
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .semantics {
                        role = Role.Button
                        stateDescription = stateLabel
                    }
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                    .testTag("fvp-setup-guidance-toggle"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.fvp_push_off_guidance_toggle),
                    modifier = Modifier.weight(1f),
                    style = OpenJumpTypes.Label,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(
                        if (expanded) R.string.result_temporal_sensitivity_hide_details
                        else R.string.result_temporal_sensitivity_show_details,
                    ),
                    style = OpenJumpTypes.Label,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            AnimatedVisibility(expanded) {
                Column(
                    modifier = Modifier
                        .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg)
                        .testTag("fvp-setup-guidance-expanded"),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    FvpPushOffLandmarkDiagram()
                    Text(
                        stringResource(R.string.fvp_push_off_guidance_body),
                        style = OpenJumpTypes.Secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("fvp-setup-guidance-body"),
                    )
                }
            }
        }
    }
}

/**
 * Ejemplo corto junto a SU campo (punto anatomico, procedimiento, procedencia, contexto o clave).
 *
 * Es la misma jerarquia tipografica secundaria que ya usan las ayudas del formulario; nunca
 * sustituye el rotulo del campo ni el aviso de revisión.
 */
@Composable
internal fun FvpInlineExample(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
