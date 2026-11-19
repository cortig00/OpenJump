package com.openjump.app.ui.history

import com.openjump.app.testsupport.ResourceCatalogs
import com.openjump.app.testsupport.positionalPlaceholders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard de paridad i18n de los avisos del borrado V2 del historial.
 *
 * [com.openjump.app.ui.angles.AngleEditorStringsParityTest] cubre SÓLO las claves `angle_*`; las
 * claves `history_v2_delete_*` de este slice no estaban cubiertas por ningún guard. Este test es JVM
 * puro: lee los recursos a través del snapshot compartido [ResourceCatalogs] (un parseo por fichero
 * y suite) y no toca código de producción ni recursos.
 *
 * Comprueba, sobre los 13 catálogos (base española + 12 locales):
 * 1. existen las tres claves `history_v2_delete_*` y son exactamente las mismas que la base;
 * 2. no hay claves duplicadas ni valores vacíos;
 * 3. cada valor conserva los mismos placeholders posicionales (`%N$s`/`%N$d`) que la base.
 *
 * Si un fichero no se puede localizar o parsear, el test falla con contexto: nunca pasa por omisión.
 */
class HistoryV2DeletionStringsParityTest {

    @Test
    fun `los trece recursos contienen exactamente las mismas claves history_v2_delete que la base`() {
        assertEquals("Se esperaban ${locales.size} recursos de strings", 13, catalogs.size)
        val base = catalogs.catalog(BASE_LOCALE)
        val baseKeys = base.namesWithPrefix(PREFIX).keys
        assertEquals("Claves $PREFIX esperadas en la base", MIN_EXPECTED_KEYS, baseKeys.size)
        for (locale in catalogs.ordered) {
            val localeKeys = locale.namesWithPrefix(PREFIX)
            val missing = (baseKeys - localeKeys.keys).sorted()
            val extra = (localeKeys.keys - baseKeys).sorted()
            assertTrue("[${locale.label}] faltan claves $PREFIX: $missing", missing.isEmpty())
            assertTrue("[${locale.label}] claves $PREFIX ajenas a la base: $extra", extra.isEmpty())
            for (key in baseKeys) {
                assertEquals(
                    "[${locale.label}] '$key' debe aparecer exactamente una vez",
                    1,
                    localeKeys.getValue(key).size,
                )
            }
        }
    }

    @Test
    fun `ningun aviso history_v2_delete esta vacio y no hay claves duplicadas`() {
        for (catalog in catalogs.ordered) {
            val values = catalog.namesWithPrefix(PREFIX)
            val duplicated = values.filterValues { it.size > 1 }.keys.sorted()
            assertTrue("[${catalog.label}] claves $PREFIX duplicadas: $duplicated", duplicated.isEmpty())
            val blank = values.filterValues { entries -> entries.any { it.isBlank() } }.keys.sorted()
            assertTrue("[${catalog.label}] claves $PREFIX con valor vacío: $blank", blank.isEmpty())
        }
    }

    @Test
    fun `los placeholders de history_v2_delete coinciden con la base en indice y tipo`() {
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
        const val PREFIX = "history_v2_delete_"
        const val BASE_LOCALE = "values"

        /** Cuerpo con cuenta de fotogramas + reconfirmación + cleanup: las tres del slice S4. */
        const val MIN_EXPECTED_KEYS = 3

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

        /** Snapshot propio de esta suite: cada fichero se parsea una vez, sin caché global. */
        val catalogs: ResourceCatalogs by lazy { ResourceCatalogs.load(locales) }
    }
}
