package com.openjump.app.data.angles

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pruebas JVM deterministas del **requisito de propiedad y durabilidad del directorio raíz**.
 *
 * Invariante bajo prueba: el raíz lo provee el llamante (en la app, `filesDir`, creado por la
 * plataforma) y el almacén **nunca** lo crea, ni directa ni indirectamente. Si el raíz no existe, es
 * un enlace o no es un directorio real, la escritura se rechaza con su error tipado **antes de
 * cualquier otra E/S**: sin temporales, sin reservas, sin `fsync` y sin publicar nada.
 *
 * El motivo es la honestidad del informe de durabilidad: un raíz creado por el propio almacén jamás
 * tendría su entrada `fsync`-ada en su padre (queda fuera del alcance del almacén), así que una
 * segunda escritura —o dos escrituras concurrentes— observarían el raíz como existente y declararían
 * durable un nombre que nadie confirmó. Rechazar el raíz ausente elimina esa ambigüedad.
 *
 * Ninguna prueba lee ni modifica rutas del proyecto: cada una usa su propio [TemporaryFolder].
 */
class PrivateAngleImageStoreRootOwnershipTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(root: File, io: AngleImageFileIo = NioAngleImageFileIo()): PrivateAngleImageStore =
        PrivateAngleImageStore(rootDirectory = root, fileIo = io)

    private fun write(store: PrivateAngleImageStore, bytes: ByteArray): AngleImageWriteResult =
        store.write(ByteArrayInputStream(bytes), expectedByteCount = bytes.size.toLong())

    @Test
    fun `un raiz ausente se rechaza sin crearlo ni tocar el padre`() {
        val base = folder.newFolder("padre")
        val foreign = File(base, "ajeno.txt").apply { writeText("dato del llamante") }
        val foreignDirectory = File(base, "database").apply { mkdirs() }
        val root = File(base, "raiz-ausente")
        val io = RecordingDirectorySyncIo()

        val failed = assertWriteFailed(write(store(root, io), payload(64)))

        assertEquals(
            AngleImageStoreError.RootDirectoryUnusable(AngleImageRootViolation.ABSENT),
            failed.error,
        )
        assertFalse("el almacen nunca crea el raiz", root.exists())
        assertEquals(
            "el padre queda exactamente como estaba",
            listOf("ajeno.txt", "database"),
            base.list().orEmpty().sorted(),
        )
        assertEquals("dato del llamante", foreign.readText())
        assertTrue("el directorio ajeno sobrevive", foreignDirectory.isDirectory)
        assertEquals("no se intenta sincronizar ningun directorio", emptyList<File>(), io.syncedDirectories)
        assertFalse("no hay temporal que retener", failed.temporaryFileRetained)
        assertFalse("no hay directorio de clave que retener", failed.directoryRetained)
    }

    @Test
    fun `un raiz ausente repetido sigue rechazandose y nunca aparece`() {
        val base = folder.newFolder("raiz-ausente-repetida")
        val root = File(base, "raiz")
        val bytes = payload(96)

        val results = (1..3).map { write(store(root), bytes) }

        results.forEach { result ->
            assertEquals(
                AngleImageStoreError.RootDirectoryUnusable(AngleImageRootViolation.ABSENT),
                assertWriteFailed(result).error,
            )
        }
        assertFalse("repetir la escritura no acaba creando el raiz", root.exists())
        assertEquals("el padre no cambia", emptyList<String>(), base.list().orEmpty().toList())
    }

    @Test
    fun `dos escritores concurrentes sobre un raiz ausente no lo crean ni publican nada`() {
        val base = folder.newFolder("raiz-ausente-concurrente")
        val root = File(base, "raiz")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val results = Collections.synchronizedList(ArrayList<AngleImageWriteResult>())
        try {
            val tasks = (1..2).map { index ->
                pool.submit {
                    start.await(5, TimeUnit.SECONDS)
                    results += write(store(root), payload(128, seed = index * 7))
                }
            }
            start.countDown()
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
            assertTrue("el pool debe terminar", pool.awaitTermination(30, TimeUnit.SECONDS))
        }

        assertEquals(2, results.size)
        results.forEach { result ->
            assertEquals(
                "ninguna instancia crea el raiz ni declara durabilidad",
                AngleImageStoreError.RootDirectoryUnusable(AngleImageRootViolation.ABSENT),
                assertWriteFailed(result).error,
            )
        }
        assertFalse("el raiz no aparece por concurrencia", root.exists())
        assertFalse("tampoco se crea el subdirectorio del almacen", imagesDirectory(root).exists())
        assertEquals("el padre no cambia", emptyList<String>(), base.list().orEmpty().toList())
    }

    @Test
    fun `un raiz que es un fichero regular se rechaza sin modificarlo`() {
        val base = folder.newFolder("raiz-fichero")
        val root = File(base, "no-es-directorio").apply { writeText("contenido del llamante") }

        val failed = assertWriteFailed(write(store(root), payload(64)))

        assertEquals(
            AngleImageStoreError.RootDirectoryUnusable(AngleImageRootViolation.NOT_DIRECTORY),
            failed.error,
        )
        assertEquals("el fichero preexistente no se toca", "contenido del llamante", root.readText())
        assertEquals(listOf("no-es-directorio"), base.list().orEmpty().toList())
    }

    @Test
    fun `un raiz que es un enlace simbolico se rechaza y no se escribe a traves de el`() {
        val base = folder.newFolder("raiz-enlace")
        val target = File(base, "destino-real").apply { mkdirs() }
        val link = File(base, "raiz-enlace")
        Files.createSymbolicLink(link.toPath(), target.toPath())

        val failed = assertWriteFailed(write(store(link), payload(64)))

        assertEquals(
            AngleImageStoreError.RootDirectoryUnusable(AngleImageRootViolation.SYMBOLIC_LINK),
            failed.error,
        )
        assertEquals("no se escribe nada dentro del destino del enlace", 0, target.listFiles().orEmpty().size)
        assertTrue("el enlace no se reemplaza", Files.isSymbolicLink(link.toPath()))
    }

    @Test
    fun `solo el llamante provee el raiz y entonces la publicacion si declara durabilidad`() {
        val base = folder.newFolder("raiz-proveida")
        val root = File(base, "raiz")
        val bytes = payload(64)
        val io = RecordingDirectorySyncIo()

        // 1) Sin raíz: el almacén se niega y no deja rastro alguno.
        val rejected = assertWriteFailed(write(store(root, io), bytes))
        assertEquals(
            AngleImageStoreError.RootDirectoryUnusable(AngleImageRootViolation.ABSENT),
            rejected.error,
        )
        assertFalse(root.exists())
        assertEquals(emptyList<File>(), io.syncedDirectories)

        // 2) El llamante lo crea (en la app lo hace la plataforma con `filesDir`). La escritura
        //    observa un raíz **preexistente**, lo sincroniza en esta operación y sólo entonces
        //    declara durabilidad: no hereda ninguna confirmación de un raíz creado por el almacén.
        assertTrue("el llamante puede crear el raiz", root.mkdir())
        val stored = assertStored(write(store(root, io), bytes))

        assertEquals(
            listOf(keyDirectory(root, stored.image.imageKey), imagesDirectory(root), root),
            io.syncedDirectories,
        )
        assertEquals(listOf(AngleImageDirectorySync.SYNCED), stored.durability.ancestorDirectories)
        assertTrue("con raíz preexistente confirmado sí hay durabilidad", stored.durability.directoryEntriesSynced)
        assertArrayEquals(bytes, publicImage(root, stored.image.imageKey).readBytes())
    }
}
