package com.openjump.app.data

/** A point refers to its root and (for bilateral comparisons) the exact child attempt. */
data class ProgressPoint(
    val assessmentId: Long,
    val attemptOrdinal: Int,
    val dateTime: Long,
    val value: Double,
    val fromBilateral: Boolean,
)

/** PR spans all eligible history; only the latest [points] are plotted. */
data class ProgressSeries(
    val condition: PersonalRecordSeries,
    val points: List<ProgressPoint>,
    val totalCount: Int,
    val personalBest: PersonalRecord,
)

const val PROGRESS_POINT_LIMIT = 50

/** Normalize elapsed time to [0, 1] without overflowing Long subtraction. */
fun progressXPositions(times: List<Long>): List<Float> {
    if (times.isEmpty()) return emptyList()
    val first = times.first().toDouble()
    val span = times.last().toDouble() - first
    return if (span == 0.0) List(times.size) { 0.5f }
    else times.map { ((it.toDouble() - first) / span).toFloat().coerceIn(0f, 1f) }
}

/** The same eligibility, grouping and chronological tie-break as Personal Records. */
fun buildProgressSeries(
    candidates: List<PersonalRecordCandidate>,
    pointLimit: Int = PROGRESS_POINT_LIMIT,
): List<ProgressSeries> {
    require(pointLimit > 0)
    val grouped = candidates.mapNotNull { candidate ->
        personalRecordSeries(candidate)?.let { it to candidate }
    }.groupBy({ it.first }, { it.second })
    return calculatePersonalRecords(candidates).mapNotNull { best ->
        val chronological = grouped[best.series]?.sortedWith(
            compareBy<PersonalRecordCandidate> { it.dateTime }
                .thenBy { it.assessmentId }.thenBy { it.attemptOrdinal },
        ) ?: return@mapNotNull null
        ProgressSeries(
            condition = best.series,
            points = chronological.takeLast(pointLimit).map {
                ProgressPoint(it.assessmentId, it.attemptOrdinal, it.dateTime, it.value, it.fromBilateral)
            },
            totalCount = chronological.size,
            personalBest = best,
        )
    }
}
