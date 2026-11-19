package com.openjump.app.data.backup

import com.openjump.app.data.angles.AngleImageDirectorySync
import com.openjump.app.data.angles.AngleImageDurability
import com.openjump.app.data.angles.AngleImageKey
import com.openjump.app.data.angles.AngleImageStoreError
import com.openjump.app.data.angles.AngleImageWriteResult
import com.openjump.app.data.angles.NioAngleImageFileIo
import com.openjump.app.data.angles.PrivateAngleImageStore
import com.openjump.app.data.angles.StoredAngleImage
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Contrato JVM de la preparacion de imagenes de restore (G4a) con **puertos fake** de copia, probe,
 * almacen y sistema de ficheros.
 *
 * Lo que se demuestra aqui, sin Android, sin Room y sin dispositivo:
 *  - el mapeo `clave importada -> [StoredAngleImage]` solo se entrega cuando **todas** las imagenes
 *    estan publicadas, verificadas y con clave nueva; es inmutable y de orden determinista, y no
 *    contiene rutas ni URIs;
 *  - cada etapa (spool, copia, `fsync`, probe, almacen) falla de forma tipada y **nunca** devuelve un
 *    parcial valido: un fallo en la segunda imagen descarta la primera;
 *  - una fuente demasiado larga la rechaza el tope propio del spool, no el disco;
 *  - la cancelacion se respeta antes de tocar disco y durante el almacen;
 *  - los presupuestos (por imagen, total y numero) se comprueban antes de cualquier E/S;
 *  - se borran **solo** los spools propios (nunca un fichero del almacen ni un ajeno), tambien en
 *    exito, y un borrado fallido se informa preservando el fichero;
 *  - las claves ya publicadas cuando el fallo ocurre despues se informan como huerfanos y se
 *    **conservan**, tambien si el fallo llega al cerrar el flujo de lectura despues de publicar;
 *  - las claves cuya publicacion el almacen declara **incierta** viajan en una lista aparte, se
 *    conservan y nunca se presentan como verificadas;
 *  - los residuos que informa el almacen (`temporaryFileRetained`/`directoryRetained`) se propagan
 *    tal cual, incluida la cancelacion, sin disfrazarlos de fallo de limpieza de spool;
 *  - el resultado del almacen se conserva **siempre** antes de cerrar el flujo de lectura: si el
 *    cierre tambien falla, no se pierde ni la publicacion incierta ni los residuos y el fallo del
 *    almacen y el de cierre se informan como dos causas distintas, sin fingir exito; el cierre puede
 *    fallar con `IOException` o con `RuntimeException`, y ninguna de las dos rutas descarta el
 *    resultado autoritativo del almacen ni finge exito;
 *  - la pieza es bloqueante y **no** expone ningun adaptador `suspend`;
 *  - una clave nueva repetida o igual a la importada se rechaza como error de identidad.
 *
 * El unico punto de contacto con disco es el directorio temporal de JUnit que hace de staging
 * privado: los ficheros se crean y se borran de verdad para poder afirmar que no queda basura y que
 * no se toca nada ajeno.
 */
private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { String.format(Locale.ROOT, "%02x", it) }

class MediaBackupImagePreparationTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    // --- 1. Camino feliz ------------------------------------------------------------------------

    @Test
    fun preparesEveryImageWithNewKeysAndDeterministicImmutableMapping() {
        val staging = newStaging()
        val sentinel = UUID.randomUUID()
        val first = declaration(1, payload = "uno".toByteArray(), width = 3, height = 4)
        val second = declaration(2, payload = "dos-dos".toByteArray(), width = 5, height = 6)
        val source = FakeSource(listOf(second, first), payloads = payloadsOf(second, first))
        val filePort = FakeFilePort(staging)
        val store = FakeStore(newKeys = listOf(NEW_KEY_B, NEW_KEY_A))

        val result = preparer(staging, filePort, store = store).prepare(source)

        val prepared = result as MediaBackupImagePreparationResult.Prepared
        // Orden determinista por clave importada ascendente (la fuente las dio al reves).
        assertEquals(listOf(first.imageKey, second.imageKey), prepared.images.byImportedKey.keys.toList())
        assertEquals(listOf(NEW_KEY_B, NEW_KEY_A), prepared.images.storedKeys)
        val storedFirst = prepared.images.byImportedKey.getValue(first.imageKey)
        assertEquals(NEW_KEY_B, storedFirst.imageKey)
        assertEquals(first.byteCount, storedFirst.byteCount)
        assertEquals(first.sha256, storedFirst.sha256Hex)
        assertEquals(first.byteCount + second.byteCount, prepared.images.totalByteCount)
        assertEquals(2, prepared.images.count)
        // El almacen fake declara durabilidad confirmada por defecto: se refleja tal cual.
        assertTrue(prepared.images.fullyDurable)
        assertEquals(emptyList<String>(), prepared.images.unsyncedStoredKeys)

        // La clave importada NUNCA se reutiliza y el mapeo es inmutable.
        assertNotEquals(first.imageKey, storedFirst.imageKey)
        try {
            (prepared.images.byImportedKey as MutableMap<String, StoredAngleImage>)[first.imageKey] = storedFirst
            throw AssertionError("the mapping must be immutable")
        } catch (_: UnsupportedOperationException) {
            // correcto
        }

        // El almacen recibe exactamente los bytes del spool (no bytes inventados ni vacios).
        assertEquals(first.bytes().toList(), store.delivered[0].toList())
        assertEquals(second.bytes().toList(), store.delivered[1].toList())
        assertEquals(listOf(first.byteCount, second.byteCount), store.declaredByteCounts)

        // Ni un spool queda en el staging: se limpian tambien en exito.
        assertEquals(emptyList<String>(), filePort.createdNames.filter { File(staging, it).exists() })
        assertEquals(filePort.createdNames.size, filePort.deletedNames.size)
        assertTrue("se creo un spool por imagen", filePort.createdNames.size == 2)

        // Ninguna ruta ni URI en el resultado: solo claves opacas hex32 y sellos.
        val text = prepared.images.byImportedKey.keys.joinToString() + prepared.images.storedKeys.joinToString()
        assertFalse("el resultado no puede contener rutas", text.contains("/") || text.contains(staging.name))
        assertFalse("el resultado no puede contener el UUID del sandbox", text.contains(sentinel.toString()))
        assertTrue(prepared.images.storedKeys.all { it.matches(Regex("[0-9a-f]{32}")) })
    }

    @Test
    fun zeroImagesReturnsAnEmptyPreparedSetWithoutTouchingDisk() {
        val staging = newStaging()
        val filePort = FakeFilePort(staging)
        val store = FakeStore(newKeys = emptyList())

        val result = preparer(staging, filePort, store = store).prepare(FakeSource(emptyList(), emptyMap()))

        val prepared = result as MediaBackupImagePreparationResult.Prepared
        assertEquals(0, prepared.images.count)
        assertEquals(emptyList<String>(), prepared.images.storedKeys)
        assertEquals(0L, prepared.images.totalByteCount)
        assertEquals(emptyList<String>(), filePort.createdNames)
        assertEquals(emptyList<Long>(), store.declaredByteCounts)
    }

    // --- 2. Fallo en una etapa concreta ---------------------------------------------------------

    @Test
    fun spoolCreationFailureIsTypedAndLeavesNothingPublished() {
        val staging = newStaging()
        val declaration = declaration(1, "a".toByteArray(), width = 2, height = 2)
        val filePort = FakeFilePort(staging, failCreate = true)
        val store = FakeStore(listOf(NEW_KEY_A))

        val failedResult = failed(preparer(staging, filePort, store = store).prepare(sourceOf(declaration)))

        assertEquals(MediaBackupPreparationStage.SPOOL, failedResult.failure.stage())
        assertEquals(declaration.imageKey, failedResult.failure.imageKey())
        assertEquals(emptyList<String>(), failedResult.publishedOrphanKeys)
        assertEquals(emptyList<Long>(), store.declaredByteCounts)
    }

    @Test
    fun copyFailureOnSecondImageDiscardsTheFirstAndReportsItAsOrphan() {
        val staging = newStaging()
        val first = declaration(1, "primera".toByteArray(), width = 6, height = 7)
        val second = declaration(2, "segunda".toByteArray(), width = 8, height = 9)
        val source = FakeSource(
            listOf(first, second),
            payloadsOf(first, second),
            failKeys = setOf(second.imageKey),
        )
        val filePort = FakeFilePort(staging)
        val store = FakeStore(listOf(NEW_KEY_A, NEW_KEY_B))

        val result = preparer(staging, filePort, store = store).prepare(source)

        val failed = result as MediaBackupImagePreparationResult.Failed
        assertEquals(MediaBackupPreparationStage.COPY, failed.failure.stage())
        assertEquals(second.imageKey, failed.failure.imageKey())
        // La clave ya publicada se informa como huerfano y NO se borra.
        assertEquals(listOf(NEW_KEY_A), failed.publishedOrphanKeys)
        assertEquals(emptyList<String>(), store.deletedKeys)
        assertEquals(1, store.declaredByteCounts.size)
        // Todos los spools propios se borran, incluido el de la imagen que fallo.
        assertEquals(filePort.createdNames.size, filePort.deletedNames.size)
        assertTrue("ningun spool propio debe sobrevivir", filePort.createdNames.none { File(staging, it).exists() })
    }

    @Test
    fun partialCorruptionWhileCopyingLeavesNoSpoolBehind() {
        val staging = newStaging()
        val declaration = declaration(1, "0123456789".toByteArray(), width = 4, height = 4)
        val filePort = FakeFilePort(staging)
        val store = FakeStore(listOf(NEW_KEY_A))
        val source = FakeSource(
            listOf(declaration),
            mapOf(declaration.imageKey to declaration.bytes()),
            partialWriteKeys = setOf(declaration.imageKey),
        )

        val failed = failed(preparer(staging, filePort, store = store).prepare(source))

        assertEquals(MediaBackupPreparationStage.COPY, failed.failure.stage())
        assertEquals(emptyList<Long>(), store.declaredByteCounts)
        assertEquals(filePort.createdNames.size, filePort.deletedNames.size)
        assertTrue(filePort.createdNames.none { File(staging, it).exists() })
    }

    @Test
    fun missingSourceImageIsReportedAsCopyFailure() {
        val staging = newStaging()
        val declaration = declaration(1, "x".toByteArray(), width = 1, height = 1)
        val source = FakeSource(
            listOf(declaration),
            payloadsOf(declaration),
            unavailableKeys = setOf(declaration.imageKey),
        )

        val failed = failed(preparer(staging, FakeFilePort(staging), store = FakeStore(listOf(NEW_KEY_A))).prepare(source))

        assertEquals(MediaBackupPreparationStage.COPY, failed.failure.stage())
        assertTrue(failed.failure.cause is MediaBackupImportException.ImageUnavailable)
    }

    @Test
    fun sourceTooLongIsRejectedByTheOwnSpoolBoundBeforeTheStore() {
        val staging = newStaging()
        val declaration = declaration(1, "cuatro".toByteArray(), width = 2, height = 3)
        val store = FakeStore(listOf(NEW_KEY_A))
        val source = FakeSource(
            listOf(declaration),
            payloadsOf(declaration),
            extraBytesKeys = setOf(declaration.imageKey),
        )

        val failed = failed(preparer(staging, FakeFilePort(staging), store = store).prepare(source))

        assertEquals(MediaBackupPreparationStage.SPOOL, failed.failure.stage())
        assertEquals(declaration.imageKey, failed.failure.imageKey())
        assertEquals(emptyList<Long>(), store.declaredByteCounts)
        assertEquals(emptyList<String>(), failed.publishedOrphanKeys)
    }

    @Test
    fun transferredIntegrityDifferentFromTheDeclarationIsRejected() {
        val staging = newStaging()
        val declaration = declaration(1, "sello".toByteArray(), width = 3, height = 3)
        val store = FakeStore(listOf(NEW_KEY_A))
        val source = FakeSource(
            listOf(declaration),
            payloadsOf(declaration),
            wrongShaKeys = setOf(declaration.imageKey),
        )

        val failed = failed(preparer(staging, FakeFilePort(staging), store = store).prepare(source))

        assertEquals(MediaBackupPreparationStage.COPY, failed.failure.stage())
        assertEquals(emptyList<Long>(), store.declaredByteCounts)
    }

    @Test
    fun fsyncFailureStopsBeforeProbeAndStore() {
        val staging = newStaging()
        val declaration = declaration(1, "fsync".toByteArray(), width = 3, height = 3)
        val filePort = FakeFilePort(staging, failSync = true)
        val store = FakeStore(listOf(NEW_KEY_A))
        val probe = FakeProbe()

        val failed = failed(preparer(staging, filePort, store = store, probe = probe).prepare(sourceOf(declaration)))

        assertEquals(MediaBackupPreparationStage.SYNC, failed.failure.stage())
        assertEquals(emptyList<String>(), probe.probedKeys)
        assertEquals(emptyList<Long>(), store.declaredByteCounts)
        assertEquals(filePort.createdNames.size, filePort.deletedNames.size)
    }

    @Test
    fun pngProbeRejectionStopsBeforePublication() {
        val staging = newStaging()
        val declaration = declaration(1, "png".toByteArray(), width = 7, height = 7)
        val store = FakeStore(listOf(NEW_KEY_A))
        val probe = FakeProbe(failKeys = setOf(declaration.imageKey))

        val failed = failed(preparer(staging, FakeFilePort(staging), store = store, probe = probe).prepare(sourceOf(declaration)))

        assertEquals(MediaBackupPreparationStage.PROBE, failed.failure.stage())
        assertEquals(listOf(declaration.imageKey), probe.probedKeys)
        assertEquals(emptyList<Long>(), store.declaredByteCounts)
        assertEquals(emptyList<String>(), failed.publishedOrphanKeys)
    }

    @Test
    fun probeIdentityDifferentFromTheDeclarationIsRejected() {
        val staging = newStaging()
        val declaration = declaration(1, "png".toByteArray(), width = 7, height = 7)
        val store = FakeStore(listOf(NEW_KEY_A))
        val probe = FakeProbe(wrongDimensionsKeys = setOf(declaration.imageKey))

        val failed = failed(preparer(staging, FakeFilePort(staging), store = store, probe = probe).prepare(sourceOf(declaration)))

        assertEquals(MediaBackupPreparationStage.PROBE, failed.failure.stage())
        assertEquals(emptyList<Long>(), store.declaredByteCounts)
    }

    @Test
    fun enospcFromTheStoreIsReportedAsAStoreRejection() {
        val staging = newStaging()
        val declaration = declaration(1, "disco".toByteArray(), width = 5, height = 5)
        val store = FakeStore(listOf(NEW_KEY_A), storeError = AngleImageStoreError.InsufficientStorage(10L, 0L))
        val filePort = FakeFilePort(staging)

        val failed = failed(preparer(staging, filePort, store = store).prepare(sourceOf(declaration)))

        val rejection = failed.failure as MediaBackupPreparationFailure.StoreRejected
        assertEquals(declaration.imageKey, rejection.imageKey)
        assertTrue(rejection.error is AngleImageStoreError.InsufficientStorage)
        assertEquals(emptyList<String>(), failed.publishedOrphanKeys)
        assertEquals(filePort.createdNames.size, filePort.deletedNames.size)
    }

    @Test
    fun unexpectedStoreIOExceptionIsReportedAsStepFailure() {
        val staging = newStaging()
        val declaration = declaration(1, "boom".toByteArray(), width = 2, height = 2)
        val store = FakeStore(listOf(NEW_KEY_A), throwOnWrite = IOException("disk gone"))

        val failed = failed(preparer(staging, FakeFilePort(staging), store = store).prepare(sourceOf(declaration)))

        assertEquals(MediaBackupPreparationStage.STORE, failed.failure.stage())
    }

    // --- 3. Identidad de la clave publicada -----------------------------------------------------

    @Test
    fun unsyncedDirectoryDurabilityIsReportedAndNeverClaimedAsDurable() {
        val staging = newStaging()
        val declaration = declaration(1, "durabilidad".toByteArray(), width = 2, height = 2)
        val store = FakeStore(listOf(NEW_KEY_A), durability = UNCONFIRMED_DURABILITY)

        val prepared = preparer(staging, FakeFilePort(staging), store = store)
            .prepare(sourceOf(declaration)) as MediaBackupImagePreparationResult.Prepared

        assertEquals(listOf(NEW_KEY_A), prepared.images.storedKeys)
        assertFalse("no se puede afirmar durabilidad no confirmada", prepared.images.fullyDurable)
        assertEquals(listOf(NEW_KEY_A), prepared.images.unsyncedStoredKeys)
    }

    @Test
    fun storeReturningTheImportedKeyIsRejectedAndTheKeyIsPreserved() {
        val staging = newStaging()
        val declaration = declaration(1, "misma".toByteArray(), width = 2, height = 2)
        val store = FakeStore(listOf(declaration.imageKey))

        val failed = failed(preparer(staging, FakeFilePort(staging), store = store).prepare(sourceOf(declaration)))

        assertEquals(MediaBackupPreparationStage.STORE, failed.failure.stage())
        assertTrue(failed.failure is MediaBackupPreparationFailure.IdentityMismatch)
        assertEquals(listOf(declaration.imageKey), failed.publishedOrphanKeys)
        assertEquals(emptyList<String>(), store.deletedKeys)
    }

    @Test
    fun storeReturningADifferentDigestIsRejectedAndTheKeyIsPreserved() {
        val staging = newStaging()
        val declaration = declaration(1, "digest".toByteArray(), width = 2, height = 2)
        val store = FakeStore(listOf(NEW_KEY_A), storedShaOverride = "0".repeat(64))

        val failed = failed(preparer(staging, FakeFilePort(staging), store = store).prepare(sourceOf(declaration)))

        assertEquals(MediaBackupPreparationStage.STORE, failed.failure.stage())
        assertTrue(failed.failure is MediaBackupPreparationFailure.IdentityMismatch)
        assertEquals(listOf(NEW_KEY_A), failed.publishedOrphanKeys)
        assertEquals(emptyList<String>(), store.deletedKeys)
    }

    @Test
    fun duplicateNewKeyAcrossImagesIsRejected() {
        val staging = newStaging()
        val first = declaration(1, "uno".toByteArray(), width = 2, height = 2)
        val second = declaration(2, "dos".toByteArray(), width = 2, height = 2)
        val store = FakeStore(listOf(NEW_KEY_A, NEW_KEY_A))

        val failed = failed(
            preparer(staging, FakeFilePort(staging), store = store)
                .prepare(FakeSource(listOf(first, second), payloadsOf(first, second))),
        )

        assertEquals(MediaBackupPreparationStage.STORE, failed.failure.stage())
        assertEquals(second.imageKey, failed.failure.imageKey())
        // Una sola clave huerfana (deduplicada) y ninguna imagen borrada.
        assertEquals(listOf(NEW_KEY_A), failed.publishedOrphanKeys)
        assertEquals(emptyList<String>(), store.deletedKeys)
    }

    // --- 4. Cancelacion -------------------------------------------------------------------------

    @Test
    fun cancellationBeforeStartCreatesNoSpoolAndPublishesNothing() {
        val staging = newStaging()
        val declaration = declaration(1, "cancel".toByteArray(), width = 2, height = 2)
        val filePort = FakeFilePort(staging)
        val store = FakeStore(listOf(NEW_KEY_A))

        val failed = failed(
            preparer(staging, filePort, store = store)
                .prepare(sourceOf(declaration), MediaBackupArchiveCancellation { true }),
        )

        assertTrue(failed.failure is MediaBackupPreparationFailure.Canceled)
        assertEquals(emptyList<String>(), filePort.createdNames)
        assertEquals(emptyList<Long>(), store.declaredByteCounts)
    }

    @Test
    fun cancellationBetweenImagesStopsWithoutPublishingTheSecond() {
        val staging = newStaging()
        val first = declaration(1, "aa".toByteArray(), width = 2, height = 2)
        val second = declaration(2, "bb".toByteArray(), width = 2, height = 2)
        val store = FakeStore(listOf(NEW_KEY_A, NEW_KEY_B))
        // La comprobacion inicial consume una consulta y la de la primera imagen otra: con 2, la
        // cancelacion llega justo antes de la segunda imagen.
        var remaining = 2

        val failed = failed(
            preparer(staging, FakeFilePort(staging), store = store)
                .prepare(FakeSource(listOf(first, second), payloadsOf(first, second))) { remaining-- <= 0 },
        )

        assertTrue(failed.failure is MediaBackupPreparationFailure.Canceled)
        assertEquals(listOf(first.byteCount), store.declaredByteCounts)
        assertEquals(listOf(NEW_KEY_A), failed.publishedOrphanKeys)
        assertEquals(emptyList<String>(), store.deletedKeys)
    }

    @Test
    fun storeCancellationIsReportedAsCanceledAndNothingIsValid() {
        val staging = newStaging()
        val declaration = declaration(1, "cancel-store".toByteArray(), width = 2, height = 2)
        val store = FakeStore(listOf(NEW_KEY_A), storeError = AngleImageStoreError.Canceled)

        val failed = failed(preparer(staging, FakeFilePort(staging), store = store).prepare(sourceOf(declaration)))

        assertTrue(failed.failure is MediaBackupPreparationFailure.Canceled)
        assertEquals(emptyList<String>(), failed.publishedOrphanKeys)
    }

    // --- 5. Presupuestos antes de la E/S --------------------------------------------------------

    @Test
    fun perImageBudgetIsRejectedBeforeCreatingASpool() {
        val staging = newStaging()
        val declaration = declaration(1, "demasiado".toByteArray(), width = 2, height = 2)
        val filePort = FakeFilePort(staging)
        val store = FakeStore(listOf(NEW_KEY_A))

        val failed = failed(
            preparer(staging, filePort, store = store, limits = MediaBackupPreparationLimits(maxImageBytes = declaration.byteCount - 1, maxTotalBytes = 1024L))
                .prepare(sourceOf(declaration)),
        )

        assertTrue(failed.failure is MediaBackupPreparationFailure.BudgetExceeded)
        assertEquals(emptyList<String>(), filePort.createdNames)
        assertEquals(emptyList<Long>(), store.declaredByteCounts)
    }

    @Test
    fun totalBudgetIsRejectedWithAnExactSumBeforeCreatingASpool() {
        val staging = newStaging()
        val first = declaration(1, ByteArray(8) { 1 }, width = 2, height = 2)
        val second = declaration(2, ByteArray(8) { 2 }, width = 2, height = 2)
        val filePort = FakeFilePort(staging)

        val failed = failed(
            preparer(
                staging,
                filePort,
                store = FakeStore(listOf(NEW_KEY_A, NEW_KEY_B)),
                limits = MediaBackupPreparationLimits(maxImageBytes = 8L, maxTotalBytes = 15L),
            ).prepare(FakeSource(listOf(first, second), payloadsOf(first, second))),
        )

        assertTrue(failed.failure is MediaBackupPreparationFailure.BudgetExceeded)
        assertEquals(emptyList<String>(), filePort.createdNames)
    }

    @Test
    fun imageCountBudgetIsRejectedBeforeCreatingASpool() {
        val staging = newStaging()
        val first = declaration(1, "a".toByteArray(), width = 1, height = 1)
        val second = declaration(2, "b".toByteArray(), width = 1, height = 1)
        val filePort = FakeFilePort(staging)

        val failed = failed(
            preparer(staging, filePort, store = FakeStore(listOf(NEW_KEY_A)), limits = MediaBackupPreparationLimits(maxImages = 1))
                .prepare(FakeSource(listOf(first, second), payloadsOf(first, second))),
        )

        assertTrue(failed.failure is MediaBackupPreparationFailure.BudgetExceeded)
        assertEquals(emptyList<String>(), filePort.createdNames)
    }

    @Test
    fun repeatedDeclaredKeyIsRejectedAsSourceContractFailure() {
        val staging = newStaging()
        val declaration = declaration(1, "dup".toByteArray(), width = 2, height = 2)
        val filePort = FakeFilePort(staging)

        val failed = failed(
            preparer(staging, filePort, store = FakeStore(listOf(NEW_KEY_A)))
                .prepare(FakeSource(listOf(declaration, declaration), mapOf(declaration.imageKey to declaration.bytes()))),
        )

        assertTrue(failed.failure is MediaBackupPreparationFailure.SourceRejected)
        assertEquals(emptyList<String>(), filePort.createdNames)
    }

    // --- 6. Limpieza propia y huerfanos ---------------------------------------------------------

    @Test
    fun spoolCleanupFailureIsReportedAndTheSpoolIsPreserved() {
        val staging = newStaging()
        val declaration = declaration(1, "limpieza".toByteArray(), width = 2, height = 2)
        val filePort = FakeFilePort(staging, failDelete = true)

        val prepared = preparer(staging, filePort, store = FakeStore(listOf(NEW_KEY_A)))
            .prepare(sourceOf(declaration)) as MediaBackupImagePreparationResult.Prepared

        // Las imagenes estan publicadas y verificadas, pero el spool que no se pudo borrar se informa.
        assertTrue("un spool que no se pudo borrar se informa en el exito", prepared.spoolCleanupFailed)
        assertEquals(listOf(NEW_KEY_A), prepared.images.storedKeys)
        assertEquals(1, filePort.createdNames.size)
        assertTrue("el spool propio se preserva si no se pudo borrar", File(staging, filePort.createdNames.single()).exists())
    }

    @Test
    fun failureNeverDeletesForeignFilesInTheStagingDirectory() {
        val staging = newStaging()
        val sentinel = File(staging, "sentinel-ajeno.bin").apply { writeBytes("ajeno".toByteArray()) }
        val foreignDirectory = File(staging, "directorio-ajeno").apply { mkdirs() }
        val declaration = declaration(1, "propio".toByteArray(), width = 2, height = 2)
        val store = FakeStore(listOf(NEW_KEY_A), throwOnWrite = IOException("boom"))

        val failed = failed(
            preparer(staging, FakeFilePort(staging), store = store).prepare(sourceOf(declaration)),
        )

        assertEquals(MediaBackupPreparationStage.STORE, failed.failure.stage())
        assertTrue("un fichero ajeno debe sobrevivir", sentinel.isFile)
        assertEquals("ajeno", sentinel.readText())
        assertTrue("un directorio ajeno debe sobrevivir", foreignDirectory.isDirectory)
    }

    // --- 7. Frontera del llamante: sin adaptador suspend ---------------------------------------

    @Test
    fun thePreparerExposesNoSuspendAdapterAndPrepareReturnsTheResultDirectly() {
        val suspendAdapters = MediaBackupImagePreparer::class.java.declaredMethods.filter { method ->
            method.parameterTypes.any { it.name == "kotlin.coroutines.Continuation" }
        }
        assertTrue("no debe existir ningun adaptador suspend: ${suspendAdapters.map { it.name }}", suspendAdapters.isEmpty())

        val prepareMethods = MediaBackupImagePreparer::class.java.declaredMethods.filter { it.name == "prepare" }
        assertTrue("prepare debe existir", prepareMethods.isNotEmpty())
        assertTrue(
            "prepare es bloqueante y devuelve el resultado, no una promesa",
            prepareMethods.all { it.returnType.name == MediaBackupImagePreparationResult::class.java.name },
        )
    }

    // --- 8. Publicacion registrada antes del cierre del flujo ----------------------------------

    @Test
    fun publishedKeyIsRegisteredBeforeTheSpoolReadStreamCloses() {
        val staging = newStaging()
        val declaration = declaration(1, "cierre".toByteArray(), width = 3, height = 3)
        val store = FakeStore(listOf(NEW_KEY_A))
        val filePort = FakeFilePort(staging, failSourceCloseOnOpen = 2)

        val failedResult = failed(preparer(staging, filePort, store = store).prepare(sourceOf(declaration)))

        assertEquals("el probe abre una vez y el almacen otra", 2, filePort.sourceOpens)
        assertEquals(MediaBackupPreparationStage.STORE, failedResult.failure.stage())
        assertTrue("el cierre del spool es la causa del fallo", failedResult.failure.cause is IOException)
        // El almacen SI publico antes de que fallara el cierre: la clave se conserva y se informa.
        assertEquals(listOf(NEW_KEY_A), failedResult.publishedOrphanKeys)
        assertEquals(emptyList<String>(), failedResult.uncertainPublishedKeys)
        assertEquals(emptyList<String>(), store.deletedKeys)
        assertEquals(listOf(declaration.byteCount), store.declaredByteCounts)
        assertTrue("el fallo es un StepFailed, no un resultado parcial", failedResult.failure is MediaBackupPreparationFailure.StepFailed)
        assertEquals("el spool propio se borra aunque falle el cierre", filePort.createdNames.size, filePort.deletedNames.size)
    }

    @Test
    fun closeFailureAfterRealStorePublicationStillReportsThePublishedKey() {
        val staging = newStaging()
        val storeRoot = temporaryFolder.newFolder("store-${UUID.randomUUID()}")
        val realStore = PrivateAngleImageStore(rootDirectory = storeRoot, fileIo = NioAngleImageFileIo())
        val declaration = declaration(1, "real".toByteArray(), width = 2, height = 2)
        val filePort = FakeFilePort(staging, failSourceCloseOnOpen = 2)
        val preparer = MediaBackupImagePreparer(
            stagingDirectory = staging,
            probe = FakeProbe(),
            store = MediaBackupPrivateStoreAdapter(realStore),
            filePort = filePort,
        )

        val failedResult = failed(preparer.prepare(sourceOf(declaration)))

        assertEquals("el probe abre una vez y el almacen otra", 2, filePort.sourceOpens)
        assertEquals(MediaBackupPreparationStage.STORE, failedResult.failure.stage())
        val publishedKey = failedResult.publishedOrphanKeys.single()
        assertTrue("la clave publicada debe ser canonica", AngleImageKey.isCanonical(publishedKey))
        // El almacen real publico el fichero antes de que fallara el cierre: no se pierde la clave.
        val publishedFile = storeRoot.walkTopDown().firstOrNull { it.isFile && it.parentFile?.name == publishedKey }
        assertTrue("el almacen real debe haber publicado un fichero en la clave $publishedKey", publishedFile != null)
        assertEquals(declaration.byteCount, publishedFile!!.length())
        assertEquals(emptyList<String>(), failedResult.uncertainPublishedKeys)
    }

    // --- 9. Publicacion incierta y residuos del almacen ---------------------------------------

    @Test
    fun uncertainPublicationIsPreservedAndReportedSeparately() {
        val staging = newStaging()
        val declaration = declaration(1, "incierta".toByteArray(), width = 2, height = 2)
        val uncertainKey = "33333333333333333333333333333333"
        val store = FakeStore(
            listOf(NEW_KEY_A),
            storeError = AngleImageStoreError.PublicationUncertain(uncertainKey, "verification after rename failed"),
        )

        val failedResult = failed(preparer(staging, FakeFilePort(staging), store = store).prepare(sourceOf(declaration)))

        val rejection = failedResult.failure as MediaBackupPreparationFailure.StoreRejected
        assertTrue(rejection.error is AngleImageStoreError.PublicationUncertain)
        // Ni se mezcla con las verificadas ni se presenta como valida; y se conserva.
        assertEquals(emptyList<String>(), failedResult.publishedOrphanKeys)
        assertEquals(listOf(uncertainKey), failedResult.uncertainPublishedKeys)
        assertEquals(emptyList<String>(), store.deletedKeys)
    }

    @Test
    fun storeRetainedTemporaryAndDirectoryArePropagatedWithoutFakingSpoolCleanup() {
        val staging = newStaging()
        val declaration = declaration(1, "residuos".toByteArray(), width = 2, height = 2)
        val filePort = FakeFilePort(staging)
        val store = FakeStore(
            listOf(NEW_KEY_A),
            storeError = AngleImageStoreError.InsufficientStorage(requiredBytes = 4096L, usableBytes = 0L),
            temporaryFileRetained = true,
            directoryRetained = true,
        )

        val failedResult = failed(preparer(staging, filePort, store = store).prepare(sourceOf(declaration)))

        assertTrue(failedResult.failure is MediaBackupPreparationFailure.StoreRejected)
        assertTrue("el temporal retenido por el almacen se propaga", failedResult.temporaryFileRetained)
        assertTrue("el directorio retenido por el almacen se propaga", failedResult.directoryRetained)
        assertFalse("un residuo del almacen no es un fallo de limpieza de spool", failedResult.spoolCleanupFailed)
        // El spool propio si se borro: los dos conceptos no se confunden.
        assertEquals(filePort.createdNames.size, filePort.deletedNames.size)
    }

    @Test
    fun spoolCleanupFailureAndStoreRetainedFlagsAreReportedIndependently() {
        val staging = newStaging()
        val declaration = declaration(1, "ambos".toByteArray(), width = 2, height = 2)
        val filePort = FakeFilePort(staging, failDelete = true)
        val store = FakeStore(
            listOf(NEW_KEY_A),
            storeError = AngleImageStoreError.InsufficientStorage(requiredBytes = 4096L, usableBytes = 0L),
            temporaryFileRetained = true,
            directoryRetained = true,
        )

        val failedResult = failed(preparer(staging, filePort, store = store).prepare(sourceOf(declaration)))

        assertTrue("el spool propio no borrado se informa", failedResult.spoolCleanupFailed)
        assertTrue(failedResult.temporaryFileRetained)
        assertTrue(failedResult.directoryRetained)
        assertTrue("el spool propio se preserva", File(staging, filePort.createdNames.single()).exists())
    }

    @Test
    fun canceledStoreFailureStillPropagatesRetainedFlags() {
        val staging = newStaging()
        val declaration = declaration(1, "cancelado-residuos".toByteArray(), width = 2, height = 2)
        val store = FakeStore(
            listOf(NEW_KEY_A),
            storeError = AngleImageStoreError.Canceled,
            temporaryFileRetained = true,
            directoryRetained = true,
        )

        val failedResult = failed(preparer(staging, FakeFilePort(staging), store = store).prepare(sourceOf(declaration)))

        assertTrue(failedResult.failure is MediaBackupPreparationFailure.Canceled)
        assertTrue("tambien al convertir Canceled", failedResult.temporaryFileRetained)
        assertTrue("tambien al convertir Canceled", failedResult.directoryRetained)
        assertEquals(emptyList<String>(), failedResult.publishedOrphanKeys)
    }

    @Test
    fun uncertainStoreResultSurvivesSpoolStreamCloseFailure() {
        val staging = newStaging()
        val declaration = declaration(1, "incierta-y-cierre".toByteArray(), width = 2, height = 2)
        val uncertainKey = "44444444444444444444444444444444"
        val uncertainError = AngleImageStoreError.PublicationUncertain(
            uncertainKey,
            "verification after rename failed",
        )
        val filePort = FakeFilePort(staging, failSourceCloseOnOpen = 2)
        val store = FakeStore(
            listOf(NEW_KEY_A),
            storeError = uncertainError,
            temporaryFileRetained = true,
            directoryRetained = true,
        )

        val failedResult = failed(preparer(staging, filePort, store = store).prepare(sourceOf(declaration)))

        // El resultado del almacen NO se pierde por el fallo de cierre: incertidumbre y residuos.
        assertEquals(emptyList<String>(), failedResult.publishedOrphanKeys)
        assertEquals(listOf(uncertainKey), failedResult.uncertainPublishedKeys)
        assertTrue("el temporal retenido por el almacen se propaga", failedResult.temporaryFileRetained)
        assertTrue("el directorio retenido por el almacen se propaga", failedResult.directoryRetained)
        assertFalse("un residuo del almacen no es un fallo de limpieza de spool", failedResult.spoolCleanupFailed)
        // Ambas causas honestas: fallo tipado del almacen y cierre fallido, ninguna descartada.
        val merged = failedResult.failure as MediaBackupPreparationFailure.StoreFailureAndSpoolCloseFailed
        val rejection = merged.storeFailure as MediaBackupPreparationFailure.StoreRejected
        assertEquals(uncertainError, rejection.error)
        assertEquals(declaration.imageKey, rejection.imageKey)
        assertSame("el fallo del almacen es la causa principal", merged.storeFailure, merged.cause)
        assertSame("el fallo de cierre se conserva aparte", merged.closeFailure, merged.suppressed.single())
        assertTrue(
            "la causa de cierre debe ser el cierre del spool: ${merged.closeFailure.message}",
            merged.closeFailure.message.orEmpty().contains("close"),
        )
        // El fallo se informa en la etapa del almacen, y el spool propio se limpia igual.
        assertEquals(MediaBackupPreparationStage.STORE, failedResult.failure.stage())
        assertEquals(filePort.createdNames.size, filePort.deletedNames.size)
        assertEquals(emptyList<String>(), store.deletedKeys)
        assertEquals(listOf(declaration.byteCount), store.declaredByteCounts)
    }

    @Test
    fun canceledStoreResultSurvivesSpoolStreamCloseFailure() {
        val staging = newStaging()
        val declaration = declaration(1, "cancelado-y-cierre".toByteArray(), width = 2, height = 2)
        val filePort = FakeFilePort(staging, failSourceCloseOnOpen = 2)
        val store = FakeStore(
            listOf(NEW_KEY_A),
            storeError = AngleImageStoreError.Canceled,
            temporaryFileRetained = true,
            directoryRetained = true,
        )

        val failedResult = failed(preparer(staging, filePort, store = store).prepare(sourceOf(declaration)))

        assertEquals(emptyList<String>(), failedResult.publishedOrphanKeys)
        assertEquals(emptyList<String>(), failedResult.uncertainPublishedKeys)
        assertTrue("la cancelacion no pierde los residuos", failedResult.temporaryFileRetained)
        assertTrue("la cancelacion no pierde los residuos", failedResult.directoryRetained)
        assertFalse(failedResult.spoolCleanupFailed)
        val merged = failedResult.failure as MediaBackupPreparationFailure.StoreFailureAndSpoolCloseFailed
        assertSame("la cancelacion sigue siendo el fallo del almacen", MediaBackupPreparationFailure.Canceled, merged.storeFailure)
        assertSame(merged.storeFailure, merged.cause)
        assertSame(merged.closeFailure, merged.suppressed.single())
        assertEquals(filePort.createdNames.size, filePort.deletedNames.size)
    }

    @Test
    fun uncertainStoreResultAndRetainedFlagsSurviveRuntimeExceptionOnSpoolClose() {
        val staging = newStaging()
        val declaration = declaration(1, "incierta-y-cierre-runtime".toByteArray(), width = 2, height = 2)
        val uncertainKey = "55555555555555555555555555555555"
        val uncertainError = AngleImageStoreError.PublicationUncertain(
            uncertainKey,
            "verification after rename failed",
        )
        val closeFailure = IllegalStateException("spool read stream close failed at runtime")
        val filePort = FakeFilePort(staging, failSourceCloseOnOpen = 2, sourceCloseFailure = { closeFailure })
        val store = FakeStore(
            listOf(NEW_KEY_A),
            storeError = uncertainError,
            temporaryFileRetained = true,
            directoryRetained = true,
        )

        val failedResult = failed(preparer(staging, filePort, store = store).prepare(sourceOf(declaration)))

        // Un cierre con RuntimeException no puede tapar la incertidumbre ni los residuos del almacen.
        assertEquals(emptyList<String>(), failedResult.publishedOrphanKeys)
        assertEquals(listOf(uncertainKey), failedResult.uncertainPublishedKeys)
        assertTrue("el temporal retenido por el almacen se propaga", failedResult.temporaryFileRetained)
        assertTrue("el directorio retenido por el almacen se propaga", failedResult.directoryRetained)
        assertFalse("un residuo del almacen no es un fallo de limpieza de spool", failedResult.spoolCleanupFailed)
        val merged = failedResult.failure as MediaBackupPreparationFailure.StoreFailureAndSpoolCloseFailed
        val rejection = merged.storeFailure as MediaBackupPreparationFailure.StoreRejected
        assertEquals(uncertainError, rejection.error)
        assertEquals(declaration.imageKey, rejection.imageKey)
        assertSame("el fallo del almacen sigue siendo la causa principal", merged.storeFailure, merged.cause)
        assertSame("el cierre RuntimeException se conserva tal cual", closeFailure, merged.closeFailure)
        assertSame(closeFailure, merged.suppressed.single())
        assertEquals(MediaBackupPreparationStage.STORE, failedResult.failure.stage())
        // El spool propio se limpia igual y no se toca ninguna clave del almacen.
        assertEquals(filePort.createdNames.size, filePort.deletedNames.size)
        assertEquals(emptyList<String>(), store.deletedKeys)
        assertEquals(listOf(declaration.byteCount), store.declaredByteCounts)
    }

    @Test
    fun canceledStoreResultAndRetainedFlagsSurviveRuntimeExceptionOnSpoolClose() {
        val staging = newStaging()
        val declaration = declaration(1, "cancelado-y-cierre-runtime".toByteArray(), width = 2, height = 2)
        val closeFailure = IllegalStateException("spool read stream close failed at runtime")
        val filePort = FakeFilePort(staging, failSourceCloseOnOpen = 2, sourceCloseFailure = { closeFailure })
        val store = FakeStore(
            listOf(NEW_KEY_A),
            storeError = AngleImageStoreError.Canceled,
            temporaryFileRetained = true,
            directoryRetained = true,
        )

        val failedResult = failed(preparer(staging, filePort, store = store).prepare(sourceOf(declaration)))

        assertEquals(emptyList<String>(), failedResult.publishedOrphanKeys)
        assertEquals(emptyList<String>(), failedResult.uncertainPublishedKeys)
        assertTrue("la cancelacion no pierde los residuos", failedResult.temporaryFileRetained)
        assertTrue("la cancelacion no pierde los residuos", failedResult.directoryRetained)
        assertFalse(failedResult.spoolCleanupFailed)
        val merged = failedResult.failure as MediaBackupPreparationFailure.StoreFailureAndSpoolCloseFailed
        assertSame("la cancelacion sigue siendo el fallo del almacen", MediaBackupPreparationFailure.Canceled, merged.storeFailure)
        assertSame(merged.storeFailure, merged.cause)
        assertSame("el cierre RuntimeException se conserva tal cual", closeFailure, merged.closeFailure)
        assertSame(closeFailure, merged.suppressed.single())
        assertEquals(filePort.createdNames.size, filePort.deletedNames.size)
    }

    @Test
    fun storedPublicationSurvivesRuntimeExceptionOnSpoolClose() {
        val staging = newStaging()
        val declaration = declaration(1, "publicada-y-cierre-runtime".toByteArray(), width = 2, height = 2)
        val closeFailure = IllegalStateException("spool read stream close failed at runtime")
        val filePort = FakeFilePort(staging, failSourceCloseOnOpen = 2, sourceCloseFailure = { closeFailure })
        val store = FakeStore(listOf(NEW_KEY_A))

        val failedResult = failed(preparer(staging, filePort, store = store).prepare(sourceOf(declaration)))

        // La clave ya publicada y verificada no se pierde porque el cierre falle de otra forma.
        assertEquals(listOf(NEW_KEY_A), failedResult.publishedOrphanKeys)
        assertEquals(emptyList<String>(), failedResult.uncertainPublishedKeys)
        assertFalse(failedResult.temporaryFileRetained)
        assertFalse(failedResult.directoryRetained)
        assertEquals(MediaBackupPreparationStage.STORE, failedResult.failure.stage())
        val step = failedResult.failure as MediaBackupPreparationFailure.StepFailed
        assertSame("el cierre RuntimeException es la causa del fallo", closeFailure, step.cause)
        assertTrue(
            "el fallo debe nombrar el cierre posterior a la publicacion: ${step.message}",
            step.message.orEmpty().contains("close"),
        )
        assertEquals(filePort.createdNames.size, filePort.deletedNames.size)
        assertEquals(emptyList<String>(), store.deletedKeys)
    }

    // --- Helpers --------------------------------------------------------------------------------

    private fun newStaging(): File = temporaryFolder.newFolder("staging-${UUID.randomUUID()}")

    private fun preparer(
        staging: File,
        filePort: FakeFilePort,
        store: FakeStore,
        probe: FakeProbe = FakeProbe(),
        limits: MediaBackupPreparationLimits = MediaBackupPreparationLimits(),
    ): MediaBackupImagePreparer = MediaBackupImagePreparer(
        stagingDirectory = staging,
        probe = probe,
        store = store,
        limits = limits,
        filePort = filePort,
    )

    private fun sourceOf(declaration: MediaBackupImageDeclaration): FakeSource =
        FakeSource(listOf(declaration), mapOf(declaration.imageKey to declaration.bytes()))

    private fun payloadsOf(vararg declarations: MediaBackupImageDeclaration): Map<String, ByteArray> =
        declarations.associate { it.imageKey to it.bytes() }

    private fun failed(result: MediaBackupImagePreparationResult): MediaBackupImagePreparationResult.Failed {
        assertTrue("se esperaba un fallo y llego $result", result is MediaBackupImagePreparationResult.Failed)
        return result as MediaBackupImagePreparationResult.Failed
    }

    private fun MediaBackupPreparationFailure.stage(): MediaBackupPreparationStage = when (this) {
        is MediaBackupPreparationFailure.StepFailed -> stage
        is MediaBackupPreparationFailure.IdentityMismatch -> stage
        is MediaBackupPreparationFailure.StoreRejected -> MediaBackupPreparationStage.STORE
        is MediaBackupPreparationFailure.StoreFailureAndSpoolCloseFailed -> MediaBackupPreparationStage.STORE
        else -> throw AssertionError("el fallo no tiene etapa: $this")
    }

    private fun MediaBackupPreparationFailure.imageKey(): String = when (this) {
        is MediaBackupPreparationFailure.StepFailed -> imageKey
        is MediaBackupPreparationFailure.IdentityMismatch -> imageKey
        is MediaBackupPreparationFailure.StoreRejected -> imageKey
        else -> throw AssertionError("el fallo no tiene imagen: $this")
    }

    /** Cuerpo declarado de cada imagen: el SHA del manifiesto se calcula sobre estos bytes. */
    private val declaredPayloads = HashMap<String, ByteArray>()

    private fun declaration(index: Int, payload: ByteArray, width: Int, height: Int): MediaBackupImageDeclaration {
        val imageKey = String.format(Locale.ROOT, "%032x", index)
        declaredPayloads[imageKey] = payload
        return MediaBackupImageDeclaration(
            imageKey = imageKey,
            byteCount = payload.size.toLong(),
            sha256 = sha256Hex(payload),
            width = width,
            height = height,
        )
    }

    private fun MediaBackupImageDeclaration.bytes(): ByteArray = declaredPayloads.getValue(imageKey)

    // --- Dobles de prueba -----------------------------------------------------------------------

    /** Fuente fake: payloads en memoria con fallos inyectables por clave. */
    private class FakeSource(
        private val declared: List<MediaBackupImageDeclaration>,
        private val payloads: Map<String, ByteArray>,
        private val failKeys: Set<String> = emptySet(),
        private val unavailableKeys: Set<String> = emptySet(),
        private val partialWriteKeys: Set<String> = emptySet(),
        private val extraBytesKeys: Set<String> = emptySet(),
        private val wrongShaKeys: Set<String> = emptySet(),
    ) : MediaBackupImagePreparationSource {

        override val declarations: List<MediaBackupImageDeclaration> get() = declared.sortedBy { it.imageKey }

        override fun copyImageTo(
            imageKey: String,
            output: OutputStream,
            cancellation: MediaBackupArchiveCancellation,
        ): MediaBackupImageTransfer {
            val declaration = declared.single { it.imageKey == imageKey }
            if (imageKey in unavailableKeys) throw MediaBackupImportException.ImageUnavailable(imageKey)
            val bytes = payloads.getValue(imageKey)
            if (imageKey in partialWriteKeys) {
                output.write(bytes, 0, bytes.size / 2)
                throw IOException("staged entry truncated")
            }
            output.write(bytes)
            if (imageKey in extraBytesKeys) {
                output.write(ByteArray(1))
                throw IOException("staged entry expands beyond the declared byteCount")
            }
            if (imageKey in failKeys) throw IOException("staged entry cannot be transferred")
            return MediaBackupImageTransfer(
                imageKey = imageKey,
                byteCount = declaration.byteCount,
                sha256 = if (imageKey in wrongShaKeys) "f".repeat(64) else declaration.sha256,
            )
        }
    }

    /** FS fake: crea y borra ficheros reales en el staging, con fallos inyectables. */
    private class FakeFilePort(
        private val staging: File,
        private val failCreate: Boolean = false,
        private val failSync: Boolean = false,
        private val failDelete: Boolean = false,
        private val failSourceCloseOnOpen: Int = 0,
        private val sourceCloseFailure: (() -> Throwable)? = null,
    ) : MediaBackupPreparationFilePort {

        val createdNames = Collections.synchronizedList(ArrayList<String>())
        val deletedNames = Collections.synchronizedList(ArrayList<String>())
        var sourceOpens = 0
            private set
        private var sequence = 0

        override fun createSpoolFile(stagingDirectory: File): File {
            if (failCreate) throw IOException("no space left on device")
            val file = File(stagingDirectory, "$SPOOL_PREFIX${sequence++}.part")
            assertTrue("el spool fake debe crearse de verdad", file.createNewFile())
            createdNames += file.name
            return file
        }

        override fun openSpoolSink(file: File): MediaBackupPreparationSink {
            val stream = FileOutputStream(file, false)
            return object : MediaBackupPreparationSink {
                override fun write(buffer: ByteArray, offset: Int, length: Int) = stream.write(buffer, offset, length)

                override fun sync() {
                    if (failSync) throw IOException("fsync failed")
                    stream.fd.sync()
                }

                override fun close() = stream.close()
            }
        }

        override fun openSpoolSource(file: File): InputStream {
            val stream = Files.newInputStream(file.toPath())
            val openIndex = ++sourceOpens
            if (openIndex != failSourceCloseOnOpen) return stream
            // Cierre que falla DESPUES de cerrar el descriptor real: simula el fallo al volver del
            // `use` una vez que el almacen ya publico (el probe abre antes, con otro indice).
            return object : FilterInputStream(stream) {
                override fun close() {
                    super.close()
                    throw sourceCloseFailure?.invoke() ?: IOException("spool read stream close failed")
                }
            }
        }

        override fun deleteSpoolFile(file: File): Boolean {
            if (failDelete) return !file.exists()
            val deleted = file.delete()
            if (deleted) deletedNames += file.name
            return !file.exists()
        }
    }

    /** Probe fake: no decodifica; devuelve la identidad declarada o falla segun configuracion. */
    private class FakeProbe(
        private val failKeys: Set<String> = emptySet(),
        private val wrongDimensionsKeys: Set<String> = emptySet(),
    ) : MediaBackupPreparationProbePort {

        val probedKeys = ArrayList<String>()

        override fun probe(
            expectation: MediaBackupPngExpectation,
            openStream: () -> InputStream,
            cancellation: MediaBackupArchiveCancellation,
        ): MediaBackupPngVerified {
            probedKeys += expectation.imageKey
            // El probe real lee el fichero: aqui se comprueba que el spool existe y tiene bytes.
            val observed = openStream().use { it.readBytes() }
            assertEquals("el spool debe contener el byteCount declarado", expectation.byteCount, observed.size.toLong())
            if (expectation.imageKey in failKeys) throw MediaBackupPngProbeException.HeaderRejected("not a PNG")
            return MediaBackupPngVerified(
                imageKey = expectation.imageKey,
                width = if (expectation.imageKey in wrongDimensionsKeys) expectation.width + 1 else expectation.width,
                height = expectation.height,
                area = expectation.width.toLong() * expectation.height,
                decodedBytes = 4L * expectation.width * expectation.height,
                byteCount = expectation.byteCount,
                sha256 = expectation.sha256,
            )
        }
    }

    /** Almacen fake: lee el spool, devuelve claves nuevas y registra lo que se le pide. */
    private class FakeStore(
        private val newKeys: List<String>,
        private val storeError: AngleImageStoreError? = null,
        private val throwOnWrite: IOException? = null,
        private val storedShaOverride: String? = null,
        private val durability: AngleImageDurability = SYNCED_DURABILITY,
        private val temporaryFileRetained: Boolean = false,
        private val directoryRetained: Boolean = false,
    ) : MediaBackupPreparationStorePort {

        val delivered = ArrayList<ByteArray>()
        val declaredByteCounts = ArrayList<Long>()
        val deletedKeys = ArrayList<String>()
        private var index = 0

        override fun write(
            source: InputStream,
            expectedByteCount: Long,
            shouldCancel: () -> Boolean,
        ): AngleImageWriteResult {
            throwOnWrite?.let { throw it }
            val bytes = source.readBytes()
            delivered += bytes
            declaredByteCounts += expectedByteCount
            assertEquals("el almacen debe recibir exactamente los bytes declarados", expectedByteCount, bytes.size.toLong())
            if (storeError != null) {
                return AngleImageWriteResult.Failed(storeError, temporaryFileRetained, directoryRetained)
            }
            val key = newKeys[index]
            index++
            return AngleImageWriteResult.Stored(
                image = StoredAngleImage(
                    imageKey = key,
                    byteCount = expectedByteCount,
                    sha256Hex = storedShaOverride ?: sha256Hex(bytes),
                ),
                durability = durability,
            )
        }
    }

    private companion object {
        val SYNCED_DURABILITY = AngleImageDurability(
            keyDirectory = AngleImageDirectorySync.SYNCED,
            parentDirectory = AngleImageDirectorySync.SYNCED,
        )
        val UNCONFIRMED_DURABILITY = AngleImageDurability(
            keyDirectory = AngleImageDirectorySync.SYNCED,
            parentDirectory = AngleImageDirectorySync.UNSUPPORTED,
        )
        const val NEW_KEY_A = "11111111111111111111111111111111"
        const val NEW_KEY_B = "22222222222222222222222222222222"
        const val SPOOL_PREFIX = DefaultMediaBackupPreparationFilePort.SPOOL_PREFIX
    }
}
