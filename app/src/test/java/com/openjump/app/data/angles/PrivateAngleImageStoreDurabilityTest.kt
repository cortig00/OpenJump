package com.openjump.app.data.angles

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pruebas JVM deterministas del **informe de durabilidad** de la publicación: qué directorios se
 * sincronizan, en qué orden y qué se declara cuando el backend no lo soporta o falla.
 *
 * Invariante bajo prueba: **cada** publicación sincroniza, de más profundo a más superficial, el
 * directorio de la clave, el del almacén y el raíz, y sólo declara durabilidad si los tres se
 * confirmaron **en esta operación**. Que `angle-images` existiera al empezar no se acepta como
 * confirmación de su entrada en el raíz: otro escritor pudo crearla sin sincronizar su padre
 * todavía, o un intento anterior pudo dejar la entrada sin confirmar. Un `FAILED`/`UNSUPPORTED`
 * del raíz impide `directoryEntriesSynced` incluso con `angle-images` preexistente, y nunca
 * pierde la clave ni el fichero ya publicado.
 *
 * El raíz lo provee el llamante y debe existir ya: el almacén nunca lo crea, y un raíz ausente se
 * rechaza antes de sincronizar nada (ver [PrivateAngleImageStoreRootOwnershipTest]).
 *
 * El backend registrador decide el resultado de cada `fsync` de directorio, así que no se depende
 * del sistema de ficheros del host. Estas pruebas **no** afirman nada sobre un corte de energía
 * real: comprueban que el almacén sincroniza todas las entradas que necesita y que sólo declara
 * durabilidad cuando **todas** las sincronizaciones reportadas han sido `SYNCED`.
 *
 * Ninguna prueba lee ni modifica rutas del proyecto: cada una usa su propio [TemporaryFolder].
 */
class PrivateAngleImageStoreDurabilityTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(root: File, io: AngleImageFileIo): PrivateAngleImageStore =
        PrivateAngleImageStore(rootDirectory = root, fileIo = io)

    private fun write(store: PrivateAngleImageStore, bytes: ByteArray): AngleImageWriteResult =
        store.write(ByteArrayInputStream(bytes), expectedByteCount = bytes.size.toLong())

    @Test
    fun `sincroniza clave, almacen y raiz cuando angle-images acaba de crearse`() {
        val root = folder.newFolder("raiz-existente")
        val io = RecordingDirectorySyncIo()
        val bytes = payload(256)

        val stored = assertStored(write(store(root, io), bytes))

        val images = imagesDirectory(root)
        val keyDir = keyDirectory(root, stored.image.imageKey)
        assertEquals(
            "orden de más profundo a más superficial: clave, almacén y raíz",
            listOf(keyDir, images, root),
            io.syncedDirectories,
        )
        assertTrue("todas las entradas se sincronizaron: ${stored.durability}", stored.durability.directoryEntriesSynced)
        assertEquals(
            listOf(AngleImageDirectorySync.SYNCED),
            stored.durability.ancestorDirectories,
        )
        assertArrayEquals(bytes, publicImage(root, stored.image.imageKey).readBytes())
    }

    @Test
    fun `un angle-images preexistente no confirmado vuelve a sincronizar el raiz`() {
        val root = folder.newFolder("raiz-ya-existente")
        // Otro escritor (o un intento anterior) creó `angle-images`; observar el directorio NO
        // demuestra que su entrada en el raíz esté persistida. La publicación debe repetir el
        // `fsync` del raíz en vez de heredar una confirmación de la que no tiene evidencia.
        imagesDirectory(root).mkdirs()
        val io = RecordingDirectorySyncIo()
        val bytes = payload(128)

        val stored = assertStored(write(store(root, io), bytes))

        val images = imagesDirectory(root)
        val keyDir = keyDirectory(root, stored.image.imageKey)
        assertEquals(
            "el raíz se sincroniza también cuando angle-images ya existía",
            listOf(keyDir, images, root),
            io.syncedDirectories,
        )
        assertEquals(
            "la entrada de angle-images en el raíz se reporta confirmada en esta operación",
            listOf(AngleImageDirectorySync.SYNCED),
            stored.durability.ancestorDirectories,
        )
        assertTrue(stored.durability.directoryEntriesSynced)
        assertArrayEquals(bytes, publicImage(root, stored.image.imageKey).readBytes())
    }

    @Test
    fun `dos instancias intercaladas no heredan la sincronizacion del raiz`() {
        val root = folder.newFolder("dos-instalaciones")
        val firstBytes = payload(128, seed = 3)
        val firstIo = RecordingDirectorySyncIo()
        val first = assertStored(write(store(root, firstIo), firstBytes))
        assertTrue(
            "la primera escritura crea angle-images y confirma el raíz",
            first.durability.directoryEntriesSynced,
        )

        // Segunda instancia sobre el mismo raíz: `angle-images` ya existe, pero su propio `fsync`
        // del raíz falla. No puede declarar durable lo que no ha confirmado, ni siquiera cuando la
        // primera instancia sí lo confirmó: la observación no es una garantía heredada.
        val secondBytes = payload(96, seed = 5)
        val secondIo = RecordingDirectorySyncIo(result = { directory ->
            if (directory == root) AngleImageDirectorySync.FAILED else AngleImageDirectorySync.SYNCED
        })
        val second = assertStored(write(store(root, secondIo), secondBytes))

        val images = imagesDirectory(root)
        assertEquals(
            "cada instancia sincroniza clave, almacén y raíz en su propia publicación",
            listOf(keyDirectory(root, second.image.imageKey), images, root),
            secondIo.syncedDirectories,
        )
        assertEquals(
            listOf(AngleImageDirectorySync.SYNCED),
            first.durability.ancestorDirectories,
        )
        assertEquals(
            listOf(AngleImageDirectorySync.FAILED),
            second.durability.ancestorDirectories,
        )
        assertFalse(
            "la segunda escritura no hereda la durabilidad del raíz de la primera",
            second.durability.directoryEntriesSynced,
        )
        assertArrayEquals(firstBytes, publicImage(root, first.image.imageKey).readBytes())
        assertArrayEquals(secondBytes, publicImage(root, second.image.imageKey).readBytes())
    }

    @Test
    fun `un raiz no soportado impide durabilidad aunque angle-images preexista`() {
        val root = folder.newFolder("raiz-no-soportado-preexistente")
        imagesDirectory(root).mkdirs()
        val io = RecordingDirectorySyncIo(result = { directory ->
            if (directory == root) AngleImageDirectorySync.UNSUPPORTED else AngleImageDirectorySync.SYNCED
        })
        val bytes = payload(64)
        val store = store(root, io)

        val stored = assertStored(write(store, bytes))

        assertEquals(
            listOf(keyDirectory(root, stored.image.imageKey), imagesDirectory(root), root),
            io.syncedDirectories,
        )
        assertEquals(AngleImageDirectorySync.SYNCED, stored.durability.keyDirectory)
        assertEquals(AngleImageDirectorySync.SYNCED, stored.durability.parentDirectory)
        assertEquals(listOf(AngleImageDirectorySync.UNSUPPORTED), stored.durability.ancestorDirectories)
        assertFalse(
            "sin confirmar la entrada de angle-images en el raíz no hay durabilidad, " +
                "aunque el subdirectorio preexistiera",
            stored.durability.directoryEntriesSynced,
        )
        assertArrayEquals(bytes, publicImage(root, stored.image.imageKey).readBytes())
        assertTrue(
            "la clave publicada se conserva y sigue verificable",
            store.verify(stored.image.imageKey, AngleImageIntegrity(bytes.size.toLong(), stored.image.sha256Hex))
                is AngleImageVerifyResult.Verified,
        )
    }

    @Test
    fun `un raiz fallido impide durabilidad aunque angle-images preexista`() {
        val root = folder.newFolder("raiz-fallido-preexistente")
        imagesDirectory(root).mkdirs()
        val io = RecordingDirectorySyncIo(result = { directory ->
            if (directory == root) AngleImageDirectorySync.FAILED else AngleImageDirectorySync.SYNCED
        })
        val bytes = payload(64, seed = 9)
        val store = store(root, io)

        val stored = assertStored(write(store, bytes))

        assertEquals(
            listOf(keyDirectory(root, stored.image.imageKey), imagesDirectory(root), root),
            io.syncedDirectories,
        )
        assertEquals(listOf(AngleImageDirectorySync.FAILED), stored.durability.ancestorDirectories)
        assertFalse(
            "un fsync fallido del raíz no declara durabilidad ni con angle-images preexistente",
            stored.durability.directoryEntriesSynced,
        )
        assertEquals("la clave se conserva", bytes.size.toLong(), stored.image.byteCount)
        assertArrayEquals(bytes, publicImage(root, stored.image.imageKey).readBytes())
    }

    @Test
    fun `un backend que no soporta fsync de directorios no declara durabilidad`() {
        val root = folder.newFolder("sin-soporte")
        val io = RecordingDirectorySyncIo(result = { AngleImageDirectorySync.UNSUPPORTED })
        val bytes = payload(64)

        val stored = assertStored(write(store(root, io), bytes))

        assertEquals(AngleImageDirectorySync.UNSUPPORTED, stored.durability.keyDirectory)
        assertEquals(AngleImageDirectorySync.UNSUPPORTED, stored.durability.parentDirectory)
        assertEquals(listOf(AngleImageDirectorySync.UNSUPPORTED), stored.durability.ancestorDirectories)
        assertFalse("no se promete durabilidad no soportada", stored.durability.directoryEntriesSynced)
        assertEquals("la clave publicada se conserva", bytes.size.toLong(), stored.image.byteCount)
        assertArrayEquals(bytes, publicImage(root, stored.image.imageKey).readBytes())
    }

    @Test
    fun `un raiz no soportado deja la publicacion sin durabilidad confirmada`() {
        val root = folder.newFolder("raiz-sin-soporte")
        val io = RecordingDirectorySyncIo(result = { directory ->
            if (directory == root) AngleImageDirectorySync.UNSUPPORTED else AngleImageDirectorySync.SYNCED
        })
        val bytes = payload(64)

        val stored = assertStored(write(store(root, io), bytes))

        val images = imagesDirectory(root)
        val keyDir = keyDirectory(root, stored.image.imageKey)
        assertEquals(listOf(keyDir, images, root), io.syncedDirectories)
        assertEquals(AngleImageDirectorySync.SYNCED, stored.durability.keyDirectory)
        assertEquals(AngleImageDirectorySync.SYNCED, stored.durability.parentDirectory)
        assertEquals(listOf(AngleImageDirectorySync.UNSUPPORTED), stored.durability.ancestorDirectories)
        assertFalse(
            "la entrada de angle-images no quedó confirmada en el raíz",
            stored.durability.directoryEntriesSynced,
        )
    }

    @Test
    fun `un syncDirectory que lanza tras el rename conserva la clave y el fichero`() {
        val root = folder.newFolder("sync-lanza")
        val io = RecordingDirectorySyncIo(throwOnSync = { true })
        val bytes = payload(96)
        val store = store(root, io)

        val stored = assertStored(write(store, bytes))

        assertTrue("la clave sobrevive al fallo de fsync", AngleImageKey.isCanonical(stored.image.imageKey))
        assertEquals(AngleImageDirectorySync.FAILED, stored.durability.keyDirectory)
        assertEquals(AngleImageDirectorySync.FAILED, stored.durability.parentDirectory)
        assertEquals(listOf(AngleImageDirectorySync.FAILED), stored.durability.ancestorDirectories)
        assertFalse("un fsync fallido no declara durabilidad", stored.durability.directoryEntriesSynced)
        assertEquals("se intentaron las tres entradas", 3, io.syncedDirectories.size)
        assertArrayEquals("el fichero publicado se preserva", bytes, publicImage(root, stored.image.imageKey).readBytes())
        assertTrue(
            "la imagen sigue siendo verificable",
            store.verify(stored.image.imageKey, AngleImageIntegrity(bytes.size.toLong(), stored.image.sha256Hex))
                is AngleImageVerifyResult.Verified,
        )
    }

    @Test
    fun `un fallo solo del raiz tras el rename conserva la clave y el fichero`() {
        val root = folder.newFolder("raiz-lanza")
        // `angle-images` preexistente: el caso en el que antes se omitía el raíz.
        imagesDirectory(root).mkdirs()
        val io = RecordingDirectorySyncIo(throwOnSync = { directory -> directory == root })
        val bytes = payload(112, seed = 11)
        val store = store(root, io)

        val stored = assertStored(write(store, bytes))

        assertTrue("la clave no se pierde por el fallo del raíz", AngleImageKey.isCanonical(stored.image.imageKey))
        assertEquals(AngleImageDirectorySync.SYNCED, stored.durability.keyDirectory)
        assertEquals(AngleImageDirectorySync.SYNCED, stored.durability.parentDirectory)
        assertEquals(
            "el raíz se intentó y su excepción se reporta como FAILED",
            listOf(AngleImageDirectorySync.FAILED),
            stored.durability.ancestorDirectories,
        )
        assertEquals(
            listOf(keyDirectory(root, stored.image.imageKey), imagesDirectory(root), root),
            io.syncedDirectories,
        )
        assertFalse(
            "un fallo del raíz impide directoryEntriesSynced",
            stored.durability.directoryEntriesSynced,
        )
        assertArrayEquals(bytes, publicImage(root, stored.image.imageKey).readBytes())
        assertTrue(
            "la imagen publicada sigue verificable",
            store.verify(stored.image.imageKey, AngleImageIntegrity(bytes.size.toLong(), stored.image.sha256Hex))
                is AngleImageVerifyResult.Verified,
        )
    }

    @Test
    fun `un raiz ausente se rechaza antes de crear o sincronizar nada`() {
        val base = folder.newFolder("sin-raiz")
        val root = File(base, "raiz-nueva")
        val io = RecordingDirectorySyncIo()
        val bytes = payload(64)

        val failed = assertWriteFailed(write(store(root, io), bytes))

        // Crear el raíz aquí dejaría su entrada en el padre sin `fsync` y una escritura posterior la
        // observaría como existente: se declararía durable un nombre que nadie confirmó. El almacén
        // se niega con error tipado y no toca nada.
        assertEquals(
            AngleImageStoreError.RootDirectoryUnusable(AngleImageRootViolation.ABSENT),
            failed.error,
        )
        assertFalse("el almacén nunca crea el raíz", root.exists())
        assertEquals("el padre no cambia", emptyList<String>(), base.list().orEmpty().toList())
        assertEquals("no se intenta ningún `fsync` de directorio", emptyList<File>(), io.syncedDirectories)
        assertFalse(failed.temporaryFileRetained)
        assertFalse(failed.directoryRetained)
    }
}

/**
 * Backend **registrador**: delega la E/S en [NioAngleImageFileIo] y sustituye únicamente
 * `syncDirectory`, para decidir por prueba el resultado (`SYNCED`, `UNSUPPORTED`, `FAILED`) o si
 * lanza (globalmente o sólo para ciertos directorios). Registra cada directorio sincronizado en
 * orden de llamada.
 *
 * El resultado inyectado es ficción controlada del test, no evidencia de un corte de energía real.
 */
internal class RecordingDirectorySyncIo(
    private val result: (File) -> AngleImageDirectorySync = { AngleImageDirectorySync.SYNCED },
    private val throwOnSync: (File) -> Boolean = { false },
) : NioAngleImageFileIo() {

    val syncedDirectories: MutableList<File> = mutableListOf()

    override fun syncDirectory(directory: File): AngleImageDirectorySync {
        syncedDirectories += directory
        if (throwOnSync(directory)) throw IOException("fsync de directorio inyectado por la prueba")
        return result(directory)
    }
}
