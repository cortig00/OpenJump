package com.openjump.app.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

enum class AppLanguage(val languageTag: String) {
    SYSTEM(""),
    SPANISH("es"),
    SPANISH_LATIN_AMERICA("es-419"),
    ENGLISH("en"),
    FRENCH("fr"),
    GERMAN("de"),
    PORTUGUESE_BRAZIL("pt-BR"),
    PORTUGUESE_PORTUGAL("pt-PT"),
    ITALIAN("it"),
    TURKISH("tr"),
    POLISH("pl"),
    JAPANESE("ja"),
    KOREAN("ko");

    companion object {
        fun fromLanguageTag(languageTag: String?): AppLanguage = when {
            languageTag.isNullOrBlank() -> SYSTEM
            isLatinAmericanSpanish(languageTag) -> SPANISH_LATIN_AMERICA
            languageTag.startsWith("es", ignoreCase = true) -> SPANISH
            languageTag.startsWith("en", ignoreCase = true) -> ENGLISH
            languageTag.startsWith("fr", ignoreCase = true) -> FRENCH
            languageTag.startsWith("de", ignoreCase = true) -> GERMAN
            languageTag.startsWith("pt-BR", ignoreCase = true) -> PORTUGUESE_BRAZIL
            languageTag.startsWith("pt-PT", ignoreCase = true) -> PORTUGUESE_PORTUGAL
            languageTag.startsWith("it", ignoreCase = true) -> ITALIAN
            languageTag.startsWith("tr", ignoreCase = true) -> TURKISH
            languageTag.substringBefore('-').equals("pl", ignoreCase = true) -> POLISH
            languageTag.substringBefore('-').equals("ja", ignoreCase = true) -> JAPANESE
            languageTag.substringBefore('-').equals("ko", ignoreCase = true) -> KOREAN
            else -> SYSTEM
        }

        /**
         * `es-419` es el subtag BCP 47 del español de Hispanoamérica. Se exige que `419` sea el
         * subtag de región completo para no reclamar etiquetas como `es-4190` ni `es-US`; las
         * extensiones/privados posteriores (`es-419-u-nu-latn`) no cambian la decisión. El español
         * genérico (`es`, `es-ES`) sigue resolviéndose a [SPANISH].
         */
        private fun isLatinAmericanSpanish(languageTag: String): Boolean {
            val subtags = languageTag.trim().replace('_', '-').split('-')
            return subtags.size >= 2 &&
                subtags[0].equals("es", ignoreCase = true) &&
                subtags[1].equals("419", ignoreCase = true)
        }
    }
}

object AppLanguageManager {
    fun current(): AppLanguage =
        AppLanguage.fromLanguageTag(AppCompatDelegate.getApplicationLocales()[0]?.toLanguageTag())

    fun apply(language: AppLanguage) {
        val locales = if (language == AppLanguage.SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(language.languageTag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
