package com.openjump.app.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import com.openjump.app.encoder.EncoderExercise
import com.openjump.app.encoder.EncoderSetup
import com.openjump.app.protocol.MeasurementSide
import com.openjump.app.protocol.ProtocolCatalog
import com.openjump.app.protocol.ProtocolId
import com.openjump.app.protocol.ProtocolSetup
import kotlinx.coroutines.flow.Flow

/** The two measurement pipelines that a collective test can schedule. */
enum class TestingFamily { JUMP, ENCODER }
enum class TestingStatus { ACTIVE, COMPLETED, CANCELLED }

@Entity(
    tableName = "testing_sessions",
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("groupId"), Index(value = ["status", "updatedAt"]), Index("createdAt")],
)
data class TestingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long? = null,
    val groupNameSnapshot: String? = null,
    val family: String,
    val protocolId: String? = null,
    val exercise: String? = null,
    val side: String? = null,
    val dropHeightCm: Double? = null,
    val loadKg: Double? = null,
    val plateDiameterCm: Double? = null,
    val targetAttempts: Int,
    val status: String = TestingStatus.ACTIVE.name,
    val currentOrdinal: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val completedAt: Long? = null,
) {
    init {
        require(targetAttempts >= 1) { "El número de intentos debe ser al menos uno." }
        require(family == TestingFamily.JUMP.name || family == TestingFamily.ENCODER.name)
    }
}

@Entity(
    tableName = "testing_participants",
    primaryKeys = ["sessionId", "athleteId"],
    foreignKeys = [
        ForeignKey(
            entity = TestingSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AthleteEntity::class,
            parentColumns = ["id"],
            childColumns = ["athleteId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["sessionId", "ordinal"], unique = true), Index("athleteId")],
)
data class TestingParticipantEntity(
    val sessionId: Long,
    val athleteId: Long,
    val ordinal: Int,
    val skippedAt: Long? = null,
)

data class TestingSessionWithParticipants(
    @Embedded val session: TestingSessionEntity,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val participants: List<TestingParticipantEntity>,
)

data class TestingParticipantProgressRow(
    val sessionId: Long,
    val athleteId: Long,
    val ordinal: Int,
    val skippedAt: Long?,
    val athleteName: String,
    val completedAttempts: Int,
    val targetAttempts: Int,
)

data class TestingParticipantProgress(
    val participant: TestingParticipantEntity,
    val athleteName: String,
    val completedAttempts: Int,
    val targetAttempts: Int,
) {
    val isComplete: Boolean get() = completedAttempts >= targetAttempts
    val isOmitted: Boolean get() = participant.skippedAt != null
}

@Dao
interface TestingDao {
    @Insert
    suspend fun insertSession(session: TestingSessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertParticipants(participants: List<TestingParticipantEntity>)

    @Transaction
    suspend fun createSnapshot(session: TestingSessionEntity, participants: List<TestingParticipantEntity>): Long {
        require(participants.map { it.ordinal }.distinct().size == participants.size) {
            "El roster de testing debe tener ordinales únicos."
        }
        val id = insertSession(session)
        if (participants.isNotEmpty()) {
            insertParticipants(participants.map { it.copy(sessionId = id) })
        }
        return id
    }

    @Query("SELECT * FROM testing_sessions WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): TestingSessionEntity?

    @Transaction
    @Query("SELECT * FROM testing_sessions WHERE id = :id LIMIT 1")
    suspend fun detail(id: Long): TestingSessionWithParticipants?

    @Transaction
    @Query("SELECT * FROM testing_sessions WHERE id = :id LIMIT 1")
    fun observeDetail(id: Long): Flow<TestingSessionWithParticipants?>

    @Query("SELECT * FROM testing_sessions WHERE status = 'ACTIVE' ORDER BY updatedAt DESC, id DESC LIMIT 20")
    fun active(): Flow<List<TestingSessionEntity>>

    @Query("SELECT * FROM testing_sessions ORDER BY updatedAt DESC, id DESC LIMIT 20")
    fun recent(): Flow<List<TestingSessionEntity>>

    @Query("SELECT * FROM testing_participants WHERE sessionId = :sessionId ORDER BY ordinal, athleteId")
    suspend fun participants(sessionId: Long): List<TestingParticipantEntity>

    @Query("SELECT * FROM testing_participants WHERE sessionId = :sessionId AND ordinal = :ordinal LIMIT 1")
    suspend fun participantAt(sessionId: Long, ordinal: Int): TestingParticipantEntity?

    @Query(
        """
        SELECT p.sessionId, p.athleteId, p.ordinal, p.skippedAt,
               a.displayName AS athleteName, COUNT(m.id) AS completedAttempts,
               s.targetAttempts
        FROM testing_participants p
        JOIN testing_sessions s ON s.id = p.sessionId AND s.family = 'JUMP'
        JOIN athletes a ON a.id = p.athleteId
        LEFT JOIN assessments m ON m.testingSessionId = p.sessionId AND m.athleteId = p.athleteId
        WHERE p.sessionId = :sessionId
        GROUP BY p.sessionId, p.athleteId, p.ordinal, p.skippedAt, a.displayName, s.targetAttempts
        UNION ALL
        SELECT p.sessionId, p.athleteId, p.ordinal, p.skippedAt,
               a.displayName AS athleteName, COUNT(m.id) AS completedAttempts,
               s.targetAttempts
        FROM testing_participants p
        JOIN testing_sessions s ON s.id = p.sessionId AND s.family = 'ENCODER'
        JOIN athletes a ON a.id = p.athleteId
        LEFT JOIN encoder_sessions m ON m.testingSessionId = p.sessionId AND m.athleteId = p.athleteId
        WHERE p.sessionId = :sessionId
        GROUP BY p.sessionId, p.athleteId, p.ordinal, p.skippedAt, a.displayName, s.targetAttempts
        ORDER BY 3, 2
        """,
    )
    suspend fun progressRows(sessionId: Long): List<TestingParticipantProgressRow>

    @Query("SELECT COUNT(*) FROM testing_sessions AS s INNER JOIN testing_participants AS p ON p.sessionId = s.id WHERE s.id = :testingSessionId AND s.family = :family AND s.status = 'ACTIVE' AND p.athleteId = :athleteId AND p.skippedAt IS NULL")
    suspend fun activeParticipant(testingSessionId: Long, athleteId: Long, family: String): Int

    @Query("UPDATE testing_sessions SET currentOrdinal = :ordinal, updatedAt = :updatedAt WHERE id = :sessionId AND status = 'ACTIVE'")
    suspend fun setCurrentOrdinal(sessionId: Long, ordinal: Int, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("UPDATE testing_participants SET skippedAt = :skippedAt WHERE sessionId = :sessionId AND athleteId = :athleteId AND EXISTS (SELECT 1 FROM testing_sessions WHERE id = :sessionId AND status = 'ACTIVE')")
    suspend fun setSkipped(sessionId: Long, athleteId: Long, skippedAt: Long?): Int

    @Query("UPDATE testing_sessions SET status = :status, completedAt = :completedAt, updatedAt = :updatedAt WHERE id = :sessionId AND status = 'ACTIVE'")
    suspend fun setStatus(sessionId: Long, status: String, completedAt: Long? = null, updatedAt: Long = System.currentTimeMillis()): Int
}

class TestingRepository(
    private val dao: TestingDao,
    private val groups: GroupDao,
) {
    fun active(): Flow<List<TestingSessionEntity>> = dao.active()
    fun recent(): Flow<List<TestingSessionEntity>> = dao.recent()
    suspend fun byId(id: Long): TestingSessionEntity? = dao.byId(id)
    suspend fun detail(id: Long): TestingSessionWithParticipants? = dao.detail(id)
    fun observeDetail(id: Long): Flow<TestingSessionWithParticipants?> = dao.observeDetail(id)

    /** Captures active members and the group name in one immutable testing header. */
    suspend fun create(
        groupId: Long?,
        family: TestingFamily,
        protocolId: String? = null,
        exercise: String? = null,
        side: String? = null,
        dropHeightCm: Double? = null,
        loadKg: Double? = null,
        plateDiameterCm: Double? = null,
        targetAttempts: Int,
        now: Long = System.currentTimeMillis(),
    ): Long {
        require(targetAttempts >= 1) { "El número de intentos debe ser al menos uno." }
        val snapshot = requireNotNull(groupId) { "Selecciona un grupo activo." }.let { groups.activeSnapshot(it) }
        val (group, members) = requireNotNull(snapshot) { "El grupo no existe o está archivado." }
        require(members.isNotEmpty()) { "El grupo no tiene atletas activos." }
        when (family) {
            TestingFamily.JUMP -> {
                val definition = requireNotNull(ProtocolCatalog.find(protocolId)) { "El protocolo no es válido." }
                require(definition.id != com.openjump.app.protocol.ProtocolId.ASYMMETRY) { "ASYMMETRY no está disponible en Testing." }
                require(definition.availability.name == "AVAILABLE") { "El protocolo todavía no está disponible." }
                val setup = ProtocolSetup(MeasurementSide.fromStorageKey(side), dropHeightCm)
                require(ProtocolCatalog.validateSetup(definition, setup) == null) { "La configuración del protocolo no es válida." }
            }
            TestingFamily.ENCODER -> {
                val selectedExercise = runCatching { EncoderExercise.valueOf(requireNotNull(exercise)) }
                    .getOrElse { error("El ejercicio encoder no es válido.") }
                EncoderSetup(selectedExercise, requireNotNull(loadKg))
                requireNotNull(plateDiameterCm).also { require(it.isFinite() && it in 10.0..60.0) }
            }
        }
        return dao.createSnapshot(
            TestingSessionEntity(
                groupId = groupId,
                groupNameSnapshot = group.name,
                family = family.name,
                protocolId = protocolId,
                exercise = exercise,
                side = side,
                dropHeightCm = dropHeightCm,
                loadKg = loadKg,
                plateDiameterCm = plateDiameterCm,
                targetAttempts = targetAttempts,
                createdAt = now,
                updatedAt = now,
            ),
            members.mapIndexed { ordinal, athlete -> TestingParticipantEntity(0, athlete.id, ordinal) },
        )
    }

    suspend fun progress(sessionId: Long): List<TestingParticipantProgress> = dao.progressRows(sessionId).map { row ->
        TestingParticipantProgress(
            participant = TestingParticipantEntity(row.sessionId, row.athleteId, row.ordinal, row.skippedAt),
            athleteName = row.athleteName,
            completedAttempts = row.completedAttempts,
            targetAttempts = row.targetAttempts,
        )
    }

    suspend fun setCurrentParticipant(sessionId: Long, ordinal: Int): Boolean {
        require(dao.participantAt(sessionId, ordinal) != null) { "El participante no existe." }
        return dao.setCurrentOrdinal(sessionId, ordinal) == 1
    }

    suspend fun setCurrentAthlete(sessionId: Long, athleteId: Long): Boolean {
        val participant = dao.participants(sessionId).firstOrNull {
            it.athleteId == athleteId && it.skippedAt == null
        } ?: return false
        return dao.setCurrentOrdinal(sessionId, participant.ordinal) == 1
    }

    suspend fun next(sessionId: Long): TestingParticipantEntity? {
        val session = requireNotNull(dao.byId(sessionId))
        val progress = progress(sessionId)
        val candidate = progress.asSequence()
            .filter { !it.isOmitted && !it.isComplete }
            .filter { it.participant.ordinal > session.currentOrdinal }
            .map { it.participant }
            .firstOrNull()
        return candidate?.takeIf { dao.setCurrentOrdinal(sessionId, it.ordinal) == 1 }
    }

    suspend fun back(sessionId: Long): TestingParticipantEntity? {
        val session = requireNotNull(dao.byId(sessionId))
        val participant = dao.participants(sessionId)
            .filter { it.ordinal < session.currentOrdinal }
            .maxByOrNull { it.ordinal }
        return participant?.takeIf { dao.setCurrentOrdinal(sessionId, it.ordinal) == 1 }
    }

    suspend fun skip(sessionId: Long, athleteId: Long): TestingParticipantEntity? {
        if (dao.setSkipped(sessionId, athleteId, System.currentTimeMillis()) != 1) return null
        return next(sessionId)
    }

    suspend fun complete(sessionId: Long): Boolean =
        dao.setStatus(sessionId, TestingStatus.COMPLETED.name, System.currentTimeMillis()) == 1

    suspend fun cancel(sessionId: Long): Boolean =
        dao.setStatus(sessionId, TestingStatus.CANCELLED.name, null) == 1
}
