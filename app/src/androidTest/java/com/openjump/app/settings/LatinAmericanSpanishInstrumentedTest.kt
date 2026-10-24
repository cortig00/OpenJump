package com.openjump.app.settings

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.MainActivity
import com.openjump.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Contrato del locale `es-419` (Español de Latinoamérica) en runtime Android.
 *
 * Cubre lo que un test JVM no puede: la resolución real de recursos con la configuración de locale
 * BCP 47 (`values-b+es+419`), el fallback entre `es-419` e `en`, los formatos/plurales y el
 * round-trip de [AppLanguageManager] tras recrear una actividad real.
 *
 * Conserva los locales previos con un delegate activo y los restaura en `finally`, antes de cerrar
 * la actividad. No borra preferencias, onboarding ni mediciones. La persistencia entre procesos
 * se comprueba por separado en el smoke del selector.
 */
@RunWith(AndroidJUnit4::class)
class LatinAmericanSpanishInstrumentedTest {

    private val appContext: Context = ApplicationProvider.getApplicationContext()

    private fun contextWithLocales(vararg tags: String): Context {
        val locales = LocaleList(*tags.map { Locale.forLanguageTag(it) }.toTypedArray())
        val configuration = Configuration(appContext.resources.configuration)
        configuration.setLocales(locales)
        return appContext.createConfigurationContext(configuration)
    }

    @Test
    fun latinAmericanSpanishShowsNeutralVocabulary() {
        val context = contextWithLocales("es-419")
        assertEquals("Configuración", context.getString(R.string.nav_settings))
        assertEquals("Configuración", context.getString(R.string.settings_title))
        assertEquals("Video", context.getString(R.string.jump_flow_video))
        assertEquals("Saltos", context.getString(R.string.home_jumps_title))
        val importVideo = context.getString(R.string.common_import_video)
        assertTrue(importVideo, importVideo.contains("video"))
        assertFalse(importVideo, importVideo.contains("vídeo"))
        assertFalse(context.getString(R.string.nav_settings).contains("Ajustes"))
    }

    @Test
    fun latinAmericanSpanishFormatsPlaceholdersAndPlurals() {
        val context = contextWithLocales("es-419")
        assertEquals("Paso 2 de 4", context.getString(R.string.jump_flow_progress, 2, 4))
        assertEquals(
            "1 medición",
            context.resources.getQuantityString(R.plurals.comparison_measurements, 1, 1),
        )
        assertEquals(
            "3 mediciones",
            context.resources.getQuantityString(R.plurals.comparison_measurements, 3, 3),
        )
        assertEquals("Video", context.getString(R.string.encoder_result_tab_video))
    }

    @Test
    fun latinAmericanSpanishWinsOverEnglishInALocaleList() {
        val latin = contextWithLocales("es-419", "en")
        // El fallback de la lista no es inglés: gana es-419 mientras esté disponible.
        assertEquals("Saltos", latin.getString(R.string.home_jumps_title))
        assertEquals("Configuración", latin.getString(R.string.nav_settings))
        // Los otros locales siguen intactos.
        assertEquals("Jumps", contextWithLocales("en").getString(R.string.home_jumps_title))
        assertEquals("Ajustes", contextWithLocales("es").getString(R.string.nav_settings))
        assertEquals("Ajustes", contextWithLocales("es-ES").getString(R.string.nav_settings))
    }

    @Test
    fun applyingLatinAmericanSpanishSurvivesActivityRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var previousLocales: LocaleListCompat
            scenario.onActivity {
                previousLocales = AppCompatDelegate.getApplicationLocales()
            }
            try {
                scenario.onActivity {
                    AppLanguageManager.apply(AppLanguage.SPANISH_LATIN_AMERICA)
                }
                instrumentation.waitForIdleSync()
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals("es-419", AppCompatDelegate.getApplicationLocales().toLanguageTags())
                    assertEquals(AppLanguage.SPANISH_LATIN_AMERICA, AppLanguageManager.current())
                    assertEquals("Configuración", activity.getString(R.string.settings_title))
                    assertEquals("Video", activity.getString(R.string.jump_flow_video))
                }
            } finally {
                scenario.onActivity { AppCompatDelegate.setApplicationLocales(previousLocales) }
                instrumentation.waitForIdleSync()
            }
            scenario.onActivity {
                assertEquals(previousLocales.toLanguageTags(), AppCompatDelegate.getApplicationLocales().toLanguageTags())
            }
        }
    }
}
