package com.openjump.app.ui.history

import com.openjump.app.data.RecentEncoderSession
import com.openjump.app.data.RecentMeasurement

sealed interface MeasurementHistoryItem {
    val id: Long
    val dateTime: Long

    data class Jump(val measurement: RecentMeasurement) : MeasurementHistoryItem {
        override val id: Long = measurement.id
        override val dateTime: Long = measurement.dateTime
    }

    data class Encoder(val session: RecentEncoderSession) : MeasurementHistoryItem {
        override val id: Long = session.id
        override val dateTime: Long = session.dateTime
    }
}

fun mergeMeasurementHistory(
    jumps: List<RecentMeasurement>,
    encoderSessions: List<RecentEncoderSession>,
    limit: Int? = null,
): List<MeasurementHistoryItem> {
    require(limit == null || limit >= 0)
    val merged = (jumps.map(MeasurementHistoryItem::Jump) +
        encoderSessions.map(MeasurementHistoryItem::Encoder))
        .sortedWith(
            compareByDescending<MeasurementHistoryItem> { it.dateTime }
                .thenByDescending { it.id }
                .thenBy { it.kindOrder() },
        )
    return limit?.let(merged::take) ?: merged
}

private fun MeasurementHistoryItem.kindOrder(): Int = when (this) {
    is MeasurementHistoryItem.Encoder -> 0
    is MeasurementHistoryItem.Jump -> 1
}
