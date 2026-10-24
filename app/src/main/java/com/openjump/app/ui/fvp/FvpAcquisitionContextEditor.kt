@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openjump.app.ui.fvp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing

/**
 * ORIGEN DE INTENTOS (contexto de adquisicion declarado) en la pantalla de Condiciones.
 *
 * Slice B del plan `fvp-guided-flow-implementation.md`: el contexto deja de tener copia editable en
 * el paso 1 y su unico dueño es ESTA pantalla. Reglas vinculantes:
 *
 * - Opciones EXPLICITAS y en el mismo vocabulario de almacenamiento (`UNKNOWN` |
 *   `SAME_ACQUISITION` | `HISTORICAL_COMPOSITE`). `UNKNOWN` es el default honesto: no se infiere
 *   del atleta, la fecha, los fps ni los intentos, y sigue siendo una declaracion VALIDA.
 * - La clave opaca solo se muestra (y se puede escribir) con un contexto DECLARADO; su
 *   canonicalizacion y su cota de 128 caracteres son las que ya existen
 *   ([fvpAcquisitionContextKeyToPersist], [FVP_ACQUISITION_CONTEXT_KEY_MAX_CHARS]), de modo que la
 *   UI no puede aceptar lo que el guardado rechazaria.
 * - Toda escritura pasa por el helper del borrador `withAcquisitionContext` a traves de las
 *   mutaciones del dueño: cambiar contexto o clave RETIRA las confirmaciones de reutilizacion
 *   afectadas en la MISMA mutacion (no en un efecto posterior).
 * - `SAME_ACQUISITION` sin clave es una declaracion incompleta: se marca en el campo con el texto
 *   EXACTO ya existente y la pantalla la suma a su compuerta (`canCalculate`). No se toca ninguna
 *   formula de elegibilidad y `UNKNOWN` no bloquea.
 * - El texto tecleado se conserva por sesion (`rememberSaveable`): pasar a `UNKNOWN` y volver a
 *   `SAME_ACQUISITION` no lo pierde, y el campo se siembra del borrador al volver a la pantalla.
 * - Presentacion COMPACTA: pregunta + chips + campo de clave, con la explicacion larga plegada.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun FvpAcquisitionContextEditor(
    draft: FvpDraft,
    mutations: FvpDraftMutations,
    anchors: FvpConditionAnchors,
    modifier: Modifier = Modifier,
) {
    val kind = FvpAcquisitionContextUi.fromStorageKey(draft.acquisitionContextKind)
        ?: FvpAcquisitionContextUi.UNKNOWN
    var keyText by rememberSaveable(draft.sessionKey) {
        mutableStateOf(draft.acquisitionContextKey.orEmpty())
    }
    var helpExpanded by rememberSaveable(draft.sessionKey) { mutableStateOf(false) }
    // Sincronizacion SOLO cuando el borrador manda (restauracion/duplicado): si el texto local ya
    // representa lo persistido, se conserva TAL CUAL (incluido lo tecleado con contexto UNKNOWN).
    LaunchedEffect(draft.sessionKey, draft.acquisitionContextKind, draft.acquisitionContextKey) {
        if (fvpAcquisitionContextKeyToPersist(kind, keyText) != draft.acquisitionContextKey) {
            keyText = draft.acquisitionContextKey.orEmpty()
        }
    }
    val keyRequired = kind == FvpAcquisitionContextUi.SAME_ACQUISITION &&
        draft.acquisitionContextKey.isNullOrBlank()

    Column(
        modifier = modifier.fillMaxWidth().testTag("fvp-acquisition-context"),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            stringResource(R.string.fvp_acquisition_context),
            style = OpenJumpTypes.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            listOf(
                FvpAcquisitionContextUi.UNKNOWN to R.string.fvp_acquisition_context_unknown,
                FvpAcquisitionContextUi.SAME_ACQUISITION to R.string.fvp_acquisition_context_same,
                FvpAcquisitionContextUi.HISTORICAL_COMPOSITE to R.string.fvp_acquisition_context_historical,
            ).forEach { (option, labelRes) ->
                val isSelected = option == kind
                FilterChip(
                    colors = fvpSelectionColors(),
                    selected = isSelected,
                    onClick = {
                        // Declaracion 1:1 con el vocabulario de datos. Con UNKNOWN no se guarda
                        // clave (se conserva el texto local para poder volver sin perderlo).
                        mutations.update { live ->
                            live?.withAcquisitionContext(
                                option.storageKey,
                                fvpAcquisitionContextKeyToPersist(option, keyText),
                            ) ?: live
                        }
                    },
                    label = { Text(stringResource(labelRes)) },
                    modifier = Modifier
                        .heightIn(min = Spacing.xxxl)
                        .testTag("fvp-acquisition-context-${option.storageKey}")
                        .semantics { selected = isSelected },
                )
            }
        }
        // Solo SAME pide una clave en el recorrido normal. En una composicion historica es
        // opcional: se consulta/edita bajo demanda, sin perder metadata ya declarada.
        if (kind == FvpAcquisitionContextUi.SAME_ACQUISITION ||
            (kind == FvpAcquisitionContextUi.HISTORICAL_COMPOSITE && helpExpanded)
        ) {
            TextField(
                shape = ShapeTokens.medium,
                colors = fvpInputColors(),
                value = keyText,
                onValueChange = { raw ->
                    // Cota EXISTENTE del contrato 18 §2: no se puede teclear lo que el guardado
                    // rechazaria; la canonicalizacion (trim, no vacia) la hace el helper.
                    val limited = raw.take(FVP_ACQUISITION_CONTEXT_KEY_MAX_CHARS)
                    keyText = limited
                    mutations.update { live ->
                        live?.withAcquisitionContext(
                            kind.storageKey,
                            fvpAcquisitionContextKeyToPersist(kind, limited),
                        ) ?: live
                    }
                },
                label = { Text(stringResource(R.string.fvp_acquisition_context_key)) },
                isError = keyRequired,
                supportingText = if (keyRequired) {
                    { Text(stringResource(R.string.fvp_acquisition_context_key_required)) }
                } else {
                    null
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .bringIntoViewRequester(anchors.requester(FVP_ACQUISITION_CONTEXT_ANCHOR_KEY))
                    .testTag("fvp-acquisition-context-key"),
            )
        }
        // Ayuda extensa BAJO DEMANDA: la pregunta y las opciones quedan visibles; la explicacion
        // larga y el ejemplo no forman un muro de texto al entrar.
        TextButton(
            onClick = { helpExpanded = !helpExpanded },
            modifier = Modifier.heightIn(min = 48.dp).testTag("fvp-acquisition-context-help-toggle"),
        ) {
            Text(
                stringResource(
                    if (helpExpanded) {
                        R.string.result_temporal_sensitivity_hide_details
                    } else {
                        R.string.result_temporal_sensitivity_show_details
                    },
                ),
            )
        }
        if (helpExpanded) {
            Text(
                stringResource(R.string.fvp_acquisition_context_help),
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("fvp-acquisition-context-help"),
            )
            if (kind == FvpAcquisitionContextUi.UNKNOWN) {
                Text(
                    stringResource(R.string.fvp_acquisition_context_example),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    stringResource(R.string.fvp_acquisition_context_key_help),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.fvp_acquisition_context_key_example),
                    style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
