package com.openjump.app.ui.fvp

import com.openjump.app.testsupport.ResourceCatalogs
import com.openjump.app.testsupport.androidUnescapeForFormat
import com.openjump.app.testsupport.formatArguments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard de paridad i18n de la sección F–V–P.
 *
 * Referencias: `.pi/plans/fvp-ui-contract.md` (tabla i18n) y
 * `docs/research/fvp/09-INTEGRATION_GATE_ADDENDUM.md` (condición 8, etiqueta primaria y avisos
 * en los 8 locales). El test es JVM puro: solo lee los ficheros de recursos del módulo `app` a
 * través del snapshot compartido [ResourceCatalogs] (un parseo por fichero y suite).
 *
 * Comprueba:
 * 1. toda clave `fvp_*` de la base (`values/strings.xml`, es) existe exactamente una vez en cada
 *    locale, y no hay claves `fvp_*` extra fuera de la base;
 * 2. no hay nombres `fvp_*` duplicados en ningún fichero;
 * 3. ningún valor `fvp_*` está vacío;
 * 4. las discrepancias de recuento fuera de `fvp_` se imprimen, pero NO hacen fallar el test
 *    (son preexistentes y ajenas a esta sección);
 * 5. los argumentos de formato (`%1$s`, `%d`, ...) son idénticos a la base y el patrón es
 *    realmente formateable en el locale del catálogo.
 *
 * Si un fichero no se puede localizar o parsear, el test falla con un mensaje explícito: nunca
 * pasa por omisión.
 */
class FvpStringsParityTest {

    @Test
    fun `todos los locales tienen exactamente las mismas claves fvp que la base`() {
        val base = catalogs.catalog(BASE_LOCALE)
        val baseKeys = base.namesWithPrefix(FVP_PREFIX).keys
        assertTrue(
            "La base debe contener la sección F–V–P completa (>= $MIN_EXPECTED_FVP_KEYS claves fvp_*), " +
                "encontradas: ${baseKeys.size}",
            baseKeys.size >= MIN_EXPECTED_FVP_KEYS,
        )
        for (parsed in catalogs.ordered) {
            if (parsed.label == BASE_LOCALE) continue
            val locale = parsed.label
            val localeFvp = parsed.namesWithPrefix(FVP_PREFIX)
            val missing = (baseKeys - localeFvp.keys).sorted()
            val extra = (localeFvp.keys - baseKeys).sorted()
            assertTrue("[$locale] faltan claves fvp_* presentes en la base: $missing", missing.isEmpty())
            assertTrue("[$locale] claves fvp_* que no existen en la base: $extra", extra.isEmpty())
            for (key in baseKeys) {
                assertEquals(
                    "[$locale] '$key' debe aparecer exactamente una vez",
                    1,
                    localeFvp.getValue(key).size,
                )
            }
        }
    }

    @Test
    fun `no hay claves fvp duplicadas en ningun locale`() {
        for (parsed in catalogs.ordered) {
            val duplicated = parsed.namesWithPrefix(FVP_PREFIX)
                .filterValues { it.size > 1 }
                .keys
                .sorted()
            assertTrue("[${parsed.label}] claves fvp_* duplicadas: $duplicated", duplicated.isEmpty())
        }
    }

    @Test
    fun `ningun valor fvp esta vacio`() {
        for (parsed in catalogs.ordered) {
            val blank = parsed.namesWithPrefix(FVP_PREFIX)
                .filterValues { values -> values.any { it.isBlank() } }
                .keys
                .sorted()
            assertTrue("[${parsed.label}] claves fvp_* con valor vacío: $blank", blank.isEmpty())
        }
    }

    @Test
    fun `las discrepancias de recuento fuera de fvp se imprimen sin fallar`() {
        val parsed = catalogs.ordered
        assertEquals("deben parsearse los ${locales.size} ficheros de locale", locales.size, parsed.size)

        val counts = parsed.associate { it.label to (it.stringCount - it.namesWithPrefix(FVP_PREFIX).size) }
        println("[FvpStringsParityTest] claves no fvp por locale: $counts")

        val baseNonFvp = catalogs.catalog(BASE_LOCALE).namesWithoutPrefix(FVP_PREFIX).keys
        for (entry in parsed) {
            if (entry.label == BASE_LOCALE) continue
            val localeNonFvp = entry.namesWithoutPrefix(FVP_PREFIX).keys
            val nonFvpCount = entry.stringCount - entry.namesWithPrefix(FVP_PREFIX).size
            if (nonFvpCount != baseNonFvp.size) {
                println(
                    "[FvpStringsParityTest] AVISO (no bloqueante): ${entry.label} tiene " +
                        "$nonFvpCount claves no fvp frente a ${baseNonFvp.size} en la base",
                )
                println("[FvpStringsParityTest]   solo en la base: ${(baseNonFvp - localeNonFvp).sorted()}")
                println("[FvpStringsParityTest]   solo en ${entry.label}: ${(localeNonFvp - baseNonFvp).sorted()}")
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
        val base = catalogs.catalog(BASE_LOCALE)
        for (parsed in catalogs.ordered) {
            for (key in parsed.namesWithPrefix(FVP_PREFIX).keys) {
                val baseValue = base.values(key).singleOrNull() ?: continue
                val value = parsed.onlyValue(key)
                assertEquals(
                    "[${parsed.label}] '$key' no puede cambiar los argumentos de formato",
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
                    String.format(
                        java.util.Locale.forLanguageTag(localeTag(parsed.label)),
                        androidUnescapeForFormat(value),
                        *args,
                    )
                } catch (failure: java.util.IllegalFormatException) {
                    throw AssertionError(
                        "[${parsed.label}] '$key' no es formateable con sus argumentos " +
                            "${arguments.map { "%${it.first}\$${it.second}" }}: ${failure.message}",
                        failure,
                    )
                }
            }
        }
    }

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

        /**
         * Snapshot único de la suite: cada fichero se parsea una vez y ningún otro test puede
         * enmascarar un cambio de recursos con una caché global.
         */
        val catalogs: ResourceCatalogs by lazy { ResourceCatalogs.load(locales) }
    }
}
