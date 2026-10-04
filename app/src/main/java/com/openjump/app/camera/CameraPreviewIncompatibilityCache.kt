package com.openjump.app.camera

/**
 * Process-session memory of profiles whose bound preview did not reach STREAMING in time.
 *
 * This intentionally has no Android or persistence dependency: process death clears the cache,
 * and profile equality keeps a rejected camera/quality/size/FPS/session topology isolated.
 */
object CameraPreviewIncompatibilityCache {
    private val rejectedProfiles = mutableSetOf<CameraProductProfile>()

    @Synchronized
    fun reject(profile: CameraProductProfile): Boolean = rejectedProfiles.add(profile)

    @Synchronized
    fun isRejected(profile: CameraProductProfile): Boolean = profile in rejectedProfiles

    @Synchronized
    fun filter(profiles: Collection<CameraProductProfile>): List<CameraProductProfile> =
        profiles.filterNot { it in rejectedProfiles }

    /** FPS is unavailable only after every qualified profile at that FPS was rejected. */
    @Synchronized
    fun unavailableFps(qualifiedProfiles: Collection<CameraProductProfile>): Set<Int> =
        qualifiedProfiles
            .groupBy { it.fps }
            .filterValues { profiles -> profiles.isNotEmpty() && profiles.all { it in rejectedProfiles } }
            .keys

    /** Test-only reset; there is deliberately no production reset across screen lifecycles. */
    @Synchronized
    fun clearForTests() {
        rejectedProfiles.clear()
    }
}
