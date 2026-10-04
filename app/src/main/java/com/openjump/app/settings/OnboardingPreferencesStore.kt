package com.openjump.app.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Completion is persisted before Room bootstrap so interrupted first-run setup stays resumable. */
enum class OnboardingCompletion { PENDING, COMPLETED }

/** First-run completion shares the app's settings file and never resets on replay. */
class OnboardingPreferencesStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private val _completion = MutableStateFlow(initializeCompletion(context, preferences))
    val completion: StateFlow<OnboardingCompletion> = _completion.asStateFlow()

    /** Persists the final CTA synchronously before callers leave onboarding. */
    fun completeOnboarding() {
        persistCompletion(OnboardingCompletion.COMPLETED)
    }

    private fun persistCompletion(value: OnboardingCompletion) {
        check(preferences.edit().putString(KEY_COMPLETION, value.name).commit()) {
            "Unable to persist onboarding completion state."
        }
        _completion.value = value
    }

    companion object {
        private const val PREFERENCES = "openjump_settings"
        private const val DATABASE_NAME = "openjump.db"
        private const val KEY_COMPLETION = "onboarding_completion"

        private fun initializeCompletion(
            context: Context,
            preferences: SharedPreferences,
        ): OnboardingCompletion {
            decodeCompletion(preferences.getString(KEY_COMPLETION, null))?.let { return it }

            // Inspect legacy state before writing our own marker into this preferences file.
            val hadPreviousInstallationState =
                context.getDatabasePath(DATABASE_NAME).exists() || preferences.all.isNotEmpty()
            val initial = if (hadPreviousInstallationState) {
                OnboardingCompletion.COMPLETED
            } else {
                OnboardingCompletion.PENDING
            }
            check(preferences.edit().putString(KEY_COMPLETION, initial.name).commit()) {
                "Unable to persist initial onboarding migration state."
            }
            return initial
        }

        private fun decodeCompletion(value: String?): OnboardingCompletion? =
            value?.let { runCatching { OnboardingCompletion.valueOf(it) }.getOrNull() }
    }
}
