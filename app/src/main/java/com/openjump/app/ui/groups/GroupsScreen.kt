package com.openjump.app.ui.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openjump.app.OpenJumpApp
import com.openjump.app.R
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.GroupEntity
import com.openjump.app.data.GroupRepository
import com.openjump.app.ui.components.AthleteAvatar
import com.openjump.app.ui.components.EmptyState
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.Spacing
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GroupsViewModel(private val groups: GroupRepository, private val athletes: com.openjump.app.data.AthleteRepository) : ViewModel() {
    val groupList: StateFlow<List<GroupEntity>> = groups.all().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList(),
    )
    val activeAthletes: StateFlow<List<AthleteEntity>> = athletes.active().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList(),
    )

    fun create(name: String, notes: String, athleteIds: Set<Long>) = viewModelScope.launch {
        groups.createWithMemberships(name, notes, athleteIds.toList())
    }

    fun update(group: GroupEntity, name: String, notes: String, athleteIds: Set<Long>) = viewModelScope.launch {
        groups.updateWithMemberships(group, name, notes, athleteIds.toList())
    }

    fun members(groupId: Long, onResult: (List<AthleteEntity>) -> Unit) = viewModelScope.launch {
        onResult(groups.members(groupId))
    }

    fun observeDetail(groupId: Long) = groups.observeDetail(groupId)

    fun archive(id: Long) = viewModelScope.launch { groups.archive(id) }
    fun restore(id: Long) = viewModelScope.launch { groups.restore(id) }
    fun removeMember(groupId: Long, athleteId: Long) = viewModelScope.launch {
        groups.removeMembership(groupId, athleteId)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OpenJumpApp
                GroupsViewModel(app.groupRepository, app.athleteRepository)
            }
        }
    }
}

@Composable
fun GroupsScreen(
    viewModel: GroupsViewModel,
    onBack: () -> Unit,
    onGroupSelected: (Long) -> Unit,
) {
    val groups by viewModel.groupList.collectAsState()
    val athletes by viewModel.activeAthletes.collectAsState()
    var showArchived by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<GroupEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var archiveCandidate by remember { mutableStateOf<GroupEntity?>(null) }
    val visible = groups.filter { (it.archivedAt != null) == showArchived }

    if (archiveCandidate != null) {
        AlertDialog(
            onDismissRequest = { archiveCandidate = null },
            title = { Text(stringResource(R.string.group_archive_title)) },
            text = { Text(stringResource(R.string.group_archive_message)) },
            dismissButton = { TextButton(onClick = { archiveCandidate = null }) { Text(stringResource(R.string.common_cancel)) } },
            confirmButton = {
                TextButton(onClick = {
                    archiveCandidate?.let { viewModel.archive(it.id) }
                    archiveCandidate = null
                }) { Text(stringResource(R.string.common_confirm)) }
            },
        )
    }

    if (creating || editing != null) {
        GroupEditor(
            group = editing,
            athletes = athletes,
            loadMembers = { id, callback -> viewModel.members(id, callback) },
            onCancel = { creating = false; editing = null },
            onSave = { name, notes, selected ->
                editing?.let { viewModel.update(it, name, notes, selected) }
                    ?: viewModel.create(name, notes, selected)
                creating = false
                editing = null
            },
        )
    } else {
        Scaffold(topBar = {
            OpenJumpTopAppBar(
                title = stringResource(R.string.groups_title),
                onNavigationClick = onBack,
                navigationContentDescription = stringResource(R.string.common_back),
                iconBadge = R.drawable.ic_group,
                actions = {
                    TextButton(onClick = { creating = true }) { Text(stringResource(R.string.group_add)) }
                },
            )
        }) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                TabRow(selectedTabIndex = if (showArchived) 1 else 0) {
                    Tab(!showArchived, { showArchived = false }, text = { Text(stringResource(R.string.group_active)) })
                    Tab(showArchived, { showArchived = true }, text = { Text(stringResource(R.string.group_archived)) })
                }
                if (visible.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.groups_title),
                        body = stringResource(R.string.group_empty_body),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screenHorizontal),
                    )
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = Spacing.screenHorizontal),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        items(visible, key = { it.id }) { group ->
                            ListItem(
                                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onGroupSelected(group.id) },
                                headlineContent = { Text(group.name, style = OpenJumpTypes.SectionTitle) },
                                supportingContent = { Text(group.notes ?: stringResource(R.string.group_members_hint)) },
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                if (showArchived) {
                                    TextButton(onClick = { viewModel.restore(group.id) }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                                        Text(stringResource(R.string.group_restore))
                                    }
                                } else {
                                    TextButton(onClick = { editing = group }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                                        Text(stringResource(R.string.group_edit))
                                    }
                                    TextButton(onClick = { archiveCandidate = group }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                                        Text(stringResource(R.string.group_archive))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupEditor(
    group: GroupEntity?,
    athletes: List<AthleteEntity>,
    loadMembers: (Long, (List<AthleteEntity>) -> Unit) -> Unit,
    onCancel: () -> Unit,
    onSave: (String, String, Set<Long>) -> Unit,
) {
    var name by remember(group?.id) { mutableStateOf(group?.name.orEmpty()) }
    var notes by remember(group?.id) { mutableStateOf(group?.notes.orEmpty()) }
    var selected by remember(group?.id) { mutableStateOf(emptySet<Long>()) }
    var membersLoaded by remember(group?.id) { mutableStateOf(group == null) }
    LaunchedEffect(group?.id) {
        if (group != null) loadMembers(group.id) { members ->
            selected = members.map { it.id }.toSet()
            membersLoaded = true
        }
    }
    Scaffold(topBar = {
        OpenJumpTopAppBar(
            title = stringResource(if (group == null) R.string.group_add_title else R.string.group_edit_title),
            onNavigationClick = onCancel,
            navigationContentDescription = stringResource(R.string.common_back),
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.group_name)) }, singleLine = true)
            OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.group_notes)) }, minLines = 2)
            Text(stringResource(R.string.group_select_athletes), style = OpenJumpTypes.SectionTitle, modifier = Modifier.padding(top = Spacing.md))
            if (athletes.isEmpty()) {
                Text(stringResource(R.string.group_no_active_athletes))
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    items(athletes, key = { it.id }) { athlete ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                                selected = if (athlete.id in selected) selected - athlete.id else selected + athlete.id
                            }.semantics { role = Role.Checkbox },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = athlete.id in selected, onCheckedChange = null)
                            AthleteAvatar(athlete.displayName, athlete.avatarKey, size = 40.dp)
                            Text(athlete.displayName, Modifier.padding(start = Spacing.sm))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(onClick = onCancel, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.common_cancel)) }
                Button(
                    onClick = { onSave(name.trim(), notes.trim(), selected) },
                    enabled = name.isNotBlank() && membersLoaded,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.group_save))
                }
            }
        }
    }
}

@Composable
fun GroupDetailScreen(
    groupId: Long,
    viewModel: GroupsViewModel,
    onBack: () -> Unit,
    onAthleteSelected: (Long) -> Unit,
    onTesting: (Long) -> Unit = {},
) {
    val detail by remember(groupId) { viewModel.observeDetail(groupId) }.collectAsState(initial = null)
    val group = detail
    if (group == null) {
        EmptyState(stringResource(R.string.group_missing), stringResource(R.string.group_missing_body), Modifier.fillMaxSize())
        return
    }
    Scaffold(topBar = {
        OpenJumpTopAppBar(
            title = group.group.name,
            onNavigationClick = onBack,
            navigationContentDescription = stringResource(R.string.common_back),
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.screenHorizontal)) {
            Text(group.group.notes.orEmpty(), color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.group_members), style = OpenJumpTypes.SectionTitle, modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.sm))
            if (group.members.isEmpty()) {
                Text(stringResource(R.string.group_no_members))
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    items(group.members, key = { it.id }) { athlete ->
                        ListItem(
                            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onAthleteSelected(athlete.id) },
                            headlineContent = { Text(athlete.displayName) },
                            leadingContent = { AthleteAvatar(athlete.displayName, athlete.avatarKey) },
                            supportingContent = if (athlete.archivedAt != null) {
                                { Text(stringResource(R.string.group_archived_member)) }
                            } else null,
                            trailingContent = if (group.group.archivedAt == null) {
                                {
                                    TextButton(
                                        onClick = { viewModel.removeMember(groupId, athlete.id) },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) { Text(stringResource(R.string.group_remove_member)) }
                                }
                            } else null,
                        )
                    }
                }
            }
            if (group.group.archivedAt != null) {
                Button(onClick = { viewModel.restore(groupId) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.group_restore)) }
            } else {
                OutlinedButton(onClick = { onTesting(groupId) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.testing_create)) }
                OutlinedButton(onClick = { viewModel.archive(groupId) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.group_archive)) }
            }
        }
    }
}
