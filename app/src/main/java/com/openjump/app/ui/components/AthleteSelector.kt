package com.openjump.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import androidx.compose.ui.res.stringResource
import com.openjump.app.data.AthleteEntity
import com.openjump.app.ui.theme.Spacing
import com.openjump.app.ui.theme.ShapeTokens
import androidx.compose.ui.graphics.Color

/** Measurement-only athlete choice; it has no global navigation or profile semantics. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AthleteSelector(
    athletes: List<AthleteEntity>,
    selectedAthleteId: Long?,
    onAthleteSelected: (Long) -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    compact: Boolean = false,
    softSurface: Boolean = false,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val selected = if (loading) null else athletes.firstOrNull { it.id == selectedAthleteId && it.archivedAt == null }
    val label = when {
        loading -> stringResource(R.string.athlete_selector_loading)
        selected != null -> selected.displayName
        else -> stringResource(R.string.athlete_selector_select)
    }
    val buttonColors = if (compact) {
        ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
            containerColor = if (softSurface) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent,
        )
    } else {
        ButtonDefaults.outlinedButtonColors()
    }

    OutlinedButton(
        onClick = { open = true },
        enabled = !loading,
        colors = buttonColors,
        border = if (softSurface) null else ButtonDefaults.outlinedButtonBorder,
        shape = if (softSurface) ShapeTokens.medium else ButtonDefaults.outlinedShape,
        contentPadding = if (compact) {
            PaddingValues(horizontal = if (softSurface) Spacing.md else Spacing.sm, vertical = Spacing.xs)
        } else {
            ButtonDefaults.ContentPadding
        },
        modifier = modifier.fillMaxWidth().heightIn(min = if (compact) Spacing.xxxl else 56.dp),
    ) {
        if (compact) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (selected != null) {
                    AthleteAvatar(
                        selected.displayName,
                        selected.avatarKey,
                        size = 32.dp,
                        modifier = androidx.compose.ui.Modifier.clearAndSetSemantics { },
                    )
                }
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selected != null) {
                    Text(
                        stringResource(R.string.athlete_selector_change),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                if (selected != null) {
                    AthleteAvatar(
                        selected.displayName,
                        selected.avatarKey,
                        size = 40.dp,
                        modifier = androidx.compose.ui.Modifier.clearAndSetSemantics { },
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(stringResource(R.string.athlete_selector_label), style = MaterialTheme.typography.labelMedium)
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
                if (!loading) {
                    Text(
                        stringResource(if (selected == null) R.string.athlete_selector_select else R.string.athlete_selector_change),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
            }
        }
    }

    if (open) {
        AthleteSelectorSheet(
            athletes = athletes,
            selectedAthleteId = selectedAthleteId,
            loading = loading,
            onDismiss = { open = false },
            onSelected = {
                onAthleteSelected(it)
                open = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AthleteSelectorSheet(
    athletes: List<AthleteEntity>,
    selectedAthleteId: Long?,
    loading: Boolean,
    onDismiss: () -> Unit,
    onSelected: (Long) -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    val active = athletes.filter { it.archivedAt == null }
    val filtered = filterActiveAthletes(athletes, search)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(stringResource(R.string.athlete_selector_title), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                singleLine = true,
                label = { Text(stringResource(R.string.athlete_selector_search)) },
            )
            if (loading) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.lg),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.athlete_selector_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (active.isEmpty()) {
                Text(
                    stringResource(R.string.athlete_selector_empty),
                    modifier = Modifier.padding(vertical = Spacing.lg),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (filtered.isEmpty()) {
                Text(
                    stringResource(R.string.athlete_selector_no_matches),
                    modifier = Modifier.padding(vertical = Spacing.lg),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(filtered, key = { it.id }) { athlete ->
                        ListItem(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(
                                selected = athlete.id == selectedAthleteId,
                                role = Role.RadioButton,
                                onClick = { onSelected(athlete.id) },
                            ),
                            leadingContent = {
                                AthleteAvatar(
                                    athlete.displayName,
                                    athlete.avatarKey,
                                    size = 40.dp,
                                    modifier = androidx.compose.ui.Modifier.clearAndSetSemantics { },
                                )
                            },
                            headlineContent = { Text(athlete.displayName) },
                            trailingContent = if (athlete.id == selectedAthleteId) {
                                {
                                    Text(
                                        stringResource(R.string.athlete_selector_selected),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = androidx.compose.ui.Modifier.clearAndSetSemantics { },
                                    )
                                }
                            } else null,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }
}

internal fun filterActiveAthletes(athletes: List<AthleteEntity>, query: String): List<AthleteEntity> {
    val normalized = query.trim()
    return athletes.filter { it.archivedAt == null &&
        (normalized.isBlank() || it.displayName.contains(normalized, ignoreCase = true)) }
}
