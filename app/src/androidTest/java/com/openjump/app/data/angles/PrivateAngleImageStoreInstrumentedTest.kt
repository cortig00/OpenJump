package com.openjump.app.data.angles

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pruebas instrumentadas del almacén privado de imágenes anotadas sobre **plataforma real**
 * (API 35): el sistema de ficheros del sandbox de la app, los primitivos Android
 * (`android.system.Os`, `StatFs`), `fsync` real de `FileOutputStream` y el backend de producción
 * [AndroidAngleImageFileIo] inyectado explícitamente.
 *
 * ## Qué demuestra (y qué no)
 *
 * Las pruebas JVM ya cubren la aritmética de cuota, los fallos tipados y la limpieza con E/S
 * inyectada; aquí no se repite eso con dobles. Lo que sólo el dispositivo puede demostrar es:
 *
 * 1. `mkdir(2)` **exclusivo** falla con `EEXIST` ante un directorio de clave ya existente y el
 *    `rename(2)` **interno** mueve el temporal al nombre fijo dentro del directorio propio (mismo
 *    inodo, `st_nlink == 1`: ni copia ni enlace duro). El backend de producción traduce el `EEXIST`
 *    de `mkdir` a `FileAlreadyExistsException`. **No se usa `link(2)`**: en API 35 SELinux lo deniega
 *    para `untrusted_app` con `EACCES` (evidencia del run E1), y el layout por directorio de clave
 *    no lo necesita;
 * 2. una clave ya reservada no se reutiliza ni se sobrescribe aunque se repita, y el directorio de
 *    clave no se reutiliza ni se reemplaza;
 * 3. el sumidero de producción (`FileOutputStream` + `FileDescriptor.sync`) sincroniza sin error y
 *    los bytes quedan íntegros;
 * 4. tamaño y SHA-256 sellados coinciden con los bytes escritos, y el fichero **corrupto** se
 *    reporta como corrupción sin borrarse ni entregarse, distinto de **ausente**;
 * 5. el volumen declara espacio y el preflight físico rechaza una reserva mayor que el espacio
 *    utilizable sin crear temporales;
 * 6. dos instancias del mismo directorio comparten presupuesto y rechazan límites divergentes
 *    (fail-closed), y la concurrencia con latch no supera la cuota;
 * 7. el raíz lo provee el llamante y **debe existir ya** como directorio real: si falta, el
 *    almacén no lo crea (ni directa ni indirectamente) y rechaza la escritura con
 *    `RootDirectoryUnusable(ABSENT)` sin retener temporales ni directorios, sin declarar
 *    durabilidad y preservando intactos los datos preexistentes del sandbox.
 *
 * **No se afirma** nada sobre contenido de imagen: este almacén sólo sella bytes opacos. No se
 * parsea cabecera PNG, no se comprueban dimensiones y no se toca Room ni la UI.
 *
 * ## Aislamiento
 *
 * Cada prueba usa una subcarpeta nueva en `context.filesDir` cuyo nombre es un UUID impredecible;
 * antes de crearla se comprueba que no existía. Los escenarios que necesitan presupuestos
 * distintos usan subdirectorios propios **dentro** de esa carpeta (el presupuesto pertenece al
 * directorio real y reutilizarlo con otros límites sería, por diseño, un `LimitsConflict`). Los
 * ficheros "preexistentes" que se comprueban son los que la propia prueba escribe en su sandbox.
 *
 * El raíz de cada almacén se crea **explícitamente aquí**, con aserción, porque el contrato del
 * almacén exige un directorio real preexistente provisto por el llamante (en la app, `filesDir`).
 * El escenario de raíz ausente comprueba justo lo contrario: que el almacén no lo crea.
 * `@After` borra exclusivamente la carpeta de esta prueba, comprobando que su nombre es el prefijo
 * más el UUID exacto generado en `@Before`; nunca un fixture, una base de datos ni un fichero ajeno.
 */
@RunWith(AndroidJUnit4::class)
class PrivateAngleImageStoreInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var sandbox: File

    /** UUID exacto del sandbox de esta prueba: única ruta que `@After` está autorizado a borrar. */
    private val sandboxId: String = UUID.randomUUID().toString()

    private val io = AndroidAngleImageFileIo()

    @Before
    fun createSandbox() {
        val root = File(context.filesDir, "$SANDBOX_PREFIX$sandboxId")
        assertFalse("el sandbox no debe existir antes de la prueba: $root", root.exists())
        assertTrue("no se pudo crear el sandbox $root", root.mkdirs())
        sandbox = root
        assertTrue(sandbox.isDirectory)
        // El sandbox debe colgar exactamente de filesDir, no de una ruta resuelta o enlazada.
        assertEquals(context.filesDir.canonicalFile, sandbox.canonicalFile.parentFile)
    }

    @After
    fun deleteSandbox() {
        if (!::sandbox.isInitialized) return
        val target = sandbox
        val parent = target.canonicalFile.parentFile
        assertEquals("sólo se borra un sandbox bajo filesDir", context.filesDir.canonicalFile, parent)
        assertEquals(
            "sólo se borra el sandbox del UUID de esta prueba",
            "$SANDBOX_PREFIX$sandboxId",
            target.name,
        )
        assertEquals(
            "el sufijo del sandbox debe seguir siendo el UUID de esta prueba",
            sandboxId,
            UUID.fromString(target.name.substringAfter(SANDBOX_PREFIX)).toString(),
        )
        assertTrue("el sandbox debe borrarse completo", target.deleteRecursively() || !target.exists())
        assertFalse("el sandbox no debe sobrevivir a la prueba", target.exists())
    }

    // ---------- 1. mkdir exclusivo real, rename interno y sin reutilización de clave ----------

    @Test
    fun publicationReservesKeyWithRealExclusiveMkdirAndNeverReusesIt() {
        val key = "a".repeat(AngleImageKey.HEX_LENGTH)
        val sentinel = sentinelFile()

        // (a) Primitivos reales del backend sobre este volumen: mkdir exclusivo + rename interno.
        val probeRoot = File(sandbox, "mkdir-probe").apply { assertTrue(mkdirs()) }
        val owned = File(probeRoot, "clave-propia")
        val temporary = File(owned, "${AngleImageFileIo.TEMPORARY_FILE_PREFIX}prueba.part")
        val target = File(owned, AngleImageFileIo.FINAL_FILE_NAME)
        val probeBytes = payload(96, seed = 3)
        assertEquals(
            "el backend debe crear el directorio de la clave",
            owned.path,
            io.createKeyDirectory(probeRoot, owned.name).path,
        )
        assertTrue("el directorio de la clave es un directorio real", io.isDirectory(owned))
        try {
            Os.mkdir(owned.path, OsConstants.S_IRWXU)
            fail("un segundo mkdir sobre la misma ruta debía fallar con EEXIST")
        } catch (expected: ErrnoException) {
            assertEquals("el kernel debe devolver EEXIST", OsConstants.EEXIST, expected.errno)
            assertTrue("el directorio existente no se toca", io.isDirectory(owned))
        }
        try {
            io.createKeyDirectory(probeRoot, owned.name)
            fail("el mkdir exclusivo del backend debía rechazar una ruta existente")
        } catch (existing: java.nio.file.FileAlreadyExistsException) {
            assertTrue("la ruta existente se conserva", io.isDirectory(owned))
        }
        io.openSink(temporary).use { sink ->
            sink.write(probeBytes, 0, probeBytes.size)
            sink.sync()
        }
        val beforeRename = Os.lstat(temporary.path)
        io.publishWithinOwnedDirectory(temporary, target, owned)
        val afterRename = Os.lstat(target.path)
        assertEquals(
            "el rename interno conserva el inodo: no hay copia",
            beforeRename.st_ino,
            afterRename.st_ino,
        )
        assertEquals("un único nombre para el fichero: sin enlace duro", 1L, afterRename.st_nlink)
        assertFalse("el temporal ya no existe tras el rename", io.exists(temporary))
        assertArrayEquals(probeBytes, target.readBytes())
        val secondTemporary = File(owned, "${AngleImageFileIo.TEMPORARY_FILE_PREFIX}prueba-2.part")
        io.openSink(secondTemporary).use { sink ->
            sink.write(probeBytes, 0, probeBytes.size)
            sink.sync()
        }
        try {
            io.publishWithinOwnedDirectory(secondTemporary, target, owned)
            fail("publicar sobre el nombre final existente debía fracasar sin reemplazar")
        } catch (existing: java.nio.file.FileAlreadyExistsException) {
            assertArrayEquals("el nombre final no se reemplaza", probeBytes, target.readBytes())
        }
        io.delete(secondTemporary)
        assertEquals("sigue sin enlaces duros", 1L, Os.lstat(target.path).st_nlink)

        // (b) El almacén publica en el directorio de su clave y sella los bytes.
        val root = createStoreRoot("publish")
        val store = store(root = root, key = { key })
        val firstBytes = payload(4_096)
        val first = assertStored(store.write(ByteArrayInputStream(firstBytes), firstBytes.size.toLong()))
        assertEquals(key, first.image.imageKey)
        assertEquals(firstBytes.size.toLong(), first.image.byteCount)
        assertEquals(sha256Hex(firstBytes), first.image.sha256Hex)
        assertFalse(first.temporaryFileRetained)
        val keyDirectory = File(imagesDirectory(root), key)
        assertTrue("el directorio de la clave debe existir", io.isDirectory(keyDirectory))
        assertFalse("el directorio de la clave no puede ser un enlace", io.isSymbolicLink(keyDirectory))
        val published = publicImage(root, key)
        assertTrue("el fichero publicado debe existir", io.isRegularFile(published))
        assertFalse("el fichero publicado no puede ser un enlace simbólico", io.isSymbolicLink(published))
        assertArrayEquals(firstBytes, published.readBytes())
        assertEquals(
            "sólo el nombre fijo dentro del directorio de la clave",
            listOf(AngleImageFileIo.FINAL_FILE_NAME),
            entryNames(keyDirectory),
        )
        assertEquals("un único directorio de clave", listOf(key), publishedKeyNames(root))
        assertEquals("sin temporales propios", emptyList<String>(), temporaryNames(root))
        assertEquals("sin enlaces duros en lo publicado", 1L, Os.lstat(published.path).st_nlink)
        val durability = first.durability
        assertTrue(
            "la durabilidad de directorios debe reportarse tipada (observado: $durability)",
            durability.keyDirectory in AngleImageDirectorySync.entries &&
                durability.parentDirectory in AngleImageDirectorySync.entries,
        )
        val publishedModified = published.lastModified()

        // (c) Repetir la clave no reemplaza el contenido ya publicado ni reutiliza el directorio.
        val secondBytes = payload(4_096, seed = 91)
        val second = assertWriteFailed(
            store.write(ByteArrayInputStream(secondBytes), secondBytes.size.toLong()),
        )
        assertEquals(AngleImageStoreError.KeyAlreadyExists(key), second.error)
        assertFalse(second.temporaryFileRetained)
        assertFalse("el directorio de la clave no se retira", second.directoryRetained)
        assertArrayEquals("los bytes publicados no cambian", firstBytes, published.readBytes())
        assertEquals("el fichero previo no se reescribe", publishedModified, published.lastModified())
        assertEquals(listOf(key), publishedKeyNames(root))
        assertEquals(emptyList<String>(), temporaryNames(root))
        assertTrue(
            store.verify(key, AngleImageIntegrity(firstBytes.size.toLong(), sha256Hex(firstBytes)))
                is AngleImageVerifyResult.Verified,
        )
        assertSentinelIntact(sentinel)
    }

    // ---------- 2. fsync real y lectura verificada acotada ----------

    @Test
    fun realSinkSyncsAndVerifiedReadBoundsDeliveredBytes() {
        val sentinel = sentinelFile()
        // (a) Sumidero de producción sobre el sandbox: escribir, fsync, cerrar y releer.
        val probeDirectory = File(sandbox, "sink-probe")
        io.ensureDirectory(probeDirectory)
        val sinkFile = File(probeDirectory, "sincronizado.bin")
        val sinkBytes = payload(8_192, seed = 7)
        io.openSink(sinkFile).use { sink ->
            sink.write(sinkBytes, 0, sinkBytes.size)
            sink.sync()
        }
        assertEquals(sinkBytes.size.toLong(), sinkFile.length())
        val reread = io.openSource(sinkFile).use { it.readBytes() }
        assertArrayEquals("los bytes sincronizados deben releerse iguales", sinkBytes, reread)

        // (b) Escritura del almacén que cruza varios bloques del búfer interno (64 KiB).
        val root = createStoreRoot("read")
        val store = store(root = root)
        val bytes = payload(200_000, seed = 11)
        val image = assertStored(store.write(ByteArrayInputStream(bytes), bytes.size.toLong())).image
        assertEquals(bytes.size.toLong(), image.byteCount)
        assertEquals(sha256Hex(bytes), image.sha256Hex)
        assertEquals(
            "el uso medido en el volumen real es el del fichero publicado",
            bytes.size.toLong(),
            io.usageBytes(imagesDirectory(root)),
        )
        assertEquals(emptyList<String>(), temporaryNames(root))

        // (c) La lectura verificada entrega exactamente los bytes sellados, no más.
        var consumerCalls = 0
        val result = store.readVerified(
            image.imageKey,
            AngleImageIntegrity(image.byteCount, image.sha256Hex),
        ) { source ->
            consumerCalls++
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            var total = 0L
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                total += read
            }
            assertEquals("el flujo acotado no debe exceder el tamaño verificado", image.byteCount, total)
            assertEquals("después del tamaño verificado el flujo devuelve -1", -1, source.read())
            out.toByteArray()
        }
        val delivered = result as AngleImageReadResult.Delivered
        assertEquals(1, consumerCalls)
        assertEquals(image, delivered.image)
        assertArrayEquals(bytes, delivered.value)
        assertSentinelIntact(sentinel)
    }

    // ---------- 3. Corrupción y ausencia, sin borrar ni reparar ----------

    @Test
    fun corruptionIsReportedWithoutDeletingAndAbsenceIsDistinct() {
        val sentinel = sentinelFile()
        val root = createStoreRoot("corruption")
        val store = store(root = root)
        val bytes = payload(1_024, seed = 21)
        val image = assertStored(store.write(ByteArrayInputStream(bytes), bytes.size.toLong())).image
        val integrity = AngleImageIntegrity(image.byteCount, image.sha256Hex)
        val published = publicImage(root, image.imageKey)
        assertTrue("recién publicado debe verificar", store.verify(image.imageKey, integrity) is AngleImageVerifyResult.Verified)

        // Truncado: el tamaño ya no cuadra.
        val truncatedLength = image.byteCount - 1
        RandomAccessFile(published, "rw").use { it.setLength(truncatedLength) }
        assertEquals(
            AngleImageStoreError.Corrupted(image.imageKey, AngleImageCorruptionReason.SIZE_MISMATCH),
            assertVerifyFailed(store.verify(image.imageKey, integrity)).error,
        )
        assertTrue("la corrupción no se borra", published.isFile)
        assertEquals(truncatedLength, published.length())
        assertConsumerNotInvoked(store, image, integrity)

        // Mismo tamaño, otro contenido: sólo el digest lo detecta.
        published.writeBytes(bytes)
        assertEquals(bytes.size.toLong(), published.length())
        RandomAccessFile(published, "rw").use { handle ->
            handle.seek(0L)
            handle.write(0x00)
        }
        val tampered = published.readBytes()
        assertEquals(
            AngleImageStoreError.Corrupted(image.imageKey, AngleImageCorruptionReason.DIGEST_MISMATCH),
            assertVerifyFailed(store.verify(image.imageKey, integrity)).error,
        )
        assertConsumerNotInvoked(store, image, integrity)
        assertArrayEquals("verificar no repara ni reescribe el fichero", tampered, published.readBytes())

        // Ausente: distinto de corrupto, y sin resucitar nada.
        assertTrue("el borrado del propio fichero de la prueba debe funcionar", published.delete())
        assertEquals(
            AngleImageStoreError.Absent(image.imageKey),
            assertVerifyFailed(store.verify(image.imageKey, integrity)).error,
        )
        assertConsumerNotInvoked(store, image, integrity)
        assertFalse(published.exists())
        assertEquals("no queda ningún final publicado", emptyList<String>(), publishedFileNames(root))
        assertEquals("el directorio de la clave se preserva", listOf(image.imageKey), publishedKeyNames(root))
        assertEquals(emptyList<String>(), temporaryNames(root))
        assertSentinelIntact(sentinel)
    }

    // ---------- 4. Límite físico y errores antes de publicar ----------

    @Test
    fun physicalAndPerFileLimitsAreRejectedWithoutTemporaries() {
        val sentinel = sentinelFile()
        // (a) El volumen declara espacio utilizable de verdad.
        val usable = io.usableSpaceBytes(sandbox)
        assertNotNull("StatFs debe declarar espacio utilizable", usable)
        val usableBytes = requireNotNull(usable) { "StatFs debe declarar espacio utilizable" }
        assertTrue("espacio utilizable inverosímil: $usableBytes", usableBytes > 0L)

        // (b) Pedir más de lo que declara el volumen se rechaza sin escribir nada. El margen
        // (+4 GiB) hace que ninguna variación razonable del espacio libre convierta el rechazo en
        // publicación, sin escribir gigabytes.
        val physicalRoot = createStoreRoot("physical-limit")
        val required = usableBytes + 4L * 1_024L * 1_024L * 1_024L
        val physicalLimits = AngleImageStoreLimits(maxImageBytes = required, maxTotalBytes = required)
        val physical = PrivateAngleImageStore(rootDirectory = physicalRoot, limits = physicalLimits, fileIo = io)
        val physicalResult = assertWriteFailed(
            physical.write(ByteArrayInputStream(payload(16)), expectedByteCount = required),
        )
        val storage = physicalResult.error
        assertTrue("error: $storage", storage is AngleImageStoreError.InsufficientStorage)
        storage as AngleImageStoreError.InsufficientStorage
        assertEquals(required, storage.requiredBytes ?: -1L)
        val usableReported = requireNotNull(storage.usableBytes) { "el error debe declarar el espacio observado" }
        assertTrue("el espacio declarado debe ser menor que lo pedido: $storage", usableReported < required)
        assertFalse(physicalResult.temporaryFileRetained)
        assertEquals("no se crea ningún temporal", emptyList<String>(), temporaryNames(physicalRoot))
        assertEquals("no se publica nada", emptyList<String>(), publishedKeyNames(physicalRoot))

        // (c) Límite por fichero aplicado durante el streaming.
        val fileRoot = createStoreRoot("file-limit")
        val smallLimits = AngleImageStoreLimits(maxImageBytes = 64, maxTotalBytes = 1_024)
        val small = PrivateAngleImageStore(rootDirectory = fileRoot, limits = smallLimits, fileIo = io)
        val tooLarge = assertWriteFailed(small.write(ByteArrayInputStream(payload(65))))
        assertEquals(AngleImageStoreError.TooLarge(64, 65), tooLarge.error)
        assertEquals(emptyList<String>(), temporaryNames(fileRoot))
        assertEquals(emptyList<String>(), publishedKeyNames(fileRoot))

        // (d) Clave no canónica: se rechaza antes de construir la ruta.
        val keyRoot = createStoreRoot("invalid-key")
        val escaping = PrivateAngleImageStore(
            rootDirectory = keyRoot,
            generateKey = { "../../escape.png" },
            fileIo = io,
        )
        assertEquals(
            AngleImageStoreError.InvalidKey("../../escape.png"),
            assertWriteFailed(escaping.write(ByteArrayInputStream(payload(8)), 8L)).error,
        )
        assertFalse("nada se escribe fuera del almacén", File(sandbox, "escape.png").exists())
        assertFalse(File(context.filesDir, "escape.png").exists())
        assertEquals(emptyList<String>(), publishedKeyNames(keyRoot))
        assertSentinelIntact(sentinel)
    }

    // ---------- 5. Dos instancias del mismo directorio ----------

    @Test
    fun twoInstancesOfSameDirectoryShareBudgetAndRejectDivergentLimits() {
        val sentinel = sentinelFile()
        val root = createStoreRoot("shared-budget")
        val limits = AngleImageStoreLimits(maxImageBytes = 600, maxTotalBytes = 1_000)
        val first = PrivateAngleImageStore(rootDirectory = root, limits = limits, fileIo = io)
        val second = PrivateAngleImageStore(rootDirectory = root, limits = limits, fileIo = io)
        val firstBytes = payload(600, seed = 31)
        val firstImage = assertStored(first.write(ByteArrayInputStream(firstBytes), 600L)).image

        val secondResult = assertWriteFailed(second.write(ByteArrayInputStream(payload(500, seed = 41)), 500L))
        val quota = secondResult.error
        assertTrue("error: $quota", quota is AngleImageStoreError.QuotaExceeded)
        quota as AngleImageStoreError.QuotaExceeded
        assertEquals(1_000L, quota.maxTotalBytes)
        assertEquals("el uso compartido es el publicado por la otra instancia", 600L, quota.usedByteCount)
        assertEquals(listOf(firstImage.imageKey), publishedKeyNames(root))
        assertEquals(600L, publicImage(root, firstImage.imageKey).length())
        assertArrayEquals(firstBytes, publicImage(root, firstImage.imageKey).readBytes())

        // Otra instancia con otros límites para el mismo directorio: fail-closed, sin tocar disco.
        val conflictingLimits = AngleImageStoreLimits(maxImageBytes = 600, maxTotalBytes = 2_000)
        val conflicting = PrivateAngleImageStore(rootDirectory = root, limits = conflictingLimits, fileIo = io)
        val conflictResult = assertWriteFailed(conflicting.write(ByteArrayInputStream(payload(32)), 32L))
        assertEquals(
            AngleImageStoreError.LimitsConflict(declared = conflictingLimits, registered = limits),
            conflictResult.error,
        )
        assertEquals("el rechazo no publica", listOf(firstImage.imageKey), publishedKeyNames(root))
        assertEquals(emptyList<String>(), temporaryNames(root))
        assertTrue(
            first.verify(firstImage.imageKey, AngleImageIntegrity(600L, firstImage.sha256Hex))
                is AngleImageVerifyResult.Verified,
        )
        assertSentinelIntact(sentinel)
    }

    // ---------- 6. Concurrencia determinista que no supera la cuota ----------

    @Test
    fun simultaneousWritesWithLatchDoNotExceedSharedQuota() {
        val sentinel = sentinelFile()
        val root = createStoreRoot("concurrent")
        val limits = AngleImageStoreLimits(maxImageBytes = 600, maxTotalBytes = 1_000)
        val stores = listOf(
            PrivateAngleImageStore(rootDirectory = root, limits = limits, fileIo = io),
            PrivateAngleImageStore(rootDirectory = root, limits = limits, fileIo = io),
        )
        val payloads = listOf(payload(600, seed = 51), payload(600, seed = 61))
        val ready = CountDownLatch(stores.size)
        val go = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(stores.size)
        try {
            val futures = stores.mapIndexed { index, store ->
                executor.submit<AngleImageWriteResult> {
                    ready.countDown()
                    assertTrue("la escritura $index debe arrancar con la otra", go.await(10, TimeUnit.SECONDS))
                    store.write(ByteArrayInputStream(payloads[index]), 600L)
                }
            }
            assertTrue("las dos escrituras deben estar listas", ready.await(10, TimeUnit.SECONDS))
            go.countDown()
            val results = futures.map { it.get(30, TimeUnit.SECONDS) }

            val stored = results.filterIsInstance<AngleImageWriteResult.Stored>()
            val failures = results.filterIsInstance<AngleImageWriteResult.Failed>()
            assertEquals("exactamente una publicación: $results", 1, stored.size)
            assertEquals("la otra debe ser un rechazo tipado: $results", 1, failures.size)
            assertTrue("error: ${failures.single().error}", failures.single().error is AngleImageStoreError.QuotaExceeded)

            val keyDirs = imagesDirectory(root).listFiles().orEmpty().filter { it.isDirectory }
            assertEquals("un único directorio de clave", 1, keyDirs.size)
            assertEquals("no quedan temporales propios", emptyList<String>(), temporaryNames(root))
            val publishedFinals = keyDirs.single().listFiles().orEmpty()
                .filter { it.name == AngleImageFileIo.FINAL_FILE_NAME && it.isFile }
            assertEquals("sólo el final dentro del directorio de clave", 1, publishedFinals.size)
            val totalBytes = publishedFinals.sumOf { it.length() }
            assertTrue("la cuota no puede superarse: $totalBytes > 1000", totalBytes <= 1_000L)
            assertEquals(600L, totalBytes)
            val winner = stored.single().image
            assertEquals(keyDirs.single().name, winner.imageKey)
            assertEquals(winner.sha256Hex, sha256Hex(publishedFinals.single().readBytes()))
            assertTrue(
                "el contenido publicado es uno de los enviados",
                payloads.any { it.contentEquals(publishedFinals.single().readBytes()) },
            )
            assertTrue(
                stores[0].verify(winner.imageKey, AngleImageIntegrity(winner.byteCount, winner.sha256Hex))
                    is AngleImageVerifyResult.Verified,
            )
        } finally {
            executor.shutdownNow()
        }
        assertSentinelIntact(sentinel)
    }

    // ---------- 7. Raíz ausente: rechazo tipado, sin crearlo y sin tocar nada ----------

    @Test
    fun absentRootIsRejectedWithoutBeingCreatedAndPreservesPreexistingData() {
        val sentinel = sentinelFile()
        val foreignDirectory = File(sandbox, "ajena").apply {
            assertTrue("no se pudo crear el directorio ajeno del llamante", mkdir())
        }
        val bytes = payload(64, seed = 71)
        val root = File(sandbox, "raiz-ausente")
        assertFalse("el raíz ausente no debe existir antes de la prueba: $root", root.exists())
        assertEquals(
            "el raíz ausente cuelga del sandbox UUID de esta prueba",
            sandbox.canonicalFile,
            root.canonicalFile.parentFile,
        )
        val absent = store(root = root)

        // (a) Sin raíz no hay publicación: error tipado, sin temporales ni directorio retenidos y
        // sin crear el raíz. Repetirlo no lo acaba creando.
        repeat(2) { attempt ->
            val rejected = assertWriteFailed(absent.write(ByteArrayInputStream(bytes), bytes.size.toLong()))
            assertEquals(
                "intento ${attempt + 1}: un raíz ausente debe rechazarse tipado, fue $rejected",
                AngleImageStoreError.RootDirectoryUnusable(AngleImageRootViolation.ABSENT),
                rejected.error,
            )
            assertFalse("no hay temporal propio que retener", rejected.temporaryFileRetained)
            assertFalse("no hay directorio de clave que retener", rejected.directoryRetained)
        }
        assertFalse("el almacén nunca crea el raíz", root.exists())
        assertFalse("tampoco su subdirectorio interno", imagesDirectory(root).exists())
        assertEquals(
            "el sandbox conserva exactamente lo que había (sandbox UUID + sentinela)",
            listOf("ajena", "app-data.sentinel"),
            sandbox.listFiles().orEmpty().map { it.name }.sorted(),
        )
        assertTrue("el directorio preexistente del llamante sobrevive", foreignDirectory.isDirectory)
        assertEquals(
            "no se escribe nada dentro del directorio preexistente",
            emptyList<String>(),
            foreignDirectory.list().orEmpty().toList(),
        )
        assertSentinelIntact(sentinel)

        // (b) Sólo cuando el llamante crea el raíz de verdad, la misma instancia publica y sella:
        // el rechazo anterior era por ausencia, no un fallo genérico.
        assertTrue("el llamante debe poder crear su raíz", root.mkdir())
        assertTrue("el raíz del llamante es un directorio real", io.isDirectory(root))
        val stored = assertStored(absent.write(ByteArrayInputStream(bytes), bytes.size.toLong()))
        assertEquals(bytes.size.toLong(), stored.image.byteCount)
        assertEquals(sha256Hex(bytes), stored.image.sha256Hex)
        assertArrayEquals(bytes, publicImage(root, stored.image.imageKey).readBytes())
        assertEquals(listOf(stored.image.imageKey), publishedKeyNames(root))
        assertEquals(emptyList<String>(), temporaryNames(root))
        assertSentinelIntact(sentinel)
    }

    // ---------- Helpers ----------

    /**
     * Crea el **raíz real y preexistente** del almacén dentro del sandbox UUID de esta prueba.
     *
     * El contrato exige que el raíz lo provea el llamante ya creado (en la app, `filesDir`): el
     * almacén nunca lo crea. Por eso cada escenario lo crea aquí con aserción y comprueba que cuelga
     * del sandbox de la prueba y que no es un enlace (el almacén rechazaría ese raíz).
     */
    private fun createStoreRoot(name: String): File {
        val root = File(sandbox, name)
        assertFalse("el raíz $name no debe existir antes de la prueba: $root", root.exists())
        assertTrue("no se pudo crear el raíz $name", root.mkdir())
        assertTrue("el raíz $name debe ser un directorio real", io.isDirectory(root))
        assertFalse("el raíz $name no puede ser un enlace", io.isSymbolicLink(root))
        assertEquals(
            "el raíz $name debe colgar del sandbox de la prueba",
            sandbox.canonicalFile,
            root.canonicalFile.parentFile,
        )
        return root
    }

    private fun store(root: File, key: (() -> String)? = null): PrivateAngleImageStore =
        PrivateAngleImageStore(
            rootDirectory = root,
            generateKey = key ?: AngleImageKey::generate,
            fileIo = io,
        )

    private fun imagesDirectory(root: File): File =
        File(root, PrivateAngleImageStore.IMAGE_DIRECTORY_NAME)

    /** Directorio propio de la clave (reservado con `mkdir` exclusivo). */
    private fun keyDirectory(root: File, imageKey: String): File =
        File(imagesDirectory(root), imageKey)

    /** Fichero final publicado dentro del directorio de la clave. */
    private fun publicImage(root: File, imageKey: String): File =
        File(keyDirectory(root, imageKey), AngleImageFileIo.FINAL_FILE_NAME)

    /** Nombres de los directorios de clave (un directorio por imagen). */
    private fun publishedKeyNames(root: File): List<String> =
        imagesDirectory(root).listFiles().orEmpty()
            .filter { it.isDirectory }
            .map { it.name }
            .sorted()

    /** Nombres de los ficheros finales publicados dentro de los directorios de clave. */
    private fun publishedFileNames(root: File): List<String> =
        imagesDirectory(root).listFiles().orEmpty()
            .filter { it.isDirectory }
            .flatMap { directory -> directory.listFiles().orEmpty().toList() }
            .filter { it.isFile && it.name == AngleImageFileIo.FINAL_FILE_NAME }
            .map { it.name }
            .sorted()

    private fun entryNames(directory: File): List<String> =
        directory.listFiles().orEmpty().map { it.name }.sorted()

    private fun temporaryNames(root: File): List<String> =
        imagesDirectory(root).listFiles().orEmpty()
            .flatMap { entry -> if (entry.isDirectory) entry.listFiles().orEmpty().toList() else listOf(entry) }
            .filter { it.name.startsWith(AngleImageFileIo.TEMPORARY_FILE_PREFIX) }
            .map { it.name }
            .sorted()

    /** Fichero propio del sandbox que simula los datos preexistentes del llamante. */
    private fun sentinelFile(): ByteArray {
        val bytes = "datos preexistentes del llamante: no tocar".toByteArray()
        File(sandbox, "app-data.sentinel").writeBytes(bytes)
        return bytes
    }

    private fun assertSentinelIntact(expected: ByteArray) {
        val sentinel = File(sandbox, "app-data.sentinel")
        assertTrue("el fichero preexistente debe seguir ahí", sentinel.isFile)
        assertArrayEquals("el fichero preexistente se preserva byte a byte", expected, sentinel.readBytes())
    }

    private fun assertConsumerNotInvoked(
        store: PrivateAngleImageStore,
        image: StoredAngleImage,
        integrity: AngleImageIntegrity,
    ) {
        val calls = AtomicInteger()
        val result = store.readVerified(image.imageKey, integrity) {
            calls.incrementAndGet()
            it.readBytes()
        }
        assertTrue("se esperaba un fallo de lectura: $result", result is AngleImageReadResult.Failed)
        assertEquals("el consumidor no debe recibir bytes no verificados", 0, calls.get())
    }

    private fun payload(size: Int, seed: Int = 17): ByteArray =
        ByteArray(size) { index -> ((index * 31 + seed) and 0xFF).toByte() }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun assertStored(result: AngleImageWriteResult): AngleImageWriteResult.Stored {
        assertTrue("se esperaba Stored, fue $result", result is AngleImageWriteResult.Stored)
        return result as AngleImageWriteResult.Stored
    }

    private fun assertWriteFailed(result: AngleImageWriteResult): AngleImageWriteResult.Failed {
        assertTrue("se esperaba Failed, fue $result", result is AngleImageWriteResult.Failed)
        return result as AngleImageWriteResult.Failed
    }

    private fun assertVerifyFailed(result: AngleImageVerifyResult): AngleImageVerifyResult.Failed {
        assertTrue("se esperaba Failed, fue $result", result is AngleImageVerifyResult.Failed)
        return result as AngleImageVerifyResult.Failed
    }

    private companion object {
        /** Prefijo del sandbox; el resto del nombre es un UUID impredecible. */
        const val SANDBOX_PREFIX: String = "angle-store-instrumented-"
    }
}
