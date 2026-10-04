package com.openjump.app.data

import com.openjump.app.protocol.MeasurementSide
import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.MetricUnit
import com.openjump.app.protocol.ProtocolId

/** Only the columns needed to compare persisted results. Video and attempt graphs stay unloaded. */
data class PersonalRecordCandidate(
    val assessmentId: Long,
    val attemptOrdinal: Int,
    val dateTime: Long,
    val protocolId: String,
    val side: String?,
    val dropHeightCm: Double?,
    val metricKey: String,
    val value: Double,
    val unit: String,
    val fromBilateral: Boolean,
)

data class PersonalRecordSeries(
    val protocolId: ProtocolId,
    val metricKey: MetricKey,
    val unit: MetricUnit,
    val side: MeasurementSide? = null,
    /** Exact canonical centimetres, never a rounded display value. */
    val dropHeightCm: Double? = null,
)

data class PersonalRecord(
    val series: PersonalRecordSeries,
    val value: Double,
    val dateTime: Long,
    val assessmentId: Long,
    val attemptOrdinal: Int,
    val fromBilateral: Boolean,
    /** Best value strictly before this record in chronological (date, id, attempt) order. */
    val previousBest: Double?,
)

/** A PR is the primary, positive, finite metric of a known protocol and condition. */
fun personalRecordSeries(candidate: PersonalRecordCandidate): PersonalRecordSeries? {
    val protocol = ProtocolId.fromStorageKey(candidate.protocolId) ?: return null
    val expected = when (protocol) {
        ProtocolId.CMJ, ProtocolId.SJ, ProtocolId.ABALAKOV, ProtocolId.UNILATERAL ->
            MetricKey.HEIGHT_CM to MetricUnit.CENTIMETER
        ProtocolId.DROP_JUMP -> MetricKey.RSI to MetricUnit.METER_PER_SECOND
        ProtocolId.HORIZONTAL -> MetricKey.DISTANCE_M to MetricUnit.METER
        ProtocolId.ASYMMETRY, ProtocolId.REPEATED_10_5 -> return null
    }
    if (candidate.metricKey != expected.first.storageKey || candidate.unit != expected.second.storageKey ||
        !candidate.value.isFinite() || candidate.value <= 0.0
    ) return null
    if (candidate.fromBilateral && protocol != ProtocolId.UNILATERAL) return null
    if (!candidate.fromBilateral && candidate.attemptOrdinal != 0) return null
    val side = MeasurementSide.fromStorageKey(candidate.side)
    if (protocol == ProtocolId.UNILATERAL && side == null) return null
    if (protocol != ProtocolId.UNILATERAL && candidate.side != null) return null
    val dropHeight = if (protocol == ProtocolId.DROP_JUMP) {
        candidate.dropHeightCm?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    } else null
    return PersonalRecordSeries(protocol, expected.first, expected.second, side, dropHeight)
}

/** Earliest occurrence owns an equal best; bilateral children compete with ordinary unilateral jumps. */
fun calculatePersonalRecords(candidates: List<PersonalRecordCandidate>): List<PersonalRecord> {
    val winners = linkedMapOf<PersonalRecordSeries, PersonalRecord>()
    candidates.sortedWith(compareBy<PersonalRecordCandidate> { it.dateTime }
        .thenBy { it.assessmentId }.thenBy { it.attemptOrdinal }).forEach { candidate ->
        val series = personalRecordSeries(candidate) ?: return@forEach
        val previous = winners[series]
        if (previous == null || candidate.value > previous.value) {
            winners[series] = PersonalRecord(
                series, candidate.value, candidate.dateTime, candidate.assessmentId,
                candidate.attemptOrdinal, candidate.fromBilateral, previous?.value,
            )
        }
    }
    return winners.values.sortedWith(compareBy<PersonalRecord> { it.series.protocolId.ordinal }
        .thenBy { it.series.side?.ordinal ?: -1 }
        .thenBy { it.series.dropHeightCm ?: 0.0 })
}
