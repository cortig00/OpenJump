package com.openjump.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guard de paridad completa de los catálogos nuevos `hi` (hindi) y `b+zh+Hans` (chino
 * simplificado) frente al catálogo base español (`values/strings.xml`).
 *
 * A diferencia del guard de `es-419`, estos catálogos no se sirven con overlay parcial: son
 * copias completas del base con dos etiquetas nativas nuevas. Este test JVM puro lee los recursos
 * del módulo `app` y comprueba, sin instalar nada ni arrancar Android:
 *
 * 1. conjunto completo de claves `<string>` (1942) y `<plurals>` (12), sin duplicados ni faltas;
 * 2. cada plural conserva sus cantidades (`one`/`other`, 24 ítems) con texto no vacío;
 * 3. mismos `printf` (índice, flags/ancho/precisión y conversión) en strings y en cada ítem,
 *    los mismos escapes Android, el mismo markup inline y los mismos espacios de borde;
 * 4. `formatted="false"` se respeta: en esas claves el `%` es literal y no se intenta formatear
 *    (la limitación de Sayers conserva su porcentaje literal, `2.7 %` frente a `2,7 %` del base);
 * 5. los textos con argumentos se formatean de verdad con `Locale.forLanguageTag("hi")` y
 *    `Locale.forLanguageTag("zh-Hans")` y argumentos tipados: el catálogo nuevo no depende de
 *    argumentos de otro idioma;
 * 6. las dos etiquetas nativas (`हिंदी`, `简体中文`) existen con el mismo valor en los 15 catálogos
 *    y las etiquetas nativas previas (`Español`, `한국어`) siguen intactas;
 * 7. `androidResources.localeFilters` de `app/build.gradle.kts` conserva los 12 filtros previos y
 *    añade `"hi"` y `"b+zh+Hans"`: sin eso AGP filtraría los catálogos nuevos del APK aunque
 *    existieran en `res/`;
 * 8. la categoría CLDR `one` del hindi cubre 0 y 1, así que en los cuatro textos de series de
 *    cámara el ítem `one` no puede fijar un `1` literal: los llamadores
 *    (`pluralStringResource(resource, targetAttempts, targetAttempts)`) ya pasan el recuento real
 *    en todas las categorías, y el contrato exigido a ese ítem es exactamente el `%d` del ítem
 *    `other` del catálogo base. La excepción está acotada a esas cuatro claves de `hi`: no es una
 *    relajación general de la paridad, y el resto de cantidades e idiomas siguen comparándose
 *    contra el ítem base homónimo.
 *
 * Si un fichero no se puede localizar o parsear, el test falla con un mensaje explícito: nunca
 * pasa por omisión.
 */
class HindiAndSimplifiedChineseStringsParityTest {

    private data class Catalog(
        val label: String,
        val strings: Map<String, String>,
        val plurals: Map<String, Map<String, String>>,
        val unformatted: Set<String>,
    )

    /** Especificación de un `printf`: índice, flags/ancho/precisión y conversión. */
    private data class FormatSpec(val index: Int, val modifiers: String, val conversion: Char)

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
        Regex("""\\[nt\\]""").findAll(value).map { it.value }.sorted().toList()

    /**
     * `printf` del texto: índice, flags/ancho/precisión y conversión, en orden de aparición.
     * Ignora el escape `%%`. No se usa en claves `formatted="false"`, donde el `%` es literal.
     */
    private fun formatSpecs(value: String): List<FormatSpec> {
        // Acepta índice posicional, flags, ancho y precisión: `%1$.2f`, `%-4d`, `%2$s`.
        val pattern = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?([a-zA-Z%])""")
        val result = mutableListOf<FormatSpec>()
        var sequential = 1
        for (match in pattern.findAll(value)) {
            val conversion = match.groupValues[3].single()
            if (conversion == '%') continue
            val explicit = match.groupValues[1]
            val index = explicit.removeSuffix("$").toIntOrNull() ?: sequential
            var modifiers = match.value.removePrefix("%")
            if (explicit.isNotEmpty()) modifiers = modifiers.removePrefix(explicit)
            result += FormatSpec(index, modifiers.dropLast(1), conversion)
            if (explicit.isEmpty()) sequential += 1
        }
        return result.sortedWith(compareBy({ it.index }, { it.modifiers }, { it.conversion }))
    }

    private fun androidUnescape(value: String): String =
        value.replace("\\'", "'").replace("\\\"", "\"")

    /** Argumentos tipados según la conversión: los tipos no cambian entre idiomas. */
    private fun typedArguments(specs: List<FormatSpec>): Array<Any?> {
        val maxIndex = specs.maxOfOrNull { it.index } ?: return emptyArray()
        val arguments = Array<Any?>(maxIndex) { "x" }
        specs.forEach { spec ->
            arguments[spec.index - 1] = when (spec.conversion) {
                'd', 'o', 'x', 'X' -> 1L
                'f', 'e', 'E', 'g', 'G', 'a', 'A' -> 1.0
                else -> "x"
            }
        }
        return arguments
    }

    private fun assertFormatsInLocale(catalogTag: String, name: String, value: String) {
        val specs = formatSpecs(value)
        if (specs.isEmpty()) return
        try {
            val formatted = String.format(
                Locale.forLanguageTag(catalogTag),
                androidUnescape(value),
                *typedArguments(specs),
            )
            assertTrue("[$catalogTag/$name] formateado vacío", formatted.isNotBlank())
        } catch (failure: java.util.IllegalFormatException) {
            throw AssertionError("[$catalogTag/$name] no es formateable: ${failure.message}", failure)
        }
    }

    /** Fallo explícito si falta la clave: nunca se pasa por omisión ni por `getValue`. */
    private fun stringOf(catalog: Catalog, name: String): String =
        catalog.strings[name]
            ?: throw AssertionError("[${catalog.label}] falta la clave <string> '$name'")

    private fun pluralOf(catalog: Catalog, name: String): Map<String, String> =
        catalog.plurals[name]
            ?: throw AssertionError("[${catalog.label}] falta el plural '$name'")

    /**
     * Contrato `printf` esperado para un ítem de plural.
     *
     * Salvo una excepción acotada, es el del ítem base homónimo: la paridad de `printf` no se
     * relaja. La excepción es CLDR y sólo aplica a `hi` + `one` + las cuatro claves de series de
     * cámara: el hindi usa la categoría `one` para 0 y 1, así que un `1` literal en ese ítem
     * renderizaría "1" tanto para 0 como para 1. Los llamadores
     * (`pluralStringResource(resource, targetAttempts, targetAttempts)`) ya pasan el recuento en
     * todas las categorías, de modo que el contrato correcto es exactamente el `%d` del ítem
     * `other` del base: un único entero (`%d`, índice 1, sin flags ni ancho/precisión). La firma se
     * exige exacta: faltar el argumento, añadir otro, cambiar el tipo o tocar otra clave falla.
     */
    private fun expectedPluralFormatSpecs(
        tag: String,
        name: String,
        quantity: String,
        baseItems: Map<String, String>,
    ): List<FormatSpec> {
        if (tag == HINDI_TAG && quantity == "one" && name in HINDI_COUNT_FORMATTED_ONE_KEYS) {
            val baseOther = baseItems["other"]
                ?: throw AssertionError("[$BASE_LOCALE/$name] falta la cantidad 'other' en el base")
            val expected = formatSpecs(baseOther)
            assertEquals(
                "[$BASE_LOCALE/$name/other] el recuento debe ser un único entero %d",
                listOf(FormatSpec(1, "", 'd')),
                expected,
            )
            return expected
        }
        val baseValue = baseItems[quantity]
            ?: throw AssertionError("[$BASE_LOCALE/$name] falta la cantidad '$quantity' en el base")
        return formatSpecs(baseValue)
    }

    /**
     * Formatea [value] con un único argumento entero en el locale [catalogTag]. Si el texto exigiera
     * más argumentos o un tipo distinto, `String.format` lanza y el test falla: el recuento es el
     * único argumento del contrato.
     */
    private fun formatWithCount(catalogTag: String, value: String, count: Int): String = try {
        String.format(Locale.forLanguageTag(catalogTag), androidUnescape(value), count.toLong())
    } catch (failure: java.util.IllegalFormatException) {
        throw AssertionError("[$catalogTag] '$value' no acepta un único entero: ${failure.message}", failure)
    }

    @Test
    fun `hi and zh hans have exactly the full base key set`() {
        val base = parse(BASE_LOCALE, basePath())
        // El catálogo base es grande: un fichero truncado no debe pasar por "paridad".
        assertTrue(
            "La base debe tener >= $MIN_EXPECTED_KEYS strings, tiene ${base.strings.size}",
            base.strings.size >= MIN_EXPECTED_KEYS,
        )
        assertEquals("strings del base", EXPECTED_STRINGS, base.strings.size)
        assertEquals("plurals del base", EXPECTED_PLURALS, base.plurals.size)
        for ((tag, path) in NEW_CATALOGS) {
            val catalog = parse(tag, path)
            assertEquals("[$tag] strings", EXPECTED_STRINGS, catalog.strings.size)
            assertEquals("[$tag] plurals", EXPECTED_PLURALS, catalog.plurals.size)
            assertEquals(
                "[$tag] claves <string> solo en la base",
                emptyList<String>(),
                (base.strings.keys - catalog.strings.keys).sorted(),
            )
            assertEquals(
                "[$tag] claves <string> solo en $tag",
                emptyList<String>(),
                (catalog.strings.keys - base.strings.keys).sorted(),
            )
            assertEquals(
                "[$tag] claves <plurals> solo en la base",
                emptyList<String>(),
                (base.plurals.keys - catalog.plurals.keys).sorted(),
            )
            assertEquals(
                "[$tag] claves <plurals> solo en $tag",
                emptyList<String>(),
                (catalog.plurals.keys - base.plurals.keys).sorted(),
            )
            // Fallback silencioso: ninguna cadena puede quedar vacía y esperar la del base.
            catalog.strings.forEach { (name, value) ->
                assertTrue("[$tag/$name] valor vacío", value.isNotBlank())
            }
        }
    }

    @Test
    fun `hi and zh hans keep every plural quantity and item`() {
        val base = parse(BASE_LOCALE, basePath())
        assertEquals(
            "cantidades por plural del base",
            EXPECTED_PLURAL_ITEMS,
            base.plurals.values.sumOf { it.size },
        )
        for ((tag, path) in NEW_CATALOGS) {
            val catalog = parse(tag, path)
            assertEquals(
                "[$tag] ítems de plural",
                EXPECTED_PLURAL_ITEMS,
                catalog.plurals.values.sumOf { it.size },
            )
            for ((name, baseItems) in base.plurals) {
                val items = pluralOf(catalog, name)
                assertEquals("[$tag/$name] cantidades", baseItems.keys.sorted(), items.keys.sorted())
                assertTrue("[$tag/$name] sin cantidad 'one'", items.containsKey("one"))
                assertTrue("[$tag/$name] sin cantidad 'other'", items.containsKey("other"))
                for (quantity in baseItems.keys) {
                    val value = items.getValue(quantity)
                    assertTrue("[$tag/$name/$quantity] valor vacío", value.isNotBlank())
                    // Contrato del ítem base homónimo, salvo la excepción CLDR acotada de `hi`
                    // descrita en [expectedPluralFormatSpecs].
                    assertEquals(
                        "[$tag/$name/$quantity] placeholders",
                        expectedPluralFormatSpecs(tag, name, quantity, baseItems),
                        formatSpecs(value),
                    )
                    assertFormatsInLocale(tag, "$name/$quantity", value)
                }
            }
        }
    }

    @Test
    fun `hi and zh hans preserve placeholders escapes markup and spacing`() {
        val base = parse(BASE_LOCALE, basePath())
        val baseMarkup = parseWithMarkup(basePath())
        for ((tag, path) in NEW_CATALOGS) {
            val catalog = parse(tag, path)
            val markup = parseWithMarkup(path)
            // El atributo formatted="false" debe ser el mismo conjunto exacto del base.
            assertEquals("[$tag] claves formatted=false", base.unformatted, catalog.unformatted)
            for ((name, baseValue) in base.strings) {
                val value = stringOf(catalog, name)
                assertEquals("[$name] markup inline", baseMarkup[name].orEmpty(), markup[name].orEmpty())
                assertEquals("[$name] escapes Android", escapeTokens(baseValue), escapeTokens(value))
                assertEquals(
                    "[$name] espacio inicial",
                    baseValue.startsWith(" "),
                    value.startsWith(" "),
                )
                assertEquals(
                    "[$name] espacio final",
                    baseValue.endsWith(" "),
                    value.endsWith(" "),
                )
                if (name in base.unformatted) {
                    // `formatted="false"`: el `%` es literal (2.7 %, no un printf) y no se formatea.
                    assertEquals(
                        "[$tag/$name] porcentajes literales",
                        literalPercentCount(baseValue),
                        literalPercentCount(value),
                    )
                    continue
                }
                assertEquals("[$name] placeholders", formatSpecs(baseValue), formatSpecs(value))
            }
        }
    }

    @Test
    fun `hi and zh hans format their arguments in their own locale`() {
        val base = parse(BASE_LOCALE, basePath())
        var checked = 0
        for ((tag, path) in NEW_CATALOGS) {
            val catalog = parse(tag, path)
            for ((name, baseValue) in base.strings) {
                if (name in base.unformatted) continue
                val value = stringOf(catalog, name)
                if (formatSpecs(baseValue).isEmpty()) continue
                assertFormatsInLocale(tag, name, value)
                checked += 1
            }
        }
        // Cientos de textos con argumentos reales en ambos idiomas nuevos.
        assertTrue("solo se formatearon $checked textos con argumentos", checked >= 200)
    }

    @Test
    fun `the sayers limitation keeps the literal percent of the source`() {
        val base = parse(BASE_LOCALE, basePath())
        assertEquals(setOf(SAYERS_LIMITATION), base.unformatted)
        for ((tag, path) in NEW_CATALOGS) {
            val catalog = parse(tag, path)
            val baseValue = base.strings.getValue(SAYERS_LIMITATION)
            val value = stringOf(catalog, SAYERS_LIMITATION)
            assertTrue("[$tag] Sayers debe seguir marcada formatted=false", SAYERS_LIMITATION in catalog.unformatted)
            // Porcentaje literal, no un argumento: el número se localiza (2,7 → 2.7) pero el
            // catálogo nuevo no introduce un printf que dependa de argumentos.
            assertEquals("[$tag] porcentajes literales", literalPercentCount(baseValue), literalPercentCount(value))
            assertTrue("[$tag] se perdió el nombre 'Sayers'", value.contains("Sayers"))
            assertTrue("[$tag] texto vacío", value.isNotBlank())
        }
    }

    @Test
    fun `the native autonyms exist in every locale catalog`() {
        val resDir = resolveDirectory("src/main/res")
        val localeDirs = resDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values") }
            .sortedBy { it.name }
        assertEquals("número de catálogos values*", EXPECTED_LOCALE_DIRS, localeDirs.size)
        val names = localeDirs.map { it.name }.toSet()
        assertTrue("falta el catálogo values-hi", names.contains("values-hi"))
        assertTrue("falta el catálogo values-b+zh+Hans", names.contains("values-b+zh+Hans"))
        for (directory in localeDirs) {
            val file = File(directory, "strings.xml")
            assertTrue("falta ${file.path}", file.isFile)
            // Un catálogo truncado o un stub mínimo no debe pasar: los masters revisados superan
            // los 100 KiB en ambos idiomas nuevos.
            assertTrue(
                "[${directory.name}] catálogo sospechosamente pequeño: ${file.length()} B",
                file.length() > MIN_CATALOG_BYTES,
            )
            val relative = "src/main/res/${directory.name}/strings.xml"
            val catalog = parse(directory.name, relative)
            assertEquals("[${directory.name}] strings", EXPECTED_STRINGS, catalog.strings.size)
            assertEquals("[${directory.name}] plurals", EXPECTED_PLURALS, catalog.plurals.size)
            // Etiquetas nativas: nombre del idioma en su propio idioma, idéntico en los 15 catálogos.
            assertEquals("[${directory.name}] hindi", HINDI_AUTONYM, stringOf(catalog, "settings_language_hindi"))
            assertEquals(
                "[${directory.name}] chino simplificado",
                CHINESE_AUTONYM,
                stringOf(catalog, "settings_language_chinese_simplified"),
            )
            assertNotEquals(
                "[${directory.name}] etiquetas nuevas indistinguibles",
                stringOf(catalog, "settings_language_hindi"),
                stringOf(catalog, "settings_language_chinese_simplified"),
            )
            // Etiquetas nativas previas intactas en todos los catálogos.
            assertEquals("[${directory.name}] español", "Español", stringOf(catalog, "settings_language_spanish"))
            assertEquals("[${directory.name}] coreano", "한국어", stringOf(catalog, "settings_language_korean"))
        }
    }

    @Test
    fun `the build locale filters keep the old locales and add the new ones`() {
        val text = resolve("build.gradle.kts").readText()
        assertTrue(
            "generateLocaleConfig debe seguir generando la configuración por app",
            text.contains("generateLocaleConfig = true"),
        )
        val listMatch = Regex("""localeFilters\s*\+=\s*listOf\(([^)]*)\)""").find(text)
            ?: throw AssertionError("no se encontró la lista localeFilters en app/build.gradle.kts")
        val filters = Regex("\"([^\"]*)\"").findAll(listMatch.groupValues[1]).map { it.groupValues[1] }.toList()
        // Orden exacto: los 12 filtros previos y los dos nuevos añadidos al final. Sin esto AGP
        // empaquetaría solo los idiomas antiguos aunque los XML existan.
        assertEquals(
            listOf("es", "en", "fr", "de", "pt-rBR", "pt-rPT", "it", "tr", "b+es+419", "pl", "ja", "ko", "hi", "b+zh+Hans"),
            filters,
        )
        assertTrue("falta el filtro hi", filters.contains("hi"))
        assertTrue("falta el filtro b+zh+Hans", filters.contains("b+zh+Hans"))
    }

    /**
     * Los cuatro plurales de series de cámara en hindi formatean el recuento real en su ítem
     * `one`: la categoría CLDR `one` del hindi cubre 0 y 1 y los llamadores ya pasan el recuento.
     */
    @Test
    fun `the four hindi plural one items render the real count`() {
        val base = parse(BASE_LOCALE, basePath())
        val hindi = parse(HINDI_TAG, NEW_CATALOGS.getValue(HINDI_TAG))
        val hiLocale = Locale.forLanguageTag(HINDI_TAG)
        // Conjunto explícito: exactamente estas cuatro claves, ni una más ni una menos.
        assertEquals("claves con recuento en el ítem one de hi", 4, HINDI_COUNT_FORMATTED_ONE_KEYS.size)
        assertEquals(
            "claves con recuento en el ítem one de hi",
            setOf(
                "camera_series_before_recording",
                "camera_series_recording",
                "camera_bilateral_series_before_recording",
                "camera_bilateral_series_recording",
            ),
            HINDI_COUNT_FORMATTED_ONE_KEYS,
        )
        val integerCount = listOf(FormatSpec(1, "", 'd'))
        for (name in HINDI_COUNT_FORMATTED_ONE_KEYS) {
            val baseItems = pluralOf(base, name)
            val hindiItems = pluralOf(hindi, name)
            assertEquals("[$name] cantidades", baseItems.keys.sorted(), hindiItems.keys.sorted())
            // Base `other` = un único entero índice 1, y el ítem `one` de hi exige esa misma firma.
            assertEquals("[$name] base other", integerCount, formatSpecs(baseItems.getValue("other")))
            val oneValue = hindiItems.getValue("one")
            assertEquals("[$name] hi one", integerCount, formatSpecs(oneValue))
            // `one` cubre 0 y 1 en hi: ambos recuentos se sustituyen de verdad, sin asumir dígitos
            // latinos (el token se deriva con el mismo locale) ni un `1` literal.
            val rendered = listOf(0, 1).map { quantity ->
                val text = formatWithCount(HINDI_TAG, oneValue, quantity)
                val token = String.format(hiLocale, "%d", quantity)
                assertTrue("[$name/$quantity] falta el recuento '$token' en '$text'", text.contains(token))
                assertTrue(
                    "[$name/$quantity] el recuento no se sustituyó: '$text'",
                    text != androidUnescape(oneValue),
                )
                text
            }
            // Con un `1` literal, 0 y 1 renderizarían exactamente el mismo texto.
            assertNotEquals("[$name] sus recuentos 0 y 1 deben diferenciarse", rendered[0], rendered[1])
            // La categoría `other` sigue formateando su recuento (2 saltos).
            val otherText = formatWithCount(HINDI_TAG, hindiItems.getValue("other"), 2)
            val otherToken = String.format(hiLocale, "%d", 2)
            assertTrue("[$name/other] falta el recuento '$otherToken' en '$otherText'", otherText.contains(otherToken))
        }
    }

    private fun basePath() = "src/main/res/values/strings.xml"

    private fun literalPercentCount(value: String): Int = value.count { it == '%' }

    private companion object {
        const val BASE_LOCALE = "es"
        const val HINDI_TAG = "hi"
        val NEW_CATALOGS = mapOf(
            HINDI_TAG to "src/main/res/values-hi/strings.xml",
            "zh-Hans" to "src/main/res/values-b+zh+Hans/strings.xml",
        )
        /**
         * Claves `hi` cuyo ítem `one` formatea el recuento real: la categoría CLDR `one` del
         * hindi cubre 0 y 1 y los llamadores ya pasan siempre el recuento.
         */
        val HINDI_COUNT_FORMATTED_ONE_KEYS = setOf(
            "camera_series_before_recording",
            "camera_series_recording",
            "camera_bilateral_series_before_recording",
            "camera_bilateral_series_recording",
        )
        const val SAYERS_LIMITATION = "result_estimates_sayers_limitation"
        const val HINDI_AUTONYM = "हिंदी"
        const val CHINESE_AUTONYM = "简体中文"
        const val EXPECTED_STRINGS = 2004
        const val EXPECTED_PLURALS = 12
        const val EXPECTED_PLURAL_ITEMS = 24
        const val MIN_EXPECTED_KEYS = 1900
        const val MIN_CATALOG_BYTES = 100_000L
        const val EXPECTED_LOCALE_DIRS = 15
    }
}
