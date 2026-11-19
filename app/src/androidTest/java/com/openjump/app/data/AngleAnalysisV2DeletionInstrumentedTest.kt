package com.openjump.app.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.data.angles.AngleImageKey
import com.openjump.app.data.backup.MEDIA_BACKUP_MAX_IMAGES
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Presupuesto de captura por defecto (24 fotogramas) que esta puerta usaba como techo de borrado.
 *
 * **No** es un límite del formato persistido: [AngleAnalysisV2AggregateDraft.validate] no lo impone y
 * una restauración aceptada puede traer raíces mayores. Se conserva aquí sólo para documentar la
 * regresión: un agregado persistido por encima de él debe seguir siendo borrable.
 */
private const val DEFAULT_CAPTURE_FRAME_BUDGET = 24

/** Fotogramas del agregado persistido del caso de regresión: uno más que el presupuesto de captura. */
private const val FRAMES_ABOVE_DEFAULT_CAPTURE_BUDGET = DEFAULT_CAPTURE_FRAME_BUDGET + 1

/**
 * Regresión instrumentada del **borrado confirmado** del agregado multi-fotograma
 * ([AngleAnalysisV2Dao.deleteConfirmedAggregate]) sobre Room real **en memoria**.
 *
 * Lo que demuestra (y lo que no):
 *
 * - **Demuestra** que la puerta de borrado sólo acepta la prueba completa del agregado: relee raíz,
 *   fotogramas y marcas dentro de su transacción, compara por **igualdad exacta** de filas y devuelve
 *   un resultado tipado ([AngleAnalysisV2DeleteOutcome]) —nunca un booleano—; que la cascada de
 *   claves foráneas retira exactamente los fotogramas y marcas hijos del objetivo, dejando intactos
 *   otra raíz V2, una fila legacy con el **mismo id numérico** y el resto de datos de usuario; que la
 *   lista devuelta contiene **exactamente** las claves e integridad persistidas de sus imágenes; que
 *   una prueba cambiada o inválida **no** muta nada; que un borrado repetido es `Missing` y no un
 *   éxito ficticio; y que un fallo de base **dentro** de la transacción revierte en vez de informar
 *   de un borrado parcial; y que un agregado persistido con **más** fotogramas que el presupuesto de
 *   captura por defecto (24) se borra igual, porque el techo de la prueba es el contrato de backup
 *   ([MEDIA_BACKUP_MAX_IMAGES], 4096) y no el límite de la UI de alta.
 * - **No demuestra** el ciclo de ficheros: no hay imágenes reales ni se comprueba que se haya
 *   borrado ningún PNG. La reclamación de las claves devueltas es del repositorio de borrado (mutex
 *   de medios, `frameImageReferenceCount` y verificación en el almacén), que todavía no existe.
 * - **No demuestra** UI, historial, navegación ni restore. Tampoco se ha ejecutado en dispositivo en
 *   este slice: se compila, no se corre aquí.
 * - **No promete** borrar agregados persistidos al margen de los caminos soportados (alta directa
 *   por DAO o presupuesto personalizado) con más de [MEDIA_BACKUP_MAX_IMAGES] fotogramas: el contrato
 *   de backup no los cubre y la puerta los rechaza con `InvalidProof` (aquí sólo se comprueba ese
 *   rechazo, no se afirma que sean borrables).
 *
 * Aislamiento: base Room **en memoria** creada por el test; nunca se abre la base de la app ni se
 * tocan datos de usuario. La inyección de fallo usa triggers temporales sobre esa base aislada.
 */
@RunWith(AndroidJUnit4::class)
class AngleAnalysisV2DeletionInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, JumpDatabase::class.java).build()
    private val dao = database.angleAnalysisV2Dao()

    private var athleteId = 0L
    private var otherAthleteId = 0L
    private var keyCounter = 0
    private var clock = 1_000L

    @Before
    fun setUp() {
        runBlocking {
            athleteId = database.athleteDao().insert(AthleteEntity(displayName = "Atleta V2"))
            otherAthleteId = database.athleteDao().insert(AthleteEntity(displayName = "Otro atleta V2"))
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

    private fun nextImageKey(): String {
        keyCounter += 1
        return "%032x".format(keyCounter)
    }

    private fun sha256Of(seed: String): String = MessageDigest.getInstance("SHA-256")
        .digest(seed.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun mark(
        ordinal: Int,
        name: String = "Marca $ordinal",
        ax: Double = 100.0,
        ay: Double = 100.0,
    ) = AngleAnalysisV2MarkEntity(
        frameId = 0L,
        ordinal = ordinal,
        name = name,
        colorArgb = 0xFF3366CC.toInt(),
        visible = true,
        pointAX = ax,
        pointAY = ay,
        pointBX = ax + 100.0,
        pointBY = ay + 100.0,
        pointCX = ax + 200.0,
        pointCY = ay,
    )

    private data class InsertedFixture(
        val rootId: Long,
        val imageKeys: List<String>,
        val imageShas: List<String>,
    )

    /** Inserta un agregado real; [marksPerFrame] declara cuántas marcas tiene cada fotograma. */
    private suspend fun insert(
        sessionKey: String,
        athleteId: Long = this.athleteId,
        marksPerFrame: List<Int> = listOf(1),
    ): InsertedFixture {
        val imageKeys = marksPerFrame.indices.map { nextImageKey() }
        val imageShas = imageKeys.map { key -> sha256Of("png-$key") }
        clock += 1_000L
        val draft = AngleAnalysisV2AggregateDraft(
            root = AngleAnalysisV2RootEntity(
                sessionKey = sessionKey,
                athleteId = athleteId,
                createdAt = clock,
                updatedAt = clock,
            ),
            frames = marksPerFrame.mapIndexed { index, markCount ->
                AngleAnalysisV2FrameDraft(
                    frame = AngleAnalysisV2FrameEntity(
                        rootId = 0L,
                        ordinal = index,
                        assignedFrameIndex = index * 30,
                        reportedFrameIndex = index * 30,
                        assignedPtsUs = index * 1_000_000L,
                        reportedPtsUs = index * 1_000_000L,
                        encodedWidth = 1080,
                        encodedHeight = 1920,
                        presentationWidth = 1080,
                        presentationHeight = 1920,
                        rotationDegrees = 0,
                        geometryVersion = ANGLE_GEOMETRY_VERSION,
                        imageKey = imageKeys[index],
                        imageWidth = 1080,
                        imageHeight = 1920,
                        imageByteCount = 4_096L + index,
                        imageSha256 = imageShas[index],
                        imageMimeType = "image/png",
                    ),
                    marks = (0 until markCount).map { markIndex ->
                        mark(ordinal = markIndex, name = "Marca $index-$markIndex")
                    },
                )
            },
        )
        val outcome = dao.insertAggregate(draft)
        val inserted = outcome as? AngleAnalysisV2InsertOutcome.Inserted
            ?: throw AssertionError("esperaba un alta nueva, fue $outcome")
        return InsertedFixture(inserted.rootId, imageKeys, imageShas)
    }

    private suspend fun proofOf(rootId: Long): AngleAnalysisV2StoredAggregate =
        requireNotNull(dao.aggregateByRootId(rootId)) { "la raíz $rootId debería existir" }

    /**
     * Prueba sintética con [frameCount] fotogramas contiguos, ids y claves únicos, **sin** leer ni
     * escribir la base: fija la cota de la validación sin persistir miles de filas.
     */
    private fun proofWithFrameCount(
        base: AngleAnalysisV2StoredAggregate,
        frameCount: Int,
    ): AngleAnalysisV2StoredAggregate {
        val template = base.frames.first().frame
        return base.copy(
            frames = (0 until frameCount).map { index ->
                AngleAnalysisV2StoredFrame(
                    frame = template.copy(
                        id = template.id + 10_000L + index,
                        ordinal = index,
                        imageKey = "%032x".format(900_000 + index),
                        imageByteCount = 4_096L + index,
                    ),
                    marks = emptyList(),
                )
            },
        )
    }

    private fun deleted(outcome: AngleAnalysisV2DeleteOutcome): AngleAnalysisV2DeleteOutcome.Deleted =
        outcome as? AngleAnalysisV2DeleteOutcome.Deleted
            ?: throw AssertionError("esperaba Deleted, fue $outcome")

    private fun changed(outcome: AngleAnalysisV2DeleteOutcome): AngleAnalysisV2DeleteOutcome.Changed =
        outcome as? AngleAnalysisV2DeleteOutcome.Changed
            ?: throw AssertionError("esperaba Changed, fue $outcome")

    private fun invalid(outcome: AngleAnalysisV2DeleteOutcome): AngleAnalysisV2DeleteOutcome.InvalidProof =
        outcome as? AngleAnalysisV2DeleteOutcome.InvalidProof
            ?: throw AssertionError("esperaba InvalidProof, fue $outcome")

    private fun legacyAnalysis(sessionKey: String) = AngleAnalysisEntity(
        sessionKey = sessionKey,
        athleteId = athleteId,
        createdAt = 500L,
        updatedAt = 500L,
        encodedWidth = 1080,
        encodedHeight = 1920,
        presentationWidth = 1080,
        presentationHeight = 1920,
        rotationDegrees = 0,
        frameIndex = 12,
        sourcePtsUs = 400_000L,
        geometryVersion = ANGLE_GEOMETRY_VERSION,
    )

    // ---------- Borrado confirmado ----------

    @Test
    fun confirmedDeleteRemovesOnlyTargetAggregateAndCascadesItsChildren() = runBlocking {
        val target = insert(sessionKey = "v2-target", marksPerFrame = listOf(2, 1))
        val other = insert(sessionKey = "v2-other", athleteId = otherAthleteId, marksPerFrame = listOf(1))
        val targetProof = proofOf(target.rootId)

        val outcome = deleted(dao.deleteConfirmedAggregate(targetProof))

        assertEquals(target.rootId, outcome.rootId)
        // Una entrada por fotograma, en orden, con la clave y la integridad persistidas tal cual.
        assertEquals(target.imageKeys, outcome.ownedImages.map { image -> image.imageKey })
        assertEquals(
            listOf(1080, 1080),
            outcome.ownedImages.map { image -> image.imageWidth },
        )
        assertEquals(
            listOf(1920, 1920),
            outcome.ownedImages.map { image -> image.imageHeight },
        )
        assertEquals(
            listOf(4_096L, 4_097L),
            outcome.ownedImages.map { image -> image.imageByteCount },
        )
        assertEquals(target.imageShas, outcome.ownedImages.map { image -> image.imageSha256 })
        assertEquals(listOf("image/png", "image/png"), outcome.ownedImages.map { image -> image.imageMimeType })

        // Cascada: sólo desaparecen la raíz objetivo y sus 2 fotogramas + 3 marcas.
        assertEquals(1L, count("angle_analysis_v2_roots"))
        assertEquals(1L, count("angle_analysis_v2_frames"))
        assertEquals(1L, count("angle_analysis_v2_marks"))
        assertNull(dao.aggregateByRootId(target.rootId))
        assertEquals(proofOf(other.rootId), dao.aggregateByRootId(other.rootId))

        // Las claves del objetivo ya no tienen ninguna referencia persistida; la otra raíz sí.
        target.imageKeys.forEach { key ->
            assertEquals(0, dao.frameImageReferenceCount(key))
        }
        other.imageKeys.forEach { key ->
            assertEquals(1, dao.frameImageReferenceCount(key))
        }
    }

    /**
     * Regresión del techo de la prueba: 25 fotogramas superan el presupuesto de captura por defecto
     * (24), que **no** es el límite del formato persistido ([AngleAnalysisV2AggregateDraft.validate]
     * no lo impone y una restauración aceptada puede traer raíces mayores). Antes del cambio esta
     * raíz aceptada se habría rechazado como `InvalidProof` y habría quedado sin poder borrarse.
     */
    @Test
    fun persistedAggregateAboveTheDefaultCaptureBudgetIsDeletable() = runBlocking {
        val target = insert(
            sessionKey = "v2-restored-above-capture-budget",
            marksPerFrame = List(FRAMES_ABOVE_DEFAULT_CAPTURE_BUDGET) { 1 },
        )
        val survivor = insert(sessionKey = "v2-survivor-above-capture-budget", athleteId = otherAthleteId)
        val base = proofOf(target.rootId)
        val survivorProof = proofOf(survivor.rootId)

        assertEquals(FRAMES_ABOVE_DEFAULT_CAPTURE_BUDGET, base.frames.size)
        assertTrue(
            "el caso debe superar el presupuesto de captura por defecto ($DEFAULT_CAPTURE_FRAME_BUDGET)",
            base.frames.size > DEFAULT_CAPTURE_FRAME_BUDGET,
        )
        // Persistido de verdad (no un borrador) y con una prueba que la validación previa acepta.
        assertEquals(2L, count("angle_analysis_v2_roots"))
        assertEquals((FRAMES_ABOVE_DEFAULT_CAPTURE_BUDGET + 1).toLong(), count("angle_analysis_v2_frames"))
        assertEquals((FRAMES_ABOVE_DEFAULT_CAPTURE_BUDGET + 1).toLong(), count("angle_analysis_v2_marks"))
        assertNull(describeInvalidDeletionProof(base))

        val outcome = deleted(dao.deleteConfirmedAggregate(base))

        assertEquals(target.rootId, outcome.rootId)
        assertEquals(target.imageKeys, outcome.ownedImages.map { image -> image.imageKey })
        assertEquals(target.imageShas, outcome.ownedImages.map { image -> image.imageSha256 })
        assertEquals(FRAMES_ABOVE_DEFAULT_CAPTURE_BUDGET, outcome.ownedImages.size)

        // Cascada completa del objetivo y supervivencia de la otra raíz.
        assertNull(dao.aggregateByRootId(target.rootId))
        assertEquals(survivorProof, dao.aggregateByRootId(survivor.rootId))
        assertEquals(1L, count("angle_analysis_v2_roots"))
        assertEquals(1L, count("angle_analysis_v2_frames"))
        assertEquals(1L, count("angle_analysis_v2_marks"))
        target.imageKeys.forEach { key -> assertEquals(0, dao.frameImageReferenceCount(key)) }
        survivor.imageKeys.forEach { key -> assertEquals(1, dao.frameImageReferenceCount(key)) }
    }

    @Test
    fun confirmedDeleteLeavesLegacyRowsAthletesAndOtherRootsIntact() = runBlocking {
        val legacyAnalysisId = database.angleDao().insertAnalysis(
            legacyAnalysis(sessionKey = "legacy-analysis"),
        )
        database.angleDao().insertMarks(
            listOf(
                AngleMarkEntity(
                    analysisId = legacyAnalysisId,
                    ordinal = 0,
                    name = "Marca legacy",
                    colorArgb = 0xFF112233.toInt(),
                    pointAX = 10.0,
                    pointAY = 20.0,
                    pointBX = 30.0,
                    pointBY = 40.0,
                    pointCX = 50.0,
                    pointCY = 60.0,
                ),
            ),
        )
        val target = insert(sessionKey = "v2-same-numeric-id")
        val survivingRoot = insert(sessionKey = "v2-survivor", athleteId = otherAthleteId)
        // Precondición del caso: la tabla legacy y la V2 numeran por separado, así que colisionan.
        assertEquals(legacyAnalysisId, target.rootId)
        val survivingProof = proofOf(survivingRoot.rootId)

        deleted(dao.deleteConfirmedAggregate(proofOf(target.rootId)))

        assertEquals(1L, count("angle_analyses"))
        assertEquals(1L, count("angle_marks"))
        val legacy = requireNotNull(database.angleDao().analysisById(legacyAnalysisId))
        assertEquals("legacy-analysis", legacy.sessionKey)
        assertEquals(1, database.angleDao().marksForAnalysis(legacyAnalysisId).size)
        assertEquals(2L, count("athletes"))
        assertEquals(survivingProof, dao.aggregateByRootId(survivingRoot.rootId))
        assertNull(dao.aggregateByRootId(target.rootId))
    }

    @Test
    fun changedProofIsRejectedWithoutMutation() = runBlocking {
        val fixture = insert(sessionKey = "v2-changed-proof", marksPerFrame = listOf(2, 1))
        val base = proofOf(fixture.rootId)
        val firstFrame = base.frames[0]
        val secondFrame = base.frames[1]
        val firstMark = firstFrame.marks[0]

        val changedProofs = listOf(
            "geometría del fotograma" to base.copy(
                frames = listOf(
                    firstFrame.copy(
                        frame = firstFrame.frame.copy(encodedWidth = 720, presentationWidth = 720),
                    ),
                    secondFrame,
                ),
            ),
            "clave de imagen del fotograma" to base.copy(
                frames = listOf(
                    firstFrame.copy(frame = firstFrame.frame.copy(imageKey = nextImageKey())),
                    secondFrame,
                ),
            ),
            "id del fotograma" to base.copy(
                frames = listOf(
                    firstFrame.copy(frame = firstFrame.frame.copy(id = firstFrame.frame.id + 5_000L)),
                    secondFrame,
                ),
            ),
            "nombre de la marca" to base.copy(
                frames = listOf(
                    firstFrame.copy(
                        marks = listOf(firstMark.copy(name = "Otra marca"), firstFrame.marks[1]),
                    ),
                    secondFrame,
                ),
            ),
            "id de la marca" to base.copy(
                frames = listOf(
                    firstFrame.copy(
                        marks = listOf(firstMark.copy(id = firstMark.id + 7_000L), firstFrame.marks[1]),
                    ),
                    secondFrame,
                ),
            ),
            "sessionKey de la raíz" to base.copy(
                root = base.root.copy(sessionKey = "v2-changed-proof-renamed"),
            ),
            "timestamps de la raíz" to base.copy(
                root = base.root.copy(createdAt = base.root.createdAt + 1L, updatedAt = base.root.updatedAt + 1L),
            ),
            "contador de revisión" to base.copy(root = base.root.copy(revision = base.root.revision + 1)),
        )

        changedProofs.forEach { (label, changedProof) ->
            val outcome = changed(dao.deleteConfirmedAggregate(changedProof))
            assertEquals("[$label] el resultado debe nombrar la raíz guardada", fixture.rootId, outcome.rootId)
            assertTrue("[$label] el detalle no puede estar vacío", outcome.detail.isNotBlank())
            // Sin mutación: la raíz sigue exactamente como estaba y sus hijos también.
            assertEquals("[$label] la raíz no debe cambiar", base, dao.aggregateByRootId(fixture.rootId))
        }
        assertEquals(1L, count("angle_analysis_v2_roots"))
        assertEquals(2L, count("angle_analysis_v2_frames"))
        assertEquals(3L, count("angle_analysis_v2_marks"))
    }

    @Test
    fun repeatedDeleteOfTheSameProofIsMissingAndLeavesNoSuccess() = runBlocking {
        val fixture = insert(sessionKey = "v2-repeat")
        val base = proofOf(fixture.rootId)

        deleted(dao.deleteConfirmedAggregate(base))

        val repeated = dao.deleteConfirmedAggregate(base)
        val missing = repeated as? AngleAnalysisV2DeleteOutcome.Missing
            ?: throw AssertionError("esperaba Missing en la repetición, fue $repeated")
        assertEquals(fixture.rootId, missing.rootId)
        assertEquals(0L, count("angle_analysis_v2_roots"))
        assertEquals(0L, count("angle_analysis_v2_frames"))
        assertEquals(0L, count("angle_analysis_v2_marks"))
    }

    @Test
    fun invalidProofsAreRejectedWithoutMutationAndNeverDropUnknownKeys() = runBlocking {
        val fixture = insert(sessionKey = "v2-invalid-proofs", marksPerFrame = listOf(2, 1))
        val base = proofOf(fixture.rootId)
        val firstFrame = base.frames[0]
        val secondFrame = base.frames[1]
        val firstMark = firstFrame.marks[0]
        // La cota rechazada es la del contrato de backup: 4097 fotogramas, no 25.
        val oversized = proofWithFrameCount(base, MEDIA_BACKUP_MAX_IMAGES + 1)

        val invalidProofs = listOf(
            "raíz sin id persistido" to base.copy(root = base.root.copy(id = 0L)),
            "formato desconocido" to base.copy(root = base.root.copy(analysisFormat = "ANGLE_MULTI_FRAME_RARO")),
            "sessionKey vacía" to base.copy(root = base.root.copy(sessionKey = " ")),
            "sin fotogramas" to base.copy(frames = emptyList()),
            "sobre la cota de fotogramas del contrato de backup" to oversized,
            "clave no canónica" to base.copy(
                frames = listOf(
                    firstFrame.copy(frame = firstFrame.frame.copy(imageKey = "NO-ES-UNA-CLAVE-CANONICA")),
                    secondFrame,
                ),
            ),
            "clave repetida" to base.copy(
                frames = listOf(
                    firstFrame,
                    secondFrame.copy(frame = secondFrame.frame.copy(imageKey = firstFrame.frame.imageKey)),
                ),
            ),
            "ordinal de fotograma con hueco" to base.copy(
                frames = listOf(firstFrame, secondFrame.copy(frame = secondFrame.frame.copy(ordinal = 9))),
            ),
            "fotograma de otra raíz" to base.copy(
                frames = listOf(firstFrame.copy(frame = firstFrame.frame.copy(rootId = base.root.id + 999L)), secondFrame),
            ),
            "sha de imagen no hexadecimal" to base.copy(
                frames = listOf(
                    firstFrame.copy(frame = firstFrame.frame.copy(imageSha256 = "no-es-un-sha")),
                    secondFrame,
                ),
            ),
            "marca sin nombre" to base.copy(
                frames = listOf(
                    firstFrame.copy(marks = listOf(firstMark.copy(name = ""), firstFrame.marks[1])),
                    secondFrame,
                ),
            ),
            "coordenada no finita" to base.copy(
                frames = listOf(
                    firstFrame.copy(
                        marks = listOf(firstMark.copy(pointAX = Double.NaN), firstFrame.marks[1]),
                    ),
                    secondFrame,
                ),
            ),
            "marca de otro fotograma" to base.copy(
                frames = listOf(
                    firstFrame.copy(marks = listOf(firstMark.copy(frameId = firstMark.frameId + 500L), firstFrame.marks[1])),
                    secondFrame,
                ),
            ),
        )

        invalidProofs.forEach { (label, invalidProof) ->
            val outcome = dao.deleteConfirmedAggregate(invalidProof)
            val rejection = invalid(outcome)
            assertTrue("[$label] el detalle no puede estar vacío", rejection.detail.isNotBlank())
            assertEquals("[$label] nada puede cambiar", base, dao.aggregateByRootId(fixture.rootId))
        }
        assertEquals(1L, count("angle_analysis_v2_roots"))
        assertEquals(2L, count("angle_analysis_v2_frames"))
        assertEquals(3L, count("angle_analysis_v2_marks"))
        // La clave manipulada no se ha "adoptado" ni borrado: el agregado sigue con sus claves.
        assertEquals(fixture.imageKeys, proofOf(fixture.rootId).frames.map { frame -> frame.frame.imageKey })
        fixture.imageKeys.forEach { key ->
            assertEquals(1, dao.frameImageReferenceCount(key))
        }
    }

    // ---------- Atomicidad: el DELETE no puede informar de un éxito que no ocurrió ----------

    @Test
    fun zeroRowRootDeleteRollsBackAndIsNotReportedAsDeleted() = runBlocking {
        val fixture = insert(sessionKey = "v2-zero-row", marksPerFrame = listOf(1, 1))
        val base = proofOf(fixture.rootId)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER v2_delete_ignored BEFORE DELETE ON angle_analysis_v2_roots " +
                "BEGIN SELECT RAISE(IGNORE); END",
        )

        assertThrows(AngleAnalysisV2DeleteRollbackException::class.java) {
            runBlocking { dao.deleteConfirmedAggregate(base) }
        }

        assertEquals("la transacción debe revertirse", base, dao.aggregateByRootId(fixture.rootId))
        assertEquals(1L, count("angle_analysis_v2_roots"))
        assertEquals(2L, count("angle_analysis_v2_frames"))
        assertEquals(2L, count("angle_analysis_v2_marks"))

        database.openHelper.writableDatabase.execSQL("DROP TRIGGER v2_delete_ignored")
        // La misma prueba vuelve a funcionar: el fallo no dejó la puerta bloqueada.
        deleted(dao.deleteConfirmedAggregate(base))
        assertNull(dao.aggregateByRootId(fixture.rootId))
    }

    @Test
    fun databaseFailureInsideTheTransactionRollsBackWithoutPartialDelete() = runBlocking {
        val fixture = insert(sessionKey = "v2-abort", marksPerFrame = listOf(2))
        val base = proofOf(fixture.rootId)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER v2_delete_abort BEFORE DELETE ON angle_analysis_v2_roots " +
                "BEGIN SELECT RAISE(ABORT, 'fallo inyectado'); END",
        )

        val thrown = runCatching { runBlocking { dao.deleteConfirmedAggregate(base) } }

        assertTrue("debía fallar dentro de la transacción: $thrown", thrown.exceptionOrNull() != null)
        assertTrue(
            "el fallo debe ser el error de SQLite inyectado: ${thrown.exceptionOrNull()}",
            thrown.exceptionOrNull() is SQLiteConstraintException,
        )
        assertEquals("la transacción debe revertirse", base, dao.aggregateByRootId(fixture.rootId))
        assertEquals(1L, count("angle_analysis_v2_roots"))
        assertEquals(1L, count("angle_analysis_v2_frames"))
        assertEquals(2L, count("angle_analysis_v2_marks"))
        fixture.imageKeys.forEach { key ->
            assertEquals(1, dao.frameImageReferenceCount(key))
        }

        database.openHelper.writableDatabase.execSQL("DROP TRIGGER v2_delete_abort")
        deleted(dao.deleteConfirmedAggregate(base))
        assertNull(dao.aggregateByRootId(fixture.rootId))
    }

    // ---------- Piezas puras de la validación de la prueba ----------

    @Test
    fun proofValidationRejectsUncanonicalKeysInsteadOfDroppingThem() = runBlocking {
        val fixture = insert(sessionKey = "v2-pure-validation")
        val base = proofOf(fixture.rootId)
        assertNull(describeInvalidDeletionProof(base))

        // El techo es el contrato de backup (4096 imágenes), no el presupuesto de captura: una raíz
        // aceptada justo en el límite sigue siendo borrable, y 4097 se rechaza en el caso de arriba.
        assertTrue(
            "la cota de borrado debe ser el contrato de backup ($MEDIA_BACKUP_MAX_IMAGES)",
            ANGLE_ANALYSIS_V2_DELETE_MAX_FRAMES == MEDIA_BACKUP_MAX_IMAGES,
        )
        assertNull(describeInvalidDeletionProof(proofWithFrameCount(base, MEDIA_BACKUP_MAX_IMAGES)))

        val badKey = base.copy(
            frames = listOf(
                base.frames.single().copy(frame = base.frames.single().frame.copy(imageKey = "ABCDEF")),
            ),
        )
        val detail = requireNotNull(describeInvalidDeletionProof(badKey))
        assertTrue("el detalle debe citar la clave: $detail", detail.contains("imageKey"))
        assertTrue(!AngleImageKey.isCanonical(badKey.frames.single().frame.imageKey))

        // La comparación exacta sí detecta un cambio que la huella semántica del alta ignoraría.
        val changedKey = base.copy(
            frames = listOf(base.frames.single().copy(frame = base.frames.single().frame.copy(imageKey = nextImageKey()))),
        )
        assertNull(describeInvalidDeletionProof(changedKey))
        val difference = requireNotNull(describeExactAggregateDifference(stored = base, proof = changedKey))
        assertTrue("la diferencia debe citar imageKey: $difference", difference.contains("imageKey"))
    }
}
