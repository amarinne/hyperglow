package com.eza.hyperglow.ui

import java.util.Locale

internal enum class UiLanguage {
    SYSTEM,
    ENGLISH,
    SIMPLIFIED_CHINESE
}

internal fun resolveUiLanguage(tags: String): UiLanguage {
    val first = tags.split(',').firstOrNull().orEmpty().trim()
    if (first.isEmpty()) return UiLanguage.SYSTEM
    return when (Locale.forLanguageTag(first).language.lowercase(Locale.ROOT)) {
        "zh" -> UiLanguage.SIMPLIFIED_CHINESE
        "en" -> UiLanguage.ENGLISH
        else -> UiLanguage.SYSTEM
    }
}
