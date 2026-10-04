package com.openjump.app.data

import java.text.Normalizer
import java.util.Locale

/** Normalizes free-text history searches for case- and accent-insensitive matching. */
internal fun String.normalizeForSearch(): String = Normalizer
    .normalize(this, Normalizer.Form.NFD)
    .replace("\\p{Mn}+".toRegex(), "")
    .lowercase(Locale.ROOT)
