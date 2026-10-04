package com.openjump.app.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

enum class AppLanguage(val languageTag: String) {
    SYSTEM(""),
    SPANISH("es"),
    ENGLISH("en"),
    FRENCH("fr"),
    GERMAN("de"),
    PORTUGUESE_BRAZIL("pt-BR"),
    PORTUGUESE_PORTUGAL("pt-PT"),
    ITALIAN("it"),
    TURKISH("tr");

    companion object {
        fun fromLanguageTag(languageTag: String?): AppLanguage = when {
            languageTag.isNullOrBlank() -> SYSTEM
            languageTag.startsWith("es", ignoreCase = true) -> SPANISH
            languageTag.startsWith("en", ignoreCase = true) -> ENGLISH
            languageTag.startsWith("fr", ignoreCase = true) -> FRENCH
            languageTag.startsWith("de", ignoreCase = true) -> GERMAN
            languageTag.startsWith("pt-BR", ignoreCase = true) -> PORTUGUESE_BRAZIL
            languageTag.startsWith("pt-PT", ignoreCase = true) -> PORTUGUESE_PORTUGAL
            languageTag.startsWith("it", ignoreCase = true) -> ITALIAN
            languageTag.startsWith("tr", ignoreCase = true) -> TURKISH
            else -> SYSTEM
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
