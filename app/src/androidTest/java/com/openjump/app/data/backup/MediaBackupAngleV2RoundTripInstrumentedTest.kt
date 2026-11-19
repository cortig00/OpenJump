package com.openjump.app.data.backup

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.data.AngleAnalysisV2FrameEntity
import com.openjump.app.data.AngleAnalysisV2MarkEntity
import com.openjump.app.data.AthleteEntity
import com.openjump.app.data.JumpDatabase
import com.openjump.app.data.angles.AngleImageIntegrity
import com.openjump.app.data.angles.AngleImageReadResult
import com.openjump.app.data.angles.AngleImageVerifyResult
import com.openjump.app.data.angles.AngleV2SaveFrame
import com.openjump.app.data.angles.AngleV2SaveMark
import com.openjump.app.data.angles.AngleV2SaveRepository
import com.openjump.app.data.angles.AngleV2SaveRequest
import com.openjump.app.data.angles.AngleV2SaveResult
import com.openjump.app.data.angles.AndroidAngleImageFileIo
import com.openjump.app.data.angles.PrivateAngleImageStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prueba instrumentada **integral** del ciclo guardar/exportar/restaurar de un analisis de angulos
 * multi-fotograma V2 usando solo piezas reales de produccion.
 *
 * Que se ejecuta de verdad (Android real):
 *  1. [AngleV2SaveRepository] guarda tres fotogramas con marcas y geometria distintas. Los PNG son
 *     reales (codificados con [Bitmap.compress]), no fixtures de bytes; el almacen privado
 *     ([PrivateAngleImageStore] sobre `AndroidAngleImageFileIo`) publica una clave nueva por imagen y
 *     verifica tamano/SHA-256.
 *  2. Una repeticion **identica** de la misma peticion se informa como
 *     [AngleV2SaveResult.AlreadySaved]: no se inserta una segunda fila ni se publica otro fichero.
 *  3. [MediaBackupCoordinator.export] escribe el contenedor ZIP real en un fichero del sandbox
 *     (manifiesto + una entrada `images/<clave>.png` por fotograma).
 *  4. [MediaBackupCoordinator.load] + [MediaBackupCoordinator.restore] restauran ese ZIP en una
 *     **segunda** base Room en memoria y una **segunda** raiz de imagenes, con claves de imagen
 *     nuevas.
 *  5. Se comprueba contra la segunda base: atleta legacy, raiz/sesion, orden de fotogramas, indices
 *     asignados/reportados, PTS, geometria de presentacion, `imageWidth/Height/ByteCount/Sha256/Mime`,
 *     y marcas (orden, nombre, color, visibilidad y las seis coordenadas). Los bytes se leen por la
 *     **clave nueva** con [PrivateAngleImageStore.readVerified] y se comparan byte a byte con los PNG
 *     guardados; ademas se comprueba que la raiz destino **no** contiene las claves del origen.
 *
 * Aislamiento y datos:
 *  - sin base de usuario (dos `Room.inMemoryDatabaseBuilder`), sin video, sin URI, sin SAF, sin UI;
 *  - sandbox unico `cacheDir/openjump-angle-backup-roundtrip-<uuid>` con subdirectorios propios de
 *    imagenes y staging; el UUID lo genera cada instancia (un metodo por instancia en JUnit);
 *  - la limpieza borra **solo** ese arbol, tras comprobar que es hijo directo de `cacheDir` y que
 *    lleva el UUID de esta instancia; nunca toca `filesDir` ni datos reales de la app.
 *
 * No se modifica produccion desde esta clase ni se relaja ninguna comprobacion de produccion.
 */
@RunWith(AndroidJUnit4::class)
class MediaBackupAngleV2RoundTripInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val runId: String = UUID.randomUUID().toString()
    private val sandbox: File = File(context.cacheDir, "$SANDBOX_PREFIX$runId")

    private val sourceDatabase = Room.inMemoryDatabaseBuilder(context, JumpDatabase::class.java).build()
    private val targetDatabase = Room.inMemoryDatabaseBuilder(context, JumpDatabase::class.java).build()

    /** Mismo mutex para guardado, export y restore: es el modelo de coordinacion de la app. */
    private val coordinationMutex = Mutex()

    private lateinit var sourceImageRoot: File
    private lateinit var targetImageRoot: File
    private lateinit var sourceStaging: File
    private lateinit var targetStaging: File
    private lateinit var archive: File
    private lateinit var sentinel: File
    private lateinit var sourceStore: PrivateAngleImageStore
    private lateinit var targetStore: PrivateAngleImageStore
    private lateinit var sourceSaveRepository: AngleV2SaveRepository
    private lateinit var exporter: MediaBackupCoordinator
    private lateinit var importer: MediaBackupCoordinator

    private val sentinelBytes = "openjump-angle-backup-roundtrip-$runId".toByteArray(Charsets.UTF_8)

    @Before
    fun createOwnSandbox() {
        val cacheRoot = context.cacheDir.canonicalFile
        assertFalse("el sandbox de este UUID no debe preexistir", sandbox.exists())
        assertEquals(
            "el sandbox debe ser hijo directo del cache de la app",
            cacheRoot,
            sandbox.canonicalFile.parentFile,
        )
        assertTrue("el sandbox unico debe crearse", sandbox.mkdirs())
        sourceImageRoot = File(sandbox, "source-images").also { assertTrue(it.mkdirs()) }
        targetImageRoot = File(sandbox, "target-images").also { assertTrue(it.mkdirs()) }
        sourceStaging = File(sandbox, "source-staging").also { assertTrue(it.mkdirs()) }
        targetStaging = File(sandbox, "target-staging").also { assertTrue(it.mkdirs()) }
        archive = File(sandbox, "backup.zip")
        sentinel = File(sandbox, "sentinel-own.bin").also { it.writeBytes(sentinelBytes) }

        sourceStore = PrivateAngleImageStore(rootDirectory = sourceImageRoot, fileIo = AndroidAngleImageFileIo())
        targetStore = PrivateAngleImageStore(rootDirectory = targetImageRoot, fileIo = AndroidAngleImageFileIo())
        sourceSaveRepository = AngleV2SaveRepository(
            dao = sourceDatabase.angleAnalysisV2Dao(),
            imageStore = sourceStore,
            coordinationMutex = coordinationMutex,
        )
        exporter = MediaBackupCoordinator(
            dao = sourceDatabase.manualBackupDao(),
            imageStore = sourceStore,
            stagingDirectory = sourceStaging,
            coordinationMutex = coordinationMutex,
        )
        importer = MediaBackupCoordinator(
            dao = targetDatabase.manualBackupDao(),
            imageStore = targetStore,
            stagingDirectory = targetStaging,
            coordinationMutex = coordinationMutex,
        )
    }

    @After
    fun closeEverythingAndDeleteOnlyOwnSandbox() {
        sourceDatabase.close()
        targetDatabase.close()
        val cacheRoot = context.cacheDir.canonicalFile
        val root = sandbox.canonicalFile
        assertEquals("solo se borra un hijo directo del cache", cacheRoot, root.parentFile)
        assertEquals("solo se borra el sandbox de esta instancia", "$SANDBOX_PREFIX$runId", root.name)
        if (root.exists()) deleteOwnTree(root)
        assertFalse("el sandbox debe desaparecer tras la limpieza", root.exists())
    }

    @Test
    fun multiFrameAngleAnalysisSurvivesZipRoundTripIntoSecondDatabaseAndImageRoot() = runBlocking {
        // Dueño real de la raiz V2: el manifiesto exige que el atleta exista en el payload legacy.
        val athleteId = sourceDatabase.athleteDao().insert(
            AthleteEntity(displayName = "angle round trip fixture"),
        )
        val athlete = requireNotNull(sourceDatabase.athleteDao().byId(athleteId)) {
            "el atleta fixture debe existir"
        }
        val sessionKey = "angle-round-trip-$runId"
        val fixtures = fixtures()
        val request = AngleV2SaveRequest(
            sessionKey = sessionKey,
            athleteId = athleteId,
            frames = fixtures.map { it.toSaveFrame() },
        )

        // 1. Guardado V2 real: PNG reales publicados con claves nuevas y filas en una transaccion.
        val saved = requireNotNull(sourceSaveRepository.save(request) as? AngleV2SaveResult.Saved) {
            "el guardado V2 real debe publicar los tres PNG y sus filas"
        }
        assertEquals(3, saved.imageKeys.size)
        assertEquals("cada fotograma publica su propia clave", 3, saved.imageKeys.toSet().size)
        saved.imageKeys.forEach { key ->
            assertTrue("la clave '$key' debe ser canonica", key.matches(CANONICAL_KEY))
        }
        fixtures.forEachIndexed { index, fixture ->
            assertArrayEquals(
                "la imagen original $index debe estar publicada con sus bytes exactos",
                fixture.png,
                readOwnImage(sourceStore, saved.imageKeys[index], expectedIntegrity(fixture)),
            )
        }

        val sourceV2Dao = sourceDatabase.angleAnalysisV2Dao()
        val sourceRoot = requireNotNull(sourceV2Dao.rootBySessionKey(sessionKey)) {
            "la raiz guardada debe poder leerse por sessionKey"
        }
        assertEquals(saved.rootId, sourceRoot.id)
        assertEquals(athleteId, sourceRoot.athleteId)
        val sourceFrames = sourceV2Dao.framesForRoot(sourceRoot.id)
        assertEquals(expectedSnapshots(fixtures), sourceFrames.map { snapshotOf(it) })
        assertEquals(saved.imageKeys, sourceFrames.map { it.imageKey })

        // 2. Repeticion identica: idempotente, sin filas ni ficheros nuevos.
        val imagesBeforeRepeat = countOwnImageFiles(sourceImageRoot)
        val repeated = requireNotNull(sourceSaveRepository.save(request) as? AngleV2SaveResult.AlreadySaved) {
            "una repeticion identica debe informarse como AlreadySaved"
        }
        assertEquals("la repeticion debe devolver la misma raiz", saved.rootId, repeated.rootId)
        assertEquals("la repeticion no debe dejar imagenes huerfanas", emptyList<String>(), repeated.orphanedImageKeys)
        assertEquals("la repeticion no debe publicar otra imagen", imagesBeforeRepeat, countOwnImageFiles(sourceImageRoot))
        assertEquals(3, countOwnImageFiles(sourceImageRoot))
        assertEquals(
            "la repeticion no debe insertar otro fotograma",
            expectedSnapshots(fixtures),
            sourceDatabase.angleAnalysisV2Dao().framesForRoot(sourceRoot.id).map { snapshotOf(it) },
        )

        // 3. Export real a ZIP con el coordinador de producto.
        val exportSummary = FileOutputStream(archive).use { output -> exporter.export(output) }
        assertEquals(3, exportSummary.imageCount)
        assertTrue("el backup debe declarar medios", exportSummary.mediaIncluded)
        assertEquals("el resumen debe describir el ZIP escrito", archive.length(), exportSummary.archiveByteCount)
        assertTrue("el ZIP debe existir", archive.isFile && archive.length() > 0L)
        assertTrue(
            "el export no debe dejar temporales en su staging",
            sourceStaging.list()?.toList().orEmpty().isEmpty(),
        )
        ZipFile(archive).use { zip ->
            val entries = zip.entries().toList()
            assertEquals(1 + saved.imageKeys.size, entries.size)
            assertTrue(
                "el contenedor debe declarar el manifiesto",
                entries.any { it.name == MEDIA_BACKUP_MANIFEST_ENTRY },
            )
            val entryByName = entries.associateBy { it.name }
            saved.imageKeys.forEachIndexed { index, key ->
                val entry = entryByName["images/$key.png"]
                assertTrue("el ZIP debe transportar la imagen de '$key'", entry != null)
                assertEquals(
                    "la entrada ZIP debe llevar los bytes del PNG $index",
                    fixtures[index].png.size.toLong(),
                    entry!!.size,
                )
            }
        }

        // 4. Restauracion en la segunda base y la segunda raiz de imagenes.
        val load = FileInputStream(archive).use { input -> importer.load(input, archive.length()) }
        val media = requireNotNull(load as? MediaBackupLoad.Media) {
            "un ZIP del coordinador debe cargarse como contenedor con medios"
        }
        val restored = media.use { prepared -> importer.restore(prepared) }
        val applied = requireNotNull(restored as? MediaBackupRestoreOutcome.Restored) {
            "la restauracion real debe aplicarse, fue $restored"
        }
        assertEquals(3, applied.imageCount)
        assertEquals(3, applied.restoredImageKeys.toSet().size)
        assertTrue(
            "la restauracion debe publicar claves nuevas, no reutilizar las del origen",
            applied.restoredImageKeys.none { it in saved.imageKeys },
        )
        assertTrue(
            "el lector/restore no debe dejar temporales en su staging",
            targetStaging.list()?.toList().orEmpty().isEmpty(),
        )

        // 5. Verificacion integral contra la segunda base y la segunda raiz de imagenes.
        val targetV2Dao = targetDatabase.angleAnalysisV2Dao()
        val targetRoot = requireNotNull(targetV2Dao.rootBySessionKey(sessionKey)) {
            "la restauracion debe conservar la sessionKey"
        }
        assertEquals(athleteId, targetRoot.athleteId)
        assertEquals(0, targetRoot.revision)

        val targetFrames = targetV2Dao.framesForRoot(targetRoot.id)
        assertEquals("deben restaurarse los tres fotogramas", 3, targetFrames.size)
        assertEquals(listOf(0, 1, 2), targetFrames.map { it.ordinal })
        assertEquals(expectedSnapshots(fixtures), targetFrames.map { snapshotOf(it) })
        assertEquals(
            "cada fotograma restaurado debe apuntar a una de las claves publicadas",
            applied.restoredImageKeys.toSet(),
            targetFrames.map { it.imageKey }.toSet(),
        )
        targetFrames.forEachIndexed { index, frame ->
            val fixture = fixtures[index]
            assertNotEquals(
                "cada fotograma debe tener una clave NUEVA",
                saved.imageKeys[index],
                frame.imageKey,
            )
            val restoredBytes = readOwnImage(
                targetStore,
                frame.imageKey,
                AngleImageIntegrity(byteCount = frame.imageByteCount, sha256Hex = frame.imageSha256),
            )
            assertArrayEquals(
                "los bytes leidos por la clave nueva deben ser el PNG guardado $index",
                fixture.png,
                restoredBytes,
            )
            assertEquals(expectedIntegrity(fixture).byteCount, frame.imageByteCount)
            assertEquals(expectedIntegrity(fixture).sha256Hex, frame.imageSha256)
            assertEquals(MEDIA_BACKUP_IMAGE_MIME_TYPE, frame.imageMimeType)
            assertPngDimensions(restoredBytes, fixture.width, fixture.height)
            assertTrue(
                "la raiz destino no debe contener la clave del origen '${saved.imageKeys[index]}'",
                targetStore.verify(
                    saved.imageKeys[index],
                    expectedIntegrity(fixture),
                ) is AngleImageVerifyResult.Failed,
            )
            assertEquals(
                "las marcas deben conservarse en orden y valor",
                fixture.marks,
                targetV2Dao.marksForFrame(frame.id).map { markOf(it) },
            )
        }
        assertEquals(3, countOwnImageFiles(targetImageRoot))

        // Legacy: el atleta vuelve intacto y el manifesto restaurado sigue siendo exportable.
        assertEquals("el atleta debe volver intacto", athlete, targetDatabase.athleteDao().byId(athleteId))
        val targetDao = targetDatabase.manualBackupDao()
        assertTrue("el fixture no debe inventar evaluaciones legacy", targetDao.assessments().isEmpty())
        val sourceTables = sourceDatabase.manualBackupDao().snapshotWithAngleMedia().legacy.tables
        val targetManifest = targetDao.snapshotWithAngleMedia()
        assertEquals(
            "las tablas legacy restauradas deben coincidir con el origen",
            sourceTables,
            targetManifest.legacy.tables,
        )
        assertEquals(3, targetManifest.angleV2.frames.size)
        assertEquals(3, targetManifest.angleV2.images.size)
        assertEquals(
            applied.restoredImageKeys.toSet(),
            targetManifest.angleV2.images.map { it.imageKey }.toSet(),
        )

        // El origen no cambia por exportar ni restaurar: mismas filas, mismas claves, mismos ficheros.
        val sourceAfterRestore = sourceV2Dao.framesForRoot(sourceRoot.id)
        assertEquals(expectedSnapshots(fixtures), sourceAfterRestore.map { snapshotOf(it) })
        assertEquals(saved.imageKeys, sourceAfterRestore.map { it.imageKey })
        assertEquals(3, countOwnImageFiles(sourceImageRoot))
        assertArrayEquals("el centinela propio debe seguir intacto", sentinelBytes, sentinel.readBytes())
    }

    // --- Fixtures ------------------------------------------------------------------------------

    /** Tres fotogramas con geometria, instantes y marcas distintas: un cruce de orden se nota. */
    private fun fixtures(): List<FrameFixture> = listOf(
        FrameFixture(
            width = 48,
            height = 32,
            png = png(48, 32, seed = 11),
            assignedFrameIndex = 30,
            reportedFrameIndex = 31,
            assignedPtsUs = 1_000_000L,
            reportedPtsUs = 1_033_333L,
            encodedWidth = 1920,
            encodedHeight = 1080,
            presentationWidth = 1080,
            presentationHeight = 1920,
            rotationDegrees = 90,
            geometryVersion = 2,
            marks = listOf(
                AngleV2SaveMark(
                    name = "rodilla",
                    colorArgb = -0x10000,
                    visible = true,
                    pointAX = 120.5,
                    pointAY = 240.25,
                    pointBX = 130.75,
                    pointBY = 260.5,
                    pointCX = 150.0,
                    pointCY = 280.125,
                ),
                AngleV2SaveMark(
                    name = "cadera",
                    colorArgb = 0x003366,
                    visible = false,
                    pointAX = 10.0,
                    pointAY = 20.5,
                    pointBX = 30.25,
                    pointBY = 40.75,
                    pointCX = 50.5,
                    pointCY = 60.0,
                ),
            ),
        ),
        FrameFixture(
            width = 40,
            height = 24,
            png = png(40, 24, seed = 29),
            assignedFrameIndex = 46,
            reportedFrameIndex = 47,
            assignedPtsUs = 1_533_333L,
            reportedPtsUs = 1_566_666L,
            encodedWidth = 1920,
            encodedHeight = 1080,
            presentationWidth = 1920,
            presentationHeight = 1080,
            rotationDegrees = 0,
            geometryVersion = 2,
            marks = listOf(
                AngleV2SaveMark(
                    name = "tobillo",
                    colorArgb = 0x0000FF,
                    visible = true,
                    pointAX = 5.5,
                    pointAY = 6.25,
                    pointBX = 7.75,
                    pointBY = 8.5,
                    pointCX = 9.125,
                    pointCY = 10.0,
                ),
            ),
        ),
        FrameFixture(
            width = 32,
            height = 28,
            png = png(32, 28, seed = 47),
            assignedFrameIndex = 70,
            reportedFrameIndex = null,
            assignedPtsUs = 2_333_333L,
            reportedPtsUs = null,
            encodedWidth = 1280,
            encodedHeight = 720,
            presentationWidth = 720,
            presentationHeight = 1280,
            rotationDegrees = 270,
            geometryVersion = 1,
            marks = listOf(
                AngleV2SaveMark(
                    name = "hombro",
                    colorArgb = 0x00FF00,
                    visible = true,
                    pointAX = 11.0,
                    pointAY = 12.0,
                    pointBX = 13.0,
                    pointBY = 14.0,
                    pointCX = 15.0,
                    pointCY = 16.0,
                ),
            ),
        ),
    )

    private class FrameFixture(
        val width: Int,
        val height: Int,
        val png: ByteArray,
        val assignedFrameIndex: Int,
        val reportedFrameIndex: Int?,
        val assignedPtsUs: Long,
        val reportedPtsUs: Long?,
        val encodedWidth: Int,
        val encodedHeight: Int,
        val presentationWidth: Int,
        val presentationHeight: Int,
        val rotationDegrees: Int,
        val geometryVersion: Int,
        val marks: List<AngleV2SaveMark>,
    ) {
        fun toSaveFrame(): AngleV2SaveFrame = AngleV2SaveFrame(
            assignedFrameIndex = assignedFrameIndex,
            reportedFrameIndex = reportedFrameIndex,
            assignedPtsUs = assignedPtsUs,
            reportedPtsUs = reportedPtsUs,
            encodedWidth = encodedWidth,
            encodedHeight = encodedHeight,
            presentationWidth = presentationWidth,
            presentationHeight = presentationHeight,
            rotationDegrees = rotationDegrees,
            geometryVersion = geometryVersion,
            pngBytes = png,
            imageWidth = width,
            imageHeight = height,
            marks = marks,
        )
    }

    /** Proyeccion comparable de las columnas que el guardado debe persistir y el restore conservar. */
    private data class FrameSnapshot(
        val ordinal: Int,
        val assignedFrameIndex: Int,
        val reportedFrameIndex: Int?,
        val assignedPtsUs: Long,
        val reportedPtsUs: Long?,
        val encodedWidth: Int,
        val encodedHeight: Int,
        val presentationWidth: Int,
        val presentationHeight: Int,
        val rotationDegrees: Int,
        val geometryVersion: Int,
        val imageWidth: Int,
        val imageHeight: Int,
        val imageByteCount: Long,
        val imageSha256: String,
        val imageMimeType: String,
    )

    private fun expectedSnapshots(fixtures: List<FrameFixture>): List<FrameSnapshot> =
        fixtures.mapIndexed { ordinal, fixture ->
            FrameSnapshot(
                ordinal = ordinal,
                assignedFrameIndex = fixture.assignedFrameIndex,
                reportedFrameIndex = fixture.reportedFrameIndex,
                assignedPtsUs = fixture.assignedPtsUs,
                reportedPtsUs = fixture.reportedPtsUs,
                encodedWidth = fixture.encodedWidth,
                encodedHeight = fixture.encodedHeight,
                presentationWidth = fixture.presentationWidth,
                presentationHeight = fixture.presentationHeight,
                rotationDegrees = fixture.rotationDegrees,
                geometryVersion = fixture.geometryVersion,
                imageWidth = fixture.width,
                imageHeight = fixture.height,
                imageByteCount = fixture.png.size.toLong(),
                imageSha256 = sha256Hex(fixture.png),
                imageMimeType = MEDIA_BACKUP_IMAGE_MIME_TYPE,
            )
        }

    private fun snapshotOf(frame: AngleAnalysisV2FrameEntity): FrameSnapshot = FrameSnapshot(
        ordinal = frame.ordinal,
        assignedFrameIndex = frame.assignedFrameIndex,
        reportedFrameIndex = frame.reportedFrameIndex,
        assignedPtsUs = frame.assignedPtsUs,
        reportedPtsUs = frame.reportedPtsUs,
        encodedWidth = frame.encodedWidth,
        encodedHeight = frame.encodedHeight,
        presentationWidth = frame.presentationWidth,
        presentationHeight = frame.presentationHeight,
        rotationDegrees = frame.rotationDegrees,
        geometryVersion = frame.geometryVersion,
        imageWidth = frame.imageWidth,
        imageHeight = frame.imageHeight,
        imageByteCount = frame.imageByteCount,
        imageSha256 = frame.imageSha256,
        imageMimeType = frame.imageMimeType,
    )

    private fun markOf(mark: AngleAnalysisV2MarkEntity): AngleV2SaveMark = AngleV2SaveMark(
        name = mark.name,
        colorArgb = mark.colorArgb,
        visible = mark.visible,
        pointAX = mark.pointAX,
        pointAY = mark.pointAY,
        pointBX = mark.pointBX,
        pointBY = mark.pointBY,
        pointCX = mark.pointCX,
        pointCY = mark.pointCY,
    )

    private fun expectedIntegrity(fixture: FrameFixture): AngleImageIntegrity = AngleImageIntegrity(
        byteCount = fixture.png.size.toLong(),
        sha256Hex = sha256Hex(fixture.png),
    )

    // --- Helpers de imagen ----------------------------------------------------------------------

    /** PNG real codificado en el dispositivo; el fixture no depende de ficheros externos. */
    private fun png(width: Int, height: Int, seed: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val red = (x * 7 + seed) and 0xFF
                    val green = (y * 11 + seed) and 0xFF
                    val blue = (x * y + seed) and 0xFF
                    bitmap.setPixel(x, y, (0xFF shl 24) or (red shl 16) or (green shl 8) or blue)
                }
            }
            val output = ByteArrayOutputStream()
            assertTrue(
                "el dispositivo debe codificar el PNG sintetico",
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output),
            )
            return output.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertPngDimensions(bytes: ByteArray, width: Int, height: Int) {
        assertTrue("la imagen debe ser un PNG con IHDR, era de ${bytes.size} bytes", bytes.size >= 24)
        assertArrayEquals(PNG_SIGNATURE, bytes.copyOfRange(0, PNG_SIGNATURE.size))
        assertEquals("IHDR", String(bytes, 12, 4, Charsets.US_ASCII))
        assertEquals(width, readU32(bytes, 16).toInt())
        assertEquals(height, readU32(bytes, 20).toInt())
    }

    /** Lee bytes verificados por clave; un fallo del almacen es un fallo del test, no un `null`. */
    private fun readOwnImage(
        store: PrivateAngleImageStore,
        imageKey: String,
        expected: AngleImageIntegrity,
    ): ByteArray = when (val result = store.readVerified(imageKey, expected) { input -> input.readBytes() }) {
        is AngleImageReadResult.Delivered -> result.value
        is AngleImageReadResult.Failed -> throw AssertionError(
            "la imagen '$imageKey' no se pudo leer verificada: ${result.error}",
        )
    }

    private fun countOwnImageFiles(root: File): Int = root.walkTopDown().count { it.isFile }

    // --- Helpers de fichero y binarios ----------------------------------------------------------

    private fun deleteOwnTree(directory: File) {
        directory.listFiles().orEmpty().forEach { entry ->
            val symbolic = java.nio.file.Files.isSymbolicLink(entry.toPath())
            if (entry.isDirectory && !symbolic) {
                deleteOwnTree(entry)
            } else {
                assertTrue("no se pudo borrar el artefacto propio '${entry.name}'", entry.delete())
            }
        }
        assertTrue("no se pudo borrar el directorio propio '${directory.name}'", directory.delete())
    }

    /**
     * Lee un entero de 32 bits sin signo; los campos del PNG (`IHDR`) van en orden de red (big
     * endian). Solo se usa para las dimensiones del `IHDR`, no para campos little endian como ZIP.
     */
    private fun readU32(bytes: ByteArray, at: Int): Long =
        ((bytes[at].toLong() and 0xFF) shl 24) or
            ((bytes[at + 1].toLong() and 0xFF) shl 16) or
            ((bytes[at + 2].toLong() and 0xFF) shl 8) or
            (bytes[at + 3].toLong() and 0xFF)

    private companion object {
        const val SANDBOX_PREFIX = "openjump-angle-backup-roundtrip-"

        val CANONICAL_KEY = Regex("[0-9a-f]{32}")
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}

/** SHA-256 en hexadecimal minuscula, independiente de Locale y del formato del dispositivo. */
private fun sha256Hex(bytes: ByteArray): String {
    val digits = "0123456789abcdef".toCharArray()
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
        digits[(byte.toInt() and 0xFF) ushr 4].toString() + digits[byte.toInt() and 0x0F]
    }
}
