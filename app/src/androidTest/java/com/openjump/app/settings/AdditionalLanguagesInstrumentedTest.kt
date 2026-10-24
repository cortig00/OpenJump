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
import com.openjump.app.ui.titleResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Actual Android resource matching, native labels, CLDR quantities and locale recreation. */
@RunWith(AndroidJUnit4::class)
class AdditionalLanguagesInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val languages = listOf(AppLanguage.POLISH, AppLanguage.JAPANESE, AppLanguage.KOREAN)
    private val settings = listOf("Ustawienia", "設定", "설정")

    private fun localized(tag: String): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag(tag), Locale.ENGLISH))
        return context.createConfigurationContext(config)
    }

    @Test
    fun nativeCatalogsResolveInsteadOfSpanishOrEnglish() {
        languages.forEachIndexed { index, language ->
            val resources = localized(language.languageTag)
            assertEquals(settings[index], resources.getString(R.string.nav_settings))
            assertEquals(settings[index], resources.getString(R.string.settings_title))
            assertTrue(resources.getString(R.string.settings_jump_mark_start_title).isNotBlank())
            assertEquals("Polski", resources.getString(AppLanguage.POLISH.titleResource()))
            assertEquals("日本語", resources.getString(AppLanguage.JAPANESE.titleResource()))
            assertEquals("한국어", resources.getString(AppLanguage.KOREAN.titleResource()))
            val jumps = resources.getString(R.string.home_jumps_title)
            assertFalse(jumps, jumps == "Jumps" || jumps == "Saltos")
        }
        assertEquals("Ajustes", localized("es").getString(R.string.nav_settings))
        assertEquals("Configuración", localized("es-419").getString(R.string.nav_settings))
        assertEquals("Settings", localized("en").getString(R.string.nav_settings))
    }

    @Test
    fun polishQuantitiesUseSingularFewAndMany() {
        val resources = localized("pl").resources
        mapOf(1 to "1 pomiar", 2 to "2 pomiary", 5 to "5 pomiarów", 12 to "12 pomiarów", 22 to "22 pomiary", 101 to "101 pomiarów").forEach { (count, expected) ->
            assertEquals(expected, resources.getQuantityString(R.plurals.comparison_measurements, count, count))
        }
    }

    @Test
    fun cjkQuantitiesSupportOneAndManyWithoutSpanishSingularFallback() {
        listOf("ja", "ko").forEach { tag ->
            val resources = localized(tag).resources
            listOf(0, 1, 2, 12, 22).forEach { count ->
                val measurement = resources.getQuantityString(R.plurals.comparison_measurements, count, count)
                val series = resources.getQuantityString(R.plurals.camera_series_before_recording, count, count)
                assertTrue("$tag/$count $measurement", measurement.contains(count.toString()))
                assertTrue("$tag/$count $series", series.contains(count.toString()))
                assertFalse(measurement, measurement.contains("medición") || measurement.contains("measurement"))
                assertFalse(series, series.contains("Graba") || series.contains("Record"))
            }
        }
    }

    @Test fun polishSelectionSurvivesRecreation() = assertLocaleRecreation(AppLanguage.POLISH, "Ustawienia")
    @Test fun japaneseSelectionSurvivesRecreation() = assertLocaleRecreation(AppLanguage.JAPANESE, "設定")
    @Test fun koreanSelectionSurvivesRecreation() = assertLocaleRecreation(AppLanguage.KOREAN, "설정")

    private fun assertLocaleRecreation(language: AppLanguage, title: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var original: LocaleListCompat
            scenario.onActivity { original = AppCompatDelegate.getApplicationLocales() }
            try {
                scenario.onActivity { AppLanguageManager.apply(language) }
                instrumentation.waitForIdleSync()
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals(language, AppLanguageManager.current())
                    assertEquals(language.languageTag, AppCompatDelegate.getApplicationLocales()[0]?.language)
                    assertEquals(title, activity.getString(R.string.settings_title))
                }
            } finally {
                scenario.onActivity { AppCompatDelegate.setApplicationLocales(original) }
                instrumentation.waitForIdleSync()
            }
            scenario.onActivity { assertEquals(original.toLanguageTags(), AppCompatDelegate.getApplicationLocales().toLanguageTags()) }
        }
    }
}
