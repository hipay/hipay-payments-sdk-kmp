package com.hipay.core

import java.util.Locale

/**
 * Android convenience: set the SDK's forced UI language from a [java.util.Locale].
 *
 * Uses the locale's language tag, normalized case-insensitively and region-tolerantly
 * (`Locale.FRANCE` / `Locale("fr")` → `"fr"`). To clear it, call the common
 * [HiPaySettings.setLocaleOverride] with `null`.
 *
 * @since 0.3.0
 */
public fun HiPaySettings.setLocaleOverride(locale: Locale) {
    setLocaleOverride(locale.toLanguageTag())
}
