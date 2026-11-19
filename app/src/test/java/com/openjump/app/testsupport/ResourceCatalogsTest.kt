package com.openjump.app.testsupport

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.xml.sax.SAXException

/**
 * Tests focales del helper JVM [ResourceCatalogs].
 *
 * Cubren lo que los guards de i18n dan por sentado y no pueden comprobar con los recursos reales:
 * un parseo por fichero y snapshot inmutable, resolución desde la raíz del repositorio o desde
 * `app` con fallo explícito, XML inválido y DOCTYPE/entidades externas rechazados, y que la lectura
 * conserva duplicados, valores vacíos, placeholders y cantidades de plural por catálogo.
 *
 * Todos los fixtures son temporales y aislados ([TemporaryFolder]): no se lee ni se escribe ningún
 * recurso del proyecto.
 */
class ResourceCatalogsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // --- snapshot: un parseo por fichero, inmutable -----------------------------------------------

    @Test
    fun `parsea cada fichero una sola vez y comparte la lectura entre etiquetas`() {
        writeCatalog(
            path = "app/src/main/res/values/strings.xml",
            body = """
                <string name="a">uno</string>
                <string name="b">dos</string>
            """.trimIndent(),
        )

        val snapshot = ResourceCatalogs.load(
            linkedMapOf(
                "es" to "app/src/main/res/values/strings.xml",
                "es-419" to "src/main/res/values/strings.xml",
            ),
            workingDirectory = temporaryFolder.root,
        )

        assertEquals(2, snapshot.size)
        assertEquals(listOf("es", "es-419"), snapshot.labels)
        assertEquals("un mismo fichero se parsea una vez", 1, snapshot.parsedFileCount)
        assertEquals(
            snapshot.catalog("es").file.canonicalFile,
            snapshot.catalog("es-419").file.canonicalFile,
        )
        assertEquals(snapshot.catalog("es").values("a"), snapshot.catalog("es-419").values("a"))
    }

    @Test
    fun `el snapshot es inmutable y no expone colecciones modificables`() {
        writeCatalog("src/main/res/values/strings.xml", """<string name="a">uno</string>""")
        val catalog = ResourceCatalogs.load(
            linkedMapOf("es" to "src/main/res/values/strings.xml"),
            workingDirectory = temporaryFolder.root,
        ).catalog("es")

        val names = catalog.namesWithPrefix("")
        val values = catalog.values("a")
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (names as MutableMap<String, List<String>>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (values as MutableList<String>).add("otro")
        }
        assertEquals("los intentos fallidos no alteran el snapshot", listOf("uno"), catalog.values("a"))
    }

    // --- resolución de rutas ----------------------------------------------------------------------

    @Test
    fun `resuelve desde la raiz del repositorio y desde el cwd del modulo app`() {
        writeCatalog("app/src/main/res/values-en/strings.xml", """<string name="a">one</string>""")

        val fromRoot = ResourceCatalogs.load(
            linkedMapOf("en" to "app/src/main/res/values-en/strings.xml"),
            workingDirectory = temporaryFolder.root,
        )
        val fromApp = ResourceCatalogs.load(
            linkedMapOf("en" to "src/main/res/values-en/strings.xml"),
            workingDirectory = File(temporaryFolder.root, "app"),
        )

        assertEquals("one", fromRoot.catalog("en").value("a"))
        assertEquals("one", fromApp.catalog("en").value("a"))
        assertEquals(fromRoot.catalog("en").file, fromApp.catalog("en").file)
    }

    @Test
    fun `falla con contexto si la ruta declarada no existe`() {
        val failure = assertThrows(AssertionError::class.java) {
            ResourceCatalogs.load(
                linkedMapOf("es" to "src/main/res/values/strings.xml"),
                workingDirectory = temporaryFolder.root,
            )
        }

        val message = failure.message.orEmpty()
        assertTrue(message, message.contains("src/main/res/values/strings.xml"))
        assertTrue(message, message.contains("Directorio de trabajo"))
        assertTrue(message, message.contains(temporaryFolder.root.absolutePath))
    }

    @Test
    fun `falla con contexto si el locale no declara etiqueta o ruta`() {
        val blankLabel = assertThrows(AssertionError::class.java) {
            ResourceCatalogs.load(
                linkedMapOf(" " to "src/main/res/values/strings.xml"),
                workingDirectory = temporaryFolder.root,
            )
        }
        assertTrue(blankLabel.message.orEmpty(), blankLabel.message.orEmpty().contains("Locale sin etiqueta"))

        val blankPath = assertThrows(AssertionError::class.java) {
            ResourceCatalogs.load(linkedMapOf("es" to "  "), workingDirectory = temporaryFolder.root)
        }
        assertTrue(blankPath.message.orEmpty(), blankPath.message.orEmpty().contains("'es'"))
    }

    @Test
    fun `catalog de una etiqueta no declarada falla con las etiquetas disponibles`() {
        writeCatalog("src/main/res/values/strings.xml", """<string name="a">uno</string>""")
        val snapshot = ResourceCatalogs.load(
            linkedMapOf("es" to "src/main/res/values/strings.xml"),
            workingDirectory = temporaryFolder.root,
        )

        val failure = assertThrows(AssertionError::class.java) { snapshot.catalog("fr") }
        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("[es]"))
    }

    // --- XML endurecido y fallos de parseo --------------------------------------------------------

    @Test
    fun `un xml mal formado falla con la ruta declarada y el fichero resuelto`() {
        writeCatalog("src/main/res/values/strings.xml", """<string name="a">x</resources>""")

        val failure = assertThrows(AssertionError::class.java) {
            ResourceCatalogs.load(
                linkedMapOf("es" to "src/main/res/values/strings.xml"),
                workingDirectory = temporaryFolder.root,
            )
        }
        val message = failure.message.orEmpty()
        assertTrue(message, message.contains("No se pudo parsear src/main/res/values/strings.xml"))
        assertTrue(message, message.contains("strings.xml"))
        assertTrue("la causa es un error de parseo XML", failure.cause is SAXException)
    }

    @Test
    fun `un DOCTYPE con entidad externa se rechaza y no filtra el fichero referenciado`() {
        val secret = temporaryFolder.newFile("secret.txt").apply { writeText("SECRETO") }
        val directory = temporaryFolder.newFolder("src", "main", "res", "values")
        File(directory, "strings.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE resources [<!ENTITY xxe SYSTEM "${secret.toURI()}">]>
            <resources><string name="a">&xxe;</string></resources>
            """.trimIndent(),
        )

        val failure = assertThrows(AssertionError::class.java) {
            ResourceCatalogs.load(
                linkedMapOf("es" to "src/main/res/values/strings.xml"),
                workingDirectory = temporaryFolder.root,
            )
        }
        val message = failure.message.orEmpty()
        assertTrue(message, message.contains("No se pudo parsear"))
        assertTrue("el valor de la entidad externa nunca llega al catálogo", !message.contains("SECRETO"))
    }

    // --- fidelidad de la lectura ------------------------------------------------------------------

    @Test
    fun `conserva nombres duplicados para que el guard pueda detectarlos`() {
        val catalog = catalogOf(
            """
            <string name="dup">uno</string>
            <string name="dup">dos</string>
            <string name="solo">único</string>
            """.trimIndent(),
        )

        assertEquals(2, catalog.stringCount)
        assertEquals(listOf("uno", "dos"), catalog.values("dup"))
        assertEquals("la última aparición gana en value()", "dos", catalog.value("dup"))
        assertEquals("único", catalog.onlyValue("solo"))
        val failure = assertThrows(AssertionError::class.java) { catalog.onlyValue("dup") }
        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("exactamente una vez"))
        assertEquals(emptyList<String>(), catalog.values("ausente"))
        assertNull(catalog.value("ausente"))
    }

    @Test
    fun `conserva los valores vacios y el texto sin el markup inline`() {
        val catalog = catalogOf(
            """
            <string name="vacio"></string>
            <string name="espacios">   </string>
            <string name="markup"><b>Negrita</b> y texto</string>
            """.trimIndent(),
        )

        assertEquals("", catalog.value("vacio"))
        assertTrue(catalog.value("espacios")!!.isBlank())
        assertEquals("Negrita y texto", catalog.value("markup"))
    }

    @Test
    fun `expone los plurales por cantidad sin reinterpretar el catalogo`() {
        val catalog = catalogOf(
            """
            <plurals name="referencia">
                <item quantity="one">%d repetición</item>
                <item quantity="other">%d repeticiones</item>
            </plurals>
            <plurals name="referencia">
                <item quantity="many">declaración posterior ignorada</item>
            </plurals>
            <plurals name="solo_other"><item quantity="other">%d ángulos</item></plurals>
            """.trimIndent(),
        )

        assertEquals(listOf("one", "other"), catalog.pluralItems("referencia").keys.toList())
        assertEquals("%d repeticiones", catalog.pluralItems("referencia")["other"])
        // ja/ko conservan sólo `other`: el helper no añade cantidades que el fichero no declara.
        assertEquals(listOf("other"), catalog.pluralItems("solo_other").keys.toList())
        assertEquals(emptyMap<String, String>(), catalog.pluralItems("ausente"))
    }

    // --- utilidades de texto compartidas ----------------------------------------------------------

    @Test
    fun `formatArguments conserva indices explicitos secuenciales y el escape de porcentaje`() {
        assertEquals(listOf(1 to 's', 2 to 'd'), formatArguments("%1\$s tiene %2\$d"))
        assertEquals(listOf(1 to 's', 2 to 'd'), formatArguments("%s y %d"))
        assertEquals(listOf(2 to 's'), formatArguments("%2\$s"))
        assertEquals(
            "un %% literal no es un argumento",
            emptyList<Pair<Int, Char>>(),
            formatArguments("100%%"),
        )
    }

    @Test
    fun `positionalPlaceholders solo compara indices explicitos de texto y entero`() {
        assertEquals(
            mapOf(2 to 's', 1 to 'd'),
            positionalPlaceholders("%2\$s ángulos en %1\$d fotogramas"),
        )
        assertEquals(emptyMap<Int, Char>(), positionalPlaceholders("%d sin índice"))
        assertEquals(emptyMap<Int, Char>(), positionalPlaceholders("100%%"))
        assertEquals(emptyMap<Int, Char>(), positionalPlaceholders("sin placeholders"))
    }

    @Test
    fun `androidUnescapeForFormat deshace el apostrofo escapado de Android`() {
        assertEquals("l'angle", androidUnescapeForFormat("l\\'angle"))
        assertEquals("sin escapes", androidUnescapeForFormat("sin escapes"))
    }

    // --- fixtures ---------------------------------------------------------------------------------

    /** Escribe [path] bajo la raíz temporal con el cuerpo XML recibido y devuelve el fichero. */
    private fun writeCatalog(path: String, body: String): File {
        val file = File(temporaryFolder.root, path)
        file.parentFile.mkdirs()
        file.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n$body\n</resources>\n")
        return file
    }

    private fun catalogOf(stringsXml: String): ResourceCatalog {
        writeCatalog("app/src/main/res/values/strings.xml", stringsXml)
        return ResourceCatalogs.load(
            linkedMapOf("es" to "app/src/main/res/values/strings.xml"),
            workingDirectory = temporaryFolder.root,
        ).catalog("es")
    }
}
