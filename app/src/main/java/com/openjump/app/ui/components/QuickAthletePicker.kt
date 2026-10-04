package com.openjump.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.data.AthleteEntity
/** Small, reusable in-flow picker. It only exposes active athletes; no archive action is possible. */
@Composable
fun QuickAthletePicker(
    athletes: List<AthleteEntity>,
    currentAthleteId: Long?,
    modifier: Modifier = Modifier,
    allowedAthleteIds: Set<Long> = emptySet(),
    enabled: Boolean = true,
    singleLine: Boolean = false,
    onAthleteSelected: (Long) -> Unit,
) {
    val choices = athletes.filter { it.archivedAt == null &&
        (allowedAthleteIds.isEmpty() || it.id in allowedAthleteIds) }
    var expanded by remember { mutableStateOf(false) }
    val currentAthlete = choices.firstOrNull { it.id == currentAthleteId }
        ?: athletes.firstOrNull { it.id == currentAthleteId }
    val currentName = currentAthlete?.displayName ?: stringResource(R.string.athlete_unassigned)
    Box(modifier) {
        TextButton(onClick = { expanded = true }, enabled = enabled && choices.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AthleteAvatar(
                    displayName = currentName,
                    avatarKey = currentAthlete?.avatarKey,
                    size = 32.dp,
                )
                Text(
                    stringResource(R.string.quick_athlete, currentName),
                    modifier = Modifier.padding(start = 8.dp),
                    maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                    overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { athlete ->
                DropdownMenuItem(
                    text = { Text(athlete.displayName) },
                    leadingIcon = {
                        AthleteAvatar(athlete.displayName, athlete.avatarKey, size = 36.dp)
                    },
                    onClick = {
                        onAthleteSelected(athlete.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
