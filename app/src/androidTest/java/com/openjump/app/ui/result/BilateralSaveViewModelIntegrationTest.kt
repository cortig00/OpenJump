package com.openjump.app.ui.result

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.JumpDatabase
import com.openjump.app.data.JumpRepository
import com.openjump.app.protocol.BilateralAttemptSnapshot
import com.openjump.app.protocol.BilateralComparison
import com.openjump.app.protocol.MeasurementDraft
import com.openjump.app.protocol.MeasurementSide
import com.openjump.app.protocol.MetricKey
import com.openjump.app.protocol.MetricUnit
import com.openjump.app.protocol.MetricValue
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.ProtocolResult
import com.openjump.app.protocol.ProtocolSetup
import com.openjump.app.video.PersistedVideoGrantStore
import com.openjump.app.video.VideoUriGrantReconciler
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F-004 real persistence proof (compile-only on this KVM-blocked station):
 * real `JumpDatabase` (in-memory) + real `JumpRepository` + no-op grant store
 * drive the production [BilateralResultViewModel] id overload. Missing athlete
 * FK fails without a callback; inserting the athlete and retrying proves the
 * real Room id; a rapid double-tap invokes the callback once.
 * `tools/validate.sh android-test-compile` covers it here; device run is KVM-blocked.
 */
@RunWith(AndroidJUnit4::class)
class BilateralSaveViewModelIntegrationTest {

    @get:Rule val compose = createComposeRule()

    private lateinit var database: JumpDatabase
    private lateinit var repository: JumpRepository
    private lateinit var reconciler: VideoUriGrantReconciler

    private val noOpGrants = object : PersistedVideoGrantStore {
        override fun persistedReadUris(): Set<String> = emptySet()
        override fun takeReadGrant(uri: String) = Unit
        override fun releaseReadGrant(uri: String) = Unit
    }

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            JumpDatabase::class.java,
        ).build()
        repository = JumpRepository(database.jumpDao())
        reconciler = VideoUriGrantReconciler(
            grants = noOpGrants,
            savedReferences = { emptySet() },
            liveReferences = { emptySet() },
        )
    }

    @After
    fun closeDatabase() = database.close()

    private fun comparison(sessionKey: String, athleteId: Long): BilateralComparison {
        fun snapshot(side: MeasurementSide, ordinal: Int): BilateralAttemptSnapshot {
            val draft = MeasurementDraft(
                sessionKey = "$sessionKey-${side.name}-$ordinal",
                protocolId = ProtocolId.UNILATERAL,
                athleteId = athleteId,
                setup = ProtocolSetup(side = side),
            )
            val result = ProtocolResult(
                ProtocolId.UNILATERAL,
                MetricValue(MetricKey.HEIGHT_CM, 30.0 + ordinal, MetricUnit.CENTIMETER),
                emptyList(),
            )
            return BilateralAttemptSnapshot(side, ordinal, draft, result, detectedFps = 120)
        }
        return BilateralComparison(
            sessionKey = sessionKey,
            athleteId = athleteId,
            targetAttempts = 1,
            attempts = listOf(snapshot(MeasurementSide.LEFT, 1), snapshot(MeasurementSide.RIGHT, 1)),
        )
    }

    @Test
    fun missingAthleteFailsThenInsertAndRetryProvesRealId() {
        val viewModel = BilateralResultViewModel(repository, reconciler) { }
        // No athlete inserted: FK/active check must fail, never calling back.
        var missingCallback: Long? = null
        compose.runOnUiThread {
            viewModel.save(comparison("missing-${UUID.randomUUID()}", 9999L)) { id, _ ->
                missingCallback = id
            }
        }
        compose.waitUntil(5_000) { viewModel.saveState.value is BilateralSaveState.Error }
        assertNull(missingCallback)
        assertTrue(viewModel.saveState.value is BilateralSaveState.Error)
        viewModel.clearError()

        // Insert the athlete and retry with its real id: proves the real Room id.
        val athleteId = runBlocking {
            database.athleteDao().insert(
                AthleteEntity(displayName = "Bilateral VM", createdAt = 1L, updatedAt = 1L),
            )
        }
        val sessionKey = "retry-${UUID.randomUUID()}"
        var savedId: Long? = null
        compose.runOnUiThread {
            viewModel.save(comparison(sessionKey, athleteId)) { id, _ ->
                savedId = id
            }
        }
        compose.waitUntil(5_000) { viewModel.saveState.value is BilateralSaveState.Saved }
        val id = requireNotNull(savedId) { "VM must forward the real Room id" }
        assertTrue(id > 0L)
        assertEquals(id, runBlocking { database.jumpDao().assessmentIdForSession(sessionKey) })
        val stored = runBlocking { repository.measurementById(id) }
        assertEquals(ProtocolId.ASYMMETRY, requireNotNull(stored).protocolId)
    }

    @Test
    fun rapidDoubleTapInvokesFinalSaveOnce() {
        val athleteId = runBlocking {
            database.athleteDao().insert(
                AthleteEntity(displayName = "Bilateral double tap", createdAt = 1L, updatedAt = 1L),
            )
        }
        val viewModel = BilateralResultViewModel(repository, reconciler) { }
        val sessionKey = "double-${UUID.randomUUID()}"
        val callbacks = mutableListOf<Long>()
        compose.runOnUiThread {
            viewModel.save(comparison(sessionKey, athleteId)) { id, _ -> callbacks += id }
            // Rapid second tap while Saving: synchronous guard drops it.
            viewModel.save(comparison(sessionKey, athleteId)) { id, _ -> callbacks += id }
        }
        compose.waitUntil(5_000) { viewModel.saveState.value is BilateralSaveState.Saved }
        compose.waitForIdle()
        assertEquals(listOf(runBlocking { database.jumpDao().assessmentIdForSession(sessionKey) }), callbacks)
        assertEquals(1, callbacks.size)
    }
}
