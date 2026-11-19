package com.openjump.app.ui.angles

import com.openjump.app.testsupport.ResourceCatalogs
import com.openjump.app.testsupport.positionalPlaceholders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard JVM del bloque de textos `angle_polish_*` del detalle guardado (controles del visor,
 * pestañas, contexto, conteos y el vacío de un análisis guardado sin fotogramas).
 *
 * Comprueba, en los 13 catálogos y sin Android:
 * - que TODAS las claves `angle_polish_*` existen exactamente una vez, con valor no vacío, y que el
 *   conjunto es IDÉNTICO en los 13 catálogos (ni falta una traducción ni sobra una clave suelta);
 * - que los placeholders posicionales son los mismos que en la base y los que la pantalla pasa;
 * - que los plurales existen con `other` y con cantidades válidas en todos los catálogos;
 * - que el catálogo base (español por defecto) y `values-es` dicen EXACTAMENTE lo mismo para estas
 *   claves;
 * - que las claves antiguas que la pantalla, el historial y el editor siguen usando NO se han
 *   retirado ni reescrito en este slice (el guard sólo AÑADE).
 *
 * No es una revisión lingüística nativa: verifica presencia, unicidad, forma del formato y que el
 * prefijo nuevo no se mezcle con los bloques anteriores.
 */
class AnglePolishStringsParityTest {

    @Test
    fun `los trece catalogos declaran exactamente las mismas claves angle_polish`() {
        assertEquals("Se esperaban ${locales.size} catálogos", 13, catalogs.size)
        val base = catalogs.catalog(BASE_LOCALE)
        assertEquals(
            "El catálogo base define el conjunto esperado de claves angle_polish_*",
            EXPECTED_STRINGS,
            base.namesWithPrefix(PREFIX).keys,
        )
        for (catalog in catalogs.ordered) {
            val keys = catalog.namesWithPrefix(PREFIX).keys
            assertEquals(
                "[${catalog.label}] claves angle_polish_* distintas de la base: " +
                    "faltan=${(EXPECTED_STRINGS - keys).sorted()} sobran=${(keys - EXPECTED_STRINGS).sorted()}",
                EXPECTED_STRINGS,
                keys,
            )
            for (name in EXPECTED_STRINGS) {
                assertEquals(
                    "[${catalog.label}] '$name' debe aparecer exactamente una vez",
                    1,
                    catalog.values(name).size,
                )
                assertTrue(
                    "[${catalog.label}] '$name' no puede estar vacío",
                    catalog.onlyValue(name).isNotBlank(),
                )
            }
            for (plural in EXPECTED_PLURALS) {
                val items = catalog.pluralItems(plural)
                assertTrue(
                    "[${catalog.label}] falta el plural '$plural' (o está vacío)",
                    items.isNotEmpty(),
                )
                assertTrue(
                    "[${catalog.label}] '$plural' debe declarar 'other': ${items.keys}",
                    "other" in items.keys,
                )
                assertTrue(
                    "[${catalog.label}] '$plural' declara cantidades no válidas: ${items.keys - VALID_QUANTITIES}",
                    items.keys.all { it in VALID_QUANTITIES },
                )
                assertTrue(
                    "[${catalog.label}] '$plural' tiene un 'other' vacío",
                    items.getValue("other").isNotBlank(),
                )
            }
        }
    }

    @Test
    fun `los placeholders de angle_polish coinciden con la base y con lo que la pantalla pasa`() {
        val base = catalogs.catalog(BASE_LOCALE)
        // Formatos con datos: los argumentos REALES de la UI, no sólo la igualdad con la base.
        assertEquals(
            "La posición del fotograma lleva posición y total",
            mapOf(1 to 'd', 2 to 'd'),
            positionalPlaceholders(base.onlyValue("angle_polish_position_counter")),
        )
        assertEquals(
            "Abrir en Análisis lleva el número de fotograma",
            mapOf(1 to 'd'),
            positionalPlaceholders(base.onlyValue("angle_polish_open_in_analysis")),
        )
        assertEquals(
            "El zoom aplicado lleva el valor formateado como texto",
            mapOf(1 to 's'),
            positionalPlaceholders(base.onlyValue("angle_polish_control_zoom_value")),
        )
        val withData = setOf(
            "angle_polish_position_counter",
            "angle_polish_open_in_analysis",
            "angle_polish_control_zoom_value",
        )
        withData.forEach { key ->
            assertEquals(
                "[base] '$key' debe llevar el argumento esperado",
                expectedPlaceholders.getValue(key),
                positionalPlaceholders(base.onlyValue(key)),
            )
        }
        // Etiquetas sin datos: si alguna ganara un placeholder, el formato fallaría en ejecución.
        for (plain in EXPECTED_STRINGS - withData) {
            assertEquals(
                "[base] '$plain' no debe llevar placeholders",
                emptyMap<Int, Char>(),
                positionalPlaceholders(base.onlyValue(plain)),
            )
        }
        // Los dos plurales de conteo SÍ llevan argumento: la pantalla llama a
        // `pluralStringResource(id, cantidad, cantidad)`, así que cada cantidad declarada formatea
        // el número con `%1$d`. Se comprueba TODA cantidad declarada en la base, no sólo `other`.
        for (plural in EXPECTED_PLURALS) {
            for ((quantity, value) in base.pluralItems(plural)) {
                assertEquals(
                    "[base] el plural '$plural' ($quantity) debe formatear el número de la cantidad",
                    expectedPluralPlaceholders,
                    positionalPlaceholders(value),
                )
            }
        }
        // El resto de catálogos no puede perder ni reordenar argumentos respecto a la base. Las
        // CANTIDADES de plural sí pueden variar por idioma (ja/ko declaran sólo `other`, pl añade
        // `few`/`many`): cada catálogo tiene que formatear `%1$d` en TODAS las que declare.
        for (catalog in catalogs.ordered) {
            if (catalog.label == BASE_LOCALE) continue
            for (key in withData) {
                assertEquals(
                    "[${catalog.label}] placeholders de '$key' distintos de la base",
                    positionalPlaceholders(base.onlyValue(key)),
                    positionalPlaceholders(catalog.onlyValue(key)),
                )
            }
            for (plural in EXPECTED_PLURALS) {
                for ((quantity, value) in catalog.pluralItems(plural)) {
                    assertEquals(
                        "[${catalog.label}] el plural '$plural' ($quantity) debe formatear el número de la cantidad",
                        expectedPluralPlaceholders,
                        positionalPlaceholders(value),
                    )
                }
            }
        }
    }

    @Test
    fun `el catalogo base y values-es dicen lo mismo en las claves angle_polish`() {
        val base = catalogs.catalog(BASE_LOCALE)
        val spanish = catalogs.catalog("values-es")
        for (name in EXPECTED_STRINGS) {
            assertEquals(
                "El español por defecto no puede derivar entre los dos ficheros que la app trata como fuente ('$name')",
                base.onlyValue(name),
                spanish.onlyValue(name),
            )
        }
        for (plural in EXPECTED_PLURALS) {
            assertEquals(
                "El plural '$plural' del español por defecto debe ser idéntico en values y values-es",
                base.pluralItems(plural),
                spanish.pluralItems(plural),
            )
        }
    }

    @Test
    fun `las claves antiguas que siguen en uso no desaparecen de ningun catalogo`() {
        for (catalog in catalogs.ordered) {
            val missing = PRESERVED_STRINGS.filter { catalog.values(it).isEmpty() }
            assertTrue(
                "[${catalog.label}] desaparecieron claves previas: ${missing.sorted()}",
                missing.isEmpty(),
            )
            // El vacío del EDITOR sigue existiendo: el detalle guardado usa una copia propia.
            assertTrue(
                "[${catalog.label}] el editor se quedó sin su texto de vacío",
                catalog.onlyValue("angle_multi_frame_frames_empty").isNotBlank(),
            )
            val missingPlurals = PRESERVED_PLURALS.filter { catalog.pluralItems(it).isEmpty() }
            assertTrue(
                "[${catalog.label}] desaparecieron plurales previos: ${missingPlurals.sorted()}",
                missingPlurals.isEmpty(),
            )
            // El prefijo nuevo no invade el bloque antiguo de otra pantalla.
            assertFalse(
                "[${catalog.label}] 'angle_multi_frame_frames_empty' no puede quedar duplicado bajo el prefijo nuevo",
                catalog.values("angle_polish_empty_frames_editor").isNotEmpty(),
            )
        }
    }

    private companion object {
        const val BASE_LOCALE = "values"
        const val PREFIX = "angle_polish_"

        /** Cantidades de plural que Android reconoce; cualquier otra es un error de recurso. */
        val VALID_QUANTITIES = setOf("zero", "one", "two", "few", "many", "other")

        /** Textos del bloque nuevo (P2 y P3) más el vacío propio del detalle guardado (P4). */
        val EXPECTED_STRINGS: Set<String> = setOf(
            "angle_polish_tab_analysis",
            "angle_polish_tab_frames",
            "angle_polish_tab_info",
            "angle_polish_position_counter",
            "angle_polish_open_in_analysis",
            "angle_polish_selected_frame",
            "angle_polish_info_context",
            "angle_polish_info_image",
            "angle_polish_info_technical",
            "angle_polish_control_close",
            "angle_polish_control_zoom_in",
            "angle_polish_control_zoom_out",
            "angle_polish_control_fit",
            "angle_polish_control_move",
            "angle_polish_control_move_on",
            "angle_polish_control_move_off",
            "angle_polish_control_pan_left",
            "angle_polish_control_pan_up",
            "angle_polish_control_pan_right",
            "angle_polish_control_pan_down",
            "angle_polish_control_zoom_value",
            "angle_polish_control_viewer_bar",
            "angle_polish_empty_frames",
        )

        val EXPECTED_PLURALS: Set<String> = setOf(
            "angle_polish_frames_count",
            "angle_polish_marks_count",
        )

        /**
         * Argumentos de los dos plurales de conteo. La UI pasa la cantidad como selector y el mismo
         * número como argumento de formato (`pluralStringResource(id, n, n)`), así que cada
         * `<item quantity=...>` declarado formatea `%1$d`.
         */
        val expectedPluralPlaceholders: Map<Int, Char> = mapOf(1 to 'd')

        /** Argumentos que la UI pasa a cada formato con datos. */
        val expectedPlaceholders: Map<String, Map<Int, Char>> = mapOf(
            "angle_polish_position_counter" to mapOf(1 to 'd', 2 to 'd'),
            "angle_polish_open_in_analysis" to mapOf(1 to 'd'),
            "angle_polish_control_zoom_value" to mapOf(1 to 's'),
        )

        /** Claves de slices anteriores que este slice no puede haber retirado. */
        val PRESERVED_STRINGS: Set<String> = setOf(
            "angle_list_title",
            "angle_saved_title",
            "angle_saved_frame",
            "angle_saved_points",
            "angle_saved_value_invalid",
            "angle_multi_frame_frames_empty",
            "angle_multi_frame_frame_label",
            "angle_v2_saved_read_only_note",
            "angle_v2_saved_share_all",
            "angle_v2_saved_share_all_description",
            "angle_v2_saved_share_all_busy",
            "angle_detail_owner_name",
            "angle_history_options",
            "history_type_angle",
            "angle_hidden",
            "athlete_unassigned",
            "common_back",
            "encoder_result_disclosure_expand",
            "encoder_result_disclosure_collapse",
        )

        val PRESERVED_PLURALS: Set<String> = setOf(
            "history_angle_count",
        )

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

        val catalogs: ResourceCatalogs by lazy { ResourceCatalogs.load(locales) }
    }
}
