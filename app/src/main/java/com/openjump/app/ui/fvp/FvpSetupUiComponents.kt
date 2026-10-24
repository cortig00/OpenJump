package com.openjump.app.ui.fvp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import com.openjump.fvp.FvpProtocol

/** Mismo lenguaje tonal de Encoder, sin acoplar FVP al codigo ni al estado de otro instrumento. */
@Composable
internal fun fvpInputColors() = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    unfocusedIndicatorColor = Color.Transparent,
)

@Composable
internal fun fvpSelectionColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
    selectedLabelColor = MaterialTheme.colorScheme.primary,
    selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
)

/**
 * Fila de ejercicio y hoja de eleccion como Encoder. Solo posee el estado de apertura de la hoja:
 * protocolo, valores, Savers y validacion siguen perteneciendo al formulario de Setup.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FvpProtocolSelector(
    protocol: FvpProtocol,
    onSelected: (FvpProtocol) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Surface(
        onClick = {
            // Cambio de tarea solicitado por el usuario; no editar ni normalizar el texto del campo.
            focusManager.clearFocus()
            keyboard?.hide()
            open = true
        },
        modifier = modifier.fillMaxWidth().testTag("fvp-setup-protocol"),
        shape = ShapeTokens.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 64.dp).padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    stringResource(R.string.testing_exercise),
                    style = OpenJumpTypes.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (protocol == FvpProtocol.SJ) stringResource(R.string.protocol_sj_title) + " · SJ"
                    else stringResource(R.string.fvp_protocol_cmj_conditional),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("fvp-setup-protocol-value"),
                )
            }
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (open) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            modifier = Modifier.testTag("fvp-protocol-picker"),
        ) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                Text(
                    stringResource(R.string.testing_exercise),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm)
                        .semantics { heading() },
                )
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 480.dp).selectableGroup(),
                ) {
                    items(listOf(FvpProtocol.SJ, FvpProtocol.CMJ), key = { it.name }) { option ->
                        ListItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 64.dp)
                                .selectable(selected = option == protocol, role = Role.RadioButton) {
                                    open = false
                                    onSelected(option)
                                }
                                .testTag("fvp-protocol-${option.name}"),
                            colors = ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                            leadingContent = { RadioButton(selected = option == protocol, onClick = null) },
                            headlineContent = {
                                Text(
                                    if (option == FvpProtocol.SJ) stringResource(R.string.protocol_sj_title) + " · SJ"
                                    else stringResource(R.string.fvp_protocol_cmj_conditional),
                                )
                            },
                            supportingContent = {
                                Text(stringResource(
                                    if (option == FvpProtocol.SJ) R.string.protocol_sj_description
                                    else R.string.protocol_cmj_description,
                                ))
                            },
                        )
                    }
                }
                TextButton(
                    onClick = { open = false },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("fvp-protocol-cancel"),
                ) { Text(stringResource(R.string.common_cancel)) }
            }
        }
    }
}
