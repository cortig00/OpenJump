package com.openjump.app.ui.angles

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.data.ANGLE_GEOMETRY_VERSION
import com.openjump.app.data.AngleAnalysisV2Dao
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.JumpDatabase
import com.openjump.app.data.angles.AndroidAngleImageFileIo
import com.openjump.app.data.angles.AngleImageIntegrity
import com.openjump.app.data.angles.AngleImageReadResult
import com.openjump.app.data.angles.AngleV2SaveFrame
import com.openjump.app.data.angles.AngleV2SaveMark
import com.openjump.app.data.angles.AngleV2SaveRepository
import com.openjump.app.data.angles.AngleV2SaveRequest
import com.openjump.app.data.angles.AngleV2SaveResult
import com.openjump.app.data.angles.PngPixelSize
import com.openjump.app.data.angles.PrivateAngleImageStore
import com.openjump.app.data.angles.pngPixelSize
import com.openjump.app.data.angles.sha256HexOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lectura del detalle V2 y preparación del share verificado, **sin vídeo y sin la base del usuario**.
 *
 * Lo que este test demuestra:
 *
 * - El fixture se persiste con las piezas reales de guardado ([AngleV2SaveRepository] +
 *   [PrivateAngleImageStore] + transacción V2 del DAO) sobre una base Room **en memoria** y una raíz
 *   de imágenes **con UUID** dentro de `cacheDir`. El PNG del fixture es sintético: este test no abre
 *   vídeo, ni fuente congelada, ni player, ni el editor; la raíz se identifica solo por su id de tabla.
 * - La lectura del detalle usa el `ViewModel` real ([AngleV2SavedDetailViewModel.load]) con la
 *   consulta transaccional real (`aggregateByRootId`), comprueba root id, dueño, fotograma y marcas
 *   proyectadas, y entrega la imagen por la lectura **verificada** del almacén
 *   ([PrivateAngleImageStore.readVerified]): la cabecera y el bitmap cuadran con la fila.
 * - La preparación de la copia para compartir usa el componente real ([AngleV2FrameShareStager]) con
 *   el `FileProvider` real de la app (`@xml/file_paths` sólo declara `cacheDir/shared-angle-frames/`):
 *   el resultado `Ready` se abre por `ContentResolver`, sus bytes son **idénticos** al PNG guardado y
 *   su tamaño/SHA-256/dimensiones coinciden con la fila. La copia queda fuera del almacén privado.
 * - Una integridad errónea **no se comparte**: un SHA-256 o un tamaño declarados que no coinciden con
 *   el fichero se rechazan (`Corrupted`, sin copia nueva), y un PNG trocado después de cargar el
 *   detalle tampoco se convierte en copia compartible ni en imagen válida del detalle.
 *
 * Aislamiento: in-memory Room, raíz de imágenes y copias propias; nunca se abre la base de la app, el
 * vídeo ni `AppNav`. Si el test creó la copia de compartición, la borra al terminar; un fichero
 * preexistente con el mismo contenido no se toca.
 *
 * Los nombres de los `@Test` deben ser `camelCase` sin espacios: las lambdas del cuerpo generan
 * clases anónimas cuyo `SimpleName` hereda el nombre del método, y con `minSdk = 26` el DEX
 * resultante es < 040, que prohíbe espacios en `SimpleName` (fallo de `dexBuilderDebugAndroidTest`).
 */
@RunWith(AndroidJUnit4::class)
class AngleMultiFrameFlowDetailShareInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var imageRoot: File
    private lateinit var database: JumpDatabase
    private lateinit var dao: AngleAnalysisV2Dao
    private lateinit var imageStore: PrivateAngleImageStore
    private lateinit var stager: AngleV2FrameShareStager
    private lateinit var viewModel: AngleV2SavedDetailViewModel
    private lateinit var saved: AngleV2SaveResult.Saved
    private lateinit var pngBytes: ByteArray
    private var athleteId: Long = 0L

    @Before
    fun setUp() {
        imageRoot = File(context.cacheDir, "$IMAGE_ROOT_PREFIX${UUID.randomUUID()}").apply { mkdirs() }
        database = Room.inMemoryDatabaseBuilder(context, JumpDatabase::class.java).build()
        dao = database.angleAnalysisV2Dao()
        athleteId = runBlocking {
            database.athleteDao().insert(AthleteEntity(displayName = "Atleta detalle V2"))
        }
        imageStore = PrivateAngleImageStore(
            rootDirectory = imageRoot,
            fileIo = AndroidAngleImageFileIo(),
        )
        val repository = AngleV2SaveRepository(
            dao = dao,
            imageStore = imageStore,
            coordinationMutex = Mutex(),
        )
        pngBytes = syntheticPngBytes()
        saved = when (val result = runBlocking { repository.save(fixtureRequest(pngBytes)) }) {
            is AngleV2SaveResult.Saved -> result
            else -> throw AssertionError("El fixture del detalle no se guardó: $result")
        }
        assertEquals("El fixture debe publicar un PNG y su clave.", 1, saved.imageKeys.size)

        stager = AngleV2FrameShareStager(
            cacheRoot = context.cacheDir,
            readVerifiedBytes = ::readVerifiedImageBytes,
            uriForFile = { file ->
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            },
        )
        viewModel = AngleV2SavedDetailViewModel(
            dao = dao,
            readImageBytes = ::readVerifiedImageBytes,
            prepareShare = stager::publish,
        )
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::imageRoot.isInitialized) imageRoot.deleteRecursively()
    }

    @Test
    fun elDetalleSeLeePorRootIdSinVideoYLaCopiaCompartidaEsElPngVerificado() {
        val frame = loadSingleFrame()
        assertEquals("image/png", frame.imageMimeType)
        assertEquals(PNG_WIDTH, frame.imageWidth)
        assertEquals(PNG_HEIGHT, frame.imageHeight)
        assertEquals(pngBytes.size.toLong(), frame.imageByteCount)
        assertEquals("La fila declara el SHA-256 real del PNG guardado.", sha256HexOf(pngBytes), frame.imageSha256)

        // Lectura verificada del almacén por el ViewModel real del detalle.
        val image = runBlocking { viewModel.image(frame) }
        assertTrue(
            "La imagen verificada del detalle debía estar disponible: $image",
            image is AngleV2SavedImage.Available,
        )
        val bitmap = (image as AngleV2SavedImage.Available).bitmap
        try {
            assertEquals("El bitmap decodificado debe medir lo declarado.", PNG_WIDTH, bitmap.width)
            assertEquals(PNG_HEIGHT, bitmap.height)
        } finally {
            bitmap.recycle()
        }

        // Preparación real de la copia para compartir (FileProvider real, raíz de caché de la app).
        val target = shareTargetFor(frame.imageSha256)
        val existedBefore = target.exists()
        try {
            val result = runBlocking { viewModel.shareImage(frame) }
            assertTrue(
                "La preparación del share debía entregar la copia verificada: $result",
                result is AngleV2FrameShareResult.Ready,
            )
            val uri = (result as AngleV2FrameShareResult.Ready).uri
            assertEquals(ContentResolver.SCHEME_CONTENT, uri.scheme)
            assertEquals("${context.packageName}.fileprovider", uri.authority)
            assertEquals("El proveedor sirve el PNG staging con su nombre de contenido.", target.name, uri.lastPathSegment)
            assertTrue("La copia publicada debe ser un fichero real.", target.isFile)
            assertFalse(
                "La copia compartida no puede estar dentro del almacén privado.",
                target.canonicalPath.startsWith(imageRoot.canonicalPath + File.separator),
            )

            val sharedBytes = requireNotNull(context.contentResolver.openInputStream(uri)) {
                "El FileProvider debía servir el PNG compartido de $uri."
            }.use { it.readBytes() }
            assertArrayEquals(
                "Los bytes compartidos deben ser exactamente el PNG guardado.",
                pngBytes,
                sharedBytes,
            )
            assertEquals(frame.imageByteCount, sharedBytes.size.toLong())
            assertEquals(frame.imageSha256, sha256HexOf(sharedBytes))
            assertEquals(PngPixelSize(PNG_WIDTH, PNG_HEIGHT), pngPixelSize(sharedBytes))
            assertArrayEquals("La copia en disco coincide con lo servido por el proveedor.", sharedBytes, target.readBytes())

            val decoded = requireNotNull(
                BitmapFactory.decodeByteArray(
                    sharedBytes,
                    0,
                    sharedBytes.size,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
                ),
            ) { "El PNG compartido debe decodificarse." }
            try {
                assertEquals(PNG_WIDTH, decoded.width)
                assertEquals(PNG_HEIGHT, decoded.height)
            } finally {
                decoded.recycle()
            }
        } finally {
            // Sólo se retira la copia que este test creó; una preexistente no se toca.
            if (!existedBefore) target.delete()
        }
    }

    @Test
    fun unaIntegridadErroneaNoSeComparteNiPublicaCopia() {
        val frame = loadSingleFrame()
        val shareDir = File(context.cacheDir, AngleV2FrameShare.DIRECTORY_NAME)
        val sharesBefore = fileCount(shareDir)

        val wrongDigest = "0".repeat(64)
        assertEquals(
            "Un SHA-256 distinto del fichero no puede compartirse.",
            AngleV2FrameShareResult.Corrupted,
            stager.publish(frame.imageKey, AngleImageIntegrity(frame.imageByteCount, wrongDigest)),
        )
        assertFalse(
            "No debe publicarse una copia para una integridad errónea.",
            File(shareDir, AngleV2FrameShare.fileNameFor(wrongDigest)).exists(),
        )

        assertEquals(
            "Un tamaño declarado distinto del fichero no puede compartirse.",
            AngleV2FrameShareResult.Corrupted,
            stager.publish(
                frame.imageKey,
                AngleImageIntegrity(frame.imageByteCount + 1L, frame.imageSha256),
            ),
        )
        assertEquals(
            "Los rechazos de integridad no publican ninguna copia.",
            sharesBefore,
            fileCount(shareDir),
        )

        // PNG trocado después de cargar el detalle: la fila sigue declarando la integridad original.
        val storedFiles = imageRoot.walkTopDown().filter { it.isFile }.toList()
        assertEquals(
            "El almacén aislado debía contener un único PNG del fixture.",
            1,
            storedFiles.size,
        )
        FileOutputStream(storedFiles.single(), true).use { it.write(0x00) }

        assertEquals(
            "Un PNG trocado no puede convertirse en copia compartible.",
            AngleV2FrameShareResult.Corrupted,
            runBlocking { viewModel.shareImage(frame) },
        )
        assertEquals(
            "El intento con el PNG trocado no publica nada.",
            sharesBefore,
            fileCount(shareDir),
        )
        assertEquals(
            "El detalle no puede presentar un PNG trocado como imagen válida.",
            AngleV2SavedImage.Corrupted,
            runBlocking { viewModel.image(frame) },
        )
    }

    // --- fixture real y utilidades de espera ----------------------------------------------------

    /**
     * Carga la única raíz guardada con la identidad de tabla real y devuelve su fotograma.
     *
     * El `ViewModel` no abre vídeo, player ni índice de frames: sólo consulta la raíz y proyecta sus
     * filas. La espera es acotada (no un `waitForIdle` de Compose, que aquí no aplica).
     */
    private fun loadSingleFrame(): AngleV2SavedDetailFrame {
        viewModel.load(saved.rootId)
        val content = awaitLoadedContent()
        assertEquals(saved.rootId, content.rootId)
        assertEquals("El detalle conserva el dueño real de la fila.", athleteId, requireNotNull(content.athleteId))
        assertEquals(1, content.frames.size)
        assertEquals(1, content.markCount)
        val frame = content.frames.single()
        val mark = frame.marks.single()
        assertEquals(FIXTURE_MARK.name, mark.name)
        assertTrue("La marca del fixture es visible.", mark.visible)
        assertEquals(FIXTURE_MARK.pointAX, mark.pointA.x, 0.0)
        assertEquals(FIXTURE_MARK.pointAY, mark.pointA.y, 0.0)
        assertEquals(FIXTURE_MARK.pointBX, mark.pointB.x, 0.0)
        assertEquals(FIXTURE_MARK.pointBY, mark.pointB.y, 0.0)
        assertEquals(FIXTURE_MARK.pointCX, mark.pointC.x, 0.0)
        assertEquals(FIXTURE_MARK.pointCY, mark.pointC.y, 0.0)
        assertTrue("El ángulo guardado debe ser medible.", mark.degrees != null)
        return frame
    }

    /** Espera acotada al estado `Loaded` del ViewModel; un estado terminal distinto falla con su valor. */
    private fun awaitLoadedContent(
        timeoutMillis: Long = DETAIL_TIMEOUT_MILLIS,
    ): AngleV2SavedDetailContent {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var last: AngleV2SavedDetailState = viewModel.state.value
        while (System.currentTimeMillis() < deadline) {
            last = viewModel.state.value
            when (last) {
                is AngleV2SavedDetailState.Loaded ->
                    return (last as AngleV2SavedDetailState.Loaded).content

                is AngleV2SavedDetailState.Missing,
                is AngleV2SavedDetailState.Error,
                -> throw AssertionError("La lectura del detalle no cargó la raíz: $last")

                AngleV2SavedDetailState.Loading -> Thread.sleep(POLL_MILLIS)
            }
        }
        throw AssertionError(
            "Timeout esperando la lectura del detalle V2; último estado: $last",
        )
    }

    /** Lectura verificada real del almacén aislado (misma costura que usa la app). */
    private fun readVerifiedImageBytes(
        imageKey: String,
        integrity: AngleImageIntegrity,
    ): AngleImageReadResult<ByteArray> =
        imageStore.readVerified(imageKey = imageKey, expected = integrity) { input -> input.readBytes() }

    /** Copia que publicaría el stager para [sha256Hex] (nombre derivado del SHA declarado). */
    private fun shareTargetFor(sha256Hex: String): File =
        File(File(context.cacheDir, AngleV2FrameShare.DIRECTORY_NAME), AngleV2FrameShare.fileNameFor(sha256Hex))

    private fun fileCount(directory: File): Int = directory.listFiles()?.count { it.isFile } ?: 0

    /** Petición de guardado real del fixture: un fotograma con un ángulo y un PNG sintético. */
    private fun fixtureRequest(bytes: ByteArray): AngleV2SaveRequest = AngleV2SaveRequest(
        // Sin vídeo: la sesión del fixture se identifica por un UUID, no por una captura.
        sessionKey = "$SESSION_PREFIX${UUID.randomUUID()}",
        athleteId = athleteId,
        frames = listOf(
            AngleV2SaveFrame(
                assignedFrameIndex = 0,
                reportedFrameIndex = 0,
                assignedPtsUs = 0L,
                reportedPtsUs = 0L,
                encodedWidth = PNG_WIDTH,
                encodedHeight = PNG_HEIGHT,
                presentationWidth = PNG_WIDTH,
                presentationHeight = PNG_HEIGHT,
                rotationDegrees = 0,
                geometryVersion = ANGLE_GEOMETRY_VERSION,
                pngBytes = bytes,
                imageWidth = PNG_WIDTH,
                imageHeight = PNG_HEIGHT,
                marks = listOf(FIXTURE_MARK),
            ),
        ),
    )

    /** PNG sintético de fixture (no es dato de usuario) con una anotación dibujada de verdad. */
    private fun syntheticPngBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(PNG_WIDTH, PNG_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(24, 28, 32))
            val paint = Paint().apply {
                color = Color.rgb(240, 196, 64)
                strokeWidth = 3f
                style = Paint.Style.STROKE
            }
            canvas.drawLine(8f, 8f, 56f, 12f, paint)
            canvas.drawLine(56f, 12f, 40f, 40f, paint)
            canvas.drawLine(40f, 40f, 8f, 8f, paint)
            val output = ByteArrayOutputStream()
            assertTrue(
                "El fixture debe comprimirse a PNG.",
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output),
            )
            return output.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val IMAGE_ROOT_PREFIX: String = "angle-detail-share-"
        const val SESSION_PREFIX: String = "angle-detail-share-session-"
        const val PNG_WIDTH: Int = 64
        const val PNG_HEIGHT: Int = 48

        /** Tiempo máximo de una lectura de detalle en memoria (DAO + proyección). */
        const val DETAIL_TIMEOUT_MILLIS: Long = 15_000L
        const val POLL_MILLIS: Long = 20L

        val FIXTURE_MARK: AngleV2SaveMark = AngleV2SaveMark(
            name = "Rodilla derecha",
            colorArgb = 0xFFF0C440.toInt(),
            visible = true,
            pointAX = 8.0,
            pointAY = 8.0,
            pointBX = 56.0,
            pointBY = 12.0,
            pointCX = 40.0,
            pointCY = 40.0,
        )
    }
}
