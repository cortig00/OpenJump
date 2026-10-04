package com.openjump.app.ui.result

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.ui.titleResource
import com.openjump.app.ui.LocalUnitSystem
import com.openjump.app.ui.currentAppLocale
import com.openjump.app.ui.theme.OpenJumpTheme

/** Proportional PTS timeline. Text legend remains available outside Canvas. */
@Composable
fun ResultTimeline(timeline: TimelineUiModel, modifier: Modifier = Modifier) {
    val preparationColor = OpenJumpTheme.colors.dataBlue
    val contactColor = MaterialTheme.colorScheme.tertiary
    val flightColor = MaterialTheme.colorScheme.primary
    fun colorFor(kind: TimelineSegmentKind): Color = when (kind) {
        TimelineSegmentKind.PREPARATION -> preparationColor
        TimelineSegmentKind.CONTACT -> contactColor
        TimelineSegmentKind.FLIGHT -> flightColor
    }
    val labels = timeline.segments.map { stringResource(it.kind.titleResource()) }
    val profile = LocalUnitSystem.current
    val locale = currentAppLocale()
    val total = ResultText.durationUs(timeline.totalDurationUs, locale, profile)
    val accessibleSummary = stringResource(
        R.string.result_timeline_accessibility,
        timeline.segments.mapIndexed { index, segment ->
            stringResource(
                R.string.result_timeline_accessibility_segment,
                labels[index].lowercase(),
                ResultText.durationUs(segment.durationUs, locale, profile),
            )
        }.joinToString("; "),
        total,
    )
    Column(modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.result_timeline_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clearAndSetSemantics { contentDescription = accessibleSummary },
        ) {
            var x = 0f
            timeline.segments.forEach { segment ->
                val width = size.width * segment.durationUs.toFloat() / timeline.totalDurationUs.toFloat()
                drawRect(
                    color = colorFor(segment.kind),
                    topLeft = androidx.compose.ui.geometry.Offset(x, 0f),
                    size = androidx.compose.ui.geometry.Size(width, size.height),
                )
                x += width
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            timeline.segments.forEachIndexed { index, segment ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(colorFor(segment.kind)),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        stringResource(
                            R.string.result_timeline_segment,
                            labels[index],
                            ResultText.durationUs(segment.durationUs, locale, profile),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Text(
                stringResource(R.string.result_timeline_total, total),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
