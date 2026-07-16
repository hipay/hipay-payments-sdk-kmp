package com.hipay.core

/**
 * Normalize a BCP-47 / ISO-639 language tag to a lowercased primary language subtag.
 *
 * Case-insensitive and region-tolerant so integrators can pass whatever they hold:
 * `"FR"`, `"fr-FR"`, `"fr_FR"`, `"  fr  "` all become `"fr"`; `"en-US"` → `"en"`;
 * `null` / blank → `null` (caller then follows the device locale).
 *
 * @since 0.3.0
 */
public fun normalizeLanguage(tag: String?): String? {
    val trimmed = tag?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    // Primary language subtag: everything before the first region/script separator ("-" or "_").
    val primary = trimmed.substringBefore('-').substringBefore('_')
    return primary.lowercase().ifEmpty { null }
}

/**
 * Resolve the effective UI language for a card component.
 *
 * Precedence: per-component `localeOverride` → SDK-wide [HiPaySettings] → device locale.
 * Every candidate is normalized ([normalizeLanguage]); the first non-null wins. A `null`
 * result means "no forced language" — the caller renders in the device locale (English fallback
 * for unsupported languages).
 *
 * @since 0.3.0
 */
public fun resolveLanguage(component: String?, settings: String?, device: String?): String? =
    normalizeLanguage(component) ?: normalizeLanguage(settings) ?: normalizeLanguage(device)
