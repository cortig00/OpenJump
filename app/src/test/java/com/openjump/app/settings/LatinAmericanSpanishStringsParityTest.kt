package com.openjump.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guard de paridad del catálogo `es-419` (Español de Latinoamérica).
 *
 * El catálogo es una copia completa del español base (`values/strings.xml`) con vocabulario
 * neutro de Hispanoamérica, no un overlay parcial. Este test JVM puro lee los recursos del módulo
 * `app` y comprueba, sin instalar nada ni arrancar Android:
 *
 * 1. mismas claves `<string>` y `<plurals>` en base y `es-419`, cada una exactamente una vez;
 * 2. mismas cantidades (`quantity`) en cada plural;
 * 3. mismos argumentos de formato (índice y conversión) en strings y en cada ítem de plural, y que
 *    el patrón es realmente formateable en el locale `es-419`;
 * 4. mismo markup inline (secuencia de etiquetas) y mismos escapes Android (`\n`, `\'`, `\"`, `\t`);
 * 5. ningún valor vacío;
 * 6. la opción nativa del selector existe en TODOS los locales con valor no vacío;
 * 7. el vocabulario neutro acordado se aplica en `es-419` (video/videos, Configuración,
 *    toca/tocar, agrega/agregar, celular) sin tocar el español base.
 *
 * Si un fichero no se puede localizar o parsear, el test falla con un mensaje explícito: nunca
 * pasa por omisión.
 */
class LatinAmericanSpanishStringsParityTest {

    private data class Catalog(
        val label: String,
        val strings: Map<String, String>,
        val plurals: Map<String, Map<String, String>>,
        /** Claves con `formatted="false"`: su `%` es literal y no se puede formatear. */
        val unformatted: Set<String>,
    )

    private fun resolve(path: String): File {
        val candidates = listOf(File(path), File("app/$path"))
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "No se encuentra '$path'. Directorio de trabajo: ${File(".").absolutePath}. " +
                    "Rutas probadas: ${candidates.joinToString { it.absolutePath }}",
            )
    }

    private fun resolveDirectory(path: String): File {
        val candidates = listOf(File(path), File("app/$path"))
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "No se encuentra el directorio '$path'. Directorio de trabajo: " +
                    "${File(".").absolutePath}",
            )
    }

    private fun parse(label: String, path: String): Catalog {
        val file = resolve(path)
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        try {
            // Endurecimiento local: los strings.xml del proyecto no usan DTD.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (_: Exception) {
            // Parser sin soporte para esta feature: el parseo normal sigue siendo válido aquí.
        }
        val document = try {
            factory.newDocumentBuilder().parse(file)
        } catch (failure: Exception) {
            throw AssertionError("No se pudo parsear $path (${file.absolutePath}): ${failure.message}", failure)
        }

        val strings = LinkedHashMap<String, String>()
        val unformatted = LinkedHashSet<String>()
        val stringNodes = document.getElementsByTagName("string")
        for (index in 0 until stringNodes.length) {
            val element = stringNodes.item(index) as? Element ?: continue
            val name = element.getAttribute("name")
            assertTrue("[$label] '$name' está duplicado en $path", strings.put(name, element.textContent) == null)
            if (element.getAttribute("formatted") == "false") unformatted += name
        }

        val plurals = LinkedHashMap<String, Map<String, String>>()
        val pluralNodes = document.getElementsByTagName("plurals")
        for (index in 0 until pluralNodes.length) {
            val element = pluralNodes.item(index) as? Element ?: continue
            val name = element.getAttribute("name")
            val items = LinkedHashMap<String, String>()
            val children = element.childNodes
            for (childIndex in 0 until children.length) {
                val child = children.item(childIndex) as? Element ?: continue
                if (child.tagName != "item") continue
                val quantity = child.getAttribute("quantity")
                assertTrue(
                    "[$label] '$name' repite la cantidad '$quantity' en $path",
                    items.put(quantity, child.textContent) == null,
                )
            }
            assertTrue("[$label] '$name' está duplicado en $path", plurals.put(name, items) == null)
        }
        return Catalog(label, strings, plurals, unformatted)
    }

    /** Firma del markup inline: nombres de etiquetas hijas en orden. */
    private fun parseWithMarkup(path: String): Map<String, List<String>> {
        val file = resolve(path)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val signature = LinkedHashMap<String, List<String>>()
        val elements = document.getElementsByTagName("*")
        for (index in 0 until elements.length) {
            val element = elements.item(index) as? Element ?: continue
            if (element.tagName != "string") continue
            val tags = mutableListOf<String>()
            val children = element.childNodes
            for (childIndex in 0 until children.length) {
                val child = children.item(childIndex) ?: continue
                if (child.nodeType == Node.ELEMENT_NODE) tags += child.nodeName
            }
            signature[element.getAttribute("name")] = tags
        }
        return signature
    }

    private fun escapeTokens(value: String): List<String> =
        Regex("""\\[n'"t\\]""").findAll(value).map { it.value }.sorted().toList()

    /** Índices y conversiones de los placeholders, en orden de aparición (ignora `%%`). */
    private fun formatArguments(value: String): List<Pair<Int, Char>> {
        // Acepta índice posicional, flags, ancho y precisión: `%1$.2f`, `%-4d`, `%2$s`.
        val pattern = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?([a-zA-Z%])""")
        val result = mutableListOf<Pair<Int, Char>>()
        var sequential = 1
        for (match in pattern.findAll(value)) {
            val conversion = match.groupValues[3].single()
            if (conversion == '%') continue
            val explicit = match.groupValues[1]
            val index = explicit.removeSuffix("$").toIntOrNull() ?: sequential
            result += index to conversion
            if (explicit.isEmpty()) sequential += 1
        }
        return result.sortedBy { it.first }
    }

    private fun androidUnescape(value: String): String =
        value.replace("\\'", "'").replace("\\\"", "\"")

    @Test
    fun `es 419 has exactly the same keys as the spanish base`() {
        val base = parse(BASE_LOCALE, basePath())
        val latin = parse(LATIN_AMERICA_LOCALE, latinAmericaPath())

        // El catálogo base es grande: un fichero truncado no debe pasar por "paridad".
        assertTrue(
            "La base debe tener >= $MIN_EXPECTED_KEYS strings, tiene ${base.strings.size}",
            base.strings.size >= MIN_EXPECTED_KEYS,
        )
        assertEquals("claves <string> solo en la base", emptyList<String>(), (base.strings.keys - latin.strings.keys).sorted())
        assertEquals("claves <string> solo en es-419", emptyList<String>(), (latin.strings.keys - base.strings.keys).sorted())
        assertEquals(
            "claves <plurals> solo en la base",
            emptyList<String>(),
            (base.plurals.keys - latin.plurals.keys).sorted(),
        )
        assertEquals(
            "claves <plurals> solo en es-419",
            emptyList<String>(),
            (latin.plurals.keys - base.plurals.keys).sorted(),
        )
    }

    @Test
    fun `generic spanish has an explicit catalog identical to the default`() {
        // Sin values-es Android puede preferir el único español calificado (es-419), incluso
        // cuando el usuario elige Español. El espejo explícito evita esa regresión de fallback.
        assertEquals(
            "values-es debe conservar íntegro el catálogo español original",
            resolve(basePath()).readText(),
            resolve("src/main/res/values-es/strings.xml").readText(),
        )
    }

    @Test
    fun `es 419 plurals keep every quantity of the base`() {
        val base = parse(BASE_LOCALE, basePath())
        val latin = parse(LATIN_AMERICA_LOCALE, latinAmericaPath())
        for ((name, baseItems) in base.plurals) {
            val latinItems = latin.plurals.getValue(name)
            assertEquals("[$name] cantidades distintas", baseItems.keys.sorted(), latinItems.keys.sorted())
        }
    }

    @Test
    fun `es 419 keeps placeholders, escapes and inline markup`() {
        val base = parse(BASE_LOCALE, basePath())
        val latin = parse(LATIN_AMERICA_LOCALE, latinAmericaPath())
        val baseMarkup = parseWithMarkup(basePath())
        val latinMarkup = parseWithMarkup(latinAmericaPath())

        for ((name, baseValue) in base.strings) {
            val value = latin.strings.getValue(name)
            assertEquals("[$name] placeholders de formato", formatArguments(baseValue), formatArguments(value))
            assertEquals("[$name] escapes Android", escapeTokens(baseValue), escapeTokens(value))
            assertEquals("[$name] markup inline", baseMarkup[name].orEmpty(), latinMarkup[name].orEmpty())
            assertTrue("[$name] valor vacío en es-419", value.isNotBlank())
            if (name !in latin.unformatted && name !in base.unformatted) assertFormatable(name, value)
        }
        for ((name, baseItems) in base.plurals) {
            val latinItems = latin.plurals.getValue(name)
            for ((quantity, baseValue) in baseItems) {
                val value = latinItems.getValue(quantity)
                assertEquals(
                    "[$name/$quantity] placeholders de formato",
                    formatArguments(baseValue),
                    formatArguments(value),
                )
                assertEquals("[$name/$quantity] escapes Android", escapeTokens(baseValue), escapeTokens(value))
                assertTrue("[$name/$quantity] valor vacío en es-419", value.isNotBlank())
                if (name !in latin.unformatted && name !in base.unformatted) {
                    assertFormatable("$name/$quantity", value)
                }
            }
        }
    }

    private fun assertFormatable(name: String, value: String) {
        val arguments = formatArguments(value)
        if (arguments.isEmpty()) return
        val maxIndex = arguments.maxOf { it.first }
        val args = Array<Any?>(maxIndex) { "x" }
        arguments.forEach { (index, conversion) ->
            args[index - 1] = when (conversion) {
                'd', 'o', 'x', 'X' -> 1L
                'f', 'e', 'E', 'g', 'G', 'a', 'A' -> 1.0
                else -> "x"
            }
        }
        try {
            String.format(java.util.Locale.forLanguageTag("es-419"), androidUnescape(value), *args)
        } catch (failure: java.util.IllegalFormatException) {
            throw AssertionError("[$name] no es formateable: ${failure.message}", failure)
        }
    }

    @Test
    fun `the selector option exists in every locale`() {
        val resDir = resolveDirectory("src/main/res")
        val localeFiles = resDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values") }
            .sortedBy { it.name }
        // Doce idiomas/regiones, más el catálogo español genérico explícito (espejo del default).
        assertTrue(
            "se esperaban al menos $EXPECTED_LOCALE_DIRS directorios values* y hay " +
                "${localeFiles.map { it.name }}",
            localeFiles.size >= EXPECTED_LOCALE_DIRS,
        )
        val baseLabel = parse(BASE_LOCALE, basePath()).strings[LABEL_KEY]
        assertTrue("la base debe declarar '$LABEL_KEY'", baseLabel != null)
        for (directory in localeFiles) {
            val file = File(directory, "strings.xml")
            assertTrue("falta ${file.path}", file.isFile)
            val relative = "src/main/res/${directory.name}/strings.xml"
            val label = parse(directory.name, relative).strings[LABEL_KEY]
            assertTrue("[${directory.name}] no declara '$LABEL_KEY'", !label.isNullOrBlank())
            // Etiqueta nativa: el nombre del idioma no se traduce, solo se anota la región.
            assertEquals("[${directory.name}] etiqueta nativa inesperada", baseLabel, label)
        }
    }

    @Test
    fun `es 419 uses neutral latin american vocabulary`() {
        val latin = parse(LATIN_AMERICA_LOCALE, latinAmericaPath())
        val base = parse(BASE_LOCALE, basePath())

        val forbidden = mapOf(
            Regex("""\b[Vv]ídeo(s)?\b""") to "vídeo (debe ser video)",
            Regex("""\bAjustes\b""") to "Ajustes (debe ser Configuración)",
            Regex("""\bajustes\b""") to "ajustes (debe ser configuración)",
            Regex("""\bpulsa(r|do|da|ndo)?\b""") to "pulsa/pulsar (debe ser toca/tocar)",
            Regex("""\bPulsa(r|do|da|ndo)?\b""") to "Pulsa/Pulsar (debe ser Toca/Tocar)",
            Regex("""\bañad(e|ir|ida|ido|idas|idos)\b""") to "añadir (debe ser agregar)",
            Regex("""\bAñad(e|ir|ida|ido|idas|idos)\b""") to "Añadir (debe ser Agregar)",
            Regex("""\bmóvil\b""") to "móvil (debe ser celular)",
        )
        for ((pattern, description) in forbidden) {
            val offenders = (latin.strings.entries.map { it.value } + latin.plurals.values.flatMap { it.values })
                .filter { pattern.containsMatchIn(it) }
            assertEquals("[es-419] valores con $description", emptyList<String>(), offenders)
        }

        assertEquals("Configuración", latin.strings.getValue("nav_settings"))
        assertEquals("Configuración", latin.strings.getValue("settings_title"))
        assertEquals("Video", latin.strings.getValue("jump_flow_video"))
        assertEquals("Saltos", latin.strings.getValue("home_jumps_title"))
        assertTrue(latin.strings.getValue("common_import_video").contains("video"))

        // El español existente no se toca: conserva sus textos y su tag.
        assertEquals("es", AppLanguage.SPANISH.languageTag)
        assertEquals("Ajustes", base.strings.getValue("nav_settings"))
        assertEquals("Vídeo", base.strings.getValue("jump_flow_video"))
        assertNotEquals(
            base.strings.getValue("nav_settings"),
            latin.strings.getValue("nav_settings"),
        )
    }

    private fun basePath() = "src/main/res/values/strings.xml"

    private fun latinAmericaPath() = "src/main/res/values-b+es+419/strings.xml"

    private companion object {
        const val BASE_LOCALE = "es"
        const val LATIN_AMERICA_LOCALE = "es-419"
        const val LABEL_KEY = "settings_language_spanish_latin_america"
        const val MIN_EXPECTED_KEYS = 1500
        const val EXPECTED_LOCALE_DIRS = 13
    }
}
