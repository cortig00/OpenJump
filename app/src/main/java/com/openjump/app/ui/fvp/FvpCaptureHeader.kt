package com.openjump.app.ui.fvp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.ui.theme.OpenJumpTheme
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing

/** Aviso integro y siempre legible, sin una capsula dominante; mismo patron informativo de Encoder. */
@Composable
internal fun FvpCaptureWarning(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_info),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = OpenJumpTheme.colors.warning,
        )
        Text(
            text = stringResource(R.string.fvp_context_experimental),
            style = OpenJumpTypes.Secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).testTag("fvp-capture-experimental"),
        )
    }
}

/**
 * Advertencia y ayuda en una sola superficie neutra, como las notas de Encoder. El aviso breve
 * permanece FUERA del plegable; todos los textos cientificos completos se conservan bajo demanda.
 * Abrir/cerrar solo cambia estado de presentacion: nunca datos, fecha, seleccion ni elegibilidad.
 */
@Composable
internal fun FvpCaptureHeader(
    modifier: Modifier = Modifier,
    testTag: String = "fvp-capture-header",
    showWarning: Boolean = true,
) {
    var helpExpanded by rememberSaveable { mutableStateOf(false) }
    val stateLabel = stringResource(
        if (helpExpanded) R.string.result_temporal_sensitivity_expanded
        else R.string.result_temporal_sensitivity_collapsed,
    )
    Surface(
        modifier = modifier.fillMaxWidth().testTag(testTag),
        shape = ShapeTokens.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            if (showWarning) {
                FvpCaptureWarning(Modifier.padding(start = Spacing.md, end = Spacing.md, top = Spacing.md))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { helpExpanded = !helpExpanded }
                    .semantics {
                        role = Role.Button
                        stateDescription = stateLabel
                    }
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                    .testTag("fvp-capture-method-toggle"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.fvp_capture_method_toggle),
                    modifier = Modifier.weight(1f),
                    style = OpenJumpTypes.Label,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(
                        if (helpExpanded) R.string.result_temporal_sensitivity_hide_details
                        else R.string.result_temporal_sensitivity_show_details,
                    ),
                    style = OpenJumpTypes.Label,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            AnimatedVisibility(helpExpanded) {
                Column(
                    modifier = Modifier
                        .padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.md)
                        .testTag("fvp-capture-method-body"),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Text(
                        stringResource(R.string.fvp_experimental_label),
                        style = OpenJumpTypes.Secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.fvp_estimate_notice_title),
                        style = OpenJumpTypes.Label,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.fvp_estimate_notice_body),
                        style = OpenJumpTypes.Secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
