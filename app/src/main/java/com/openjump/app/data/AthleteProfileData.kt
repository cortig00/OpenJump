package com.openjump.app.data

/** The only tolerance allowed when comparing canonical Encoder loads. */
const val ENCODER_LOAD_TOLERANCE_KG = 0.000001

fun sameEncoderLoad(leftKg: Double, rightKg: Double): Boolean =
    kotlin.math.abs(leftKg - rightKg) < ENCODER_LOAD_TOLERANCE_KG

/** Greedy, non-overlapping clusters anchored at the smallest load. */
fun encoderRecordClusters(records: List<AthleteEncoderRecord>): List<List<AthleteEncoderRecord>> {
    records.forEach { require(it.loadKg.isFinite() && it.loadKg >= 0.0) { "Encoder loads must be finite and non-negative." } }
    val sorted = records.sortedWith(
        compareBy<AthleteEncoderRecord> { it.exercise }
            .thenBy { it.loadKg }
            .thenBy { it.dateTime }
            .thenBy { it.sessionId },
    )
    val clusters = mutableListOf<MutableList<AthleteEncoderRecord>>()
    sorted.forEach { record ->
        val current = clusters.lastOrNull()
        val anchor = current?.firstOrNull()
        if (anchor != null &&
            anchor.exercise == record.exercise &&
            sameEncoderLoad(anchor.loadKg, record.loadKg)
        ) {
            current!!.add(record)
        } else {
            clusters += mutableListOf(record)
        }
    }
    return clusters
}

/** Representatives used for options; membership does not depend on MCV. */
fun encoderSeriesRepresentatives(records: List<AthleteEncoderRecord>): List<AthleteEncoderRecord> =
    encoderRecordClusters(records).map { cluster -> cluster.first().copy(loadKg = cluster.first().loadKg) }

/** Best session in each load cluster, preserving the historical tie-break. */
fun bestEncoderRecords(records: List<AthleteEncoderRecord>): List<AthleteEncoderRecord> =
    encoderRecordClusters(records).map { cluster ->
        cluster.reduce { best, candidate ->
            when {
                candidate.bestMcv > best.bestMcv -> candidate
                candidate.bestMcv < best.bestMcv -> best
                candidate.dateTime < best.dateTime -> candidate
                candidate.dateTime > best.dateTime -> best
                candidate.sessionId < best.sessionId -> candidate
                else -> best
            }
        }.copy(loadKg = cluster.first().loadKg)
    }

/** Stable exact representation for an already deduplicated in-memory series. */
fun encoderLoadIdentity(loadKg: Double): String = loadKg.toBits().toString(16)

/** Small, query-backed shapes used by the athlete profile. */
data class AthleteJumpSummary(
    val total: Int,
    val lastDateTime: Long?,
)

data class AthleteEncoderSummary(
    val total: Int,
    val lastDateTime: Long?,
)

data class AthleteProtocolCount(
    val protocolId: String,
    val count: Int,
)

data class AthleteJumpRecord(
    val assessmentId: Long,
    val protocolId: String,
    val side: String?,
    val value: Double,
    val unit: String,
    val dateTime: Long,
)

data class AthleteEncoderRecord(
    val sessionId: Long,
    val exercise: String,
    val loadKg: Double,
    val bestMcv: Double,
    val dateTime: Long,
)

/** A complete, query-backed summary of a homogeneous series. */
data class AthleteSeriesStats(
    val count: Int,
    val recent: Double?,
    val best: Double?,
    val average: Double?,
    /** The timestamp/id pair used to choose [recent] deterministically. */
    val recentDateTime: Long? = null,
    val recentId: Long? = null,
)

/** A point in a homogeneous longitudinal series. */
data class AthleteEvolutionPoint(
    val id: Long,
    val dateTime: Long,
    val value: Double,
    val unit: String,
    val kind: String,
)

data class AthleteHistoryPage(
    val id: Long,
    val dateTime: Long,
    val kind: String,
    val athleteId: Long?,
    val athleteName: String?,
    val protocolId: String? = null,
    val side: String? = null,
    val primaryMetricKey: String? = null,
    val primaryMetricValue: Double? = null,
    val primaryMetricUnit: String? = null,
    val exercise: String? = null,
    val loadKg: Double? = null,
    val validRepetitions: Int? = null,
    val bestMcv: Double? = null,
    val hasNotes: Boolean = false,
)

data class AthleteHistoryCursor(
    val dateTime: Long,
    val id: Long,
    /** Stable source tie-breaker for equal timestamps and ids. */
    val kind: String,
)

/** SQL-side filters shared by the global history and comparison data sources. */
data class HistoryQuery(
    val search: String? = null,
    val athleteId: Long? = null,
    val unassignedOnly: Boolean = false,
    val groupId: Long? = null,
    val protocolOrExercise: String? = null,
    val dateFrom: Long? = null,
    val dateTo: Long? = null,
    val metricMin: Double? = null,
    val metricMax: Double? = null,
)
