package com.openjump.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.ui.theme.Spacing
import java.util.Locale

@Composable
fun AthleteAvatar(
    displayName: String,
    avatarKey: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    contentDescription: String? = null,
) {
    val option = AthleteAvatarCatalog.find(avatarKey)
    Surface(
        modifier = modifier.size(size).clip(CircleShape),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        if (option != null) {
            Image(
                painter = painterResource(option.drawableRes),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = athleteInitials(displayName),
                    style = if (size >= 72.dp) MaterialTheme.typography.headlineSmall
                    else MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
fun AthleteAvatarPickerDialog(
    displayName: String,
    selectedKey: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    // When editing an older profile, highlight the replacement for its saved key.
    var pendingKey by remember(selectedKey) { mutableStateOf(AthleteAvatarCatalog.find(selectedKey)?.key) }
    val groups = AthleteAvatarCatalog.groups
    val options = AthleteAvatarCatalog.options

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.athlete_avatar_title)) },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 72.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).testTag("avatar-picker-grid"),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                groups.forEachIndexed { groupIndex, group ->
                    item(key = "section-${group.titleRes}", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = stringResource(group.titleRes),
                            modifier = Modifier.padding(top = Spacing.sm).semantics { heading() },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    itemsIndexed(group.options, key = { _, option -> option.key }) { _, option ->
                        AvatarChoice(
                            displayName = displayName,
                            avatarKey = option.key,
                            selected = pendingKey == option.key,
                            contentDescription = stringResource(R.string.athlete_avatar_option, options.indexOf(option) + 1),
                            onClick = { pendingKey = option.key },
                        )
                    }
                    if (groupIndex == 0) {
                        item(key = "initials") {
                            AvatarChoice(
                                displayName = displayName,
                                avatarKey = null,
                                selected = pendingKey == null,
                                contentDescription = stringResource(R.string.athlete_avatar_initials),
                                onClick = { pendingKey = null },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pendingKey) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun AvatarChoice(
    displayName: String,
    avatarKey: String?,
    selected: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .border(width = 3.dp, color = borderColor, shape = CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        AthleteAvatar(
            displayName = displayName,
            avatarKey = avatarKey,
            size = 64.dp,
            contentDescription = contentDescription,
        )
    }
}

internal fun athleteInitials(displayName: String): String {
    val words = displayName.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    return words.take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
        .ifBlank { "?" }
        .uppercase(Locale.getDefault())
}
