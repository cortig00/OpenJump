package com.openjump.app.ui.athletes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openjump.app.R
import com.openjump.app.data.AthleteEncoderRecord
import com.openjump.app.data.ProgressPoint
import com.openjump.app.data.ProgressSeries
import com.openjump.app.data.PROGRESS_POINT_LIMIT
import com.openjump.app.data.progressXPositions
import com.openjump.app.data.encoderSeriesRepresentatives
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.protocol.MeasurementSide
import com.openjump.app.protocol.MetricValue
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.ui.Formatting
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.MeasurementFormatting
import com.openjump.app.ui.MeasurementQuantity
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.components.EmptyState
import com.openjump.app.ui.components.OpenJumpTopAppBar
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing
import com.openjump.app.ui.titleResource
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private sealed interface ProgressPage {
    data class Jump(val protocol: ProtocolId) : ProgressPage
    data class Encoder(val exercise: String) : ProgressPage
}

@Composable
fun ProgressScreen(
    athleteId: Long,
    onBack: () -> Unit,
    onMeasurementSelected: (Long, Int?) -> Unit,
    onEncoderSelected: (Long) -> Unit,
) {
    val model: ProgressViewModel = viewModel(key = "progress-$athleteId", factory = ProgressViewModel.factory(athleteId))
    val athlete by model.athlete.collectAsState()
    val jumpSeries by model.jumps.collectAsState()
    val encoderRecords by model.encoderRecords.collectAsState()
    val pages = remember(jumpSeries, encoderRecords) {
        val jumps = ProtocolId.entries.filter { id -> jumpSeries.orEmpty().any { it.condition.protocolId == id } }
            .map(ProgressPage::Jump)
        val encoders = encoderRecords.orEmpty().map { it.exercise }.distinct().sorted().map(ProgressPage::Encoder)
        jumps + encoders
    }
    val pager = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        OpenJumpTopAppBar(
            title = stringResource(R.string.athlete_evolution), onNavigationClick = onBack,
            navigationContentDescription = stringResource(R.string.common_back),
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(athlete?.displayName ?: stringResource(R.string.athlete_unassigned),
                style = OpenJumpTypes.ScreenTitle,
                modifier = Modifier.padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm))
            when {
                jumpSeries == null || encoderRecords == null -> CircularProgressIndicator(Modifier.padding(Spacing.xl))
                pages.isEmpty() -> EmptyState(stringResource(R.string.progress_empty_title),
                    stringResource(R.string.progress_empty_body))
                else -> {
                    val index = pager.currentPage.coerceIn(pages.indices)
                    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.screenHorizontal),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { scope.launch { pager.animateScrollToPage(index - 1) } }, enabled = index > 0) {
                            Icon(painterResource(R.drawable.ic_chevron_right),
                                contentDescription = stringResource(R.string.progress_previous),
                                modifier = Modifier.rotate(180f))
                        }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            TextButton(onClick = { menuOpen = true }) {
                                Text(pageTitle(pages[index]), style = OpenJumpTypes.SectionTitle)
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                pages.forEachIndexed { pageIndex, page ->
                                    DropdownMenuItem(text = { Text(pageTitle(page)) }, onClick = {
                                        menuOpen = false
                                        scope.launch { pager.animateScrollToPage(pageIndex) }
                                    })
                                }
                            }
                        }
                        IconButton(onClick = { scope.launch { pager.animateScrollToPage(index + 1) } }, enabled = index < pages.lastIndex) {
                            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = stringResource(R.string.progress_next))
                        }
                    }
                    Text(stringResource(R.string.progress_page_count, index + 1, pages.size),
                        style = OpenJumpTypes.Label, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally))
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { pages[it].toString() }) { pageIndex ->
                        when (val page = pages[pageIndex]) {
                            is ProgressPage.Jump -> JumpProgressPage(page.protocol,
                                jumpSeries.orEmpty().filter { it.condition.protocolId == page.protocol }, onMeasurementSelected)
                            is ProgressPage.Encoder -> EncoderProgressPage(page.exercise,
                                encoderRecords.orEmpty().filter { it.exercise == page.exercise }, model, onEncoderSelected)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun pageTitle(page: ProgressPage): String = when (page) {
    is ProgressPage.Jump -> stringResource(page.protocol.titleResource())
    is ProgressPage.Encoder -> runCatching { EncoderExercise.valueOf(page.exercise) }.getOrNull()
        ?.let { stringResource(it.titleResource()) } ?: page.exercise
}

@Composable
private fun JumpProgressPage(protocol: ProtocolId, series: List<ProgressSeries>, open: (Long, Int?) -> Unit) {
    var side by rememberSaveable(protocol.storageKey) { mutableStateOf(MeasurementSide.LEFT.name) }
    var box by rememberSaveable(protocol.storageKey) { mutableStateOf<Double?>(null) }
    var boxMenu by remember { mutableStateOf(false) }
    val boxes = series.mapNotNull { it.condition.dropHeightCm }.sorted()
    val effectiveSide = side.takeIf { wanted -> series.any { it.condition.side?.name == wanted } }
        ?: series.firstOrNull()?.condition?.side?.name
    val chosen = when (protocol) {
        ProtocolId.UNILATERAL -> series.firstOrNull { it.condition.side?.name == effectiveSide }
        ProtocolId.DROP_JUMP -> series.firstOrNull { it.condition.dropHeightCm == (box?.takeIf { it in boxes } ?: boxes.firstOrNull()) }
        else -> series.firstOrNull()
    }
    val current = chosen ?: return
    val quantity = MeasurementFormatting.quantity(MetricValue(current.condition.metricKey, 0.0, current.condition.unit))
    Column {
        if (protocol == ProtocolId.UNILATERAL) {
            Row(Modifier.padding(horizontal = Spacing.screenHorizontal), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                listOf(MeasurementSide.LEFT, MeasurementSide.RIGHT).forEach { option ->
                    FilterChip(selected = effectiveSide == option.name, enabled = series.any { it.condition.side == option },
                        onClick = { side = option.name },
                        label = { Text(stringResource(if (option == MeasurementSide.LEFT) R.string.history_side_left else R.string.history_side_right)) })
                }
            }
        }
        if (protocol == ProtocolId.DROP_JUMP) {
            Box(Modifier.padding(horizontal = Spacing.screenHorizontal)) {
                TextButton(onClick = { boxMenu = true }) {
                    Text(stringResource(R.string.pr_drop_height, formatBox(current.condition.dropHeightCm!!)) + "  ▾")
                }
                DropdownMenu(expanded = boxMenu, onDismissRequest = { boxMenu = false }) {
                    boxes.forEach { height -> DropdownMenuItem(text = { Text(formatBox(height)) }, onClick = {
                        box = height; boxMenu = false
                    }) }
                }
            }
        }
        ProgressContent(
            seriesKey = "${protocol.storageKey}-${current.condition.side}-${current.condition.dropHeightCm}",
            points = current.points,
            best = current.personalBest.value,
            bestIndex = current.points.indexOfFirst { it.assessmentId == current.personalBest.assessmentId &&
                it.attemptOrdinal == current.personalBest.attemptOrdinal },
            total = current.totalCount,
            quantity = quantity,
            onOpen = { point -> open(point.assessmentId, point.attemptOrdinal.takeIf { point.fromBilateral }) },
        )
    }
}

@Composable
private fun formatBox(height: Double): String = MeasurementFormatting.format(
    height, MeasurementQuantity.SHORT_LENGTH_CM, LocalUnitSystem.current, currentAppLocale(), decimals = 2)

@Composable
private fun EncoderProgressPage(exercise: String, records: List<AthleteEncoderRecord>,
    model: ProgressViewModel, open: (Long) -> Unit) {
    val loads = encoderSeriesRepresentatives(records)
    var selectedLoad by rememberSaveable(exercise) { mutableStateOf<Double?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val selected = loads.firstOrNull { it.loadKg == selectedLoad } ?: loads.firstOrNull() ?: return
    var points by remember(exercise, selected.loadKg) { mutableStateOf<List<ProgressPoint>?>(null) }
    LaunchedEffect(exercise, selected.loadKg) {
        points = model.encoderPoints(exercise, selected.loadKg).map { ProgressPoint(it.id, 0, it.dateTime, it.value, false) }
    }
    Column {
        Box(Modifier.padding(horizontal = Spacing.screenHorizontal)) {
            TextButton(onClick = { menuOpen = true }) {
                Text(MeasurementFormatting.format(selected.loadKg, MeasurementQuantity.MASS_KG,
                    LocalUnitSystem.current, currentAppLocale()) + "  ▾")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                loads.forEach { load -> DropdownMenuItem(text = {
                    Text(MeasurementFormatting.format(load.loadKg, MeasurementQuantity.MASS_KG,
                        LocalUnitSystem.current, currentAppLocale()))
                }, onClick = { selectedLoad = load.loadKg; menuOpen = false }) }
            }
        }
        val visible = points
        if (visible == null) CircularProgressIndicator(Modifier.padding(Spacing.xl))
        else if (visible.isNotEmpty()) ProgressContent(
            seriesKey = "$exercise-${selected.loadKg}", points = visible,
            best = visible.maxOf { it.value }, bestIndex = -1, total = visible.size,
            quantity = MeasurementQuantity.SPEED_MPS, onOpen = { open(it.assessmentId) },
            // Encoder best is within the bounded window only; do not label it as an all-time PR.
            bestLabel = R.string.progress_best_shown,
        )
    }
}

@Composable
private fun ProgressContent(
    seriesKey: String,
    points: List<ProgressPoint>,
    best: Double,
    bestIndex: Int,
    total: Int,
    quantity: MeasurementQuantity,
    onOpen: (ProgressPoint) -> Unit,
    bestLabel: Int = R.string.progress_personal_best,
) {
    if (points.isEmpty()) return
    var selectedIndex by rememberSaveable(seriesKey) { mutableIntStateOf(points.lastIndex) }
    val index = selectedIndex.coerceIn(points.indices)
    val units = LocalUnitSystem.current
    val locale = currentAppLocale()
    fun display(value: Double): String = MeasurementFormatting.format(value, quantity, units, locale, decimals = 2)
    val change = if (points.size > 1 && points.first().dateTime != points.last().dateTime)
        points.last().value - points.first().value else null
    LazyColumn(contentPadding = PaddingValues(Spacing.screenHorizontal, Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        item {
            Surface(shape = ShapeTokens.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        SummaryValue(stringResource(R.string.progress_latest), display(points.last().value))
                        SummaryValue(stringResource(bestLabel), display(best))
                    }
                    if (change != null) {
                        val absolute = display(abs(change))
                        val signed = if (change > 0) "+$absolute" else if (change < 0) "−$absolute" else absolute
                        SummaryValue(stringResource(R.string.progress_change_shown), signed)
                    }
                }
            }
        }
        item {
            if (total > PROGRESS_POINT_LIMIT) Text(pluralStringResource(R.plurals.progress_latest_limit, points.size, points.size),
                style = OpenJumpTypes.Secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ProgressChart(points, quantity, index, bestIndex, onSelect = { selectedIndex = it })
        }
        item {
            val point = points[index]
            Surface(modifier = Modifier.fillMaxWidth(), shape = ShapeTokens.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(stringResource(R.string.progress_selected_point), style = OpenJumpTypes.Label)
                    Text(display(point.value), style = OpenJumpTypes.MetricValue)
                    Text(Formatting.dateTimeToText(point.dateTime), style = OpenJumpTypes.Secondary)
                    if (point.fromBilateral) Text(stringResource(R.string.pr_bilateral_origin), style = OpenJumpTypes.Secondary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { selectedIndex = index - 1 }, enabled = index > 0) {
                            Text(stringResource(R.string.progress_previous_point))
                        }
                        TextButton(onClick = { selectedIndex = index + 1 }, enabled = index < points.lastIndex) {
                            Text(stringResource(R.string.progress_next_point))
                        }
                    }
                    TextButton(onClick = { onOpen(point) }) { Text(stringResource(R.string.pr_open_measurement)) }
                }
            }
        }
        item { Text(stringResource(R.string.progress_recent), style = OpenJumpTypes.SectionTitle) }
        items(points.takeLast(3).reversed(), key = { "${it.assessmentId}-${it.attemptOrdinal}" }) { point ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(point) }.padding(vertical = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(Formatting.dateTimeToText(point.dateTime), modifier = Modifier.weight(1f), style = OpenJumpTypes.Secondary)
                Text(display(point.value), style = OpenJumpTypes.Body)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun SummaryValue(label: String, value: String) {
    Column {
        Text(label, style = OpenJumpTypes.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = OpenJumpTypes.MetricValue)
    }
}

@Composable
private fun ProgressChart(points: List<ProgressPoint>, quantity: MeasurementQuantity,
    selected: Int, bestIndex: Int, onSelect: (Int) -> Unit) {
    val units = LocalUnitSystem.current
    val locale = currentAppLocale()
    val values = points.map { MeasurementFormatting.toDisplay(it.value, quantity, units) }
    val min = values.minOrNull() ?: return
    val max = values.maxOrNull() ?: return
    val span = (max - min).takeIf { it > 0.0 } ?: 1.0
    val xs = progressXPositions(points.map { it.dateTime })
    val unit = MeasurementFormatting.unit(quantity, units).symbol
    val summary = stringResource(R.string.progress_chart_description, points.size,
        MeasurementFormatting.format(points.first().value, quantity, units, locale, decimals = 2),
        MeasurementFormatting.format(points.last().value, quantity, units, locale, decimals = 2))
    val color = MaterialTheme.colorScheme.primary
    val accent = MaterialTheme.colorScheme.tertiary
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(66.dp).height(190.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(String.format(locale, "%.2f", max), style = OpenJumpTypes.Label)
                Text(unit, style = OpenJumpTypes.Label)
                Text(String.format(locale, "%.2f", min), style = OpenJumpTypes.Label)
            }
            Canvas(Modifier.fillMaxWidth().height(190.dp)
                .pointerInput(points) {
                    detectTapGestures { tap ->
                        val left = 12.dp.toPx()
                        val width = (size.width - left * 2).coerceAtLeast(1f)
                        val target = ((tap.x - left) / width).coerceIn(0f, 1f)
                        onSelect(xs.indices.minByOrNull { abs(xs[it] - target) } ?: 0)
                    }
                }.semantics { contentDescription = summary }) {
                val left = 12.dp.toPx()
                val width = (size.width - 2 * left).coerceAtLeast(1f)
                val top = 12.dp.toPx()
                val height = (size.height - 2 * top).coerceAtLeast(1f)
                val offsets = values.mapIndexed { i, v -> Offset(left + xs[i] * width,
                    top + ((max - v) / span * height).toFloat().let { if (max == min) height / 2 else it }) }
                val path = Path()
                offsets.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                if (offsets.size > 1) drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
                offsets.forEachIndexed { i, p ->
                    drawCircle(if (i == bestIndex) accent else color, if (i == selected) 6.dp.toPx() else 3.dp.toPx(), p)
                }
            }
        }
        val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale)
        fun label(time: Long): String = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate().format(dateFormat)
        Row(Modifier.fillMaxWidth().padding(start = 66.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label(points.first().dateTime), style = OpenJumpTypes.Label)
            if (points.size > 2 && LocalConfiguration.current.screenWidthDp >= 480)
                Text(label(points[points.size / 2].dateTime), style = OpenJumpTypes.Label)
            if (points.size > 1) Text(label(points.last().dateTime), style = OpenJumpTypes.Label)
        }
        if (bestIndex >= 0) Text(stringResource(R.string.progress_pr_marker), style = OpenJumpTypes.Secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.progress_chart_hint), style = OpenJumpTypes.Secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
