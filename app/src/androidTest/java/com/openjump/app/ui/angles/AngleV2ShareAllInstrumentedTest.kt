package com.openjump.app.ui.angles

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.TransactionTooLargeException
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
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
import com.openjump.app.data.angles.PrivateAngleImageStore
import com.openjump.app.data.angles.sha256HexOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * «Compartir todas las imágenes» con piezas reales de Android: Room en memoria, almacén privado
 * aislado, `FileProvider` real y el `ViewModel` de producción.
 *
 * Lo que se demuestra:
 * - El lote se prepara desde el snapshot **cargado** y sólo devuelve `Ready` cuando **todas** las
 *   imágenes están verificadas; la lista conserva el orden del contenido y una entrada por fotograma,
 *   incluso cuando dos fotogramas comparten bytes y, por tanto, el mismo URI de copia.
 * - Cada URI se abre por `ContentResolver` y sus bytes son exactamente el PNG guardado del
 *   fotograma: no hay vídeo, ni rutas del almacén privado, ni árboles.
 * - El Intent construido por producción es `ACTION_SEND_MULTIPLE`/`image/png`, con la **misma**
 *   secuencia en `EXTRA_STREAM` y en `ClipData`, y **sólo** permiso de lectura (sin prefijo ni
 *   escritura).
 * - Un fotograma corrupto invalida el lote completo: no hay `Ready`, así que no se construye ni se
 *   lanza ningún Intent (cero imágenes entregadas).
 * - Dos toques simultáneos no preparan dos lotes: el segundo se ignora y el indicador de ocupado
 *   termina limpio.
 * - El lanzador tipado mide el `Parcel` **real** del selector final antes de despachar: si supera
 *   `MAX_INTENT_BYTES` devuelve `TooLarge` y no llama a `startActivity` (verificado con un contexto
 *   que sólo registra el despacho); falta de receptor, permiso denegado y fallo de transporte se
 *   distinguen sin propagar la excepción.
 *
 * Aislamiento: base Room en memoria, raíz de imágenes con UUID bajo `cacheDir` y copias en el
 * directorio de compartición de la app. Nunca se abre la base de la app, el vídeo ni `AppNav`, y no
 * se borra el directorio global de compartición: sólo la raíz de imágenes propia del fixture.
 *
 * Los nombres de los `@Test` deben ser `camelCase` sin espacios: con `minSdk = 26` las lambdas del
 * cuerpo generan clases anónimas cuyo `SimpleName` hereda el nombre del método y un `SimpleName` con
 * espacios produce un DEX < 040 que `dexBuilderDebugAndroidTest` rechaza.
 */
@RunWith(AndroidJUnit4::class)
class AngleV2ShareAllInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var imageRoot: File
    private lateinit var database: JumpDatabase
    private lateinit var dao: AngleAnalysisV2Dao
    private lateinit var imageStore: PrivateAngleImageStore
    private lateinit var stager: AngleV2FrameShareStager
    private lateinit var viewModel: AngleV2SavedDetailViewModel
    private var athleteId: Long = 0L

    @Before
    fun setUp() {
        imageRoot = File(context.cacheDir, "$IMAGE_ROOT_PREFIX${UUID.randomUUID()}").apply { mkdirs() }
        database = Room.inMemoryDatabaseBuilder(context, JumpDatabase::class.java).build()
        dao = database.angleAnalysisV2Dao()
        athleteId = runBlocking {
            database.athleteDao().insert(AthleteEntity(displayName = "Atleta compartir todas"))
        }
        imageStore = PrivateAngleImageStore(
            rootDirectory = imageRoot,
            fileIo = AndroidAngleImageFileIo(),
        )
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
        // Sólo la raíz de imágenes propia: las copias compartidas pueden estar siendo leídas y no se
        // barre el directorio global de compartición.
        if (::imageRoot.isInitialized) imageRoot.deleteRecursively()
    }

    @Test
    fun elLoteCompletoSeEntregaEnOrdenYElIntentLlevaLaMismaSecuenciaDeSoloLectura() {
        // Dos fotogramas comparten bytes (mismo URI de copia) y el tercero es distinto.
        val shared = syntheticPngBytes(Color.rgb(24, 28, 32))
        val other = syntheticPngBytes(Color.rgb(64, 12, 32))
        val saved = saveRoot(listOf(shared, shared, other))
        val frames = loadFrames(saved.rootId)
        assertEquals(3, frames.size)

        val ready = runBlocking { viewModel.shareAll() }
        assertTrue("El lote completo debía estar listo: $ready", ready is AngleV2ShareAllOutcome.Ready)
        val uris = (ready as AngleV2ShareAllOutcome.Ready).uris
        assertEquals("Una entrada por fotograma, sin deduplicar.", 3, uris.size)
        assertEquals(
            "Dos fotogramas con los mismos bytes publican la misma copia verificada.",
            uris[0],
            uris[1],
        )
        assertFalse("El tercer fotograma tiene otro contenido y otra copia.", uris[2] == uris[0])
        assertEquals(ContentResolver.SCHEME_CONTENT, uris[0].scheme)
        assertEquals("${context.packageName}.fileprovider", uris[0].authority)
        // El primer segmento de la URI es el `name` de la raíz declarada en `@xml/file_paths`
        // (`angle_frame_shares`), no el directorio físico que esa raíz expone
        // (`shared-angle-frames`, que es lo que vale `AngleV2FrameShare.DIRECTORY_NAME`).
        assertTrue(
            "Las copias compartidas cuelgan de la raíz de compartición declarada en @xml/file_paths.",
            uris.all { it.path?.startsWith("/angle_frame_shares/") == true },
        )
        assertTrue("El token describe la raíz cargada.", viewModel.isShareTokenCurrent(ready.token))

        // Cada URI sirve exactamente el PNG verificado de su fotograma.
        val expectedBytes = listOf(shared, shared, other)
        uris.forEachIndexed { index, uri ->
            val bytes = requireNotNull(context.contentResolver.openInputStream(uri)) {
                "El FileProvider debía servir $uri"
            }.use { it.readBytes() }
            assertArrayEquals("Los bytes de $uri deben ser el PNG del fotograma $index.", expectedBytes[index], bytes)
            assertEquals(expectedBytes[index].size.toLong(), frames[index].imageByteCount)
            assertEquals(frames[index].imageSha256, sha256HexOf(bytes))
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
                assertEquals(PNG_WIDTH, bitmap.width)
                assertEquals(PNG_HEIGHT, bitmap.height)
            } finally {
                bitmap.recycle()
            }
        }

        // Intent real que construye producción (sin lanzarlo: el entorno no tiene receptor).
        val chooser = AngleV2ShareIntents.chooser(uris, CHOOSER_TITLE, newTask = false)
        val inner = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)
        assertNotNull("El selector debe envolver el Intent de envío.", inner)
        val send = requireNotNull(inner)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
        assertEquals("image/png", send.type)
        val stream = IntentCompat.getParcelableArrayListExtra(send, Intent.EXTRA_STREAM, Uri::class.java)
        assertEquals("EXTRA_STREAM lleva la secuencia completa y en orden.", uris, stream)
        val clip = requireNotNull(send.clipData) { "El envío múltiple debe llevar ClipData." }
        assertEquals("El ClipData repite la secuencia completa.", uris.size, clip.itemCount)
        assertEquals(
            "El orden del ClipData es el mismo que el de EXTRA_STREAM.",
            uris,
            (0 until clip.itemCount).map { clip.getItemAt(it).uri },
        )
        assertTrue(
            "Sólo se concede lectura de las URI: no hay permiso de prefijo ni de escritura.",
            send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 &&
                send.flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION == 0 &&
                send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0,
        )
        // No hay vídeo ni rutas del almacén privado entre lo entregado.
        assertTrue(
            "Ninguna URI entregada puede apuntar al almacén privado.",
            uris.none { it.path?.contains(AngleImageKeySegment) == true },
        )
    }

    @Test
    fun unFotogramaCorruptoInvalidaElLoteYNoSeConstruyeNingunIntent() {
        val first = syntheticPngBytes(Color.rgb(10, 20, 30))
        val second = syntheticPngBytes(Color.rgb(40, 50, 60))
        val saved = saveRoot(listOf(first, second))
        val frames = loadFrames(saved.rootId)
        assertEquals(2, frames.size)

        // Se troca el PNG del ÚLTIMO fotograma después de cargar el detalle: la fila no cambia.
        val lastFrame = frames.last()
        val stored = storedFileFor(lastFrame.imageKey)
        assertNotNull("El almacén debía contener el PNG del último fotograma.", stored)
        FileOutputStream(requireNotNull(stored), true).use { it.write(0x00) }

        val outcome = runBlocking { viewModel.shareAll() }
        assertTrue(
            "Un fotograma corrupto no puede producir un conjunto completo: $outcome",
            outcome is AngleV2ShareAllOutcome.Failed,
        )
        assertEquals(
            AngleV2ShareAllFailure.CORRUPTED,
            (outcome as AngleV2ShareAllOutcome.Failed).failure,
        )
        // Ningún desenlace `Failed` lleva URI: no hay Intent que construir ni lanzar.
        assertFalse("El indicador de ocupado debe quedar limpio.", viewModel.shareAllBusy.value)
        assertTrue(
            "El detalle sigue mostrando el fotograma corrupto como no disponible, no como imagen válida.",
            runBlocking { viewModel.image(lastFrame) } == AngleV2SavedImage.Corrupted,
        )
    }

    @Test
    fun unaRaizDeUnSoloFotogramaSeComparteComoEnvioMultipleDeUnaEntrada() {
        val only = syntheticPngBytes(Color.rgb(90, 90, 10))
        val saved = saveRoot(listOf(only))
        loadFrames(saved.rootId)

        val ready = runBlocking { viewModel.shareAll() }
        assertTrue("Un solo fotograma también es un lote válido: $ready", ready is AngleV2ShareAllOutcome.Ready)
        val uris = (ready as AngleV2ShareAllOutcome.Ready).uris
        assertEquals(1, uris.size)

        val chooser = AngleV2ShareIntents.chooser(uris, CHOOSER_TITLE, newTask = true)
        val send = requireNotNull(IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(
            "La forma del envío no cambia con el número de imágenes.",
            Intent.ACTION_SEND_MULTIPLE,
            send.action,
        )
        assertEquals(
            listOf(uris.single()),
            IntentCompat.getParcelableArrayListExtra(send, Intent.EXTRA_STREAM, Uri::class.java),
        )
        assertEquals(1, requireNotNull(send.clipData).itemCount)
        assertTrue(
            "El selector fuera de una Activity añade NEW_TASK.",
            chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
    }

    @Test
    fun dosToquesSimultaneosNoPreparanDosLotesYElIndicadorQuedaLimpio() {
        val png = syntheticPngBytes(Color.rgb(5, 100, 200))
        val saved = saveRoot(listOf(png, png))
        loadFrames(saved.rootId)

        val outcomes = runBlocking {
            listOf(
                async { viewModel.shareAll() },
                async { viewModel.shareAll() },
            ).awaitAll()
        }
        val ignored = outcomes.count { it is AngleV2ShareAllOutcome.Ignored }
        val ready = outcomes.count {
            it is AngleV2ShareAllOutcome.Ready &&
                (it as AngleV2ShareAllOutcome.Ready).uris.size == 2
        }
        assertEquals("Sólo el primer toque prepara el lote.", 1, ready)
        assertEquals("El segundo toque no inicia otra preparación.", 1, ignored)
        assertFalse("El indicador de ocupado se limpia siempre.", viewModel.shareAllBusy.value)
        assertTrue(
            "La puerta vuelve a abrirse tras terminar.",
            runBlocking { viewModel.shareAll() } is AngleV2ShareAllOutcome.Ready,
        )
    }

    @Test
    fun unDetalleSinCargarNoComparteNada() {
        // Sin `load`, no hay snapshot: el lote no puede inventar contenido.
        val outcome = runBlocking { viewModel.shareAll() }
        assertEquals(
            AngleV2ShareAllOutcome.Failed(AngleV2ShareAllFailure.NOT_LOADED, token = null),
            outcome,
        )
        assertFalse("El indicador de ocupado se limpia también en el fallo.", viewModel.shareAllBusy.value)
    }

    // --- lanzador tipado y presupuesto medido ---------------------------------------------------

    @Test
    fun elLanzadorTipadoNoDespachaNadaSiElParcelMedidoSuperaElPresupuesto() {
        // URI con la forma real de una copia compartida: no se abre el proveedor ni se toca disco,
        // sólo se serializa el selector para medirlo.
        val oversize = (0 until OVERSIZE_URI_COUNT).map { index ->
            Uri.parse(
                "content://${context.packageName}.fileprovider/${AngleV2FrameShare.DIRECTORY_NAME}/" +
                    "f".repeat(32) + "-$index.png",
            )
        }
        val chooser = AngleV2ShareIntents.chooser(oversize, CHOOSER_TITLE, newTask = false)
        val measured = requireNotNull(AngleV2ShareIntents.measuredParcelBytes(chooser)) {
            "El selector del fixture debía poder serializarse para medirse."
        }
        assertTrue(
            "El fixture debe superar el presupuesto medido (medido=$measured, " +
                "presupuesto=${AngleV2ShareAllTransport.MAX_INTENT_BYTES}).",
            measured > AngleV2ShareAllTransport.MAX_INTENT_BYTES,
        )

        val recording = RecordingContext(context)
        val result = AngleV2ShareIntents.launch(recording, oversize, CHOOSER_TITLE)
        assertEquals(
            "Un envío que no cabe se informa como problema de transporte, no como falta de apps.",
            AngleV2ShareLaunchResult.TooLarge,
            result,
        )
        assertEquals(
            "Si el envío no cabe, no se llama a startActivity.",
            0,
            recording.dispatchCount,
        )
    }

    @Test
    fun elLanzadorTipadoDistingueDespachoFaltaDeReceptorPermisoYTransporte() {
        val uri = Uri.parse(
            "content://${context.packageName}.fileprovider/${AngleV2FrameShare.DIRECTORY_NAME}/a.png",
        )
        val uris = listOf(uri)
        val measured = requireNotNull(
            AngleV2ShareIntents.measuredParcelBytes(
                AngleV2ShareIntents.chooser(uris, CHOOSER_TITLE, newTask = false),
            ),
        )
        assertTrue("Un lote válido cabe en el presupuesto medido ($measured).", measured <= AngleV2ShareAllTransport.MAX_INTENT_BYTES)

        val launched = RecordingContext(context)
        assertEquals(AngleV2ShareLaunchResult.Launched, AngleV2ShareIntents.launch(launched, uris, CHOOSER_TITLE))
        assertEquals("Con lote válido se despacha exactamente una vez.", 1, launched.dispatchCount)

        val noReceiver = RecordingContext(context, ActivityNotFoundException("sin receptor"))
        assertEquals(
            AngleV2ShareLaunchResult.NoReceiver,
            AngleV2ShareIntents.launch(noReceiver, uris, CHOOSER_TITLE),
        )
        assertEquals("Sin receptor no se reintenta ni se degrada el envío.", 1, noReceiver.dispatchCount)

        val denied = RecordingContext(context, SecurityException("permiso denegado"))
        assertEquals(
            "Un rechazo de permisos no puede presentarse como falta de apps.",
            AngleV2ShareLaunchResult.Unavailable,
            AngleV2ShareIntents.launch(denied, uris, CHOOSER_TITLE),
        )

        val tooLarge = RecordingContext(context, TransactionTooLargeException("no cabe"))
        assertEquals(
            "Un fallo de tamaño de transacción directo se informa como TooLarge.",
            AngleV2ShareLaunchResult.TooLarge,
            AngleV2ShareIntents.launch(tooLarge, uris, CHOOSER_TITLE),
        )

        val wrapped = RecordingContext(
            context,
            RuntimeException("binder", TransactionTooLargeException("no cabe")),
        )
        assertEquals(
            "Un fallo de tamaño anidado en la causa también se identifica.",
            AngleV2ShareLaunchResult.TooLarge,
            AngleV2ShareIntents.launch(wrapped, uris, CHOOSER_TITLE),
        )

        val generic = RecordingContext(context, IllegalStateException("sistema roto"))
        assertEquals(
            "Un fallo genérico del sistema no cierra la pantalla ni se toma por éxito.",
            AngleV2ShareLaunchResult.Unavailable,
            AngleV2ShareIntents.launch(generic, uris, CHOOSER_TITLE),
        )
    }

    // --- fixture real y utilidades -------------------------------------------------------------

    /** Contexto que sólo registra el despacho: nunca arranca una Activity real. */
    private class RecordingContext(
        base: Context,
        private val failure: Throwable? = null,
    ) : ContextWrapper(base) {

        var dispatchCount: Int = 0
            private set

        override fun startActivity(intent: Intent) {
            dispatchCount++
            failure?.let { throw it }
        }
    }

    private fun saveRoot(pngs: List<ByteArray>): AngleV2SaveResult.Saved {
        val request = AngleV2SaveRequest(
            sessionKey = "$SESSION_PREFIX${UUID.randomUUID()}",
            athleteId = athleteId,
            frames = pngs.mapIndexed { index, bytes ->
                AngleV2SaveFrame(
                    assignedFrameIndex = index,
                    reportedFrameIndex = index,
                    assignedPtsUs = index * 1_000L,
                    reportedPtsUs = index * 1_000L,
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
                )
            },
        )
        val repository = AngleV2SaveRepository(dao = dao, imageStore = imageStore, coordinationMutex = Mutex())
        return when (val saved = runBlocking { repository.save(request) }) {
            is AngleV2SaveResult.Saved -> saved
            else -> throw AssertionError("El fixture no se guardó: $saved")
        }
    }

    private fun loadFrames(rootId: Long): List<AngleV2SavedDetailFrame> {
        viewModel.load(rootId)
        val deadline = System.currentTimeMillis() + DETAIL_TIMEOUT_MILLIS
        var last: AngleV2SavedDetailState = viewModel.state.value
        while (System.currentTimeMillis() < deadline) {
            last = viewModel.state.value
            when (last) {
                is AngleV2SavedDetailState.Loaded -> return last.content.frames
                is AngleV2SavedDetailState.Missing,
                is AngleV2SavedDetailState.Error,
                -> throw AssertionError("La raíz del fixture no se cargó: $last")

                AngleV2SavedDetailState.Loading -> Thread.sleep(POLL_MILLIS)
            }
        }
        throw AssertionError("Timeout esperando el detalle V2; último estado: $last")
    }

    private fun storedFileFor(imageKey: String): File? =
        imageRoot.walkTopDown().firstOrNull { it.isFile && it.path.contains(imageKey) }

    private fun readVerifiedImageBytes(
        imageKey: String,
        integrity: AngleImageIntegrity,
    ): AngleImageReadResult<ByteArray> =
        imageStore.readVerified(imageKey = imageKey, expected = integrity) { input -> input.readBytes() }

    /** PNG sintético de fixture (no es dato de usuario). */
    private fun syntheticPngBytes(background: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(PNG_WIDTH, PNG_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(background)
            val paint = Paint().apply {
                color = Color.rgb(240, 196, 64)
                strokeWidth = 3f
                style = Paint.Style.STROKE
            }
            canvas.drawLine(6f, 6f, 50f, 10f, paint)
            canvas.drawLine(50f, 10f, 38f, 38f, paint)
            canvas.drawLine(38f, 38f, 6f, 6f, paint)
            val output = ByteArrayOutputStream()
            assertTrue("El fixture debe comprimirse a PNG.", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            return output.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val IMAGE_ROOT_PREFIX: String = "angle-share-all-"
        const val SESSION_PREFIX: String = "angle-share-all-session-"
        const val PNG_WIDTH: Int = 64
        const val PNG_HEIGHT: Int = 48
        const val CHOOSER_TITLE: String = "Compartir todas las imágenes"

        /** Suficientes URI con forma real para que el selector serializado supere el presupuesto. */
        const val OVERSIZE_URI_COUNT: Int = 2600

        /** Fragmento de ruta que delata una URI del almacén privado (nunca debe compartirse). */
        const val AngleImageKeySegment: String = "/angle-images/"

        const val DETAIL_TIMEOUT_MILLIS: Long = 20_000L
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
