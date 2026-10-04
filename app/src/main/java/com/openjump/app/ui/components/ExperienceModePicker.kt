package com.openjump.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.settings.ExperienceMode
import com.openjump.app.ui.theme.LightPrimary
import com.openjump.app.ui.theme.ShapeTokens

/** Compact global mode control intended for the trailing edge of a top app bar. */
@Composable
fun ExperienceModePicker(
    mode: ExperienceMode,
    onModeSelected: (ExperienceMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val modes = listOf(
        ExperienceMode.PERSONAL to R.string.mode_personal,
        ExperienceMode.COACH to R.string.mode_coach,
    )
    val currentLabel = modes.first { it.first == mode }.second
    val currentLabelText = stringResource(currentLabel)
    val modeTitle = stringResource(R.string.mode_title)
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    Box(modifier) {
        Surface(
            onClick = { expanded = true },
            shape = ShapeTokens.full,
            color = if (isDark) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            },
            border = BorderStroke(
                width = 1.dp,
                color = if (isDark) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)
                },
            ),
            modifier = Modifier
                .height(32.dp)
                .semantics {
                    contentDescription = modeTitle
                    stateDescription = currentLabelText
                },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (isDark) MaterialTheme.colorScheme.primary else LightPrimary
                        )
                )
                Text(
                    text = currentLabelText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            modes.forEach { (item, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    leadingIcon = {
                        RadioButton(
                            selected = mode == item,
                            onClick = null,
                        )
                    },
                    onClick = {
                        expanded = false
                        if (item != mode) onModeSelected(item)
                    },
                )
            }
        }
    }
}
