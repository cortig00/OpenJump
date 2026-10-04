package com.openjump.app.ui.encoder

import com.openjump.app.encoder.EncoderRepetition
import com.openjump.app.encoder.EncoderSample

/** A UI-owned seek intention. It is deliberately not a rendered/confirmed playhead. */
internal data class EncoderResultVideoSeekIntent(
    val repetitionOrdinal: Int,
    val sourcePtsUs: Long,
)

/** Holds only the latest UI intention until the actual video player is available. */
internal data class EncoderResultVideoIntentState(
    val pending: EncoderResultVideoSeekIntent? = null,
) {
    fun explorePoint(repetitionOrdinal: Int, sourcePtsUs: Long) = copy(
        pending = EncoderResultVideoSeekIntent(repetitionOrdinal, sourcePtsUs),
    )

    fun selectRepetition(repetitionOrdinal: Int) = copy(
        pending = pending?.takeIf { it.repetitionOrdinal == repetitionOrdinal },
    )

    fun clearForRemoval() = copy(pending = null)

    /** Normal entry targets this repetition's first sample; a same-repetition chart point wins. */
    fun enterVideo(repetition: EncoderRepetition?, samples: List<EncoderSample>): EncoderResultVideoIntentState {
        if (repetition == null) return copy(pending = null)
        val existing = pending?.takeIf { it.repetitionOrdinal == repetition.ordinal }
        val startPts = samples.getOrNull(repetition.startSample)?.sourcePtsUs
        return copy(pending = existing ?: startPts?.let {
            EncoderResultVideoSeekIntent(repetition.ordinal, it)
        })
    }

    /** Rail selection explicitly returns to the canonical beginning of that repetition. */
    fun selectVideoRail(
        repetition: EncoderRepetition?,
        samples: List<EncoderSample>,
    ): EncoderResultVideoIntentState = enterVideo(repetition, samples).let { state ->
        if (repetition == null) state else state.copy(
            pending = samples.getOrNull(repetition.startSample)?.sourcePtsUs?.let {
                EncoderResultVideoSeekIntent(repetition.ordinal, it)
            },
        )
    }

    /** Consume once, and only after the player is bound and any prior request is acknowledged. */
    fun dispatchWhenReady(
        selectedOrdinal: Int?,
        playerAvailable: Boolean,
        requestOutstanding: Boolean,
    ): Pair<EncoderResultVideoIntentState, EncoderResultVideoSeekIntent?> {
        val intent = pending?.takeIf { it.repetitionOrdinal == selectedOrdinal }
        if (intent == null) return copy(pending = null) to null
        if (!playerAvailable || requestOutstanding) return this to null
        return copy(pending = null) to intent
    }
}
