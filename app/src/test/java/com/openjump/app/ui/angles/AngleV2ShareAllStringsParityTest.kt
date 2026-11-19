package com.openjump.app.ui.angles

import com.openjump.app.testsupport.ResourceCatalogs
import com.openjump.app.testsupport.positionalPlaceholders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard de i18n de «compartir todas las imágenes».
 *
 * El slice añade claves `angle_v2_saved_share_all_*` a los 13 catálogos. Además de la paridad de las
 * claves nuevas (presencia, unicidad, valor no vacío y placeholders idénticos), comprueba que el
 * conjunto completo de claves `angle_*` sigue siendo idéntico en los 13 ficheros —así ninguna clave
 * previa de S1–S4 puede desaparecer de una traducción— y que las claves anteriores de compartir y de
 * cierre siguen declaradas en la base.
 *
 * Es JVM puro: lee los recursos a través del snapshot compartido [ResourceCatalogs]. No es una
 * revisión lingüística nativa.
 */
class AngleV2ShareAllStringsParityTest {

    @Test
    fun `las trece traducciones declaran las mismas claves angle que la base`() {
        assertEquals("Se esperaban ${locales.size} catálogos", 13, catalogs.size)
        val base = catalogs.catalog(BASE_LOCALE)
        val baseKeys = base.namesWithPrefix(PREFIX).keys
        for (locale in catalogs.ordered) {
            val localeKeys = locale.namesWithPrefix(PREFIX).keys
            assertEquals(
                "[${locale.label}] faltan claves angle_*: ${(baseKeys - localeKeys).sorted()}",
                emptySet<String>(),
                baseKeys - localeKeys,
            )
            assertEquals(
                "[${locale.label}] claves angle_* ajenas a la base: ${(localeKeys - baseKeys).sorted()}",
                emptySet<String>(),
                localeKeys - baseKeys,
            )
        }
    }

    @Test
    fun `las claves nuevas de compartir todas existen una sola vez y sin valor vacio`() {
        for (catalog in catalogs.ordered) {
            val shareAll = catalog.namesWithPrefix(SHARE_ALL_PREFIX)
            assertEquals(
                "[${catalog.label}] claves de compartir todas declaradas: ${shareAll.keys.sorted()}",
                SHARE_ALL_KEYS,
                shareAll.keys,
            )
            for ((name, values) in shareAll) {
                assertEquals("[${catalog.label}] '$name' debe aparecer exactamente una vez", 1, values.size)
                assertTrue("[${catalog.label}] '$name' no puede estar vacío", values.single().isNotBlank())
            }
        }
    }

    @Test
    fun `los placeholders de las claves nuevas coinciden con la base en indice y tipo`() {
        val base = catalogs.catalog(BASE_LOCALE)
        for (catalog in catalogs.ordered) {
            if (catalog.label == BASE_LOCALE) continue
            for (key in SHARE_ALL_KEYS) {
                assertEquals(
                    "[${catalog.label}] placeholders de '$key' distintos de la base",
                    positionalPlaceholders(base.onlyValue(key)),
                    positionalPlaceholders(catalog.onlyValue(key)),
                )
            }
        }
        assertEquals(
            "La descripción de la acción lleva el número de imágenes como único placeholder.",
            mapOf(1 to 'd'),
            positionalPlaceholders(base.onlyValue("angle_v2_saved_share_all_description")),
        )
    }

    @Test
    fun `las claves previas de compartir y de S1 a S4 siguen presentes en los trece catalogos`() {
        for (catalog in catalogs.ordered) {
            val keys = catalog.namesWithPrefix(PREFIX).keys
            val missing = PRESERVED_KEYS - keys
            assertTrue("[${catalog.label}] desaparecieron claves previas: ${missing.sorted()}", missing.isEmpty())
        }
    }

    private companion object {
        const val PREFIX = "angle_"
        const val SHARE_ALL_PREFIX = "angle_v2_saved_share_all"
        const val BASE_LOCALE = "values"

        val SHARE_ALL_KEYS: Set<String> = setOf(
            "angle_v2_saved_share_all",
            "angle_v2_saved_share_all_description",
            "angle_v2_saved_share_all_busy",
            "angle_v2_saved_share_all_empty",
            "angle_v2_saved_share_all_incomplete",
            "angle_v2_saved_share_all_corrupted",
            "angle_v2_saved_share_all_unavailable",
            "angle_v2_saved_share_all_stale",
            "angle_v2_saved_share_all_too_large",
        )

        /** Claves de slices anteriores que este slice no puede haber retirado. */
        val PRESERVED_KEYS: Set<String> = setOf(
            "angle_saved_title",
            "angle_saved_frame",
            "angle_saved_marks_title",
            "angle_v2_saved_frames",
            "angle_v2_saved_image_unavailable",
            "angle_v2_saved_read_only_note",
            "angle_v2_saved_share_image",
            "angle_v2_saved_share_frame_image",
            "angle_v2_saved_share_missing",
            "angle_v2_saved_share_corrupted",
            "angle_v2_saved_share_unavailable",
            "angle_v2_saved_share_no_app",
            "angle_editor_close_discard_title",
            "angle_editor_close_discard_body_live",
            "angle_editor_close_discard_body_frames",
            "angle_editor_close_discard_body_live_and_frames",
            "angle_editor_close_discard_confirm",
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
