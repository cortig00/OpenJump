package com.openjump.app.ui.encoder

import com.openjump.app.encoder.EncoderAnalysis
import com.openjump.app.encoder.EncoderRepetition
import com.openjump.app.encoder.QualityReason
import com.openjump.app.encoder.RepetitionQuality

/** Real analysis version for the collapsed technical disclosure; no fallback is invented. */
fun technicalAnalysisVersion(analysis: EncoderAnalysis): Int = analysis.config.version

/**
 * F010/F037 presentation aggregate (pure, no Android).
 *
 * Counts measured [QualityReason] once per non-valid repetition (UNCERTAIN or
 * INVALID), once per distinct reason in that repetition's set. A repetition
 * carrying several reasons contributes to each of them, so sibling counts can
 * overlap and their sum can exceed the non-valid repetition count. No causal
 * language is invented here; labels come from QualityReason.titleResource().
 */
fun aggregateNonValidReasonCounts(
    repetitions: List<EncoderRepetition>,
): Map<QualityReason, Int> {
    val counts = mutableMapOf<QualityReason, Int>()
    repetitions
        .filter { it.quality != RepetitionQuality.VALID }
        .forEach { repetition ->
            repetition.reasons.forEach { reason ->
                counts[reason] = (counts[reason] ?: 0) + 1
            }
        }
    return counts
}
