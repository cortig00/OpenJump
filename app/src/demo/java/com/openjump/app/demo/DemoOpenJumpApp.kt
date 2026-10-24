package com.openjump.app.demo

import androidx.room.withTransaction
import com.openjump.app.OpenJumpApp
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.FvpRepository
import com.openjump.app.data.JumpDatabase
import com.openjump.app.data.JumpRepository
import com.openjump.app.math.ProtocolCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Included only by assembleDemo. The separate applicationId owns a separate data sandbox. */
class DemoOpenJumpApp : OpenJumpApp() {
    override fun onCreate() {
        super.onCreate()
        check(packageName == "com.openjump.app.fvpdemo") { "Demo bootstrap must never seed OpenJump." }
        val preferences = getSharedPreferences("fvp_demo_bootstrap", MODE_PRIVATE)
        if (preferences.getBoolean("seeded_v1", false)) return

        // Finish the small, transactional fixture before any screen/athlete bootstrap can race it.
        // All DB work runs on IO; an interruption rolls back rather than exposing half a dataset.
        val athleteId = runBlocking(Dispatchers.IO) {
            seedIfEmpty(database, System.currentTimeMillis())
        }
        if (athleteId != null) {
            runBlocking(Dispatchers.IO) { selectedAthleteStore.select(athleteRepository, athleteId) }
            onboardingPreferencesStore.completeOnboarding()
        }
        check(preferences.edit().putBoolean("seeded_v1", true).commit())
    }
}

/** No deletes/updates: existing demo data is never overwritten or reset on subsequent launches. */
internal suspend fun seedIfEmpty(database: JumpDatabase, now: Long): Long? = database.withTransaction {
    // Recover safely if the previous process committed the seed but died before the preferences marker.
    database.fvpDao().bySessionKey("demo-v1-fvp-sj-a")?.athleteId?.let { return@withTransaction it }
    if (database.athleteDao().persistedRootCount() != 0) return@withTransaction null

    val athleteId = database.athleteDao().insert(
        AthleteEntity(
            displayName = DemoSamples.ATHLETE_NAME,
            weightKg = DemoSamples.BODY_MASS_KG,
            notes = DemoSamples.NOTICE,
            createdAt = now,
            updatedAt = now,
        ),
    )
    val fvpRepository = FvpRepository(database.fvpDao())
    DemoSamples.profiles(athleteId, now).forEach { fvpRepository.save(it) }
    val jumpRepository = JumpRepository(database.jumpDao())
    DemoSamples.jumps(athleteId, now).forEach { (draft, dateTime) ->
        // Unknown fps is intentional: synthetic events are NOT evidence of captured video cadence.
        jumpRepository.save(draft, ProtocolCalculator.compute(draft), detectedFps = 0, dateTime = dateTime)
    }
    athleteId
}
