package com.openjump.app.ui.history

import com.openjump.app.testsupport.ResourceCatalogs
import com.openjump.app.testsupport.positionalPlaceholders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard de paridad i18n de la copia propia de la fila Ángulos del historial.
 *
 * `angle_history_options` es la única clave nueva del slice S1 (descripción accesible del menú
 * secundario de la fila). Este test es JVM puro: parsea los 13 catálogos con el snapshot compartido
 * [ResourceCatalogs] y no toca código de producción ni recursos.
 *
 * Comprueba:
 * 1. la clave `angle_history_options` existe EXACTAMENTE una vez en los 13 catálogos y es la única
 *    clave con prefijo `angle_history_` (una clave nueva del slice obliga a actualizar el guard);
 * 2. ningún valor está vacío;
 * 3. los placeholders posicionales son los mismos que en la base (hoy: ninguno);
 * 4. el catálogo por defecto es español (`values` y `values-es`), y `es-419` es neutral.
 *
 * Si un catálogo no se puede localizar o parsear, el test falla con contexto: nunca pasa por omisión.
 */
class AngleHistoryStringsParityTest {

    @Test
    fun `los trece catalogos declaran exactamente las mismas claves angle_history que la base`() {
        assertEquals("Se esperaban ${locales.size} recursos de strings", 13, catalogs.size)
        val base = catalogs.catalog(BASE_LOCALE)
        val baseKeys = base.namesWithPrefix(PREFIX).keys
        assertEquals("Claves $PREFIX esperadas en la base", EXPECTED_KEYS, baseKeys)
        for (catalog in catalogs.ordered) {
            val localeKeys = catalog.namesWithPrefix(PREFIX)
            val missing = (baseKeys - localeKeys.keys).sorted()
            val extra = (localeKeys.keys - baseKeys).sorted()
            assertTrue("[${catalog.label}] faltan claves $PREFIX: $missing", missing.isEmpty())
            assertTrue("[${catalog.label}] claves $PREFIX ajenas a la base: $extra", extra.isEmpty())
            for (key in baseKeys) {
                assertEquals(
                    "[${catalog.label}] '$key' debe aparecer exactamente una vez",
                    1,
                    localeKeys.getValue(key).size,
                )
            }
        }
    }

    @Test
    fun `la etiqueta angle_history_options no esta vacia en ningun catalogo`() {
        for (catalog in catalogs.ordered) {
            val value = catalog.onlyValue(KEY)
            assertTrue("[${catalog.label}] '$KEY' vacía", value.isNotBlank())
            // Sin marcas de formato: es un nombre de acción, no una plantilla.
            assertEquals(
                "[${catalog.label}] '$KEY' no debe declarar placeholders",
                emptySet<Int>(),
                positionalPlaceholders(value).keys,
            )
        }
    }

    @Test
    fun `la etiqueta angle_history_options conserva los placeholders de la base`() {
        val base = catalogs.catalog(BASE_LOCALE)
        for (catalog in catalogs.ordered) {
            if (catalog.label == BASE_LOCALE) continue
            assertEquals(
                "[${catalog.label}] placeholders de '$KEY' distintos de la base",
                positionalPlaceholders(base.onlyValue(KEY)),
                positionalPlaceholders(catalog.onlyValue(KEY)),
            )
        }
    }

    @Test
    fun `el catalogo por defecto es espanol y es419 es neutral`() {
        val expected = "Opciones"
        assertEquals("El catálogo por defecto debe declarar la copia española", expected, catalogs.catalog("values").onlyValue(KEY))
        assertEquals("values-es debe coincidir con el catálogo por defecto", expected, catalogs.catalog("values-es").onlyValue(KEY))
        assertEquals("es-419 debe usar la copia neutral española", expected, catalogs.catalog("values-b+es+419").onlyValue(KEY))
    }

    private companion object {
        const val PREFIX = "angle_history_"
        const val KEY = "angle_history_options"
        const val BASE_LOCALE = "values"

        /** Sólo la descripción del menú: el recuento reutiliza `history_angle_count`. */
        val EXPECTED_KEYS = setOf(KEY)

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
