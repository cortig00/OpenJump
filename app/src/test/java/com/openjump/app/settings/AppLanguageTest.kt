package com.openjump.app.settings

import com.openjump.app.ui.titleResource
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun `maps empty locale selection to system`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTag(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTag(""))
    }

    @Test
    fun `maps supported regional tags to their app language`() {
        assertEquals(AppLanguage.SPANISH, AppLanguage.fromLanguageTag("es-ES"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguageTag("en-US"))
        assertEquals(AppLanguage.FRENCH, AppLanguage.fromLanguageTag("fr-FR"))
        assertEquals(AppLanguage.GERMAN, AppLanguage.fromLanguageTag("de-DE"))
        assertEquals(AppLanguage.PORTUGUESE_BRAZIL, AppLanguage.fromLanguageTag("pt-BR"))
        assertEquals(AppLanguage.PORTUGUESE_PORTUGAL, AppLanguage.fromLanguageTag("pt-PT"))
        assertEquals(AppLanguage.ITALIAN, AppLanguage.fromLanguageTag("it-IT"))
        assertEquals(AppLanguage.TURKISH, AppLanguage.fromLanguageTag("tr-TR"))
    }

    @Test
    fun `maps polish japanese and korean regional tags without claiming other languages`() {
        listOf("pl", "pl-PL", "PL-pl", "pl-Latn-PL").forEach {
            assertEquals(AppLanguage.POLISH, AppLanguage.fromLanguageTag(it))
        }
        listOf("ja", "ja-JP", "JA-jp", "ja-Jpan-JP").forEach {
            assertEquals(AppLanguage.JAPANESE, AppLanguage.fromLanguageTag(it))
        }
        listOf("ko", "ko-KR", "KO-kr", "ko-Kore-KR").forEach {
            assertEquals(AppLanguage.KOREAN, AppLanguage.fromLanguageTag(it))
        }
        listOf("planet", "jargon", "komodo").forEach {
            assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTag(it))
        }
    }

    @Test
    fun `new languages have independent selector labels`() {
        val languages = listOf(AppLanguage.POLISH, AppLanguage.JAPANESE, AppLanguage.KOREAN)
        assertEquals(listOf("pl", "ja", "ko"), languages.map { it.languageTag })
        org.junit.Assert.assertTrue(AppLanguage.entries.containsAll(languages))
        assertEquals(3, languages.map { it.titleResource() }.distinct().size)
        languages.forEach { org.junit.Assert.assertNotEquals(0, it.titleResource()) }
    }

    @Test
    fun `falls back to system for unsupported tags`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTag("pt"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTag("nl-NL"))
    }

    @Test
    fun `italian and turkish are offered in the language selector`() {
        // F003: IT/TR are first-class selector options (SettingsSelectionSheet uses
        // AppLanguage.entries); the sheet already scrolls via LazyColumn.
        val entries = AppLanguage.entries
        org.junit.Assert.assertTrue(entries.contains(AppLanguage.ITALIAN))
        org.junit.Assert.assertTrue(entries.contains(AppLanguage.TURKISH))
        assertEquals(AppLanguage.ITALIAN, AppLanguage.fromLanguageTag("it"))
        assertEquals(AppLanguage.TURKISH, AppLanguage.fromLanguageTag("tr"))
        // Every option must resolve to a real label resource.
        entries.forEach { language ->
            org.junit.Assert.assertNotEquals(0, language.titleResource())
        }
    }

    @Test
    fun `maps latin american spanish tag to its own option`() {
        assertEquals("es-419", AppLanguage.SPANISH_LATIN_AMERICA.languageTag)
        assertEquals(AppLanguage.SPANISH_LATIN_AMERICA, AppLanguage.fromLanguageTag("es-419"))
        // Case-insensitive and separator-tolerant, as AppCompat returns device tags.
        assertEquals(AppLanguage.SPANISH_LATIN_AMERICA, AppLanguage.fromLanguageTag("ES-419"))
        assertEquals(AppLanguage.SPANISH_LATIN_AMERICA, AppLanguage.fromLanguageTag("es_419"))
        // BCP 47 extensions/private-use subtags after the region keep the same language.
        assertEquals(
            AppLanguage.SPANISH_LATIN_AMERICA,
            AppLanguage.fromLanguageTag("es-419-u-nu-latn"),
        )
        assertEquals(
            AppLanguage.SPANISH_LATIN_AMERICA,
            AppLanguage.fromLanguageTag("es-419-x-openjump"),
        )
    }

    @Test
    fun `plain spanish keeps resolving to spanish`() {
        assertEquals("es", AppLanguage.SPANISH.languageTag)
        assertEquals(AppLanguage.SPANISH, AppLanguage.fromLanguageTag("es"))
        assertEquals(AppLanguage.SPANISH, AppLanguage.fromLanguageTag("es-ES"))
        assertEquals(AppLanguage.SPANISH, AppLanguage.fromLanguageTag("es-MX"))
        assertEquals(AppLanguage.SPANISH, AppLanguage.fromLanguageTag("es-AR"))
        // Subtag boundary: `419` must be a whole region subtag, not a prefix of another one.
        assertEquals(AppLanguage.SPANISH, AppLanguage.fromLanguageTag("es-4190"))
        assertEquals(AppLanguage.SPANISH, AppLanguage.fromLanguageTag("es-US-419"))
    }

    @Test
    fun `every offered language round-trips through its persisted tag`() {
        AppLanguage.entries.forEach { language ->
            assertEquals(
                "${language.name} debe resolverse desde su tag '${language.languageTag}'",
                language,
                AppLanguage.fromLanguageTag(language.languageTag),
            )
        }
    }

    @Test
    fun `latin american spanish is offered with a distinct non zero label`() {
        org.junit.Assert.assertTrue(AppLanguage.entries.contains(AppLanguage.SPANISH_LATIN_AMERICA))
        org.junit.Assert.assertNotEquals(0, AppLanguage.SPANISH_LATIN_AMERICA.titleResource())
        org.junit.Assert.assertNotEquals(
            AppLanguage.SPANISH.titleResource(),
            AppLanguage.SPANISH_LATIN_AMERICA.titleResource(),
        )
    }
}
