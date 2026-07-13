package com.hipay.card.cmp

/**
 * The device's preferred language tags, preference-ordered (language + optional region per
 * entry), read natively per platform — a local expect/actual like [currentYear] because
 * commonMain has no locale API and pulling a locale library in for one read is not worth it.
 * The full list matters: native iOS bundle resolution and Android resource resolution both
 * walk the user's ordered languages, so a device preferring an unsupported language first
 * (e.g. German, then French) must land on French, not the English fallback. Consumed through
 * [firstSupportedLanguage], which owns the walk and the English fallback.
 */
internal expect fun systemLocaleLanguages(): List<String>
