package com.openjump.app.data.angles

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pruebas JVM deterministas de la **reclamación exacta** de una imagen privada publicada
 * ([PrivateAngleImageStore.reclaimVerifiedOwnedImage]).
 *
 * Invariante bajo prueba: la operación retira como máximo `angle-images/<clave>/image.bin`, y sólo
 * después de comprobar clave canónica, integridad esperada, raíz y padres reales (nunca enlaces),
 * layout exacto y tamaño/SHA-256 en disco. Nada de otras claves, vídeo, legado plano, copias de
 * compartir ni temporales se toca; no se crea el raíz ni `angle-images`; el directorio de clave se
 * retira sólo con `rmdir` no recursivo y su suerte y los `fsync` se informan tal cual.
 *
 * Los resultados tipados son el contrato: `Removed`, `Absent` (idempotente), `Retained` (se sabe
 * que no se borró nada) y `Unconfirmed` (se intentó el `unlink` y su efecto no se pudo confirmar;
 * nunca se disfraza de `Retained`). Estos tests no usan Room, ni Android, ni datos de usuario: cada
 * uno vive en su propio [TemporaryFolder].
 */
class PrivateAngleImageStoreDeleteTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(
        root: File,
        limits: AngleImageStoreLimits = AngleImageStoreLimits(),
        io: AngleImageFileIo = NioAngleImageFileIo(),
    ): PrivateAngleImageStore = PrivateAngleImageStore(
        rootDirectory = root,
        limits = limits,
        fileIo = io,
    )

    private fun writeImage(
        root: File,
        bytes: ByteArray,
        limits: AngleImageStoreLimits = AngleImageStoreLimits(),
        io: AngleImageFileIo = NioAngleImageFileIo(),
    ): StoredAngleImage = assertStored(
        store(root, limits, io).write(ByteArrayInputStream(bytes), expectedByteCount = bytes.size.toLong()),
    ).image

    private fun integrityOf(bytes: ByteArray): AngleImageIntegrity =
        AngleImageIntegrity(bytes.size.toLong(), sha256Hex(bytes))

    private fun reclaim(store: PrivateAngleImageStore, image: StoredAngleImage): AngleImageReclaimResult =
        store.reclaimVerifiedOwnedImage(image.imageKey, AngleImageIntegrity(image.byteCount, image.sha256Hex))

    private fun assertRemoved(result: AngleImageReclaimResult): AngleImageReclaimResult.Removed {
        assertTrue("se esperaba Removed, fue $result", result is AngleImageReclaimResult.Removed)
        return result as AngleImageReclaimResult.Removed
    }

    private fun assertAbsent(result: AngleImageReclaimResult): AngleImageReclaimResult.Absent {
        assertTrue("se esperaba Absent, fue $result", result is AngleImageReclaimResult.Absent)
        return result as AngleImageReclaimResult.Absent
    }

    private fun assertRetained(result: AngleImageReclaimResult): AngleImageReclaimResult.Retained {
        assertTrue("se esperaba Retained, fue $result", result is AngleImageReclaimResult.Retained)
        return result as AngleImageReclaimResult.Retained
    }

    private fun assertUnconfirmed(result: AngleImageReclaimResult): AngleImageReclaimResult.Unconfirmed {
        assertTrue("se esperaba Unconfirmed, fue $result", result is AngleImageReclaimResult.Unconfirmed)
        return result as AngleImageReclaimResult.Unconfirmed
    }

    private fun storeErrorOf(retained: AngleImageReclaimResult.Retained): AngleImageStoreError {
        val reason = retained.reason
        assertTrue("se esperaba StoreError, fue $reason", reason is AngleImageReclaimRefusal.StoreError)
        return (reason as AngleImageReclaimRefusal.StoreError).error
    }

    @Test
    fun `retira solo la clave verificada y deja el resto del almacen intacto`() {
        val root = folder.newFolder("reclaim-target")
        val firstBytes = payload(512)
        val secondBytes = payload(256, seed = 7)
        val first = writeImage(root, firstBytes)
        val second = writeImage(root, secondBytes)
        // Datos ajenos que la reclamación no puede tocar: vídeo, copia de compartir, legado plano y
        // un directorio del llamante.
        val video = File(root, "videos").apply { mkdirs() }.resolve("salto.mp4")
            .apply { writeBytes(payload(64, seed = 3)) }
        val shared = File(root, "shared-angle-frames").apply { mkdirs() }.resolve("fotograma.png")
            .apply { writeBytes(payload(48, seed = 4)) }
        val legacy = File(imagesDirectory(root), "legacy-plano.bin").apply { writeBytes(payload(32, seed = 5)) }
        val callerDirectory = File(root, "databases").apply { mkdirs() }
        val videoBytes = video.readBytes()
        val sharedBytes = shared.readBytes()
        val legacyBytes = legacy.readBytes()

        val removed = assertRemoved(reclaim(store(root), first))

        assertEquals(first, removed.image)
        assertFalse("el directorio de la clave quedó vacío: se retira", removed.directoryRetained)
        assertNull("no queda nombre dentro de la clave que confirmar", removed.durability.keyDirectory)
        assertEquals(
            "se confirma el nombre de la clave retirado en angle-images",
            AngleImageDirectorySync.SYNCED,
            removed.durability.imagesDirectory,
        )
        assertTrue(removed.durability.removalEntriesSynced)
        assertFalse("el directorio de la clave retirada no existe", keyDirectory(root, first.imageKey).exists())
        assertTrue("angle-images no se retira", imagesDirectory(root).isDirectory)

        assertArrayEquals("la otra imagen no se toca", secondBytes, publicImage(root, second.imageKey).readBytes())
        assertTrue(
            "la otra imagen sigue verificable",
            store(root).verify(second.imageKey, AngleImageIntegrity(second.byteCount, second.sha256Hex))
                is AngleImageVerifyResult.Verified,
        )
        assertArrayEquals("el vídeo no se toca", videoBytes, video.readBytes())
        assertArrayEquals("la copia de compartir no se toca", sharedBytes, shared.readBytes())
        assertArrayEquals("el legado plano no se toca", legacyBytes, legacy.readBytes())
        assertTrue("el directorio del llamante no se toca", callerDirectory.isDirectory)
    }

    @Test
    fun `una clave ausente es idempotente y nunca crea el almacen`() {
        val root = folder.newFolder("absent-idempotent")
        val io = ReclaimProbedIo()
        val absentKey = numberedKey(0x5a5a)

        val absent = assertAbsent(
            store(root, io = io).reclaimVerifiedOwnedImage(absentKey, integrityOf(payload(64))),
        )

        assertEquals(absentKey, absent.imageKey)
        assertFalse("no se crea angle-images", imagesDirectory(root).exists())
        assertEquals("ninguna mutación intentada", 0, io.deleteCalls)
        assertEquals("ningún rmdir intentado", 0, io.removeDirectoryCalls)
        assertEquals("ningún fsync intentado", emptyList<File>(), io.syncedDirectories)

        // Después de una retirada real, repetir la reclamación sigue siendo ausencia idempotente.
        val bytes = payload(96, seed = 9)
        val image = writeImage(root, bytes)
        assertRemoved(reclaim(store(root), image))
        val repeated = assertAbsent(store(root).reclaimVerifiedOwnedImage(image.imageKey, integrityOf(bytes)))
        assertEquals(image.imageKey, repeated.imageKey)
        assertTrue("angle-images se conserva", imagesDirectory(root).isDirectory)
        assertEquals("el directorio del almacén queda vacío", emptyList<String>(), imagesDirectory(root).list().orEmpty().toList())
    }

    @Test
    fun `una clave no canonica o una integridad invalida se rechazan antes de tocar el disco`() {
        val root = folder.newFolder("invalid-request")
        val bytes = payload(128)
        val image = writeImage(root, bytes)
        val io = ReclaimProbedIo()
        val instance = store(root, io = io)

        val invalidKey = instance.reclaimVerifiedOwnedImage("../../image.bin", integrityOf(bytes))
        assertEquals(
            AngleImageStoreError.InvalidKey("../../image.bin"),
            storeErrorOf(assertRetained(invalidKey)),
        )

        val badDigest = instance.reclaimVerifiedOwnedImage(
            image.imageKey,
            AngleImageIntegrity(bytes.size.toLong(), "no-es-hexadecimal"),
        )
        assertTrue(
            "integridad esperada inutilizable: ${storeErrorOf(assertRetained(badDigest))}",
            storeErrorOf(assertRetained(badDigest)) is AngleImageStoreError.InvalidIntegrityExpectation,
        )

        val zeroBytes = instance.reclaimVerifiedOwnedImage(image.imageKey, AngleImageIntegrity(0L, sha256Hex(bytes)))
        assertTrue(
            "byteCount no positivo: ${storeErrorOf(assertRetained(zeroBytes))}",
            storeErrorOf(assertRetained(zeroBytes)) is AngleImageStoreError.InvalidIntegrityExpectation,
        )

        assertEquals("ningún unlink intentado", 0, io.deleteCalls)
        assertEquals("ningún rmdir intentado", 0, io.removeDirectoryCalls)
        assertArrayEquals("la imagen sigue intacta", bytes, publicImage(root, image.imageKey).readBytes())
    }

    @Test
    fun `un tamano o un digest que no cuadran conservan el fichero`() {
        val root = folder.newFolder("integrity-mismatch")
        val bytes = payload(300)
        val image = writeImage(root, bytes)
        val instance = store(root)

        val wrongDigest = instance.reclaimVerifiedOwnedImage(
            image.imageKey,
            AngleImageIntegrity(bytes.size.toLong(), sha256Hex(payload(300, seed = 99))),
        )
        assertEquals(
            AngleImageStoreError.Corrupted(image.imageKey, AngleImageCorruptionReason.DIGEST_MISMATCH),
            storeErrorOf(assertRetained(wrongDigest)),
        )
        assertArrayEquals("un digest que no cuadra no borra", bytes, publicImage(root, image.imageKey).readBytes())

        val wrongSize = instance.reclaimVerifiedOwnedImage(
            image.imageKey,
            AngleImageIntegrity(bytes.size + 1L, sha256Hex(bytes)),
        )
        assertEquals(
            AngleImageStoreError.Corrupted(image.imageKey, AngleImageCorruptionReason.SIZE_MISMATCH),
            storeErrorOf(assertRetained(wrongSize)),
        )
        assertArrayEquals("un tamaño que no cuadra no borra", bytes, publicImage(root, image.imageKey).readBytes())

        // Con la integridad correcta la misma clave sí se retira: el rechazo no dejó estado raro.
        assertRemoved(reclaim(instance, image))
        assertFalse(publicImage(root, image.imageKey).exists())
    }

    @Test
    fun `los enlaces simbolicos en raiz, almacen, clave o fichero conservan el objetivo externo`() {
        val bytes = payload(64, seed = 2)
        val integrity = integrityOf(bytes)
        val key = fixedKey('e')

        // 1) El raíz es un enlace: no se sigue ni se clasifica como almacén propio.
        val rootCase = folder.newFolder("symlink-root")
        val externalRoot = File(rootCase, "destino-raiz").apply { mkdirs() }
        val externalRootImage = File(externalRoot, ".keep").apply { writeBytes(bytes) }
        val rootLink = File(rootCase, "raiz-enlace")
        Files.createSymbolicLink(rootLink.toPath(), externalRoot.toPath())
        val retainedRoot = assertRetained(store(rootLink).reclaimVerifiedOwnedImage(key, integrity))
        assertEquals(
            AngleImageStoreError.RootDirectoryUnusable(AngleImageRootViolation.SYMBOLIC_LINK),
            storeErrorOf(retainedRoot),
        )
        assertArrayEquals("el objetivo externo no se toca", bytes, externalRootImage.readBytes())
        assertTrue("el enlace no se reemplaza", Files.isSymbolicLink(rootLink.toPath()))

        // 2) `angle-images` es un enlace a un almacén externo con la imagen que cuadraría.
        val imagesCase = folder.newFolder("symlink-images")
        val externalImages = File(imagesCase, "almacen-externo")
        File(externalImages, key).mkdirs()
        val externalImagesFile = File(externalImages, "$key/${AngleImageFileIo.FINAL_FILE_NAME}")
            .apply { writeBytes(bytes) }
        val realRoot = File(imagesCase, "raiz-real").apply { mkdirs() }
        Files.createSymbolicLink(File(realRoot, PrivateAngleImageStore.IMAGE_DIRECTORY_NAME).toPath(), externalImages.toPath())
        val retainedImages = assertRetained(store(realRoot).reclaimVerifiedOwnedImage(key, integrity))
        assertEquals(
            AngleImageStoreError.UnsafePath(key, AngleImagePathViolation.SYMBOLIC_LINK),
            storeErrorOf(retainedImages),
        )
        assertArrayEquals("el almacén externo no se toca", bytes, externalImagesFile.readBytes())

        // 3) El directorio de la clave es un enlace a un directorio externo.
        val keyCase = folder.newFolder("symlink-key")
        val externalKeyDirectory = File(keyCase, "clave-externa").apply { mkdirs() }
        val externalKeyFile = File(externalKeyDirectory, AngleImageFileIo.FINAL_FILE_NAME).apply { writeBytes(bytes) }
        val keyRoot = File(keyCase, "raiz").apply { mkdirs() }
        imagesDirectory(keyRoot).mkdirs()
        Files.createSymbolicLink(File(imagesDirectory(keyRoot), key).toPath(), externalKeyDirectory.toPath())
        val retainedKey = assertRetained(store(keyRoot).reclaimVerifiedOwnedImage(key, integrity))
        assertEquals(
            AngleImageStoreError.UnsafePath(key, AngleImagePathViolation.SYMBOLIC_LINK),
            storeErrorOf(retainedKey),
        )
        assertArrayEquals("el fichero externo no se toca", bytes, externalKeyFile.readBytes())

        // 4) `image.bin` es un enlace a un fichero externo con tamaño y SHA-256 que cuadrarían.
        val fileCase = folder.newFolder("symlink-file")
        val externalFile = File(fileCase, "imagen-externa.bin").apply { writeBytes(bytes) }
        val fileRoot = File(fileCase, "raiz").apply { mkdirs() }
        keyDirectory(fileRoot, key).mkdirs()
        Files.createSymbolicLink(
            File(keyDirectory(fileRoot, key), AngleImageFileIo.FINAL_FILE_NAME).toPath(),
            externalFile.toPath(),
        )
        val retainedFile = assertRetained(store(fileRoot).reclaimVerifiedOwnedImage(key, integrity))
        assertEquals(
            AngleImageStoreError.Corrupted(key, AngleImageCorruptionReason.SYMBOLIC_LINK),
            storeErrorOf(retainedFile),
        )
        assertArrayEquals("el fichero externo no se toca", bytes, externalFile.readBytes())
        assertTrue(
            "el enlace sigue ahí",
            Files.isSymbolicLink(File(keyDirectory(fileRoot, key), AngleImageFileIo.FINAL_FILE_NAME).toPath()),
        )
    }

    @Test
    fun `un temporal o un subdirectorio dentro de la clave conservan todo`() {
        val temporaryRoot = folder.newFolder("unexpected-temporary")
        val temporaryBytes = payload(256)
        val temporaryImage = writeImage(temporaryRoot, temporaryBytes)
        val abandoned = File(keyDirectory(temporaryRoot, temporaryImage.imageKey), "${AngleImageFileIo.TEMPORARY_FILE_PREFIX}abandonado.part")
            .apply { writeBytes(payload(16, seed = 8)) }
        val abandonedBytes = abandoned.readBytes()

        val temporaryRetained = assertRetained(reclaim(store(temporaryRoot), temporaryImage))

        val temporaryRefusal = temporaryRetained.reason
        assertTrue(
            "layout inesperado: $temporaryRefusal",
            temporaryRefusal is AngleImageReclaimRefusal.UnexpectedKeyDirectoryContent,
        )
        temporaryRefusal as AngleImageReclaimRefusal.UnexpectedKeyDirectoryContent
        assertEquals(temporaryImage.imageKey, temporaryRefusal.imageKey)
        assertEquals(listOf(abandoned.name), temporaryRefusal.unexpectedEntryNames)
        assertArrayEquals("la imagen válida se conserva", temporaryBytes, publicImage(temporaryRoot, temporaryImage.imageKey).readBytes())
        assertArrayEquals("el temporal ajeno se conserva", abandonedBytes, abandoned.readBytes())

        val nestedRoot = folder.newFolder("unexpected-subdirectory")
        val nestedImage = writeImage(nestedRoot, payload(128, seed = 12))
        val nested = File(keyDirectory(nestedRoot, nestedImage.imageKey), "subdirectorio").apply { mkdirs() }

        val nestedRetained = assertRetained(reclaim(store(nestedRoot), nestedImage))

        val nestedRefusal = nestedRetained.reason as AngleImageReclaimRefusal.UnexpectedKeyDirectoryContent
        assertEquals(listOf(nested.name), nestedRefusal.unexpectedEntryNames)
        assertTrue("el subdirectorio inesperado se conserva", nested.isDirectory)
        assertTrue("la imagen se conserva", publicImage(nestedRoot, nestedImage.imageKey).isFile)
    }

    @Test
    fun `un fallo del unlink antes de borrar informa presente y conserva el fichero`() {
        val root = folder.newFolder("unlink-before")
        val bytes = payload(192)
        val io = ReclaimProbedIo().apply { failDeleteBefore = true }
        val image = writeImage(root, bytes)
        val instance = store(root, io = io)

        val unconfirmed = assertUnconfirmed(reclaim(instance, image))

        assertEquals(image.imageKey, unconfirmed.imageKey)
        val failure = unconfirmed.reason
        assertTrue("fallo de E/S: $failure", failure is AngleImageStoreError.IoFailure)
        assertEquals(AngleImageStoreStage.CLEANUP, (failure as AngleImageStoreError.IoFailure).stage)
        assertEquals("el fichero seguía ahí", AngleImageReclaimObservation.PRESENT, unconfirmed.observed)
        assertArrayEquals("el fichero se conserva", bytes, publicImage(root, image.imageKey).readBytes())
        assertEquals("no se intenta el rmdir", 0, io.removeDirectoryCalls)
    }

    @Test
    fun `un fallo del unlink despues de borrar no se informa como conservado`() {
        val root = folder.newFolder("unlink-after")
        val bytes = payload(224, seed = 4)
        val io = ReclaimProbedIo().apply { failDeleteAfter = true }
        val image = writeImage(root, bytes)

        val unconfirmed = assertUnconfirmed(reclaim(store(root, io = io), image))

        val failure = unconfirmed.reason
        assertTrue("fallo de E/S: $failure", failure is AngleImageStoreError.IoFailure)
        assertEquals("el unlink sí se aplicó", AngleImageReclaimObservation.ABSENT, unconfirmed.observed)
        assertFalse("el fichero ya no está", publicImage(root, image.imageKey).exists())
        assertEquals("no se intenta el rmdir sin confirmación", 0, io.removeDirectoryCalls)
    }

    @Test
    fun `si el rmdir falla se informa el directorio retenido y su sincronizacion`() {
        // a) El backend rechaza el rmdir: el directorio (vacío) se conserva y se sincroniza.
        val rejectedRoot = folder.newFolder("rmdir-rejected")
        val rejectedBytes = payload(160)
        val rejectedIo = ReclaimProbedIo().apply { removeDirectoryResult = false }
        val rejectedImage = writeImage(rejectedRoot, rejectedBytes)

        val rejected = assertRemoved(reclaim(store(rejectedRoot, io = rejectedIo), rejectedImage))

        assertTrue("el directorio se retiene", rejected.directoryRetained)
        assertEquals(AngleImageDirectorySync.SYNCED, rejected.durability.keyDirectory)
        assertNull("angle-images no cambia si la clave se conserva", rejected.durability.imagesDirectory)
        assertTrue("el unlink sí se confirmó", rejected.durability.removalEntriesSynced)
        assertTrue("el directorio de la clave sigue ahí", keyDirectory(rejectedRoot, rejectedImage.imageKey).isDirectory)
        assertFalse("pero vacío: la imagen no está", publicImage(rejectedRoot, rejectedImage.imageKey).exists())

        // b) El backend lanza en el rmdir: el fichero ya no está; decirlo de otra forma mentiría.
        val throwingRoot = folder.newFolder("rmdir-throwing")
        val throwingIo = ReclaimProbedIo().apply { throwOnRemoveDirectory = true }
        val throwingImage = writeImage(throwingRoot, payload(160, seed = 6))

        val throwing = assertRemoved(reclaim(store(throwingRoot, io = throwingIo), throwingImage))

        assertTrue("el directorio se retiene", throwing.directoryRetained)
        assertEquals(AngleImageDirectorySync.SYNCED, throwing.durability.keyDirectory)
        assertFalse(publicImage(throwingRoot, throwingImage.imageKey).exists())

        // c) `fsync` del directorio de la clave no soportado o fallido: no se declara durabilidad.
        val unsupportedRoot = folder.newFolder("rmdir-unsupported")
        val unsupportedIo = ReclaimProbedIo().apply {
            removeDirectoryResult = false
            syncResult = { AngleImageDirectorySync.UNSUPPORTED }
        }
        val unsupportedImage = writeImage(unsupportedRoot, payload(160, seed = 10))

        val unsupported = assertRemoved(reclaim(store(unsupportedRoot, io = unsupportedIo), unsupportedImage))

        assertTrue(unsupported.directoryRetained)
        assertEquals(AngleImageDirectorySync.UNSUPPORTED, unsupported.durability.keyDirectory)
        assertFalse("un fsync no soportado no declara durabilidad", unsupported.durability.removalEntriesSynced)
        assertFalse(publicImage(unsupportedRoot, unsupportedImage.imageKey).exists())
    }

    @Test
    fun `la durabilidad no se declara si falla el fsync del nombre de la clave retirada`() {
        val failedRoot = folder.newFolder("sync-failed")
        val failedIo = ReclaimProbedIo().apply {
            syncResult = { directory ->
                if (directory.name == PrivateAngleImageStore.IMAGE_DIRECTORY_NAME) {
                    AngleImageDirectorySync.FAILED
                } else {
                    AngleImageDirectorySync.SYNCED
                }
            }
        }
        val failedImage = writeImage(failedRoot, payload(128, seed = 14))

        val failed = assertRemoved(reclaim(store(failedRoot, io = failedIo), failedImage))

        assertFalse(failed.directoryRetained)
        assertNull("no hay nombre dentro de la clave retirada", failed.durability.keyDirectory)
        assertEquals(AngleImageDirectorySync.FAILED, failed.durability.imagesDirectory)
        assertFalse("un fsync fallido no declara la retirada durable", failed.durability.removalEntriesSynced)
        assertFalse(publicImage(failedRoot, failedImage.imageKey).exists())
        assertTrue("el directorio de la clave se retiró igualmente", !keyDirectory(failedRoot, failedImage.imageKey).exists())

        val unsupportedRoot = folder.newFolder("sync-unsupported")
        val unsupportedIo = ReclaimProbedIo().apply {
            syncResult = { directory ->
                if (directory.name == PrivateAngleImageStore.IMAGE_DIRECTORY_NAME) {
                    AngleImageDirectorySync.UNSUPPORTED
                } else {
                    AngleImageDirectorySync.SYNCED
                }
            }
        }
        val unsupportedImage = writeImage(unsupportedRoot, payload(128, seed = 16))

        val unsupported = assertRemoved(reclaim(store(unsupportedRoot, io = unsupportedIo), unsupportedImage))

        assertEquals(AngleImageDirectorySync.UNSUPPORTED, unsupported.durability.imagesDirectory)
        assertFalse("un fsync no soportado no declara durabilidad", unsupported.durability.removalEntriesSynced)
    }

    @Test
    fun `tras reclamar la cuota real admite una escritura nueva`() {
        val root = folder.newFolder("quota-released")
        val limits = AngleImageStoreLimits(maxImageBytes = 600, maxTotalBytes = 1_000)
        val firstBytes = payload(600)
        val secondBytes = payload(600, seed = 6)
        val first = writeImage(root, firstBytes, limits)

        val denied = assertWriteFailed(
            store(root, limits).write(ByteArrayInputStream(secondBytes), expectedByteCount = 600L),
        )
        assertTrue("la cuota debe rechazar la segunda: ${denied.error}", denied.error is AngleImageStoreError.QuotaExceeded)

        assertRemoved(reclaim(store(root, limits), first))

        val second = writeImage(root, secondBytes, limits)
        assertArrayEquals("la escritura nueva se publica entera", secondBytes, publicImage(root, second.imageKey).readBytes())
        assertEquals("la medida real no quedó obsoleta", 600L, publishedBytesOf(root))
    }

    @Test
    fun `un conflicto de limites rechaza la reclamacion sin tocar el fichero`() {
        val root = folder.newFolder("limits-conflict")
        val strictLimits = AngleImageStoreLimits(maxImageBytes = 600, maxTotalBytes = 1_000)
        val looseLimits = AngleImageStoreLimits(maxImageBytes = 600, maxTotalBytes = 2_000)
        val bytes = payload(256)
        val image = writeImage(root, bytes, strictLimits)

        val refused = assertRetained(store(root, looseLimits).reclaimVerifiedOwnedImage(image.imageKey, integrityOf(bytes)))

        val error = storeErrorOf(refused)
        assertTrue("conflicto de límites: $error", error is AngleImageStoreError.LimitsConflict)
        assertEquals(looseLimits, (error as AngleImageStoreError.LimitsConflict).declared)
        assertEquals(strictLimits, error.registered)
        assertArrayEquals("la imagen no se toca", bytes, publicImage(root, image.imageKey).readBytes())

        // El presupuesto registrado sigue operativo: la instancia coherente sí puede reclamar.
        assertRemoved(store(root, strictLimits).reclaimVerifiedOwnedImage(image.imageKey, integrityOf(bytes)))
        assertFalse(publicImage(root, image.imageKey).exists())
    }
}

/**
 * E/S real con ganchos **sólo** para el `unlink`, el `rmdir` y el `fsync` de directorio: permite
 * forzar un fallo antes o después de borrar, un `rmdir` rechazado o lanzado y cualquier resultado de
 * sincronización sin depender del estado del disco. Fuera de los ganchos delega en
 * [NioAngleImageFileIo], así que el disco real sigue siendo el del [TemporaryFolder] de cada prueba.
 */
private class ReclaimProbedIo : NioAngleImageFileIo() {

    var failDeleteBefore: Boolean = false
    var failDeleteAfter: Boolean = false
    var removeDirectoryResult: Boolean? = null
    var throwOnRemoveDirectory: Boolean = false
    var syncResult: (File) -> AngleImageDirectorySync = { AngleImageDirectorySync.SYNCED }

    var deleteCalls: Int = 0
    var removeDirectoryCalls: Int = 0
    val syncedDirectories: MutableList<File> = mutableListOf()

    override fun delete(file: File) {
        deleteCalls++
        if (failDeleteBefore) throw IOException("unlink denegado por la prueba, antes de borrar")
        super.delete(file)
        if (failDeleteAfter) throw IOException("unlink aplicado y luego falló (inyectado por la prueba)")
    }

    override fun removeEmptyDirectory(directory: File): Boolean {
        removeDirectoryCalls++
        if (throwOnRemoveDirectory) throw IOException("rmdir inyectado por la prueba")
        removeDirectoryResult?.let { return it }
        return super.removeEmptyDirectory(directory)
    }

    override fun syncDirectory(directory: File): AngleImageDirectorySync {
        syncedDirectories += directory
        return syncResult(directory)
    }
}
