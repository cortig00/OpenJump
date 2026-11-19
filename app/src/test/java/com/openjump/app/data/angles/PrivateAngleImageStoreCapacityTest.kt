package com.openjump.app.data.angles

import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pruebas JVM deterministas del **presupuesto agregado compartido** por directorio real.
 *
 * Cubren los dos defectos que el presupuesto por instancia dejaba abiertos: dos instancias sobre
 * el mismo directorio (o dos hilos de la misma instancia) que sumadas exceden la cuota pueden
 * publicar ambas, y una medida de uso tomada antes de una publicación puede usarse después (medida
 * obsoleta). También fijan el rechazo fail-closed ante límites divergentes y la aritmética sin
 * desbordamiento cerca de [Long.MAX_VALUE].
 *
 * Ninguna prueba usa reposo (`sleep`), red ni directorios del proyecto: el orden se fuerza con
 * latches en los ganchos de E/S y cada prueba usa su propio [TemporaryFolder].
 */
class PrivateAngleImageStoreCapacityTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(
        root: File,
        limits: AngleImageStoreLimits = AngleImageStoreLimits(),
        io: AngleImageFileIo = NioAngleImageFileIo(),
    ): PrivateAngleImageStore = PrivateAngleImageStore(
        rootDirectory = root,
        limits = limits,
        generateKey = AngleImageKey::generate,
        fileIo = io,
    )

    /** Directorios de clave publicados (uno por imagen; los temporales viven dentro). */
    private fun publishedFiles(root: File): List<File> = keyDirectories(root)

    /** Bytes publicados: suma de los ficheros de cada directorio de clave. */
    private fun publishedBytes(root: File): Long = publishedBytesOf(root)

    /**
     * Dos instancias distintas del mismo directorio real, lanzadas a la vez, cuyas escrituras
     * sumadas exceden la cuota: sólo una publica y el presupuesto no se supera.
     *
     * Es determinista para el código corregido con cualquier intercalado: la primera reserva deja
     * 600 bytes en vuelo, así que la segunda mide lo publicado (0 o 600) y, o bien choca con la
     * reserva en vuelo, o bien con los bytes ya publicados.
     */
    @Test
    fun `dos instancias simultaneas que suman mas que la cuota publican solo una`() {
        val limits = AngleImageStoreLimits(maxImageBytes = 600, maxTotalBytes = 1_000)
        repeat(4) { round ->
            val root = folder.newFolder("two-instances-$round")
            val first = store(root, limits)
            val second = store(root, limits)
            val bytes = payload(600)
            val ready = CountDownLatch(2)
            val go = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val futures = listOf(first, second).map { instance ->
                    executor.submit<AngleImageWriteResult> {
                        ready.countDown()
                        go.await(10, TimeUnit.SECONDS)
                        instance.write(ByteArrayInputStream(bytes), bytes.size.toLong())
                    }
                }
                assertTrue("las dos escrituras deben estar listas", ready.await(10, TimeUnit.SECONDS))
                go.countDown()

                val results = futures.map { it.get(30, TimeUnit.SECONDS) }
                val stored = results.filterIsInstance<AngleImageWriteResult.Stored>()
                val failed = results.filterIsInstance<AngleImageWriteResult.Failed>()

                assertEquals("una única publicación: $results", 1, stored.size)
                assertEquals("un único rechazo: $results", 1, failed.size)
                assertTrue(
                    "el rechazo debe ser de cuota: ${failed.single().error}",
                    failed.single().error is AngleImageStoreError.QuotaExceeded,
                )
                assertFalse("el rechazo no crea temporales", failed.single().temporaryFileRetained)
                assertEquals(600L, stored.single().image.byteCount)
                assertEquals("sólo un fichero publicado", 1, publishedFiles(root).size)
                assertEquals("presupuesto respetado", 600L, publishedBytes(root))
                assertTrue("presupuesto respetado", publishedBytes(root) <= limits.maxTotalBytes)
                assertEquals("sin temporales propios", 0, temporaryFiles(root).size)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    /** El mismo defecto dentro de una instancia: dos hilos que sumados exceden la cuota. */
    @Test
    fun `dos hilos de la misma instancia no pueden sobrepasar la cuota`() {
        val root = folder.newFolder("same-instance")
        val limits = AngleImageStoreLimits(maxImageBytes = 600, maxTotalBytes = 1_000)
        val instance = store(root, limits)
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(payload(600), payload(600, seed = 41)).map { bytes ->
                executor.submit<AngleImageWriteResult> {
                    ready.countDown()
                    go.await(10, TimeUnit.SECONDS)
                    instance.write(ByteArrayInputStream(bytes), bytes.size.toLong())
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            go.countDown()

            val results = futures.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals("una única publicación: $results", 1, results.filterIsInstance<AngleImageWriteResult.Stored>().size)
            val failed = results.filterIsInstance<AngleImageWriteResult.Failed>().single()
            assertTrue("rechazo de cuota: ${failed.error}", failed.error is AngleImageStoreError.QuotaExceeded)
            assertEquals(600L, publishedBytes(root))
            assertEquals(0, temporaryFiles(root).size)
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * Carrera medida obsoleta / commit, forzada con latches.
     *
     * La primera instancia se detiene **dentro** de la publicación (nombre definitivo aún no
     * creado) y, por tanto, con el cerrojo del presupuesto tomado. La segunda instancia intenta
     * escribir en ese momento: con la corrección no puede ni siquiera medir el uso antes de que la
     * primera publique, así que su medida nunca es obsoleta. Si midiera antes (defecto), vería 0
     * bytes y luego reservaría contra un presupuesto ya liberado, publicando 1 200 bytes sobre un
     * máximo de 1 000.
     */
    @Test
    fun `la medida no puede quedar obsoleta mientras otra instancia publica`() {
        val root = folder.newFolder("stale-measure")
        val limits = AngleImageStoreLimits(maxImageBytes = 600, maxTotalBytes = 1_000)
        val publishEntered = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)
        val measureEntered = CountDownLatch(1)
        val releaseMeasure = CountDownLatch(1)

        val publisherIo = GatedIo().apply {
            insidePublish = {
                publishEntered.countDown()
                releasePublish.await(10, TimeUnit.SECONDS)
            }
        }
        val observerIo = GatedIo().apply {
            beforeUsage = {
                measureEntered.countDown()
                releaseMeasure.await(10, TimeUnit.SECONDS)
            }
        }
        val publisher = store(root, limits, publisherIo)
        val observer = store(root, limits, observerIo)
        val bytes = payload(600)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val published = executor.submit<AngleImageWriteResult> {
                publisher.write(ByteArrayInputStream(bytes), bytes.size.toLong())
            }
            assertTrue("la publicación debe empezar", publishEntered.await(10, TimeUnit.SECONDS))

            val observed = executor.submit<AngleImageWriteResult> {
                observer.write(ByteArrayInputStream(bytes), bytes.size.toLong())
            }
            assertFalse(
                "la segunda instancia no puede medir el uso antes de que la primera publique",
                measureEntered.await(300, TimeUnit.MILLISECONDS),
            )

            releasePublish.countDown()
            assertTrue(
                "la medida debe ocurrir después de la publicación",
                measureEntered.await(10, TimeUnit.SECONDS),
            )
            releaseMeasure.countDown()

            val stored = assertStored(published.get(30, TimeUnit.SECONDS))
            val failed = assertWriteFailed(observed.get(30, TimeUnit.SECONDS))
            val error = failed.error
            assertTrue("rechazo de cuota: $error", error is AngleImageStoreError.QuotaExceeded)
            assertTrue(
                "la medida debe incluir lo ya publicado: $error",
                (error as AngleImageStoreError.QuotaExceeded).usedByteCount >= 600L,
            )
            assertEquals(600L, stored.image.byteCount)
            assertEquals("presupuesto respetado", 600L, publishedBytes(root))
            assertEquals(0, temporaryFiles(root).size)
        } finally {
            releasePublish.countDown()
            releaseMeasure.countDown()
            executor.shutdownNow()
        }
    }

    /** Dos instancias con los mismos límites comparten exactamente el mismo presupuesto. */
    @Test
    fun `dos instancias del mismo directorio comparten el presupuesto exacto`() {
        val root = folder.newFolder("shared-budget")
        val limits = AngleImageStoreLimits(maxImageBytes = 600, maxTotalBytes = 1_000)
        val first = store(root, limits)
        val second = store(root, limits)

        assertStored(first.write(ByteArrayInputStream(payload(600)), 600L))
        assertStored(second.write(ByteArrayInputStream(payload(400, seed = 3)), 400L))
        val rejected = assertWriteFailed(first.write(ByteArrayInputStream(payload(1, seed = 5)), 1L))

        val error = rejected.error
        assertTrue("rechazo de cuota: $error", error is AngleImageStoreError.QuotaExceeded)
        assertEquals(1_000L, (error as AngleImageStoreError.QuotaExceeded).maxTotalBytes)
        assertEquals("la medida incluye lo publicado por la otra instancia", 1_000L, error.usedByteCount)
        assertEquals(1_000L, publishedBytes(root))
        assertEquals(0, temporaryFiles(root).size)
    }

    /** El presupuesto se identifica por directorio **real**: una ruta equivalente lo comparte. */
    @Test
    fun `una ruta alternativa al mismo directorio real comparte presupuesto`() {
        val root = folder.newFolder("real-path")
        val limits = AngleImageStoreLimits(maxImageBytes = 600, maxTotalBytes = 1_000)
        val direct = store(root, limits)
        val alias = store(File(root, "."), limits)

        assertStored(direct.write(ByteArrayInputStream(payload(600)), 600L))
        val rejected = assertWriteFailed(alias.write(ByteArrayInputStream(payload(600, seed = 7)), 600L))

        assertTrue(
            "la ruta equivalente debe compartir presupuesto: ${rejected.error}",
            rejected.error is AngleImageStoreError.QuotaExceeded,
        )
        assertEquals(600L, publishedBytes(root))
        assertEquals(1, publishedFiles(root).size)
    }

    /**
     * Límites divergentes para el mismo directorio real: fail-closed, sin tocar disco.
     *
     * Mezclar presupuestos permitiría que la instancia más permisiva sobrepasase el total que la
     * otra considera máximo, así que se rechaza antes de crear el temporal.
     */
    @Test
    fun `limites divergentes para el mismo directorio fallan cerrado`() {
        val root = folder.newFolder("divergent-limits")
        val strictLimits = AngleImageStoreLimits(maxImageBytes = 600, maxTotalBytes = 1_000)
        val looseLimits = AngleImageStoreLimits(maxImageBytes = 600, maxTotalBytes = 2_000)
        val strict = store(root, strictLimits)
        val loose = store(root, looseLimits)

        assertStored(strict.write(ByteArrayInputStream(payload(600)), 600L))
        val rejected = assertWriteFailed(loose.write(ByteArrayInputStream(payload(600, seed = 9)), 600L))

        val error = rejected.error
        assertTrue("conflicto de límites: $error", error is AngleImageStoreError.LimitsConflict)
        val conflict = error as AngleImageStoreError.LimitsConflict
        assertEquals(looseLimits, conflict.declared)
        assertEquals(strictLimits, conflict.registered)
        assertFalse(rejected.temporaryFileRetained)
        assertEquals("la instancia conflictiva no publica", 1, publishedFiles(root).size)
        assertEquals(600L, publishedBytes(root))
        assertEquals(0, temporaryFiles(root).size)

        // El presupuesto registrado sigue vigente y operativo: caben 400 bytes más, no 401.
        assertStored(strict.write(ByteArrayInputStream(payload(400, seed = 11)), 400L))
        assertTrue(
            strict.write(ByteArrayInputStream(payload(1, seed = 13)), 1L) is AngleImageWriteResult.Failed,
        )
        assertEquals(1_000L, publishedBytes(root))
    }

    /** Directorios distintos mantienen presupuestos independientes. */
    @Test
    fun `directorios distintos no comparten presupuesto`() {
        val limits = AngleImageStoreLimits(maxImageBytes = 600, maxTotalBytes = 1_000)
        val firstRoot = folder.newFolder("directory-a")
        val secondRoot = folder.newFolder("directory-b")

        assertStored(store(firstRoot, limits).write(ByteArrayInputStream(payload(600)), 600L))
        assertStored(store(secondRoot, limits).write(ByteArrayInputStream(payload(600, seed = 17)), 600L))

        assertEquals(600L, publishedBytes(firstRoot))
        assertEquals(600L, publishedBytes(secondRoot))
    }

    /**
     * Presupuesto y reserva cerca de [Long.MAX_VALUE]: la comprobación por diferencias no desborda.
     *
     * `1 + 0 + Long.MAX_VALUE` desborda a negativo y una comprobación ingenua aceptaría la
     * escritura aunque el directorio ya ocupa un byte. Aquí debe rechazarse antes de crear nada.
     */
    @Test
    fun `un presupuesto cercano a LongMAX no desborda la cuota`() {
        val root = folder.newFolder("overflow-budget")
        val images = imagesDirectory(root).apply { mkdirs() }
        val foreign = File(images, "ajeno.bin").apply { writeBytes(ByteArray(1)) }
        val io = ProbedIo().apply { usableSpace = Long.MAX_VALUE }
        val limits = AngleImageStoreLimits(maxImageBytes = Long.MAX_VALUE, maxTotalBytes = Long.MAX_VALUE)
        val instance = store(root, limits, io)

        // Sin tamaño declarado la reserva es maxImageBytes = Long.MAX_VALUE.
        val result = instance.write(ByteArrayInputStream(payload(32)))

        val failed = assertWriteFailed(result)
        val error = failed.error
        assertTrue("la cuota debe rechazar: $error", error is AngleImageStoreError.QuotaExceeded)
        assertEquals(Long.MAX_VALUE, (error as AngleImageStoreError.QuotaExceeded).maxTotalBytes)
        assertEquals(1L, error.usedByteCount)
        assertEquals("no se crea temporal", 0, io.createdTemporaries)
        assertEquals(1, images.listFiles().orEmpty().size)
        assertEquals(1L, foreign.length())
    }

    /** Una medición negativa (E/S rota) no puede leerse como hueco libre: fail-closed. */
    @Test
    fun `una medida de uso negativa se rechaza como fallo de E-S`() {
        val root = folder.newFolder("negative-usage")
        val io = ProbedIo().apply { measuredUsage = -1L }

        val result = store(root, io = io).write(ByteArrayInputStream(payload(32)), 32L)

        val failed = assertWriteFailed(result)
        val error = failed.error
        assertTrue("fallo de E/S: $error", error is AngleImageStoreError.IoFailure)
        assertEquals(AngleImageStoreStage.CHECK_CAPACITY, (error as AngleImageStoreError.IoFailure).stage)
        assertEquals(0, io.createdTemporaries)
        assertEquals(0, imagesDirectory(root).listFiles().orEmpty().size)
    }
}

/**
 * E/S real con ganchos: se ejecutan dentro de la medición de uso y de la publicación, que es justo
 * donde el presupuesto compartido mantiene su cerrojo. Fuera de los ganchos delega en el disco.
 */
private class GatedIo : NioAngleImageFileIo() {

    @Volatile
    var beforeUsage: (() -> Unit)? = null

    @Volatile
    var insidePublish: (() -> Unit)? = null

    override fun usageBytes(directory: File): Long {
        beforeUsage?.invoke()
        return measureAngleImageUsageBytes(this, directory)
    }

    override fun publishWithinOwnedDirectory(temporary: File, target: File, ownedDirectory: File) {
        insidePublish?.invoke()
        super.publishWithinOwnedDirectory(temporary, target, ownedDirectory)
    }
}
