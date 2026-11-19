package com.openjump.app.ui.angles

import com.openjump.app.testsupport.ResourceCatalogs
import com.openjump.app.testsupport.positionalPlaceholders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guard JVM de paridad i18n de la introducción de Ángulos.
 *
 * Lee los recursos reales de producción (los 13 `app/src/main/res/values-XX/strings.xml`) a través
 * del snapshot compartido [ResourceCatalogs]; no hay copias de los textos dentro del test ni un
 * helper que fabrique el catálogo esperado. Comprueba:
 *
 * 1. los 13 catálogos declaran exactamente las mismas claves `angle_intro_*`, cada una una vez;
 * 2. la pantalla tiene el juego completo acordado (>= [EXPECTED_KEYS]) y ningún valor vacío;
 * 3. los placeholders posicionales (%1$s/%1$d) coinciden con la base española;
 * 4. `values-es` sigue siendo byte a byte el catálogo base (el espejo explícito del default);
 * 5. `es-419` usa vocabulario neutro: "video" sin tilde, sin "pulsa"/"añade".
 *
 * Es sólo paridad de recursos: no compone UI ni demuestra comportamiento en un dispositivo.
 */
class AngleIntroStringsParityTest {

    @Test
    fun `los trece catalogos declaran las mismas claves angle_intro que la base`() {
        assertEquals("Se esperaban ${locales.size} recursos de strings", 13, catalogs.size)
        val base = catalogs.catalog(BASE_LOCALE)
        val baseKeys = base.namesWithPrefix(PREFIX).keys
        assertEquals("juego de claves angle_intro_* inesperado en la base", EXPECTED_KEYS, baseKeys.sorted())
        for (locale in catalogs.ordered) {
            val localeKeys = locale.namesWithPrefix(PREFIX)
            val missing = (baseKeys - localeKeys.keys).sorted()
            val extra = (localeKeys.keys - baseKeys).sorted()
            assertTrue("[${locale.label}] faltan claves angle_intro_*: $missing", missing.isEmpty())
            assertTrue("[${locale.label}] claves angle_intro_* ajenas a la base: $extra", extra.isEmpty())
            for (key in baseKeys) {
                assertEquals(
                    "[${locale.label}] '$key' debe aparecer exactamente una vez",
                    1,
                    localeKeys.getValue(key).size,
                )
                assertTrue(
                    "[${locale.label}] '$key' no puede tener valor vacío",
                    localeKeys.getValue(key).single().isNotBlank(),
                )
            }
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

    @Test
    fun `values es sigue siendo byte a byte el catalogo base`() {
        // Sin el espejo explícito, Android puede preferir es-419 para un usuario que elige español.
        assertEquals(
            "values-es debe conservar íntegro el catálogo español original",
            resolve("app/src/main/res/values/strings.xml").readText(),
            resolve("app/src/main/res/values-es/strings.xml").readText(),
        )
    }

    @Test
    fun `es 419 usa vocabulario neutro en las claves de la introduccion`() {
        val latin = catalogs.catalog("values-b+es+419")
        val values = latin.namesWithPrefix(PREFIX).values.flatten()
        val forbidden = mapOf(
            Regex("""\b[Vv]ídeo(s)?\b""") to "vídeo (debe ser video)",
            Regex("""\b[Pp]ulsa(r|do|da|ndo)?\b""") to "pulsa/pulsar (debe ser toca/tocar)",
            Regex("""\b[Aa]ñad(e|ir|ida|ido|idas|idos)\b""") to "añadir (debe ser agregar)",
        )
        for ((pattern, description) in forbidden) {
            val offenders = values.filter { pattern.containsMatchIn(it) }
            assertEquals("[es-419] valores con $description", emptyList<String>(), offenders)
        }
        assertTrue(
            "[es-419] la pantalla debe nombrar el video sin tilde",
            latin.onlyValue("angle_intro_title").contains("video"),
        )
    }

    private fun resolve(path: String): File {
        val candidates = listOf(File(path), File("../$path"))
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "No se encuentra '$path'. Directorio de trabajo: ${File(".").absolutePath}",
            )
    }

    private companion object {
        const val PREFIX = "angle_intro_"
        const val BASE_LOCALE = "values"

        val EXPECTED_KEYS: List<String> = listOf(
            "angle_intro_heading",
            "angle_intro_help_intro",
            "angle_intro_help_precision",
            "angle_intro_help_safe_capture",
            "angle_intro_help_tips",
            "angle_intro_help_title",
            "angle_intro_help_vertex",
            "angle_intro_history_helper",
            "angle_intro_limit_body",
            "angle_intro_limit_title",
            "angle_intro_overview",
            "angle_intro_record",
            "angle_intro_select_athlete",
            "angle_intro_start_blocked",
            "angle_intro_step_capture_body",
            "angle_intro_step_capture_title",
            "angle_intro_step_pick_body",
            "angle_intro_step_pick_title",
            "angle_intro_step_save_body",
            "angle_intro_step_save_title",
            "angle_intro_tips",
            "angle_intro_title",
        ).sorted()

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
