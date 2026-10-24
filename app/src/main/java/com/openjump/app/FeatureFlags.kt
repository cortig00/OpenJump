package com.openjump.app

/**
 * Switches that decide whether an already implemented feature is offered in this build.
 *
 * They are compile-time constants generated per variant; there is no preference, deep link or
 * remote value that can turn a production-only feature on accidentally.
 */
internal object FeatureFlags {
    /** F-V-P profile: implemented and retained, but not offered in production yet. */
    val fvpEnabled: Boolean = BuildConfig.FVP_ENABLED
}
