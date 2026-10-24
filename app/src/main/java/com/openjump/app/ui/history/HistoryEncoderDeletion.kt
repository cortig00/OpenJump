package com.openjump.app.ui.history

/**
 * F035: row-level cleanup for empty Encoder sessions in HISTORY.
 *
 * An empty row is a persisted [MeasurementHistoryItem.Encoder] whose projected
 * fields show zero valid repetitions and no best MCV. `totalRepetitions` is not
 * projected into history rows, so the predicate uses only the real projected
 * fields ([RecentEncoderSession.validRepetitions] and
 * [RecentEncoderSession.bestMcv]) without schema or DAO changes.
 *
 * Rows stay listed until an explicit confirmed deletion; cancel has no effect.
 */
fun isDeletableEmptyEncoderRow(item: MeasurementHistoryItem): Boolean {
    if (item !is MeasurementHistoryItem.Encoder) return false
    return item.session.validRepetitions == 0 && item.session.bestMcv == null
}
