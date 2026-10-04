package com.openjump.app.ui

import androidx.compose.runtime.saveable.Saver
import com.openjump.app.settings.UnitProfile
import java.util.Locale

/**
 * Text and the unit profile that gives the text meaning. This prevents a
 * recomposition caused by a preference change from silently reinterpreting an
 * unfinished value.
 */
data class UnitAwareNumericInputState(
    val text: String,
    val profile: UnitProfile,
    val requiresReview: Boolean = false,
    /** Exact canonical anchor retained when a valid value is rebased between units. */
    val canonicalAnchor: Double? = null,
) {
    fun saveSnapshot(): List<Any?> = listOf(
        text,
        profile.shortLength.id,
        profile.horizontalDistance.id,
        profile.encoderDisplacement.id,
        profile.mass.id,
        profile.speed.id,
        profile.jumpTiming.id,
        requiresReview,
        canonicalAnchor,
    )

    companion object {
        fun initial(text: String, profile: UnitProfile, canonicalAnchor: Double? = null) =
            UnitAwareNumericInputState(text, profile, canonicalAnchor = canonicalAnchor)

        fun restoreSnapshot(values: List<Any?>): UnitAwareNumericInputState? {
            if (values.size !in 8..9) return null
            val profile = UnitProfile(
                shortLength = com.openjump.app.settings.ShortLengthUnit.decode(values[1] as? String) ?: return null,
                horizontalDistance = com.openjump.app.settings.HorizontalDistanceUnit.decode(values[2] as? String) ?: return null,
                encoderDisplacement = com.openjump.app.settings.EncoderDisplacementUnit.decode(values[3] as? String) ?: return null,
                mass = com.openjump.app.settings.MassUnit.decode(values[4] as? String) ?: return null,
                speed = com.openjump.app.settings.SpeedUnit.decode(values[5] as? String) ?: return null,
                jumpTiming = com.openjump.app.settings.JumpTimingUnit.decode(values[6] as? String) ?: return null,
            )
            return UnitAwareNumericInputState(
                text = values[0] as? String ?: return null,
                profile = profile,
                requiresReview = values[7] as? Boolean ?: false,
                canonicalAnchor = (values.getOrNull(8) as? Number)?.toDouble(),
            )
        }

        /** An explicit edit always adopts the currently visible profile. */
        fun edited(text: String, profile: UnitProfile, quantity: MeasurementQuantity, locale: Locale): UnitAwareNumericInputState =
            UnitAwareNumericInputState(
                text = text,
                profile = profile,
                // An explicit edit is current-profile input. Ordinary parse/domain
                // validation is intentionally kept separate from the unit-change latch.
                requiresReview = false,
                canonicalAnchor = null,
            )

        /** Rebase valid text through canonical units; retain and latch bad text. */
        fun rebase(
            state: UnitAwareNumericInputState,
            newProfile: UnitProfile,
            quantity: MeasurementQuantity,
            locale: Locale,
        ): UnitAwareNumericInputState {
            if (state.profile == newProfile) return state
            if (state.text.isBlank()) return state.copy(profile = newProfile, requiresReview = false, canonicalAnchor = null)
            val canonical = if (!state.requiresReview) {
                state.canonicalAnchor ?: MeasurementFormatting.parseCanonical(state.text, quantity, state.profile, locale)
            } else null
            return if (canonical != null) {
                state.copy(
                    text = MeasurementFormatting.formatInputValue(canonical, quantity, newProfile, locale),
                    profile = newProfile,
                    requiresReview = false,
                    canonicalAnchor = canonical,
                )
            } else {
                state.copy(profile = newProfile, requiresReview = true, canonicalAnchor = null)
            }
        }

        fun canonical(
            state: UnitAwareNumericInputState,
            quantity: MeasurementQuantity,
            locale: Locale,
            currentProfile: UnitProfile = state.profile,
        ): Double? {
            // Recomposition observes the new profile before LaunchedEffect has rebased
            // the saved text. Do not parse text with the old basis during that window.
            if (state.profile != currentProfile || state.requiresReview) return null
            return state.canonicalAnchor ?: MeasurementFormatting.parseCanonical(state.text, quantity, state.profile, locale)
        }

        /** True when current-profile text is non-empty but not parseable. */
        fun hasInvalidCurrentValue(
            state: UnitAwareNumericInputState,
            quantity: MeasurementQuantity,
            locale: Locale,
        ): Boolean = state.text.isNotBlank() && !state.requiresReview &&
            MeasurementFormatting.parseCanonical(state.text, quantity, state.profile, locale) == null

        /** Stable encoded representation suitable for rememberSaveable. */
        val Saver: Saver<UnitAwareNumericInputState, List<Any?>> = Saver(
            save = { state -> state.saveSnapshot() },
            restore = { values -> restoreSnapshot(values) },
        )
    }
}
