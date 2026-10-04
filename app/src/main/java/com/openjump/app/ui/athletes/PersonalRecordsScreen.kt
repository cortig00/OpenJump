package com.openjump.app.ui.athletes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openjump.app.R
import com.openjump.app.data.PersonalRecord
import com.openjump.app.protocol.MetricValue
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.ui.Formatting
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.components.EmptyState
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.Spacing
import com.openjump.app.ui.titleResource

@Composable
fun PersonalRecordsScreen(
    athleteId: Long,
    onBack: () -> Unit,
    onMeasurementSelected: (Long, Int?) -> Unit,
) {
    val model: PersonalRecordsViewModel = viewModel(
        key = "personal-records-$athleteId", factory = PersonalRecordsViewModel.factory(athleteId),
    )
    val athlete by model.athlete.collectAsState()
    val records by model.records.collectAsState()
    var expandedRecord by rememberSaveable(athleteId) { mutableStateOf<String?>(null) }
    var dropExpanded by rememberSaveable(athleteId) { mutableStateOf(false) }

    Scaffold(topBar = {
        OpenJumpTopAppBar(
            title = stringResource(R.string.pr_title),
            onNavigationClick = onBack,
            navigationContentDescription = stringResource(R.string.common_back),
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(Spacing.screenHorizontal, Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(athlete?.displayName ?: stringResource(R.string.athlete_unassigned), style = OpenJumpTypes.ScreenTitle)
                    Text(stringResource(R.string.pr_intro), style = OpenJumpTypes.Secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when (val current = records) {
                null -> item { CircularProgressIndicator() }
                else -> if (current.isEmpty()) {
                    item { EmptyState(stringResource(R.string.pr_empty_title), stringResource(R.string.pr_empty_body)) }
                } else {
                    current.groupBy { it.series.protocolId }.forEach { (protocol, group) ->
                        item(key = protocol.storageKey) {
                            when (protocol) {
                                ProtocolId.DROP_JUMP -> {
                                    Column {
                                        ListItem(
                                            modifier = Modifier.fillMaxWidth().clickable { dropExpanded = !dropExpanded },
                                            headlineContent = { Text(stringResource(protocol.titleResource()), style = OpenJumpTypes.SectionTitle) },
                                            supportingContent = {
                                                Text(pluralStringResource(R.plurals.pr_box_heights, group.size, group.size))
                                            },
                                            trailingContent = { RecordChevron() },
                                        )
                                        if (dropExpanded) group.forEach { record ->
                                            RecordRow(
                                                record = record,
                                                expanded = expandedRecord == record.key(),
                                                onToggle = { expandedRecord = if (expandedRecord == record.key()) null else record.key() },
                                                onMeasurementSelected = onMeasurementSelected,
                                            )
                                        }
                                    }
                                }
                                else -> {
                                    Column {
                                        Text(stringResource(protocol.titleResource()), style = OpenJumpTypes.SectionTitle)
                                        group.forEach { record ->
                                            RecordRow(
                                                record = record,
                                                expanded = expandedRecord == record.key(),
                                                onToggle = { expandedRecord = if (expandedRecord == record.key()) null else record.key() },
                                                onMeasurementSelected = onMeasurementSelected,
                                            )
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
}

private fun PersonalRecord.key(): String = "$assessmentId-$attemptOrdinal"

@Composable
private fun RecordChevron() {
    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
}

@Composable
private fun RecordRow(
    record: PersonalRecord,
    expanded: Boolean,
    onToggle: () -> Unit,
    onMeasurementSelected: (Long, Int?) -> Unit,
) {
    val unit = LocalUnitSystem.current
    val locale = currentAppLocale()
    val quantity = MeasurementFormatting.quantity(MetricValue(record.series.metricKey, record.value, record.series.unit))
    val value = MeasurementFormatting.format(record.value, quantity, unit, locale, decimals = 2)
    val improvement = record.previousBest?.let {
        MeasurementFormatting.format(record.value - it, quantity, unit, locale, decimals = 2)
    }
    Column {
        ListItem(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
            headlineContent = { Text(value, style = OpenJumpTypes.MetricValue) },
            overlineContent = {
                when {
                    record.series.dropHeightCm != null -> Text(
                        stringResource(R.string.athlete_record_side, recordCondition(record),
                            stringResource(record.series.metricKey.titleResource())), style = OpenJumpTypes.Label,
                    )
                    record.series.protocolId == ProtocolId.UNILATERAL ->
                        Text(recordCondition(record), style = OpenJumpTypes.Label)
                }
            },
            supportingContent = {
                Column {
                    Text(Formatting.dateTimeToText(record.dateTime))
                    if (record.fromBilateral) Text(stringResource(R.string.pr_bilateral_origin),
                        style = OpenJumpTypes.Secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            trailingContent = { RecordChevron() },
        )
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(recordCondition(record), style = OpenJumpTypes.Secondary)
                Text(
                    if (improvement == null) stringResource(R.string.pr_first_mark)
                    else stringResource(R.string.pr_improvement, improvement),
                    style = OpenJumpTypes.Secondary,
                )
                TextButton(onClick = {
                    onMeasurementSelected(record.assessmentId, record.attemptOrdinal.takeIf { record.fromBilateral })
                }) { Text(stringResource(R.string.pr_open_measurement)) }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
