package com.openjump.app.ui.angles

import com.openjump.app.testsupport.ResourceCatalogs
import com.openjump.app.testsupport.positionalPlaceholders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard de paridad i18n de la pantalla del editor de ángulos.
 *
 * El slice añade claves `angle_*` a los 13 ficheros `app/src/main/res/values-XX/strings.xml`
 * (base española + 12 locales). El test es JVM puro: solo lee recursos a través del snapshot
 * compartido [ResourceCatalogs] (un parseo por fichero y suite).
 *
 * Comprueba:
 * 1. los 13 ficheros existen y contienen exactamente las mismas claves `angle_*` que la base;
 * 2. no hay nombres `angle_*` duplicados en ningún fichero;
 * 3. ningún valor `angle_*` está vacío;
 * 4. cada valor conserva el mismo conjunto de placeholders posicionales (%N$s/%N$d) que la base,
 *    aunque el orden pueda cambiar por la gramática del idioma.
 *
 * Si un fichero no se puede localizar o parsear, el test falla con un mensaje explícito: nunca pasa
 * por omisión.
 */
class AngleEditorStringsParityTest {

    @Test
    fun `los trece recursos contienen exactamente las mismas claves angle que la base`() {
        assertEquals("Se esperaban ${locales.size} recursos de strings", 13, catalogs.size)
        val base = catalogs.catalog(BASE_LOCALE)
        val baseKeys = base.namesWithPrefix(PREFIX).keys
        assertTrue(
            "La base debe contener la pantalla completa (>= $MIN_EXPECTED_KEYS claves angle_*), " +
                "encontradas: ${baseKeys.size}",
            baseKeys.size >= MIN_EXPECTED_KEYS,
        )
        for (locale in catalogs.ordered) {
            val localeAngle = locale.namesWithPrefix(PREFIX)
            val missing = (baseKeys - localeAngle.keys).sorted()
            val extra = (localeAngle.keys - baseKeys).sorted()
            assertTrue("[${locale.label}] faltan claves angle_*: $missing", missing.isEmpty())
            assertTrue("[${locale.label}] claves angle_* ajenas a la base: $extra", extra.isEmpty())
            for (key in baseKeys) {
                assertEquals(
                    "[${locale.label}] '$key' debe aparecer exactamente una vez",
                    1,
                    localeAngle.getValue(key).size,
                )
            }
        }
    }

    @Test
    fun `ningun valor angle esta vacio y no hay claves duplicadas`() {
        for (catalog in catalogs.ordered) {
            val angle = catalog.namesWithPrefix(PREFIX)
            val duplicated = angle.filterValues { it.size > 1 }.keys.sorted()
            assertTrue("[${catalog.label}] claves angle_* duplicadas: $duplicated", duplicated.isEmpty())
            val blank = angle.filterValues { values -> values.any { it.isBlank() } }.keys.sorted()
            assertTrue("[${catalog.label}] claves angle_* con valor vacío: $blank", blank.isEmpty())
        }
    }

    @Test
    fun `los placeholders posicionales coinciden con la base en indice y tipo`() {
        val base = catalogs.catalog(BASE_LOCALE)
        for (catalog in catalogs.ordered) {
            if (catalog.label == BASE_LOCALE) continue
            for (key in base.namesWithPrefix(PREFIX).keys) {
                assertEquals(
                    "[${catalog.label}] placeholders de '$key' distintos de la base",
                    positionalPlaceholders(base.onlyValue(key)),
                    positionalPlaceholders(catalog.onlyValue(key)),
                )
            }
        }
    }

    private companion object {
        const val PREFIX = "angle_"
        const val BASE_LOCALE = "values"
        const val MIN_EXPECTED_KEYS = 52

        val locales: Map<String, String> = linkedMapOf(
            "values" to "app/src/main/res/values/strings.xml",
            "values-es" to "app/src/main/res/values-es/strings.xml",
            "values-en" to "app/src/main/res/values-en/strings.xml",
            "values-b+es+419" to "app/src/main/res/values-b+es+419/strings.xml",
            "values-de" to "app/src/main/res/values-de/strings.xml",
            "values-fr" to "app/src/main/res/values-fr/strings.xml",
            "values-it" to "app/src/main/res/values-it/strings.xml",
            "values-ja" to "app/src/main/res/values-ja/strings.xml",
            "values-ko" to "app/src/main/res/values-ko/strings.xml",
            "values-pl" to "app/src/main/res/values-pl/strings.xml",
            "values-pt-rBR" to "app/src/main/res/values-pt-rBR/strings.xml",
            "values-pt-rPT" to "app/src/main/res/values-pt-rPT/strings.xml",
            "values-tr" to "app/src/main/res/values-tr/strings.xml",
        )

        /**
         * Snapshot único de la suite: cada fichero se parsea una vez y ningún otro test puede
         * enmascarar un cambio de recursos con una caché global.
         */
        val catalogs: ResourceCatalogs by lazy { ResourceCatalogs.load(locales) }
    }
}
