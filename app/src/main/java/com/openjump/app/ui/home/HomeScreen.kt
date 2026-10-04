package com.openjump.app.ui.home

import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openjump.app.R
import com.openjump.app.data.PersonalRecord
import com.openjump.app.ui.athletes.PersonalRecordFeedbackBanner
import com.openjump.app.ui.components.EmptyState
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.components.ProtocolFeedbackBanner
import com.openjump.app.ui.components.ProtocolFeedbackLevel
import com.openjump.app.ui.history.HistoryEntryRow
import com.openjump.app.ui.history.MeasurementHistoryItem
import com.openjump.app.ui.labs.ExperimentalBadge
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import com.openjump.app.video.SeriesFeedback

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    seriesFeedback: SeriesFeedback? = null,
    onSeriesFeedbackConsumed: () -> Unit = {},
    recordFeedback: List<PersonalRecord> = emptyList(),
    onRecordFeedbackConsumed: () -> Unit = {},
    onJumpsSelected: () -> Unit,
    onEncoderSelected: () -> Unit,
    onHistorySelected: () -> Unit,
    onMeasurementSelected: (Long) -> Unit,
    onEncoderSessionSelected: (Long) -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val recentActivity by viewModel.recentActivity.collectAsState()
    var shownSeries by rememberSaveable { mutableStateOf<SeriesFeedback?>(null) }
    var expiresAt by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(seriesFeedback?.sessionKey) {
        seriesFeedback?.let {
            shownSeries = it
            expiresAt = SystemClock.elapsedRealtime() + 4000L
            onSeriesFeedbackConsumed()
        }
    }
    LaunchedEffect(shownSeries?.sessionKey, expiresAt) {
        val shown = shownSeries ?: return@LaunchedEffect
        delay((expiresAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        if (shownSeries == shown) shownSeries = null
    }
    val completed = shownSeries?.takeIf { it.completed && SystemClock.elapsedRealtime() < expiresAt }
    var shownRecords by remember { mutableStateOf<List<PersonalRecord>>(emptyList()) }
    LaunchedEffect(recordFeedback) {
        if (recordFeedback.isNotEmpty()) {
            shownRecords = recordFeedback
            onRecordFeedbackConsumed()
            delay(4_000L)
            if (shownRecords == recordFeedback) shownRecords = emptyList()
        }
    }

    Scaffold(
        topBar = {
            OpenJumpTopAppBar(
                title = stringResource(R.string.app_name),
                showBrandLogo = true,
                isHome = true,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                horizontal = Spacing.screenHorizontal,
                vertical = Spacing.md,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        stringResource(R.string.home_question),
                        style = OpenJumpTypes.ScreenTitle,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.home_intro),
                        style = OpenJumpTypes.Secondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            shownRecords.forEach { record ->
                item(key = "pr-${record.assessmentId}-${record.attemptOrdinal}") {
                    PersonalRecordFeedbackBanner(record)
                }
            }
            if (completed != null) {
                item {
                    ProtocolFeedbackBanner(
                        text = stringResource(R.string.series_complete_feedback, completed.targetAttempts),
                        accessibilityText = stringResource(R.string.series_complete_feedback, completed.targetAttempts),
                        level = ProtocolFeedbackLevel.COMPLETE,
                    )
                }
            }
            item {
                Surface(
                    shape = ShapeTokens.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Column {
                        JumpEntry(onJumpsSelected)
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 72.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        EncoderEntry(onEncoderSelected)
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.home_recent_title),
                        style = OpenJumpTypes.SectionTitle,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TextButton(onClick = onHistorySelected) {
                        Text(stringResource(R.string.home_view_history))
                    }
                }
            }
            if (recentActivity.isEmpty()) {
                item {
                    EmptyState(
                        title = stringResource(R.string.home_recent_empty_title),
                        body = stringResource(R.string.home_recent_empty_body),
                    )
                }
            } else {
                item {
                    Column {
                        recentActivity.forEachIndexed { index, item ->
                            HistoryEntryRow(
                                item = item,
                                onClick = {
                                    when (item) {
                                        is MeasurementHistoryItem.Jump -> onMeasurementSelected(item.id)
                                        is MeasurementHistoryItem.Encoder -> onEncoderSessionSelected(item.id)
                                    }
                                },
                                showDivider = index < recentActivity.lastIndex,
                                recent = true,
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.size(Spacing.sm)) }
        }
    }
}

@Composable
private fun JumpEntry(onClick: () -> Unit) = AnalysisEntryRow(
    title = stringResource(R.string.home_jumps_title),
    description = stringResource(R.string.home_jumps_description),
    icon = R.drawable.ic_jumps,
    onClick = onClick,
)

@Composable
private fun EncoderEntry(onClick: () -> Unit) = AnalysisEntryRow(
    title = stringResource(R.string.labs_name),
    description = stringResource(R.string.labs_home_description),
    icon = R.drawable.ic_encoder,
    experimental = true,
    onClick = onClick,
)

@Composable
private fun AnalysisEntryRow(
    title: String,
    description: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    experimental: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Surface(
            shape = ShapeTokens.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(Spacing.sm),
            )
        }
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                title,
                style = OpenJumpTypes.SectionTitle,
                fontWeight = FontWeight.SemiBold,
            )
            if (experimental) ExperimentalBadge()
            Text(
                description,
                style = OpenJumpTypes.Secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
