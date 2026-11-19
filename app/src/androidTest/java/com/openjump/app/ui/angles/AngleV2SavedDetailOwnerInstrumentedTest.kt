package com.openjump.app.ui.angles

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.data.ANGLE_GEOMETRY_VERSION
import com.openjump.app.data.AngleAnalysisV2Dao
import com.openjump.app.data.AngleAnalysisV2FrameEntity
import com.openjump.app.data.AngleAnalysisV2MarkEntity
import com.openjump.app.data.AngleAnalysisV2RootEntity
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.AthleteRepository
import com.openjump.app.data.JumpDatabase
import com.openjump.app.data.angles.AngleImageReadResult
import com.openjump.app.data.angles.AngleImageStoreError
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contexto del dueño en el detalle V2 con el `ViewModel` real y una base Room **en memoria**.
 *
 * Lo que se demuestra (sin UI, sin vídeo, sin base de la app):
 * - el nombre se resuelve por el `athleteId` de la RAÍZ, y también cuando ese atleta está
 *   archivado, porque `AthleteRepository.byId` no filtra por `archivedAt`;
 * - una raíz sin dueño no consulta ningún atleta (el atleta seleccionado global no es el dueño);
 * - un nombre en blanco se proyecta como ausente y un fallo de la consulta conserva el contenido
 *   guardado (raíz, fotogramas y marcas) con nombre `null`;
 * - una lectura antigua no puede publicar por encima de la última raíz pedida: ni al cargar otra
 *   raíz con su dueño suspendido, ni al pedir `load(0)` con la lectura anterior todavía en vuelo.
 *
 * Aislamiento: sólo `Room.inMemoryDatabaseBuilder` sin fichero y sin abrir la base normal. Las
 * esperas son siempre acotadas (latches/fechas) y el `ViewModelStore` propio se limpia al final.
 */
@RunWith(AndroidJUnit4::class)
class AngleV2SavedDetailOwnerInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private lateinit var database: JumpDatabase
    private lateinit var dao: AngleAnalysisV2Dao
    private val stores = mutableListOf<ViewModelStore>()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, JumpDatabase::class.java).build()
        dao = database.angleAnalysisV2Dao()
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { stores.forEach(ViewModelStore::clear) }
        stores.clear()
        if (::database.isInitialized) database.close()
    }

    @Test
    fun elNombreSeResuelvePorElAtletaDuenoAunqueEsteArchivado() {
        val owner = insertAthlete("Dueña Archivada", archived = true)
        insertAthlete("Otro Atleta")
        val rootId = insertRoot(athleteId = owner)
        insertFrame(rootId)

        val queried = Collections.synchronizedList(mutableListOf<Long>())
        val repository = AthleteRepository(database.athleteDao())
        val viewModel = newViewModel { athleteId ->
            queried.add(athleteId)
            repository.byId(athleteId)?.displayName
        }

        viewModel.load(rootId)
        val content = loaded(viewModel).content

        assertEquals(rootId, content.rootId)
        assertEquals("El dueño sale del athleteId de la RAÍZ.", owner, content.athleteId)
        assertEquals("El nombre del dueño archivado sigue siendo legible.", "Dueña Archivada", content.athleteName)
        assertEquals("Sólo se consulta el atleta dueño, no otro.", listOf(owner), queried.toList())
        assertNotNull(
            "El dueño del fixture está archivado; byId debe seguir incluyéndolo.",
            runBlocking { database.athleteDao().byId(owner)?.archivedAt },
        )
    }

    @Test
    fun unaRaizSinDuenoNoConsultaNingunAtleta() {
        insertAthlete("Seleccionado Global")
        val rootId = insertRoot(athleteId = null)
        insertFrame(rootId)

        val queries = AtomicInteger()
        val viewModel = newViewModel {
            queries.incrementAndGet()
            "NO DEBE USARSE"
        }

        viewModel.load(rootId)
        val content = loaded(viewModel).content

        assertNull("Una raíz sin dueño no toma el atleta global.", content.athleteId)
        assertNull(content.athleteName)
        assertEquals("Sin athleteId no hay ninguna consulta de nombre.", 0, queries.get())
    }

    @Test
    fun unFalloDelNombreConservaElContenidoGuardado() {
        val owner = insertAthlete("Dueña Fallo")
        val rootId = insertRoot(athleteId = owner)
        insertFrame(rootId, assignedFrameIndex = 1)

        val viewModel = newViewModel { throw IllegalStateException("consulta de nombre rota") }

        viewModel.load(rootId)
        val content = loaded(viewModel).content

        assertEquals("La raíz y su identidad se conservan.", rootId, content.rootId)
        assertEquals(owner, content.athleteId)
        assertNull("Un fallo de nombre no inventa un nombre.", content.athleteName)
        assertEquals(1, content.frames.size)
        assertEquals(listOf("Rodilla"), content.marks.map { it.name })
        assertEquals(2, content.frames.single().displayFrameNumber)
    }

    @Test
    fun unNombreEnBlancoQuedaAusenteYUnNombreConEspaciosSeRecorta() {
        val owner = insertAthlete("Dueña Blanco")
        val blankRoot = insertRoot(athleteId = owner)
        insertFrame(blankRoot)

        val blankViewModel = newViewModel { "   " }
        blankViewModel.load(blankRoot)
        assertNull("Un nombre en blanco no se publica como texto vacío.", loaded(blankViewModel).content.athleteName)

        val spacedRoot = insertRoot(athleteId = owner)
        insertFrame(spacedRoot, assignedFrameIndex = 1)
        val spacedViewModel = newViewModel { "  Ada  " }
        spacedViewModel.load(spacedRoot)
        assertEquals("Ada", loaded(spacedViewModel).content.athleteName)
    }

    @Test
    fun unaLecturaLentaDeUnaRaizNoPuedePublicarDespuesDeCargarOtra() {
        val ownerA = insertAthlete("Dueña A")
        val ownerB = insertAthlete("Dueño B")
        val rootA = insertRoot(athleteId = ownerA)
        insertFrame(rootA)
        val rootB = insertRoot(athleteId = ownerB)
        insertFrame(rootB)

        val enteredA = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val viewModel = newViewModel { athleteId ->
            if (athleteId == ownerA) {
                enteredA.countDown()
                releaseA.await(AWAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                "Nombre A"
            } else {
                "Nombre B"
            }
        }

        viewModel.load(rootA)
        assertTrue(
            "La lectura del dueño de A debía empezar.",
            enteredA.await(AWAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
        )

        // B se pide y publica mientras A sigue suspendida en la consulta de su dueño.
        viewModel.load(rootB)
        val loadedB = loaded(viewModel)
        assertEquals(rootB, loadedB.content.rootId)
        assertEquals("Nombre B", loadedB.content.athleteName)

        releaseA.countDown()
        settle()
        val finalState = viewModel.state.value
        assertTrue("El estado final debía seguir siendo B: $finalState", finalState is AngleV2SavedDetailState.Loaded)
        finalState as AngleV2SavedDetailState.Loaded
        assertEquals("Una lectura antigua no puede retargetear la raíz.", rootB, finalState.content.rootId)
        assertEquals("Nombre B", finalState.content.athleteName)
    }

    @Test
    fun unaRaizInvalidaImpideQueUnaLecturaAnteriorPublique() {
        val owner = insertAthlete("Dueña Pendiente")
        val rootId = insertRoot(athleteId = owner)
        insertFrame(rootId)

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val viewModel = newViewModel {
            entered.countDown()
            try {
                release.await(AWAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                "Nombre anterior"
            } finally {
                finished.countDown()
            }
        }

        viewModel.load(rootId)
        assertTrue(
            "La lectura del dueño debía empezar.",
            entered.await(AWAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
        )

        viewModel.load(0L)
        assertTrue("load(0) publica Missing.", viewModel.state.value is AngleV2SavedDetailState.Missing)

        release.countDown()
        assertTrue(
            "La lectura anterior debía terminar.",
            finished.await(AWAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
        )

        // Margen acotado para que una publicación tardía (incorrecta) se manifieste antes de
        // declarar el estado estable.
        val deadline = System.currentTimeMillis() + SETTLE_MILLIS
        while (System.currentTimeMillis() < deadline) {
            assertTrue(
                "Una lectura anterior no puede reaparecer tras load(0): ${viewModel.state.value}",
                viewModel.state.value is AngleV2SavedDetailState.Missing,
            )
            Thread.sleep(POLL_MILLIS)
        }
    }

    // --- identidad de presentación: nombre + avatar de la MISMA fila --------------------------

    @Test
    fun identityPairFromRootOwnerIncludingArchived() {
        val owner = insertAthlete("Dueña Archivada", archived = true, avatarKey = "avatar_frog_jump")
        val other = insertAthlete("Otro Atleta", avatarKey = "avatar_frog_ninja")
        assertTrue("el fixture necesita dos atletas distintos", owner != other)
        val rootId = insertRoot(athleteId = owner)
        insertFrame(rootId)

        val queried = Collections.synchronizedList(mutableListOf<Long>())
        val repository = AthleteRepository(database.athleteDao())
        val viewModel = newIdentityViewModel { athleteId ->
            queried.add(athleteId)
            repository.byId(athleteId)?.let { athlete ->
                AngleV2SavedAthleteIdentity(athlete.displayName, athlete.avatarKey)
            }
        }

        viewModel.load(rootId)
        val content = loaded(viewModel).content

        assertEquals(
            "nombre y avatar salen del atleta DUEÑO de la raíz, aunque esté archivado",
            "Dueña Archivada",
            content.athleteName,
        )
        assertEquals("el avatar es el del MISMO dueño", "avatar_frog_jump", content.athleteAvatarKey)
        assertEquals("UNA sola consulta: ni dos lecturas ni el atleta global", listOf(owner), queried.toList())
        assertTrue(
            "el avatar de un atleta que NO es el dueño no puede publicarse",
            content.athleteAvatarKey != "avatar_frog_ninja",
        )

        // Con el lector nuevo configurado, un `null` deja AMBOS campos vacíos y NO recurre al
        // callback heredado de nombre (no se mezcla nombre de una fuente con avatar de otra).
        val nullRoot = insertRoot(athleteId = owner)
        insertFrame(nullRoot, assignedFrameIndex = 1)
        val fallbackGuard = AtomicInteger()
        val nullViewModel = newIdentityViewModel(
            loadAthleteName = {
                fallbackGuard.incrementAndGet()
                "NO DEBE USARSE"
            },
            loadAthleteIdentity = { null },
        )
        nullViewModel.load(nullRoot)
        val nullContent = loaded(nullViewModel).content
        assertNull("un lector de identidad nulo no inventa nombre", nullContent.athleteName)
        assertNull("ni toma el avatar de otra fuente", nullContent.athleteAvatarKey)
        assertEquals("el callback heredado no se usa si el lector nuevo está configurado", 0, fallbackGuard.get())
    }

    @Test
    fun lateOwnerIdentityCannotRetargetNewRoot() {
        val ownerA = insertAthlete("Dueña A", avatarKey = "avatar_frog_jump")
        val ownerB = insertAthlete("Dueño B", avatarKey = "avatar_frog_ninja")
        val rootA = insertRoot(athleteId = ownerA)
        insertFrame(rootA)
        val rootB = insertRoot(athleteId = ownerB)
        insertFrame(rootB)

        val enteredA = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val viewModel = newIdentityViewModel { athleteId ->
            if (athleteId == ownerA) {
                enteredA.countDown()
                releaseA.await(AWAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                AngleV2SavedAthleteIdentity("Nombre A", "avatar_frog_jump")
            } else {
                AngleV2SavedAthleteIdentity("Nombre B", "avatar_frog_ninja")
            }
        }

        viewModel.load(rootA)
        assertTrue(
            "la lectura del dueño de A debía empezar",
            enteredA.await(AWAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
        )

        // B se pinta con SU nombre y SU avatar mientras A sigue suspendida.
        viewModel.load(rootB)
        val loadedB = loaded(viewModel).content
        assertEquals(rootB, loadedB.rootId)
        assertEquals("Nombre B", loadedB.athleteName)
        assertEquals("avatar_frog_ninja", loadedB.athleteAvatarKey)

        // La identidad TARDÍA de A no puede reescribir ni el nombre ni el avatar de B.
        releaseA.countDown()
        settle()
        val finalState = viewModel.state.value
        assertTrue("el estado final debía seguir siendo B: $finalState", finalState is AngleV2SavedDetailState.Loaded)
        finalState as AngleV2SavedDetailState.Loaded
        assertEquals("una lectura antigua no puede retargetear la raíz", rootB, finalState.content.rootId)
        assertEquals("Nombre B", finalState.content.athleteName)
        assertEquals("avatar_frog_ninja", finalState.content.athleteAvatarKey)
    }

    // --- aislamiento y helpers -------------------------------------------------------------------

    private fun newViewModel(loadAthleteName: suspend (Long) -> String?): AngleV2SavedDetailViewModel {
        val viewModel = AngleV2SavedDetailViewModel(
            dao = dao,
            readImageBytes = { imageKey, _ ->
                AngleImageReadResult.Failed(AngleImageStoreError.Absent(imageKey))
            },
            prepareShare = { _, _ -> AngleV2FrameShareResult.Unavailable },
            loadAthleteName = loadAthleteName,
        )
        // `ViewModelStore` propio: al limpiarlo se cancelan los `viewModelScope` de cada prueba.
        val store = ViewModelStore()
        store.put("owner-${UUID.randomUUID()}", viewModel)
        stores += store
        return viewModel
    }

    /** VM con el lector NUEVO de identidad (nombre + avatar en una consulta); el heredado es opcional. */
    private fun newIdentityViewModel(
        loadAthleteName: suspend (Long) -> String? = { null },
        loadAthleteIdentity: suspend (Long) -> AngleV2SavedAthleteIdentity?,
    ): AngleV2SavedDetailViewModel {
        val viewModel = AngleV2SavedDetailViewModel(
            dao = dao,
            readImageBytes = { imageKey, _ ->
                AngleImageReadResult.Failed(AngleImageStoreError.Absent(imageKey))
            },
            prepareShare = { _, _ -> AngleV2FrameShareResult.Unavailable },
            loadAthleteName = loadAthleteName,
            loadAthleteIdentity = loadAthleteIdentity,
        )
        val store = ViewModelStore()
        store.put("owner-identity-${UUID.randomUUID()}", viewModel)
        stores += store
        return viewModel
    }

    private fun insertAthlete(
        name: String,
        archived: Boolean = false,
        avatarKey: String? = null,
    ): Long = runBlocking {
        database.athleteDao().insert(
            AthleteEntity(
                displayName = name,
                archivedAt = if (archived) FIXTURE_TIME_MILLIS else null,
                avatarKey = avatarKey,
            ),
        )
    }

    private fun insertRoot(athleteId: Long?): Long = runBlocking {
        dao.insertRoot(
            AngleAnalysisV2RootEntity(
                sessionKey = "owner-test-${UUID.randomUUID()}",
                athleteId = athleteId,
                createdAt = FIXTURE_TIME_MILLIS,
                updatedAt = FIXTURE_TIME_MILLIS,
            ),
        )
    }

    private fun insertFrame(rootId: Long, assignedFrameIndex: Int = 0): Long = runBlocking {
        val frameId = dao.insertFrame(
            AngleAnalysisV2FrameEntity(
                rootId = rootId,
                ordinal = 0,
                assignedFrameIndex = assignedFrameIndex,
                reportedFrameIndex = null,
                assignedPtsUs = 0L,
                encodedWidth = 64,
                encodedHeight = 48,
                presentationWidth = 64,
                presentationHeight = 48,
                rotationDegrees = 0,
                geometryVersion = ANGLE_GEOMETRY_VERSION,
                imageKey = UUID.randomUUID().toString().replace("-", ""),
                imageWidth = 64,
                imageHeight = 48,
                imageByteCount = 128L,
                imageSha256 = "a".repeat(64),
                imageMimeType = "image/png",
            ),
        )
        dao.insertMarks(
            listOf(
                AngleAnalysisV2MarkEntity(
                    frameId = frameId,
                    ordinal = 0,
                    name = "Rodilla",
                    colorArgb = 0xFF33CC66.toInt(),
                    pointAX = 10.0,
                    pointAY = 10.0,
                    pointBX = 20.0,
                    pointBY = 10.0,
                    pointCX = 10.0,
                    pointCY = 20.0,
                ),
            ),
        )
        frameId
    }

    private fun loaded(viewModel: AngleV2SavedDetailViewModel): AngleV2SavedDetailState.Loaded {
        val state = awaitState(viewModel) { it is AngleV2SavedDetailState.Loaded }
        return state as AngleV2SavedDetailState.Loaded
    }

    private fun awaitState(
        viewModel: AngleV2SavedDetailViewModel,
        predicate: (AngleV2SavedDetailState) -> Boolean,
    ): AngleV2SavedDetailState {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS
        var last = viewModel.state.value
        while (System.currentTimeMillis() < deadline) {
            last = viewModel.state.value
            if (predicate(last)) return last
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("Timeout esperando el estado del detalle; último: $last")
    }

    /** Espera acotada a que se asiente una publicación que no debería ocurrir. */
    private fun settle() {
        Thread.sleep(SETTLE_MILLIS)
    }

    private companion object {
        const val AWAIT_TIMEOUT_MILLIS = 10_000L
        const val SETTLE_MILLIS = 750L
        const val POLL_MILLIS = 20L
        const val FIXTURE_TIME_MILLIS = 1_700_000_000_000L
    }
}
