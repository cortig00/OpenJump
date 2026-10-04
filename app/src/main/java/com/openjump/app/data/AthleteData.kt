package com.openjump.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.openjump.app.protocol.AthleteAnthropometrics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** Shown for new athletes; existing null/unknown keys still retain their initials fallback. */
const val DEFAULT_ATHLETE_AVATAR_KEY = "avatar_frog_jump"

/** Local athlete identity. Archiving keeps all historical measurements intact. */
@Entity(
    tableName = "athletes",
    indices = [
        Index(value = ["archivedAt", "displayName"]),
        Index(value = ["displayName"]),
    ],
)
data class AthleteEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    /** ISO calendar date persisted as java.time.LocalDate.toEpochDay(), never epoch millis/timezone. */
    val birthDate: Long? = null,
    val sex: String? = null,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val archivedAt: Long? = null,
    /** Stable bundled-avatar key. Null or unknown keys render the athlete's initials. */
    val avatarKey: String? = null,
    /** Canonical metric values; the UI converts them to the configured display units. */
    val weightKg: Double? = null,
    val heightCm: Double? = null,
) {
    init {
        require(displayName.isNotBlank()) { "El nombre del atleta es obligatorio." }
        require(weightKg == null || weightKg.isFinite() && weightKg > 0.0) {
            "El peso debe ser un valor positivo y finito."
        }
        require(heightCm == null || heightCm.isFinite() && heightCm > 0.0) {
            "La estatura debe ser un valor positivo y finito."
        }
    }
}

fun AthleteEntity.anthropometricsSnapshot(): AthleteAnthropometrics = AthleteAnthropometrics(
    weightKg = weightKg,
    heightCm = heightCm,
)

fun Iterable<AthleteEntity>.anthropometricsSnapshotFor(athleteId: Long): AthleteAnthropometrics =
    firstOrNull { it.id == athleteId }?.anthropometricsSnapshot() ?: AthleteAnthropometrics()

enum class AthletePermanentDeleteResult {
    DELETED,
    NOT_FOUND,
    LAST_ACTIVE_ATHLETE,
}

@Dao
interface AthleteDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(athlete: AthleteEntity): Long

    @Update
    suspend fun update(athlete: AthleteEntity): Int

    @Query("SELECT * FROM athletes WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): AthleteEntity?

    @Query("SELECT * FROM athletes WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<AthleteEntity?>

    @Query("SELECT * FROM athletes WHERE archivedAt IS NULL ORDER BY displayName COLLATE NOCASE, id")
    fun active(): Flow<List<AthleteEntity>>

    @Query("SELECT * FROM athletes ORDER BY archivedAt IS NOT NULL, displayName COLLATE NOCASE, id")
    fun all(): Flow<List<AthleteEntity>>

    @Query("SELECT * FROM athletes WHERE archivedAt IS NULL ORDER BY displayName COLLATE NOCASE, id")
    suspend fun activeOnce(): List<AthleteEntity>

    @Query("SELECT * FROM athletes ORDER BY archivedAt IS NOT NULL, displayName COLLATE NOCASE, id")
    suspend fun allOnce(): List<AthleteEntity>

    @Query("SELECT * FROM athletes WHERE archivedAt IS NOT NULL ORDER BY displayName COLLATE NOCASE, id LIMIT 1")
    suspend fun firstArchived(): AthleteEntity?

    /** Legacy measurement roots count as existing installation state even when v4 had no athletes. */
    @Query("SELECT (SELECT COUNT(*) FROM athletes) + (SELECT COUNT(*) FROM assessments) + (SELECT COUNT(*) FROM encoder_sessions)")
    suspend fun persistedRootCount(): Int

    @Query("SELECT * FROM athletes WHERE id = :id AND archivedAt IS NULL LIMIT 1")
    suspend fun activeById(id: Long): AthleteEntity?

    @Query("SELECT COUNT(*) FROM athletes WHERE archivedAt IS NULL")
    suspend fun activeCount(): Int

    @Query("SELECT COUNT(*) FROM athletes")
    suspend fun totalCount(): Int

    @Query("UPDATE athletes SET archivedAt = :archivedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setArchived(id: Long, archivedAt: Long?, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("UPDATE athletes SET archivedAt = NULL, updatedAt = :updatedAt WHERE id = :id")
    suspend fun unarchive(id: Long, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("SELECT DISTINCT sessionId FROM testing_participants WHERE athleteId = :athleteId")
    suspend fun testingSessionIdsForAthlete(athleteId: Long): List<Long>

    @Query("DELETE FROM assessments WHERE athleteId = :athleteId")
    suspend fun deleteAssessmentsForAthlete(athleteId: Long)

    @Query("DELETE FROM encoder_sessions WHERE athleteId = :athleteId")
    suspend fun deleteEncoderSessionsForAthlete(athleteId: Long)

    @Query("DELETE FROM testing_participants WHERE athleteId = :athleteId")
    suspend fun deleteTestingParticipantsForAthlete(athleteId: Long)

    @Query(
        """
        UPDATE testing_sessions
        SET currentOrdinal = COALESCE(
            (SELECT MIN(p.ordinal) FROM testing_participants AS p WHERE p.sessionId = testing_sessions.id),
            0
        ), updatedAt = :updatedAt
        WHERE id IN (:sessionIds) AND status = 'ACTIVE'
          AND NOT EXISTS (
            SELECT 1 FROM testing_participants AS current
            WHERE current.sessionId = testing_sessions.id
              AND current.ordinal = testing_sessions.currentOrdinal
          )
        """,
    )
    suspend fun repairTestingSessionCursor(sessionIds: List<Long>, updatedAt: Long)

    @Query(
        """
        DELETE FROM testing_sessions
        WHERE id IN (:sessionIds)
          AND NOT EXISTS (SELECT 1 FROM testing_participants AS p WHERE p.sessionId = testing_sessions.id)
        """,
    )
    suspend fun deleteEmptyTestingSessions(sessionIds: List<Long>)

    @Query("DELETE FROM athletes WHERE id = :id")
    suspend fun deleteAthleteById(id: Long): Int

    /** Permanently removes one athlete and every owned measurement without leaving testing orphans. */
    @Transaction
    suspend fun permanentlyDelete(
        id: Long,
        updatedAt: Long = System.currentTimeMillis(),
    ): AthletePermanentDeleteResult {
        val athlete = byId(id) ?: return AthletePermanentDeleteResult.NOT_FOUND
        if (totalCount() <= 1 || athlete.archivedAt == null && activeCount() <= 1) {
            return AthletePermanentDeleteResult.LAST_ACTIVE_ATHLETE
        }
        val testingSessionIds = testingSessionIdsForAthlete(id)
        deleteAssessmentsForAthlete(id)
        deleteEncoderSessionsForAthlete(id)
        deleteTestingParticipantsForAthlete(id)
        if (testingSessionIds.isNotEmpty()) {
            repairTestingSessionCursor(testingSessionIds, updatedAt)
            deleteEmptyTestingSessions(testingSessionIds)
        }
        return if (deleteAthleteById(id) == 1) {
            AthletePermanentDeleteResult.DELETED
        } else {
            AthletePermanentDeleteResult.NOT_FOUND
        }
    }

    @Transaction
    suspend fun bootstrapIfInstallationEmpty(): AthleteEntity? {
        if (allOnce().isNotEmpty() || persistedRootCount() != 0) return null
        val id = insert(AthleteEntity(displayName = "Atleta 1", avatarKey = DEFAULT_ATHLETE_AVATAR_KEY))
        return byId(id)
    }
}

class AthleteRepository(
    private val dao: AthleteDao,
    private val onSuccessfulDelete: suspend () -> Unit = {},
) {
    fun active(): Flow<List<AthleteEntity>> = dao.active()
    fun all(): Flow<List<AthleteEntity>> = dao.all()
    suspend fun activeOnce(): List<AthleteEntity> = dao.activeOnce()
    suspend fun allOnce(): List<AthleteEntity> = dao.allOnce()
    suspend fun firstArchived(): AthleteEntity? = dao.firstArchived()
    suspend fun persistedRootCount(): Int = dao.persistedRootCount()
    suspend fun byId(id: Long): AthleteEntity? = dao.byId(id)
    fun observeById(id: Long): Flow<AthleteEntity?> = dao.observeById(id)
    suspend fun activeById(id: Long): AthleteEntity? = dao.activeById(id)
    suspend fun create(
        displayName: String,
        birthDate: Long? = null,
        sex: String? = null,
        notes: String? = null,
        weightKg: Double? = null,
        heightCm: Double? = null,
        avatarKey: String? = DEFAULT_ATHLETE_AVATAR_KEY,
        now: Long = System.currentTimeMillis(),
    ): Long = dao.insert(
        AthleteEntity(
            displayName = displayName,
            birthDate = birthDate,
            sex = sex,
            notes = notes,
            createdAt = now,
            updatedAt = now,
            weightKg = weightKg,
            heightCm = heightCm,
            avatarKey = avatarKey,
        ),
    )

    suspend fun update(athlete: AthleteEntity): Boolean =
        dao.update(athlete.copy(updatedAt = System.currentTimeMillis())) == 1

    suspend fun archive(id: Long): Boolean = dao.setArchived(id, System.currentTimeMillis()) == 1
    suspend fun unarchive(id: Long): Boolean = dao.unarchive(id) == 1
    suspend fun permanentlyDelete(id: Long): AthletePermanentDeleteResult {
        val result = dao.permanentlyDelete(id)
        if (result == AthletePermanentDeleteResult.DELETED) {
            try {
                onSuccessfulDelete()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the successful athlete/measurement cascade if optional provider cleanup fails.
            }
        }
        return result
    }

    /** Returns the existing roster unchanged, or creates the sole first-run athlete. */
    suspend fun bootstrapIfInstallationEmpty(): AthleteEntity? = dao.bootstrapIfInstallationEmpty()
}

/** Application-wide selected athlete identity, validated against the active roster on resolve. */
class SelectedAthleteStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val _selectedAthleteId = MutableStateFlow(migratePreferences(prefs))
    val selectedAthleteId: StateFlow<Long?> = _selectedAthleteId.asStateFlow()

    /** Preserves a valid choice; selects a fallback only when the active athlete is unambiguous. */
    suspend fun resolve(repository: AthleteRepository): Long? {
        val request = nextMutation()
        return mutationMutex.withLock {
            if (request != latestMutation.get()) return@withLock selectedAthleteId.value
            val stored = readStoredId()
            _selectedAthleteId.value = stored
            val active = repository.activeOnce()
            val resolved = stored?.takeIf { id -> active.any { it.id == id } }
                ?: active.singleOrNull()?.id
            if (request == latestMutation.get()) persist(resolved)
            resolved.takeIf { request == latestMutation.get() }
        }
    }

    /** Selects only an active athlete. Newer overlapping requests supersede older pending ones. */
    suspend fun select(repository: AthleteRepository, id: Long): Boolean {
        val request = nextMutation()
        return mutationMutex.withLock {
            if (request != latestMutation.get()) return@withLock false
            if (repository.activeById(id) == null || request != latestMutation.get()) return@withLock false
            persist(id)
            true
        }
    }

    /** Associates the first-run bootstrap athlete only when there is no valid prior selection. */
    suspend fun associateBootstrapIfNeeded(repository: AthleteRepository, id: Long): Boolean {
        val request = nextMutation()
        return mutationMutex.withLock {
            if (request != latestMutation.get()) return@withLock false
            val existingId = readStoredId()
            _selectedAthleteId.value = existingId
            if (existingId != null) {
                if (repository.activeById(existingId) != null) return@withLock true
                if (request == latestMutation.get()) persist(null)
                return@withLock false
            }
            if (repository.activeById(id) == null || request != latestMutation.get()) return@withLock false
            persist(id)
            true
        }
    }

    suspend fun clear() {
        val request = nextMutation()
        mutationMutex.withLock {
            if (request == latestMutation.get()) persist(null)
        }
    }

    private fun nextMutation(): Long = latestMutation.incrementAndGet()

    private fun readStoredId(): Long? =
        (prefs.all[SELECTED_ID_KEY] as? Long)?.takeIf { it > 0L }

    private fun persist(id: Long?) {
        prefs.edit().apply {
            if (id == null) remove(SELECTED_ID_KEY) else putLong(SELECTED_ID_KEY, id)
            remove(LEGACY_LAST_USED_KEY)
        }.apply()
        _selectedAthleteId.value = id
    }

    companion object {
        private const val PREFERENCES = "openjump_settings"
        private const val SELECTED_ID_KEY = "selected_athlete_id"
        private const val LEGACY_LAST_USED_KEY = "last_used_athlete_id"
        private val migrationLock = Any()
        private val mutationMutex = Mutex()
        private val latestMutation = AtomicLong()

        /** The last-used key was written by the current implementation and takes precedence. */
        private fun migratePreferences(prefs: android.content.SharedPreferences): Long? =
            synchronized(migrationLock) {
                val current = (prefs.all[LEGACY_LAST_USED_KEY] as? Long)?.takeIf { it > 0L }
                val selected = (prefs.all[SELECTED_ID_KEY] as? Long)?.takeIf { it > 0L }
                val canonical = current ?: selected
                if (prefs.contains(LEGACY_LAST_USED_KEY) || canonical == null && prefs.contains(SELECTED_ID_KEY)) {
                    prefs.edit().apply {
                        if (canonical == null) remove(SELECTED_ID_KEY) else putLong(SELECTED_ID_KEY, canonical)
                        remove(LEGACY_LAST_USED_KEY)
                    }.commit()
                }
                canonical
            }
    }
}
