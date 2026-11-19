package com.openjump.app.data.backup

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.data.ANGLE_ANALYSIS_V2_FORMAT
import com.openjump.app.data.angles.AngleImageIntegrity
import com.openjump.app.data.angles.AngleImageVerifyResult
import com.openjump.app.data.angles.AndroidAngleImageFileIo
import com.openjump.app.data.angles.PrivateAngleImageStore
import com.openjump.app.data.angles.StoredAngleImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
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
 * Verificacion instrumentada (Android real) de la preparacion de imagenes de restore (G4a) con el
 * lector y el escritor **reales** del contenedor, el probe **real** de PNG (`BitmapFactory`) y el
 * almacen privado sobre [AndroidAngleImageFileIo].
 *
 * Por que existe: las pruebas JVM demuestran el contrato con puertos fake (no hay
 * `android.graphics` en JVM). Aqui se demuestra el camino completo en el dispositivo: contenedor
 * escrito con PNG reales -> lector estricto -> copia verificada al spool -> probe con decode real ->
 * publicacion en el almacen privado con clave nueva -> bytes y digests comparados.
 *
 * Reglas de aislamiento:
 *  - sandbox **unico** `cacheDir/openjump-media-restore-preparation-instrumented-<uuid>`;
 *  - staging del escritor, staging del lector y raiz del almacen privado propios dentro del sandbox;
 *  - fixture autocontenido: dos PNG generados en el dispositivo, sin video ni base de datos de
 *    usuario, sin Room y sin UI;
 *  - la limpieza borra solo el arbol de ese UUID, comprobando antes que cuelga de `cacheDir`.
 *
 * Esta clase **no** se ejecuta en este slice (sin AVD): se compila con `compileDebugAndroidTestKotlin`.
 */
@RunWith(AndroidJUnit4::class)
class MediaBackupImagePreparationInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val runId: String = UUID.randomUUID().toString()

    private val sandbox: File = File(context.cacheDir, "$SANDBOX_PREFIX$runId")
    private lateinit var writerStaging: File
    private lateinit var readerStaging: File
    private lateinit var storeRoot: File
    private lateinit var sentinel: File

    private val sentinelBytes = "openjump-media-restore-preparation-sentinel-$runId".toByteArray(Charsets.UTF_8)

    @Before
    fun createOwnSandbox() {
        val cacheRoot = context.cacheDir.canonicalFile
        assertFalse("the sandbox must not pre-exist for this UUID", sandbox.exists())
        assertEquals("the sandbox must be a direct child of the app cache", cacheRoot, sandbox.canonicalFile.parentFile)
        assertTrue("the unique sandbox must be created", sandbox.mkdirs())
        writerStaging = File(sandbox, "writer-staging").also { assertTrue(it.mkdirs()) }
        readerStaging = File(sandbox, "reader-staging").also { assertTrue(it.mkdirs()) }
        storeRoot = File(sandbox, "files-root").also { assertTrue(it.mkdirs()) }
        sentinel = File(readerStaging, "sentinel-own.bin").also { it.writeBytes(sentinelBytes) }
        Log.i(TAG, "sandbox=${sandbox.name}")
    }

    @After
    fun deleteOwnSandbox() {
        val cacheRoot = context.cacheDir.canonicalFile
        val root = sandbox.canonicalFile
        assertEquals("the sandbox must be a direct child of the app cache", cacheRoot, root.parentFile)
        assertEquals("the sandbox must carry this method's UUID", "$SANDBOX_PREFIX$runId", root.name)
        deleteOwnTree(root)
        assertFalse("the sandbox must be gone after cleanup", root.exists())
    }

    @Test
    fun twoFrameArchiveIsPreparedIntoNewKeysWithRealPngAndAndroidFileIo() {
        val fixtures = listOf(
            FixtureImage(key = FIXTURE_KEY_A, width = 24, height = 16, seed = 3),
            FixtureImage(key = FIXTURE_KEY_B, width = 12, height = 20, seed = 9),
        ).map { it.copy(bytes = png(it.width, it.height, it.seed)).withDigest() }
        val archive = MediaBackupArchiveWriter(writerStaging).write(
            manifestWith(fixtures),
            MediaBackupImageSource { key -> ByteArrayInputStream(fixtures.single { it.key == key }.bytes) },
        )
        try {
            val prepared = MediaBackupArchiveReader(readerStaging).read(FileInputStream(archive.file))
            try {
                assertFalse("the caller owns the prepared import", prepared.isClosed)
                val store = PrivateAngleImageStore(storeRoot, fileIo = AndroidAngleImageFileIo())
                val preparer = MediaBackupImagePreparer(readerStaging, store)

                val result = preparer.prepare(prepared)

                val preparedImages = (result as MediaBackupImagePreparationResult.Prepared).images
                assertEquals(2, preparedImages.count)
                fixtures.forEach { fixture ->
                    val stored = preparedImages.byImportedKey.getValue(fixture.key)
                    assertNotEquals("se exige una clave NUEVA", fixture.key, stored.imageKey)
                    assertTrue("la clave publicada debe ser canonica", stored.imageKey.matches(Regex("[0-9a-f]{32}")))
                    assertEquals(fixture.bytes.size.toLong(), stored.byteCount)
                    assertEquals("el SHA publicado debe ser el del PNG", fixture.sha256, stored.sha256Hex)
                    assertEquals(
                        "el almacen debe verificar lo publicado",
                        AngleImageVerifyResult.Verified(stored),
                        store.verify(stored.imageKey, AngleImageIntegrity(stored.byteCount, stored.sha256Hex)),
                    )
                    val published = File(storeRoot, "angle-images/${stored.imageKey}/image.bin")
                    assertTrue("el fichero publicado debe existir", published.isFile)
                    val onDisk = published.readBytes()
                    assertArrayEquals("los bytes publicados deben ser los del PNG", fixture.bytes, onDisk)
                    assertEquals(fixture.sha256, sha256Hex(onDisk))
                }
                // El contenedor de entrada lo posee el llamante: la preparacion no lo cierra.
                assertFalse("la preparacion no debe cerrar el import del llamante", prepared.isClosed)
                // Staging del lector: ni un spool propio sobrevive (el sentinela ajeno se preserva).
                assertEquals(emptyList<String>(), ownSpoolNames())
                assertEquals(sentinelBytes.toList(), sentinel.readBytes().toList())
            } finally {
                prepared.close()
            }
        } finally {
            archive.close()
        }
    }

    @Test
    fun aCorruptedSecondSpoolIsRejectedByTheRealProbeWithoutReturningAValidPartialSet() {
        // Contenedor VALIDO con dos PNG reales: se escribe y se lee completos (el ZIP y sus dos
        // entradas pasan el lector). El fallo se provoca despues, sobre el spool de la segunda
        // imagen ya copiado y sincronizado, para cubrir la preparacion y su fallo parcial.
        val fixtures = listOf(
            FixtureImage(key = FIXTURE_KEY_A, width = 10, height = 10, seed = 1),
            FixtureImage(key = FIXTURE_KEY_B, width = 10, height = 10, seed = 2),
        ).map { it.copy(bytes = png(it.width, it.height, it.seed)).withDigest() }
        val first = fixtures.single { it.key == FIXTURE_KEY_A }
        val second = fixtures.single { it.key == FIXTURE_KEY_B }
        // Orden real de declaraciones: el plan del contenedor y la fuente de produccion ordenan por
        // clave ascendente, asi que A se prepara primero y el segundo spool es siempre el de B.
        assertTrue("la clave A debe ordenar antes que la B", FIXTURE_KEY_A < FIXTURE_KEY_B)
        val archive = MediaBackupArchiveWriter(writerStaging).write(
            manifestWith(fixtures),
            MediaBackupImageSource { key -> ByteArrayInputStream(fixtures.single { it.key == key }.bytes) },
        )
        val filePort = CorruptSecondSpoolFilePort()
        try {
            val prepared = MediaBackupArchiveReader(readerStaging).read(FileInputStream(archive.file))
            try {
                assertEquals(
                    "el orden real de declaraciones debe ser la clave ascendente",
                    listOf(FIXTURE_KEY_A, FIXTURE_KEY_B),
                    prepared.imageKeys,
                )
                val store = PrivateAngleImageStore(storeRoot, fileIo = AndroidAngleImageFileIo())
                // Probe real (`BitmapFactory`), almacen real y un puerto de ficheros que solo
                // corrompe el segundo spool; el resto del camino es el de produccion.
                val preparer = MediaBackupImagePreparer(readerStaging, store, filePort = filePort)

                val result = preparer.prepare(prepared)

                // Nada parcial: el resultado es un fallo, nunca un conjunto `Prepared`.
                assertFalse(
                    "un spool corrompido no puede devolver imagenes",
                    result is MediaBackupImagePreparationResult.Prepared,
                )
                val failed = result as MediaBackupImagePreparationResult.Failed
                val step = failed.failure as MediaBackupPreparationFailure.StepFailed
                // El rechazo es del probe real de la SEGUNDA imagen: copia y `fsync` ya ocurrieron.
                assertEquals(MediaBackupPreparationStage.PROBE, step.stage)
                assertEquals(FIXTURE_KEY_B, step.imageKey)
                assertTrue(
                    "el probe real de PNG debe rechazar el spool corrompido",
                    step.cause is MediaBackupPngProbeException,
                )
                assertFalse("la limpieza de spools propios no debe fallar", failed.spoolCleanupFailed)

                // La perturbacion se aplico a un unico spool, el segundo, y despues de copiarlo y
                // sincronizarlo (no antes: el fallo no puede ser de copia ni de `fsync`).
                assertEquals(2, filePort.createdCount)
                assertEquals(1, filePort.corruptedCount)
                assertTrue(
                    "la corrupcion debe ocurrir tras copiar y sincronizar el spool",
                    filePort.corruptedAfterSyncAndClose,
                )
                assertArrayEquals(
                    "el segundo spool contenia los bytes sellados de la imagen B",
                    second.bytes,
                    filePort.corruptedSealedBytes,
                )
                assertEquals(second.bytes.size.toLong(), filePort.corruptedByteCount)
                assertEquals(
                    "la corrupcion no debe cambiar el tamano del spool",
                    second.bytes.size.toLong(),
                    filePort.corruptedBytes.size.toLong(),
                )
                assertFalse(
                    "la corrupcion debe cambiar el contenido del spool",
                    sha256Hex(filePort.corruptedBytes) == second.sha256,
                )

                // Primera imagen: se publico antes del fallo; la clave se informa como huerfana y
                // se puede verificar con el almacen real (no se borra ni se sobrescribe).
                val orphanKey = failed.publishedOrphanKeys.singleOrNull()
                    ?: throw AssertionError("solo la primera imagen pudo publicarse: ${failed.publishedOrphanKeys}")
                assertNotEquals("la clave publicada debe ser NUEVA", first.key, orphanKey)
                assertTrue("la clave publicada debe ser canonica", orphanKey.matches(Regex("[0-9a-f]{32}")))
                val orphan = StoredAngleImage(orphanKey, first.bytes.size.toLong(), first.sha256)
                assertEquals(
                    "el huerfano informado debe verificar en el almacen real",
                    AngleImageVerifyResult.Verified(orphan),
                    store.verify(orphanKey, AngleImageIntegrity(orphan.byteCount, orphan.sha256Hex)),
                )
                val orphanFile = File(storeRoot, "angle-images/$orphanKey/image.bin")
                assertTrue("el huerfano debe seguir en disco", orphanFile.isFile)
                assertArrayEquals(
                    "el huerfano debe contener el PNG de la primera imagen",
                    first.bytes,
                    orphanFile.readBytes(),
                )
                assertEquals(
                    "ninguna segunda clave debe haberse publicado",
                    listOf(orphanKey),
                    File(storeRoot, "angle-images").list()?.sorted().orEmpty(),
                )

                // Limpieza de lo propio y sentinela ajeno intacto.
                assertEquals(emptyList<String>(), ownSpoolNames())
                assertEquals(sentinelBytes.toList(), sentinel.readBytes().toList())
            } finally {
                prepared.close()
            }
        } finally {
            archive.close()
        }
    }

    // --- Fixture --------------------------------------------------------------------------------

    private data class FixtureImage(
        val key: String,
        val width: Int,
        val height: Int,
        val seed: Int,
        val bytes: ByteArray = ByteArray(0),
        val sha256: String = "",
    ) {
        fun withDigest(): FixtureImage = copy(sha256 = sha256Hex(bytes))
    }

    /** Manifiesto valido con una raiz y un fotograma por imagen (dos fotogramas en estas pruebas). */
    private fun manifestWith(images: List<FixtureImage>): MediaBackupManifest {
        val roots = images.mapIndexed { index, _ ->
            MediaBackupRoot(
                id = index + 1L,
                analysisFormat = ANGLE_ANALYSIS_V2_FORMAT,
                sessionKey = "restore-preparation-session-${index + 1}",
                athleteId = if (index == 0) 1L else null,
                createdAt = 1_000L + index,
                updatedAt = 2_000L + index,
                revision = index,
            )
        }
        val frames = images.mapIndexed { index, image ->
            MediaBackupFrame(
                id = 100L + index,
                rootId = index + 1L,
                ordinal = 0,
                assignedFrameIndex = index,
                reportedFrameIndex = null,
                assignedPtsUs = index * 33_333L,
                reportedPtsUs = null,
                encodedWidth = image.width,
                encodedHeight = image.height,
                presentationWidth = image.width,
                presentationHeight = image.height,
                rotationDegrees = 0,
                geometryVersion = 1,
                imageKey = image.key,
                imageWidth = image.width,
                imageHeight = image.height,
                imageByteCount = image.bytes.size.toLong(),
                imageSha256 = image.sha256,
                imageMimeType = MEDIA_BACKUP_IMAGE_MIME_TYPE,
            )
        }
        val marks = images.mapIndexed { index, image ->
            MediaBackupMark(
                id = 200L + index,
                frameId = 100L + index,
                ordinal = 0,
                name = "rodilla",
                colorArgb = -0x10000,
                visible = true,
                pointAX = 1.0,
                pointAY = 1.0,
                pointBX = image.width / 2.0,
                pointBY = image.height / 2.0,
                pointCX = (image.width - 1).toDouble(),
                pointCY = (image.height - 1).toDouble(),
            )
        }
        return MediaBackupManifest(
            contract = MEDIA_BACKUP_CONTRACT,
            formatVersion = MEDIA_BACKUP_FORMAT_VERSION,
            sourceRoomSchemaVersion = MEDIA_BACKUP_ROOM_VERSION,
            createdAtEpochMillis = 1L,
            legacy = ManualBackupCodec.decode(LEGACY_JSON),
            angleV2 = MediaBackupAngleV2(
                roots = roots,
                frames = frames,
                marks = marks,
                images = images.map { MediaBackupImage(it.key, it.bytes.size.toLong(), it.sha256) },
            ),
        )
    }

    /** PNG real generado en el dispositivo; sin ficheros externos ni video de usuario. */
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
            assertTrue("the device must encode the synthetic PNG", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            return output.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** Spools propios (prefijo de la preparacion) que quedasen en el staging del lector. */
    private fun ownSpoolNames(): List<String> =
        readerStaging.list()?.filter { it.startsWith(DefaultMediaBackupPreparationFilePort.SPOOL_PREFIX) }?.sorted()
            ?: throw AssertionError("the reader staging must be listable")

    /** Borra **solo** el arbol de este UUID (el sandbox ya validado). */
    private fun deleteOwnTree(directory: File) {
        val children = directory.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory && !java.nio.file.Files.isSymbolicLink(child.toPath())) deleteOwnTree(child) else child.delete()
        }
        directory.delete()
    }

    private companion object {
        const val TAG = "MediaRestorePreparation"
        const val SANDBOX_PREFIX = "openjump-media-restore-preparation-instrumented-"
        const val FIXTURE_KEY_A = "0123456789abcdef0123456789abcdef"
        const val FIXTURE_KEY_B = "fedcba9876543210fedcba9876543210"

        const val LEGACY_JSON =
            """{"contract":"openjump-manual-backup","formatVersion":1,"sourceRoomSchemaVersion":10,"createdAtEpochMillis":1,"mediaIncluded":false,"preferencesIncluded":false,"tables":{"athletes":[{"id":1,"displayName":"instrumented athlete","birthDate":null,"sex":null,"notes":null,"createdAt":1,"updatedAt":1,"archivedAt":null,"avatarKey":null,"weightKg":null,"heightCm":null}],"athleteGroups":[],"athleteGroupCrossRef":[],"testingSessions":[],"testingParticipants":[],"assessments":[{"id":2,"sessionKey":"instrumented-assessment","athleteId":1,"testingSessionId":null,"protocolId":"CMJ","dateTime":1,"primaryMetricKey":"HEIGHT_CM","primaryMetricValue":1.0,"primaryMetricUnit":"CENTIMETER","notes":null}],"attempts":[{"id":3,"assessmentId":2,"ordinal":0,"side":null,"source":"UNKNOWN","detectedFps":0,"dropHeightCm":null,"distanceCm":null}],"attemptEvents":[{"attemptId":3,"type":"MOVEMENT_START","ordinal":0,"ptsUs":1},{"attemptId":3,"type":"TAKEOFF","ordinal":0,"ptsUs":2},{"attemptId":3,"type":"LANDING","ordinal":0,"ptsUs":3}],"attemptMetrics":[],"attemptCalibrations":[],"attemptSpatialMarks":[],"encoderSessions":[],"encoderSamples":[],"encoderRepetitions":[],"encoderRepMetrics":[]}}"""
    }
}

/**
 * Puerto de ficheros de produccion con **una** perturbacion: cuando se abre por primera vez el
 * segundo spool creado, sus bytes se corrompen en el sitio conservando el tamano.
 *
 * El coordinador ya copio y sincronizo ese spool antes de abrirlo (primero para el probe), asi que
 * el fallo se fuerza en el probe/almacen de esa imagen y no antes. Todo lo demas delega en
 * [DefaultMediaBackupPreparationFilePort] y ningun fichero ajeno se toca.
 */
private class CorruptSecondSpoolFilePort(
    private val corruptOrdinal: Int = 1,
    private val delegate: MediaBackupPreparationFilePort = DefaultMediaBackupPreparationFilePort,
) : MediaBackupPreparationFilePort {

    private val created = ArrayList<File>()

    /** Estado por spool: `[0]` = `sync` confirmado, `[1]` = cerrado. */
    private val sinkStates = HashMap<File, BooleanArray>()
    private val corrupted = ArrayList<File>()

    val createdCount: Int get() = created.size
    val corruptedCount: Int get() = corrupted.size

    var corruptedAfterSyncAndClose: Boolean = false
        private set
    var corruptedSealedBytes: ByteArray = ByteArray(0)
        private set
    var corruptedBytes: ByteArray = ByteArray(0)
        private set
    var corruptedByteCount: Long = 0L
        private set

    override fun createSpoolFile(stagingDirectory: File): File {
        val spool = delegate.createSpoolFile(stagingDirectory)
        created += spool
        sinkStates[spool] = booleanArrayOf(false, false)
        return spool
    }

    override fun openSpoolSink(file: File): MediaBackupPreparationSink {
        val inner = delegate.openSpoolSink(file)
        val state = sinkStates.getValue(file)
        return object : MediaBackupPreparationSink {
            override fun write(buffer: ByteArray, offset: Int, length: Int) = inner.write(buffer, offset, length)

            override fun sync() {
                inner.sync()
                state[0] = true
            }

            override fun close() {
                inner.close()
                state[1] = true
            }
        }
    }

    override fun openSpoolSource(file: File): InputStream {
        if (corrupted.none { it == file } && created.getOrNull(corruptOrdinal) == file) {
            corrupted += file
            val state = sinkStates.getValue(file)
            corruptedAfterSyncAndClose = state[0] && state[1]
            corruptedSealedBytes = file.readBytes()
            corruptedByteCount = corruptedSealedBytes.size.toLong()
            corruptedBytes = corruptInPlaceKeepingLength(file)
        }
        return delegate.openSpoolSource(file)
    }

    override fun deleteSpoolFile(file: File): Boolean = delegate.deleteSpoolFile(file)
}

/** Invierte bits de una zona de datos del spool conservando el tamano; devuelve los bytes escritos. */
private fun corruptInPlaceKeepingLength(file: File): ByteArray {
    val bytes = file.readBytes()
    if (bytes.size < 64) throw IllegalStateException("the spool is too small to corrupt")
    val offset = bytes.size / 2
    val end = minOf(bytes.size, offset + 16)
    for (index in offset until end) bytes[index] = (bytes[index].toInt() xor 0x5A).toByte()
    file.writeBytes(bytes)
    return bytes
}

/** SHA-256 en minuscula, independiente de Locale y del formato del dispositivo. */
private fun sha256Hex(bytes: ByteArray): String {
    val digits = "0123456789abcdef".toCharArray()
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
        digits[(byte.toInt() and 0xFF) ushr 4].toString() + digits[byte.toInt() and 0x0F]
    }
}
