package com.openjump.app.ui.fvp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guard de paridad i18n de la sección F–V–P.
 *
 * Referencias: `.pi/plans/fvp-ui-contract.md` (tabla i18n) y
 * `docs/research/fvp/09-INTEGRATION_GATE_ADDENDUM.md` (condición 8, etiqueta primaria y avisos
 * en los 8 locales). El test es JVM puro: solo lee los ficheros de recursos del módulo `app`.
 *
 * Comprueba:
 * 1. toda clave `fvp_*` de la base (`values/strings.xml`, es) existe exactamente una vez en cada
 *    locale, y no hay claves `fvp_*` extra fuera de la base;
 * 2. no hay nombres `fvp_*` duplicados en ningún fichero;
 * 3. ningún valor `fvp_*` está vacío;
 * 4. las discrepancias de recuento fuera de `fvp_` se imprimen, pero NO hacen fallar el test
 *    (son preexistentes y ajenas a esta sección).
 *
 * Si un fichero no se puede localizar o parsear, el test falla con un mensaje explícito: nunca
 * pasa por omisión.
 */
class FvpStringsParityTest {

    private data class LocaleStrings(
        val locale: String,
        val path: String,
        val byName: Map<String, List<String>>,
    ) {
        val fvp: Map<String, List<String>> get() = byName.filterKeys { it.startsWith(FVP_PREFIX) }
        val nonFvpCount: Int get() = byName.size - fvp.size
    }

    /** Resuelve el fichero relativo al directorio de trabajo; falla con contexto si no existe. */
    private fun resolve(path: String): File {
        val candidates = listOf(File(path), File("app/$path"))
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "No se encuentra '$path' (locales F–V–P). Directorio de trabajo: " +
                    "${File(".").absolutePath}. Rutas probadas: " +
                    candidates.joinToString { it.absolutePath },
            )
    }

    private fun parse(locale: String, path: String): LocaleStrings {
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
            throw AssertionError(
                "No se pudo parsear $path (${file.absolutePath}): ${failure.message}",
                failure,
            )
        }
        val byName = LinkedHashMap<String, MutableList<String>>()
        val nodes = document.getElementsByTagName("string")
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            val name = element.getAttribute("name")
            byName.getOrPut(name) { mutableListOf() }.add(element.textContent ?: "")
        }
        return LocaleStrings(locale, path, byName)
    }

    @Test
    fun `todos los locales tienen exactamente las mismas claves fvp que la base`() {
        val base = parse(BASE_LOCALE, locales.getValue(BASE_LOCALE))
        val baseKeys = base.fvp.keys
        assertTrue(
            "La base debe contener la sección F–V–P completa (>= ${MIN_EXPECTED_FVP_KEYS} claves fvp_*), " +
                "encontradas: ${baseKeys.size}",
            baseKeys.size >= MIN_EXPECTED_FVP_KEYS,
        )
        for ((locale, path) in locales) {
            if (locale == BASE_LOCALE) continue
            val parsed = parse(locale, path)
            val missing = (baseKeys - parsed.fvp.keys).sorted()
            val extra = (parsed.fvp.keys - baseKeys).sorted()
            assertTrue("[$locale] faltan claves fvp_* presentes en la base: $missing", missing.isEmpty())
            assertTrue("[$locale] claves fvp_* que no existen en la base: $extra", extra.isEmpty())
            for (key in baseKeys) {
                val values = parsed.fvp.getValue(key)
                assertEquals("[$locale] '$key' debe aparecer exactamente una vez", 1, values.size)
            }
        }
    }

    @Test
    fun `no hay claves fvp duplicadas en ningun locale`() {
        for ((locale, path) in locales) {
            val parsed = parse(locale, path)
            val duplicated = parsed.fvp.filterValues { it.size > 1 }.keys.sorted()
            assertTrue("[$locale] claves fvp_* duplicadas: $duplicated", duplicated.isEmpty())
        }
    }

    @Test
    fun `ningun valor fvp esta vacio`() {
        for ((locale, path) in locales) {
            val parsed = parse(locale, path)
            val blank = parsed.fvp.filterValues { values -> values.any { it.isBlank() } }.keys.sorted()
            assertTrue("[$locale] claves fvp_* con valor vacío: $blank", blank.isEmpty())
        }
    }

    @Test
    fun `las discrepancias de recuento fuera de fvp se imprimen sin fallar`() {
        val parsed = locales.entries.map { (locale, path) -> parse(locale, path) }
        assertEquals("deben parsearse los ${locales.size} ficheros de locale", locales.size, parsed.size)

        val counts = parsed.associate { it.locale to it.nonFvpCount }
        println("[FvpStringsParityTest] claves no fvp por locale: $counts")

        val baseNonFvp = parsed.first { it.locale == BASE_LOCALE }
            .byName.keys.filterNot { it.startsWith(FVP_PREFIX) }.toSet()
        for (entry in parsed) {
            if (entry.locale == BASE_LOCALE) continue
            val localeNonFvp = entry.byName.keys.filterNot { it.startsWith(FVP_PREFIX) }.toSet()
            if (entry.nonFvpCount != baseNonFvp.size) {
                println(
                    "[FvpStringsParityTest] AVISO (no bloqueante): ${entry.locale} tiene " +
                        "${entry.nonFvpCount} claves no fvp frente a ${baseNonFvp.size} en la base",
                )
                println("[FvpStringsParityTest]   solo en la base: ${(baseNonFvp - localeNonFvp).sorted()}")
                println("[FvpStringsParityTest]   solo en ${entry.locale}: ${(localeNonFvp - baseNonFvp).sorted()}")
            }
        }
    }

    /**
     * Guard de ARGUMENTOS de formato: la paridad de claves no impide que una traduccion pierda,
     * duplique o renumere un `%1$s`, que es un fallo de runtime (`MissingFormatArgumentException`)
     * o un mensaje mal formado. Se exige que cada valor `fvp_*` use EXACTAMENTE los mismos
     * argumentos (indice y conversion) que la base, y se comprueba con `String.format` y argumentos
     * dummy del tipo correcto que el patron es formateable en el locale real.
     */
    @Test
    fun `los argumentos de formato fvp son consistentes entre locales y formateables`() {
        val base = parse(BASE_LOCALE, locales.getValue(BASE_LOCALE))
        for ((locale, path) in locales) {
            val parsed = parse(locale, path)
            for ((key, values) in parsed.fvp) {
                val baseValue = base.fvp[key]?.singleOrNull() ?: continue
                val value = values.single()
                assertEquals(
                    "[$locale] '$key' no puede cambiar los argumentos de formato",
                    formatArguments(baseValue),
                    formatArguments(value),
                )
                // Formateable de verdad SOLO cuando la cadena se usa con argumentos
                // (`stringResource(id, args)`): sin argumentos, un `%` literal es legitimo.
                val arguments = formatArguments(value)
                if (arguments.isEmpty()) continue
                val maxIndex = (arguments.maxOfOrNull { it.first } ?: 0)
                val args = Array<Any?>(maxIndex) { "x" }
                arguments.forEach { (index, conversion) ->
                    args[index - 1] = when (conversion) {
                        'd', 'o', 'x', 'X' -> 1L
                        'f', 'e', 'E', 'g', 'G' -> 1.0
                        else -> "x"
                    }
                }
                try {
                    String.format(java.util.Locale.forLanguageTag(localeTag(locale)), androidUnescape(value), *args)
                } catch (failure: java.util.IllegalFormatException) {
                    throw AssertionError(
                        "[$locale] '$key' no es formateable con sus argumentos " +
                            "${arguments.map { "%${it.first}\$${it.second}" }}: ${failure.message}",
                        failure,
                    )
                }
            }
        }
    }

    /** Indices y conversiones de los placeholders, en orden de aparicion (ignora `%%`). */
    private fun formatArguments(value: String): List<Pair<Int, Char>> {
        val pattern = Regex("%(\\d+\\$)?([a-zA-Z%])")
        val result = mutableListOf<Pair<Int, Char>>()
        var sequential = 1
        for (match in pattern.findAll(value)) {
            val conversion = match.groupValues[2].single()
            if (conversion == '%') continue
            val explicit = match.groupValues[1]
            val index = explicit.removeSuffix("$").toIntOrNull() ?: sequential
            result += index to conversion
            if (explicit.isEmpty()) sequential += 1
        }
        return result.sortedBy { it.first }
    }

    /** Deshace los escapes de Android que no entiende `String.format` de la JVM. */
    private fun androidUnescape(value: String): String = value.replace("\\'", "'")

    private fun localeTag(locale: String): String = when (locale) {
        "pt-rBR" -> "pt-BR"
        "pt-rPT" -> "pt-PT"
        else -> locale
    }

    private companion object {
        const val FVP_PREFIX = "fvp_"
        const val BASE_LOCALE = "es"
        const val MIN_EXPECTED_FVP_KEYS = 80

        val locales: Map<String, String> = linkedMapOf(
            "es" to "src/main/res/values/strings.xml",
            "en" to "src/main/res/values-en/strings.xml",
            "fr" to "src/main/res/values-fr/strings.xml",
            "de" to "src/main/res/values-de/strings.xml",
            "it" to "src/main/res/values-it/strings.xml",
            "pt-rBR" to "src/main/res/values-pt-rBR/strings.xml",
            "pt-rPT" to "src/main/res/values-pt-rPT/strings.xml",
            "tr" to "src/main/res/values-tr/strings.xml",
            "es-419" to "src/main/res/values-b+es+419/strings.xml",
            "pl" to "src/main/res/values-pl/strings.xml",
            "ja" to "src/main/res/values-ja/strings.xml",
            "ko" to "src/main/res/values-ko/strings.xml",
        )
    }
}
