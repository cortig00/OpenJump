package com.openjump.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.angles.AnglePoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regresión instrumentada del agregado de ángulos sobre Room real (base en memoria).
 *
 * Cubre lo que las reglas puras no pueden demostrar: idempotencia y ausencia de hijos duplicados,
 * compare-and-swap de revisión sin sobrescritura, conservación de los ids estables de marca,
 * borrado explícito y CASCADE, orden de lectura, `sourcePtsUs` exacto y que una validación fallida
 * deje intacto el guardado anterior.
 */
@RunWith(AndroidJUnit4::class)
class AngleDaoInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, JumpDatabase::class.java).build()
    private var clock = 1_000L
    private val repository = AngleRepository(database, now = { clock })
    private var athleteId = 0L
    private var otherAthleteId = 0L

    @Before
    fun setUp() {
        runBlocking {
            athleteId = database.athleteDao().insert(AthleteEntity(displayName = "Atleta"))
            otherAthleteId = database.athleteDao().insert(AthleteEntity(displayName = "Otro atleta"))
        }
    }

    @After
    fun close() {
        database.close()
    }

    // ---------- Helpers ----------

    private fun count(table: String): Long =
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    private fun mark(
        ordinal: Int,
        id: Long = NEW_ANGLE_MARK_ID,
        name: String = "Marca $ordinal",
        visible: Boolean = true,
        ax: Double = 100.0,
        ay: Double = 100.0,
    ) = AngleMarkDraft(
        id = id,
        ordinal = ordinal,
        name = name,
        colorArgb = 0xFF3366CC.toInt(),
        visible = visible,
        pointA = AnglePoint(ax, ay),
        pointB = AnglePoint(ax + 100.0, ay + 100.0),
        pointC = AnglePoint(ax + 200.0, ay),
    )

    private fun draft(
        sessionKey: String = "angle-key-1",
        athleteId: Long = this.athleteId,
        sourceIdentity: String? = "video-identity-$sessionKey",
        marks: List<AngleMarkDraft> = listOf(mark(0), mark(1)),
        rotation: Int = 0,
        encodedWidth: Int = 1920,
        encodedHeight: Int = 1080,
        presentationWidth: Int? = null,
        presentationHeight: Int? = null,
    ) = AngleAnalysisDraft(
        sessionKey = sessionKey,
        athleteId = athleteId,
        videoUri = "content://angle/$sessionKey",
        sourceIdentity = sourceIdentity,
        sourceHash = "hash-$sessionKey",
        encodedWidth = encodedWidth,
        encodedHeight = encodedHeight,
        // Por defecto, geometría de presentación coherente con la rotación; los tests inválidos
        // pueden forzar el valor incoherente.
        presentationWidth = presentationWidth
            ?: if (rotation % 180 == 0) encodedWidth else encodedHeight,
        presentationHeight = presentationHeight
            ?: if (rotation % 180 == 0) encodedHeight else encodedWidth,
        rotationDegrees = rotation,
        frameIndex = 42,
        sourcePtsUs = 123_456_789_012_345L,
        marks = marks,
    )

    private fun AngleMarkEntity.asDraft(
        ordinal: Int? = null,
        name: String? = null,
        visible: Boolean? = null,
        ax: Double? = null,
        ay: Double? = null,
    ) = AngleMarkDraft(
        id = id,
        ordinal = ordinal ?: this.ordinal,
        name = name ?: this.name,
        colorArgb = colorArgb,
        visible = visible ?: this.visible,
        pointA = AnglePoint(ax ?: pointAX, ay ?: pointAY),
        pointB = AnglePoint(pointBX, pointBY),
        pointC = AnglePoint(pointCX, pointCY),
    )

    private suspend fun snapshotOf(analysisId: Long): AngleAnalysisSnapshot =
        requireNotNull(repository.snapshot(analysisId)) { "el análisis $analysisId debería existir" }

    private fun updated(result: AngleUpdateResult): AngleUpdateResult.Updated =
        result as? AngleUpdateResult.Updated
            ?: throw AssertionError("esperaba AngleUpdateResult.Updated, fue $result")

    // ---------- Guardado nuevo ----------

    @Test
    fun saveNewPersistsRootMarkPropertiesAndExactSourcePts() = runBlocking {
        val id = repository.saveNew(draft())
        assertTrue(id > 0L)

        val snapshot = snapshotOf(id)
        val root = snapshot.analysis
        assertEquals("angle-key-1", root.sessionKey)
        assertEquals(athleteId, root.athleteId)
        assertEquals(1_000L, root.createdAt)
        assertEquals(1_000L, root.updatedAt)
        assertEquals(0, root.revision)
        assertEquals("content://angle/angle-key-1", root.videoUri)
        assertEquals("video-identity-angle-key-1", root.sourceIdentity)
        assertEquals("hash-angle-key-1", root.sourceHash)
        assertEquals(1920, root.encodedWidth)
        assertEquals(1080, root.encodedHeight)
        assertEquals(1920, root.presentationWidth)
        assertEquals(1080, root.presentationHeight)
        assertEquals(0, root.rotationDegrees)
        assertEquals(42, root.frameIndex)
        assertEquals(123_456_789_012_345L, root.sourcePtsUs)
        assertEquals(ANGLE_GEOMETRY_VERSION, root.geometryVersion)

        assertEquals(listOf(0, 1), snapshot.marks.map { it.ordinal })
        val first = snapshot.marks[0]
        assertTrue(first.id > 0L)
        assertEquals(id, first.analysisId)
        assertEquals("Marca 0", first.name)
        assertEquals(0xFF3366CC.toInt(), first.colorArgb)
        assertTrue(first.visible)
        assertEquals(100.0, first.pointAX, 0.0)
        assertEquals(100.0, first.pointAY, 0.0)
        assertEquals(200.0, first.pointBX, 0.0)
        assertEquals(200.0, first.pointBY, 0.0)
        assertEquals(300.0, first.pointCX, 0.0)
        assertEquals(100.0, first.pointCY, 0.0)
        assertEquals(2L, count("angle_marks"))
    }

    @Test
    fun saveNewIsIdempotentBySessionKeyAndNeverDuplicatesMarks() = runBlocking {
        val firstId = repository.saveNew(draft())
        val firstSnapshot = snapshotOf(firstId)

        clock = 5_000L
        val replayId = repository.saveNew(draft())
        assertEquals(firstId, replayId)
        assertEquals(1L, count("angle_analyses"))
        assertEquals(2L, count("angle_marks"))
        // El reintento no reescribe fechas, revisión ni marcas.
        assertEquals(firstSnapshot, snapshotOf(firstId))

        // Mismo sessionKey con otro dueño u otra identidad lógica: conflicto, sin tocar nada.
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.saveNew(draft(athleteId = otherAthleteId)) }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.saveNew(draft(sourceIdentity = "otra-identidad")) }
        }
        assertEquals(1L, count("angle_analyses"))
        assertEquals(2L, count("angle_marks"))
        assertEquals(firstSnapshot, snapshotOf(firstId))
    }

    @Test
    fun saveNewSupportsMultipleAnalysesWithOrderedReads() = runBlocking {
        clock = 1_000L
        val first = repository.saveNew(draft(sessionKey = "a"))
        clock = 2_000L
        val second = repository.saveNew(draft(sessionKey = "b"))
        clock = 3_000L
        val otherAthlete = repository.saveNew(
            draft(sessionKey = "c", athleteId = otherAthleteId),
        )
        assertEquals(3, setOf(first, second, otherAthlete).size)
        assertEquals(3L, count("angle_analyses"))

        val all = repository.observeAnalyses().first()
        assertEquals(listOf(otherAthlete, second, first), all.map { it.id })
        val mine = repository.observeAnalysesForAthlete(athleteId).first()
        assertEquals(listOf(second, first), mine.map { it.id })
        assertEquals(2, database.angleDao().marksForAnalysis(second).size)
        assertEquals(
            listOf("a", "b"),
            listOf(
                requireNotNull(repository.snapshotBySessionKey("a")).analysis.sessionKey,
                requireNotNull(repository.snapshotBySessionKey("b")).analysis.sessionKey,
            ),
        )
    }

    @Test
    fun saveNewRejectsUnknownAthleteAndWritesNothing() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.saveNew(draft(athleteId = 999_999L)) }
        }
        assertEquals(0L, count("angle_analyses"))
        assertEquals(0L, count("angle_marks"))
    }

    @Test
    fun invalidNewAnalysesWriteNothing() = runBlocking {
        val invalid = listOf(
            draft(rotation = 45),
            draft(rotation = 90, presentationWidth = 1920, presentationHeight = 1080),
            draft(encodedWidth = 0),
            draft(marks = listOf(mark(0, name = " "))),
            draft(marks = emptyList()),
            draft(marks = listOf(mark(0), mark(0))),
            draft(marks = listOf(mark(0, ax = -0.5))),
            draft(marks = listOf(mark(0, ay = 1080.0))),
            draft(marks = listOf(mark(0, ax = Double.NaN))),
            draft(marks = listOf(mark(0, id = 77L))),
        )
        invalid.forEach { invalidDraft ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.saveNew(invalidDraft) }
            }
        }
        assertEquals(0L, count("angle_analyses"))
        assertEquals(0L, count("angle_marks"))
    }

    // ---------- Edición ----------

    @Test
    fun updatePreservesMarkIdsAndAppliesChanges() = runBlocking {
        val id = repository.saveNew(draft())
        val before = snapshotOf(id)
        val kept = before.marks[0]
        val removed = before.marks[1]

        clock = 10_000L
        val result = repository.update(
            AngleUpdateDraft(
                analysisId = id,
                expectedRevision = 0,
                marks = listOf(
                    kept.asDraft(
                        name = "Cadera editada",
                        visible = false,
                        ax = 500.0,
                        ay = 600.0,
                    ),
                    mark(2, name = "Nueva"),
                ),
                removedMarkIds = setOf(removed.id),
            ),
        )
        val snapshot = updated(result).snapshot
        assertEquals(1, snapshot.analysis.revision)
        assertEquals(10_000L, snapshot.analysis.updatedAt)
        assertEquals(before.analysis.createdAt, snapshot.analysis.createdAt)
        assertEquals(2, snapshot.marks.size)
        // Contrato de [AngleAggregateRules.planUpdate]: el `ordinal` lo fija el llamante (la UI
        // manda índices contiguos) y el repositorio NO renumera: al borrar el ordinal 1 por id
        // explícito, la marca conservada mantiene el 0 y la nueva entra en el 2. El estado final
        // solo exige ordinales únicos y no negativos, así que el hueco es deliberado.
        assertEquals(listOf(0, 2), snapshot.marks.map { it.ordinal })
        val storedOrdinals = mutableListOf<Int>()
        database.openHelper.writableDatabase
            .query("SELECT ordinal FROM angle_marks WHERE analysisId = $id ORDER BY ordinal")
            .use { cursor -> while (cursor.moveToNext()) storedOrdinals += cursor.getInt(0) }
        assertEquals("la base guarda el ordinal indicado, sin renumerar", listOf(0, 2), storedOrdinals)

        val keptAfter = snapshot.marks.single { it.id == kept.id }
        assertEquals("Cadera editada", keptAfter.name)
        assertEquals(0, keptAfter.ordinal)
        assertFalse(keptAfter.visible)
        assertEquals(500.0, keptAfter.pointAX, 0.0)
        assertEquals(600.0, keptAfter.pointAY, 0.0)
        assertTrue(snapshot.marks.none { it.id == removed.id })

        val inserted = snapshot.marks.single { it.id != kept.id }
        assertTrue(inserted.id > 0L)
        assertEquals("Nueva", inserted.name)
        assertEquals(2, inserted.ordinal)
        assertEquals(2L, count("angle_marks"))
    }

    @Test
    fun updateWithStaleRevisionReturnsConflictAndDoesNotOverwrite() = runBlocking {
        val id = repository.saveNew(draft())
        val before = snapshotOf(id)
        val mark = before.marks[0]

        val stale = repository.update(
            AngleUpdateDraft(
                analysisId = id,
                expectedRevision = 7,
                marks = listOf(mark.asDraft(name = "No debe entrar")),
            ),
        )
        val conflict = stale as? AngleUpdateResult.Conflict
            ?: throw AssertionError("esperaba conflicto, fue $stale")
        assertEquals(id, conflict.analysisId)
        assertEquals(0, conflict.storedRevision)
        assertEquals(before, snapshotOf(id))

        val applied = repository.update(
            AngleUpdateDraft(id, expectedRevision = 0, marks = listOf(mark.asDraft(name = "Cadera"))),
        )
        assertEquals(1, updated(applied).snapshot.analysis.revision)

        val staleRetry = repository.update(
            AngleUpdateDraft(id, expectedRevision = 0, marks = listOf(mark.asDraft(name = "Tarde"))),
        )
        assertEquals(1, (staleRetry as AngleUpdateResult.Conflict).storedRevision)
        assertEquals(
            "Cadera",
            snapshotOf(id).marks.single { it.id == mark.id }.name,
        )
    }

    @Test
    fun invalidUpdateLeavesPreviousSaveIntactAndTransactionUsable() = runBlocking {
        val id = repository.saveNew(draft())
        val before = snapshotOf(id)
        val mark = before.marks[0]

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.update(
                    AngleUpdateDraft(id, 0, marks = listOf(mark.asDraft(ax = 5000.0))),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.update(
                    AngleUpdateDraft(
                        id,
                        0,
                        marks = listOf(mark.asDraft(ordinal = 1)),
                    ),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.update(
                    AngleUpdateDraft(id, 0, marks = listOf(mark(0, id = 999_999L))),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.update(
                    AngleUpdateDraft(
                        id,
                        0,
                        marks = emptyList(),
                        removedMarkIds = before.marks.map { it.id }.toSet(),
                    ),
                )
            }
        }
        assertEquals(before, snapshotOf(id))
        assertEquals(0, snapshotOf(id).analysis.revision)
        assertEquals(2L, count("angle_marks"))

        // La transacción fallida no deja el análisis bloqueado para una edición válida.
        val applied = repository.update(
            AngleUpdateDraft(id, 0, marks = listOf(mark.asDraft(name = "Sigue vivo"))),
        )
        assertEquals("Sigue vivo", updated(applied).snapshot.marks.single { it.id == mark.id }.name)
    }

    @Test
    fun updateRejectsMarksFromAnotherRoot() = runBlocking {
        val first = repository.saveNew(draft(sessionKey = "a"))
        val second = repository.saveNew(draft(sessionKey = "b"))
        val foreignMark = snapshotOf(second).marks[0]

        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.update(
                    AngleUpdateDraft(first, 0, marks = listOf(foreignMark.asDraft(name = "Robada"))),
                )
            }
        }
        assertTrue(error.message.orEmpty().contains("otra raíz"))
        assertEquals(0, snapshotOf(first).analysis.revision)
        assertEquals(foreignMark, snapshotOf(second).marks[0])
        assertEquals(4L, count("angle_marks"))
    }

    @Test
    fun updateSwapsOrdinalsWithoutUniqueIndexCollision() = runBlocking {
        val id = repository.saveNew(draft())
        val marks = snapshotOf(id).marks
        val first = marks[0]
        val second = marks[1]

        val applied = repository.update(
            AngleUpdateDraft(
                id,
                0,
                marks = listOf(
                    first.asDraft(ordinal = 1),
                    second.asDraft(ordinal = 0),
                ),
            ),
        )
        val snapshot = updated(applied).snapshot
        assertEquals(listOf(second.id, first.id), snapshot.marks.map { it.id })
        assertEquals(listOf(0, 1), snapshot.marks.map { it.ordinal })
    }

    @Test
    fun updateCanReuseOrdinalFreedByExplicitRemoval() = runBlocking {
        val id = repository.saveNew(draft())
        val marks = snapshotOf(id).marks
        val removed = marks[0]

        val applied = repository.update(
            AngleUpdateDraft(
                id,
                0,
                marks = listOf(mark(removed.ordinal, name = "Reemplazo")),
                removedMarkIds = setOf(removed.id),
            ),
        )
        val snapshot = updated(applied).snapshot
        assertEquals(2, snapshot.marks.size)
        assertTrue(snapshot.marks.none { it.id == removed.id })
        assertEquals("Reemplazo", snapshot.marks.single { it.ordinal == removed.ordinal }.name)
    }

    // ---------- Borrado ----------

    @Test
    fun deleteRemovesRootAndMarksByCascade() = runBlocking {
        val id = repository.saveNew(draft())
        assertEquals(2L, count("angle_marks"))

        assertTrue(repository.delete(id))
        assertFalse(repository.delete(id))
        assertNull(repository.snapshot(id))
        assertEquals(0L, count("angle_analyses"))
        assertEquals(0L, count("angle_marks"))
        assertFalse(
            database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use {
                it.moveToFirst()
            },
        )
    }
}
