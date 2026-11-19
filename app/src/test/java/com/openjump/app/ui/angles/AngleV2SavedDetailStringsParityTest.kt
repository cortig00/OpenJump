package com.openjump.app.ui.angles

import com.openjump.app.testsupport.ResourceCatalogs
import com.openjump.app.testsupport.positionalPlaceholders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard de i18n de los textos nuevos del detalle V2 image-first (`angle_detail_*`).
 *
 * Comprueba, en los 13 catálogos y en JVM puro:
 * - que las claves nuevas existen exactamente una vez, con valor no vacío, y con el MISMO conjunto
 *   de placeholders posicionales que el catálogo base (una traducción no puede perder ni reordenar
 *   un argumento del formato);
 * - que las claves que otras partes de la pantalla siguen usando (marcas, compartir, imagen, visor,
 *   avisos) siguen declaradas: este slice sólo AÑADE, no sustituye ni normaliza recursos previos;
 * - que el catálogo base y `values-es` siguen siendo el mismo texto (español por defecto, sin deriva
 *   entre los dos ficheros que la app trata como fuente).
 *
 * No es una revisión lingüística nativa: verifica presencia, unicidad y forma del formato.
 */
class AngleV2SavedDetailStringsParityTest {

    @Test
    fun `los trece catalogos declaran las mismas claves nuevas angle_detail`() {
        assertEquals("Se esperaban ${locales.size} catálogos", 13, catalogs.size)
        val base = catalogs.catalog(BASE_LOCALE)
        for (catalog in catalogs.ordered) {
            val keys = catalog.namesWithPrefix(PREFIX).keys
            assertEquals(
                "[${catalog.label}] claves angle_detail_* distintas de la base: ${(keys - NEW_KEYS).sorted()}",
                NEW_KEYS,
                keys,
            )
            for (name in NEW_KEYS) {
                assertEquals("[${catalog.label}] '$name' debe aparecer exactamente una vez", 1, catalog.values(name).size)
                assertTrue("[${catalog.label}] '$name' no puede estar vacío", catalog.onlyValue(name).isNotBlank())
            }
        }
        assertEquals("El catálogo base define el número esperado de claves nuevas", NEW_KEYS, base.namesWithPrefix(PREFIX).keys)
    }

    @Test
    fun `los placeholders de las claves nuevas coinciden con la base`() {
        val base = catalogs.catalog(BASE_LOCALE)
        for (catalog in catalogs.ordered) {
            if (catalog.label == BASE_LOCALE) continue
            for (key in NEW_KEYS) {
                assertEquals(
                    "[${catalog.label}] placeholders de '$key' distintos de la base",
                    positionalPlaceholders(base.onlyValue(key)),
                    positionalPlaceholders(catalog.onlyValue(key)),
                )
            }
        }
    }

    @Test
    fun `los formatos con datos llevan los argumentos que la pantalla pasa`() {
        val base = catalogs.catalog(BASE_LOCALE)
        assertEquals("El nombre del dueño lleva un solo texto", mapOf(1 to 's'), positionalPlaceholders(base.onlyValue("angle_detail_owner_name")))
        assertEquals("Ampliar lleva el número de fotograma", mapOf(1 to 'd'), positionalPlaceholders(base.onlyValue("angle_detail_expand_frame")))
        assertEquals("El aviso sin verificar lleva el número de fotograma", mapOf(1 to 'd'), positionalPlaceholders(base.onlyValue("angle_detail_extraction_unverified")))
        assertEquals("El aviso de discrepancia lleva los dos fotogramas", mapOf(1 to 'd', 2 to 'd'), positionalPlaceholders(base.onlyValue("angle_detail_extraction_mismatch")))
        assertEquals(
            "Los metadatos de imagen llevan ancho, alto, bytes y tipo",
            mapOf(1 to 'd', 2 to 'd', 3 to 'd', 4 to 's'),
            positionalPlaceholders(base.onlyValue("angle_detail_meta_image")),
        )
        assertEquals(
            "La presentación lleva ancho, alto y giro",
            mapOf(1 to 'd', 2 to 'd', 3 to 'd'),
            positionalPlaceholders(base.onlyValue("angle_detail_meta_presentation")),
        )
        assertEquals(
            "La extracción lleva el índice asignado y el reportado como texto",
            mapOf(1 to 'd', 2 to 's'),
            positionalPlaceholders(base.onlyValue("angle_detail_meta_extraction")),
        )
        assertEquals(
            "La identidad lleva clave y SHA-256 como textos",
            mapOf(1 to 's', 2 to 's'),
            positionalPlaceholders(base.onlyValue("angle_detail_meta_storage")),
        )
        // Etiquetas sin datos: si alguna ganara un placeholder, el formato fallaría en ejecución.
        for (plain in PLAIN_KEYS) {
            assertEquals("[base] '$plain' no debe llevar placeholders", emptyMap<Int, Char>(), positionalPlaceholders(base.onlyValue(plain)))
        }
    }

    @Test
    fun `las claves que la pantalla sigue usando no desaparecen de ningun catalogo`() {
        for (catalog in catalogs.ordered) {
            // Se consultan TODAS las claves declaradas, no sólo las `angle_*`: el conjunto también
            // protege claves compartidas sin ese prefijo (`athlete_unassigned`, `common_back`).
            val missing = PRESERVED_KEYS.filter { catalog.values(it).isEmpty() }
            assertTrue("[${catalog.label}] desaparecieron claves previas: ${missing.sorted()}", missing.isEmpty())
        }
    }

    @Test
    fun `el catalogo base y values-es siguen siendo el mismo texto`() {
        val base = catalogs.catalog(BASE_LOCALE)
        val spanish = catalogs.catalog("values-es")
        assertEquals(
            "El español por defecto debe ser exactamente values-es",
            base.file.readBytes().toList(),
            spanish.file.readBytes().toList(),
        )
    }

    private companion object {
        const val PREFIX = "angle_detail_"
        const val BASE_LOCALE = "values"

        val NEW_KEYS: Set<String> = setOf(
            "angle_detail_owner_name",
            "angle_detail_owner_unavailable",
            "angle_detail_expand_frame",
            "angle_detail_extraction_unverified",
            "angle_detail_extraction_mismatch",
            "angle_detail_technical_show",
            "angle_detail_technical_hide",
            "angle_detail_meta_image",
            "angle_detail_meta_presentation",
            "angle_detail_meta_extraction",
            "angle_detail_meta_reported_unknown",
            "angle_detail_meta_storage",
            "angle_detail_pan_left",
            "angle_detail_pan_right",
            "angle_detail_pan_up",
            "angle_detail_pan_down",
        )

        val PLAIN_KEYS: Set<String> = setOf(
            "angle_detail_owner_unavailable",
            "angle_detail_technical_show",
            "angle_detail_technical_hide",
            "angle_detail_meta_reported_unknown",
            "angle_detail_pan_left",
            "angle_detail_pan_right",
            "angle_detail_pan_up",
            "angle_detail_pan_down",
        )

        /** Claves de slices previos (S1 y S2A) que este slice no puede haber retirado. */
        val PRESERVED_KEYS: Set<String> = setOf(
            "angle_history_options",
            "angle_saved_title",
            "angle_saved_frame",
            "angle_saved_marks_title",
            "angle_saved_points",
            "angle_saved_value_invalid",
            "angle_saved_marks_empty",
            "angle_v2_saved_frames",
            "angle_v2_saved_image_unavailable",
            "angle_v2_saved_read_only_note",
            "angle_v2_saved_share_image",
            "angle_v2_saved_share_frame_image",
            "angle_v2_saved_share_all",
            "angle_v2_saved_share_all_description",
            "angle_v2_saved_share_all_busy",
            "angle_v2_saved_share_all_too_large",
            "angle_multi_frame_frame_label",
            "angle_multi_frame_image_pending",
            "angle_multi_frame_frame_not_captured",
            "angle_multi_frame_close",
            "angle_hidden",
            "angle_degrees_value",
            "angle_zoom_in",
            "angle_zoom_out",
            "angle_zoom_reset",
            "angle_viewport_controls_description",
            "angle_viewport_state_expanded",
            "angle_viewport_state_normal",
            "athlete_unassigned",
            "common_back",
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
