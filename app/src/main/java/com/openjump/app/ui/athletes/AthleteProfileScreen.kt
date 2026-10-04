package com.openjump.app.ui.athletes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openjump.app.R
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.AthleteHistoryPage
import com.openjump.app.data.AthleteJumpRecord
import com.openjump.app.data.PersonalRecord
import com.openjump.app.data.AthleteEncoderRecord
import com.openjump.app.data.encoderSeriesRepresentatives
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.MetricUnit
import com.openjump.app.protocol.MetricValue
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.settings.ExperienceMode
import com.openjump.app.ui.Formatting
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.components.AthleteAvatar
import com.openjump.app.ui.components.ExperienceModePicker
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.components.StatusPill
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import com.openjump.app.ui.titleResource
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun AthleteProfileScreen(
    athleteId: Long,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onMeasurementSelected: (Long) -> Unit,
    onEncoderSelected: (Long) -> Unit,
    onRecordsSelected: () -> Unit = {},
    onProgressSelected: () -> Unit = {},
    personalMode: Boolean = false,
    mode: ExperienceMode = ExperienceMode.PERSONAL,
    onModeSelected: (ExperienceMode) -> Unit = {},
    viewModel: AthleteProfileViewModel = viewModel(
        key = "athlete-profile-$athleteId",
        factory = AthleteProfileViewModel.factory(athleteId),
    ),
) {
    val athlete by viewModel.athlete.collectAsState()
    val jumpSummary by viewModel.jumpSummary.collectAsState()
    val encoderSummary by viewModel.encoderSummary.collectAsState()
    val protocolCounts by viewModel.protocolCounts.collectAsState()
    val encoderRecords by viewModel.encoderRecords.collectAsState()
    val history by viewModel.history.collectAsState()
    val unitProfile = LocalUnitSystem.current
    val locale = currentAppLocale()
    val encoderLoadLabels = remember(encoderRecords, unitProfile, locale) {
        MeasurementFormatting.formatMassLabels(encoderRecords.map { it.loadKg }, unitProfile, locale)
    }
    val pagination by viewModel.pagination.collectAsState()
    var expandedActivity by rememberSaveable(athleteId) { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshHistory() }

    Scaffold(
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(
                    if (personalMode) R.string.nav_people_personal else R.string.athlete_profile_title,
                ),
                onNavigationClick = if (personalMode) null else onBack,
                navigationContentDescription = if (personalMode) null else stringResource(R.string.common_back),
                showBrandLogo = personalMode,
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(
                            painter = painterResource(R.drawable.ic_edit),
                            contentDescription = stringResource(R.string.athlete_edit),
                        )
                    }
                    if (personalMode) {
                        ExperienceModePicker(mode, onModeSelected)
                    }
                },
            )
        },
    ) { padding ->
        val current = athlete
        if (current == null) {
            Text(stringResource(R.string.athlete_no_history), Modifier.padding(padding).padding(Spacing.lg))
        } else {
            val jumpTotal = jumpSummary?.total ?: 0
            val encoderTotal = encoderSummary?.total ?: 0
            val total = jumpTotal + encoderTotal
            val summariesReady = jumpSummary != null && encoderSummary != null
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(Spacing.screenHorizontal, Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                item { ProfileIdentity(current) }
                if (!summariesReady) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                } else {
                    item {
                        ProfileSummary(
                            jumpTotal = jumpTotal,
                            encoderTotal = encoderTotal,
                            lastActivity = history.firstOrNull()?.dateTime
                                ?: listOfNotNull(jumpSummary?.lastDateTime, encoderSummary?.lastDateTime).maxOrNull(),
                            counts = protocolCounts,
                        )
                    }
                    if (shouldShowProfileEmptyState(total, history, pagination.loading)) {
                        item { ProfileEmptyState() }
                    } else if (total > 0) {
                        item {
                            RecordsSection(encoderRecords, encoderLoadLabels, onRecordsSelected, onEncoderSelected)
                        }
                        item { ProgressEntry(onProgressSelected) }
                    }
                    if (total > 0 || history.isNotEmpty() || pagination.loading) {
                        item {
                            ActivitySection(
                                history = history,
                                encoderLoadLabels = encoderLoadLabels,
                                expanded = expandedActivity,
                                canLoadMore = pagination.canLoadMore,
                                loading = pagination.loading,
                                onExpand = { expandedActivity = !expandedActivity },
                                onLoadMore = viewModel::loadMore,
                                onMeasurementSelected = onMeasurementSelected,
                                onEncoderSelected = onEncoderSelected,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileIdentity(athlete: AthleteEntity) {
    val locale = LocalConfiguration.current.locales[0]
    val unitSystem = LocalUnitSystem.current
    val dateFormatter = remember(locale) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            AthleteAvatar(athlete.displayName, athlete.avatarKey, size = 80.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(athlete.displayName, style = OpenJumpTypes.ScreenTitle, maxLines = 2)
                StatusPill(
                    text = stringResource(if (athlete.archivedAt == null) R.string.athlete_profile_active else R.string.athlete_profile_archived),
                )
                listOfNotNull(
                    athlete.birthDate?.let {
                        stringResource(R.string.athlete_birth_date_info, dateFormatter.format(LocalDate.ofEpochDay(it)))
                    },
                    athlete.sex?.takeIf(String::isNotBlank)?.let { stringResource(R.string.athlete_sex_info, it) },
                    athlete.weightKg?.let {
                        stringResource(
                            R.string.athlete_weight_info,
                            MeasurementFormatting.format(it, MeasurementQuantity.MASS_KG, unitSystem, locale),
                        )
                    },
                    athlete.heightCm?.let {
                        stringResource(
                            R.string.athlete_height_info,
                            MeasurementFormatting.format(it, MeasurementQuantity.SHORT_LENGTH_CM, unitSystem, locale),
                        )
                    },
                ).forEach {
                    Text(it, style = OpenJumpTypes.Secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ProfileSummary(
    jumpTotal: Int,
    encoderTotal: Int,
    lastActivity: Long?,
    counts: List<com.openjump.app.data.AthleteProtocolCount>,
) {
    Surface(shape = ShapeTokens.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(stringResource(R.string.athlete_profile_summary), style = OpenJumpTypes.SectionTitle)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SummaryMetric(stringResource(R.string.athlete_summary_jumps), jumpTotal, Modifier.weight(1f))
                SummaryMetric(stringResource(R.string.athlete_summary_encoder), encoderTotal, Modifier.weight(1f))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                stringResource(
                    R.string.athlete_last_activity,
                    lastActivity?.let(Formatting::dateTimeToText) ?: stringResource(R.string.common_value_unavailable),
                ),
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            counts.forEach { count ->
                val protocol = ProtocolId.fromStorageKey(count.protocolId)
                Text(
                    stringResource(
                        R.string.athlete_family_count,
                        protocol?.let { stringResource(it.titleResource()) } ?: count.protocolId,
                        count.count,
                    ),
                    style = OpenJumpTypes.Secondary,
                )
            }
        }
    }
}

@Composable
private fun SummaryMetric(label: String, value: Int, modifier: Modifier) {
    Column(modifier) {
        Text(value.toString(), style = OpenJumpTypes.MetricHero)
        Text(label, style = OpenJumpTypes.MetricLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun shouldShowProfileEmptyState(
    total: Int,
    history: List<AthleteHistoryPage>,
    loading: Boolean,
): Boolean = total == 0 && history.isEmpty() && !loading

@Composable
private fun ProfileEmptyState() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(stringResource(R.string.athlete_empty_profile_title), style = OpenJumpTypes.SectionTitle)
            Text(stringResource(R.string.athlete_empty_profile_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RecordsSection(
    encoders: List<AthleteEncoderRecord>,
    encoderLoadLabels: Map<Double, String>,
    onAll: () -> Unit,
    onEncoder: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        Surface(
            onClick = onAll,
            modifier = Modifier.fillMaxWidth(),
            shape = ShapeTokens.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                Modifier.padding(Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_trophy), contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pr_title), style = OpenJumpTypes.Body)
                    Text(stringResource(R.string.pr_view_all), style = OpenJumpTypes.Secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
            }
        }
        if (encoders.isNotEmpty()) {
            Text(stringResource(R.string.athlete_records), style = OpenJumpTypes.SectionTitle)
            Surface(shape = ShapeTokens.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    encoders.forEachIndexed { index, record ->
                        EncoderRecordRow(record, index < encoders.lastIndex, encoderLoadLabels, onEncoder)
                    }
                }
            }
        }
    }
}

@Composable
internal fun recordCondition(record: PersonalRecord): String = when {
    record.series.side == com.openjump.app.protocol.MeasurementSide.LEFT -> stringResource(R.string.history_side_left)
    record.series.side == com.openjump.app.protocol.MeasurementSide.RIGHT -> stringResource(R.string.history_side_right)
    record.series.dropHeightCm != null -> stringResource(
        R.string.pr_drop_height,
        MeasurementFormatting.format(record.series.dropHeightCm, MeasurementQuantity.SHORT_LENGTH_CM,
            LocalUnitSystem.current, currentAppLocale(), decimals = 2),
    )
    else -> stringResource(record.series.metricKey.titleResource())
}

@Composable
private fun EncoderRecordRow(record: AthleteEncoderRecord, divider: Boolean, encoderLoadLabels: Map<Double, String>, onClick: (Long) -> Unit) {
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable { onClick(record.sessionId) },
        headlineContent = { Text(encoderSeriesLabel(record.exercise, record.loadKg, R.string.athlete_record_encoder, encoderLoadLabels)) },
        supportingContent = { Text(Formatting.dateTimeToText(record.dateTime)) },
        trailingContent = {
            Text(
                MeasurementFormatting.format(
                    record.bestMcv,
                    MeasurementQuantity.SPEED_MPS,
                    LocalUnitSystem.current,
                    currentAppLocale(),
                ),
                fontWeight = FontWeight.Bold,
            )
        },
    )
    if (divider) HorizontalDivider(Modifier.padding(horizontal = Spacing.lg))
}

@Composable
private fun ProgressEntry(onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = ShapeTokens.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_progress), contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.athlete_evolution), style = OpenJumpTypes.Body)
                Text(stringResource(R.string.progress_open), style = OpenJumpTypes.Secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
        }
    }
}

@Composable
private fun ActivitySection(
    history: List<AthleteHistoryPage>,
    expanded: Boolean,
    canLoadMore: Boolean,
    loading: Boolean,
    onExpand: () -> Unit,
    onLoadMore: () -> Unit,
    onMeasurementSelected: (Long) -> Unit,
    onEncoderSelected: (Long) -> Unit,
    encoderLoadLabels: Map<Double, String>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.athlete_history), style = OpenJumpTypes.SectionTitle, modifier = Modifier.weight(1f))
            if (history.size > 3) {
                TextButton(onClick = onExpand) { Text(stringResource(if (expanded) R.string.athlete_show_less else R.string.athlete_show_all)) }
            }
        }
        val visible = if (expanded) history else history.take(3)
        Surface(shape = ShapeTokens.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column {
                when {
                    history.isEmpty() && loading -> Box(
                        Modifier.fillMaxWidth().padding(Spacing.xl),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    history.isEmpty() -> Text(
                        stringResource(R.string.athlete_activity_empty),
                        modifier = Modifier.padding(Spacing.lg),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> visible.forEachIndexed { index, entry ->
                        ActivityRow(
                            entry = entry,
                            showDivider = index < visible.lastIndex,
                            onMeasurementSelected = onMeasurementSelected,
                            onEncoderSelected = onEncoderSelected,
                            encoderLoadLabels = encoderLoadLabels,
                        )
                    }
                }
            }
        }
        if (expanded && canLoadMore) {
            TextButton(onClick = onLoadMore, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.athlete_load_more))
            }
        }
    }
}

@Composable
private fun ActivityRow(
    entry: AthleteHistoryPage,
    showDivider: Boolean,
    onMeasurementSelected: (Long) -> Unit,
    onEncoderSelected: (Long) -> Unit,
    encoderLoadLabels: Map<Double, String>,
) {
    val jump = entry.kind == "JUMP"
    val title = if (jump) {
        ProtocolId.fromStorageKey(entry.protocolId.orEmpty())?.let { stringResource(it.titleResource()) } ?: entry.protocolId.orEmpty()
    } else {
        entry.loadKg?.let { encoderSeriesLabel(entry.exercise.orEmpty(), it, R.string.athlete_series_encoder_label, encoderLoadLabels) }
            ?: (runCatching { EncoderExercise.valueOf(entry.exercise.orEmpty()) }.getOrNull()?.let { stringResource(it.titleResource()) } ?: entry.exercise.orEmpty())
    }
    val value = if (jump) {
        entry.primaryMetricValue?.let { value ->
            formatMetricStorageValue(
                value,
                entry.primaryMetricKey.orEmpty(),
                entry.primaryMetricUnit.orEmpty(),
            )
        }
    } else {
        entry.bestMcv?.let {
            MeasurementFormatting.format(
                it,
                MeasurementQuantity.SPEED_MPS,
                LocalUnitSystem.current,
                currentAppLocale(),
            )
        }
    }
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable {
            when {
                jump -> onMeasurementSelected(entry.id)
                else -> onEncoderSelected(entry.id)
            }
        },
        overlineContent = { Text(stringResource(when { jump -> R.string.history_type_jump; else -> R.string.history_type_encoder }), style = OpenJumpTypes.Label) },
        headlineContent = { Text(title) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    listOfNotNull(
                        Formatting.dateTimeToText(entry.dateTime),
                        if (jump && entry.protocolId == ProtocolId.ASYMMETRY.storageKey) stringResource(R.string.history_bilateral_best) else null,
                    ).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = { Text(value ?: stringResource(R.string.common_value_unavailable), fontWeight = FontWeight.Bold) },
    )
    if (showDivider) HorizontalDivider(Modifier.padding(horizontal = Spacing.lg))
}

internal fun availableSeries(jumps: List<AthleteJumpRecord>, encoders: List<AthleteEncoderRecord>): Set<EvolutionSelection> {
    val result = linkedSetOf<EvolutionSelection>()
    jumps.forEach { result += EvolutionSelection.Jump(it.protocolId, it.side) }
    encoderSeriesRepresentatives(encoders).forEach { record ->
        result += EvolutionSelection.Encoder(record.exercise, record.loadKg)
    }
    return result
}

@Composable
private fun formatMetricStorageValue(value: Double, keyValue: String, unitValue: String): String {
    val key = MetricKey.fromStorageKey(keyValue)
    val unit = MetricUnit.fromStorageKey(unitValue)
    return if (key != null && unit != null) {
        MeasurementFormatting.format(
            value,
            MeasurementFormatting.quantity(MetricValue(key, value, unit)),
            LocalUnitSystem.current,
            currentAppLocale(),
            decimals = 2,
        )
    } else {
        MeasurementFormatting.formatRawStoredValue(value, unitValue, currentAppLocale())
    }
}

@Composable
private fun encoderSeriesLabel(
    exerciseKey: String,
    loadKg: Double,
    resource: Int,
    encoderLoadLabels: Map<Double, String> = emptyMap(),
): String {
    val exercise = runCatching { EncoderExercise.valueOf(exerciseKey) }.getOrNull()
    val exerciseLabel = exercise?.let { stringResource(it.titleResource()) } ?: exerciseKey
    val loadLabel = encoderLoadLabels[loadKg] ?: MeasurementFormatting.format(
        loadKg,
        MeasurementQuantity.MASS_KG,
        LocalUnitSystem.current,
        currentAppLocale(),
    )
    return stringResource(resource, exerciseLabel, loadLabel)
}
