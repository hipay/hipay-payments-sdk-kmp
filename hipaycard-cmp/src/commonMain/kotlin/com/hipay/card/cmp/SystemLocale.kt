package com.hipay.card.cmp

/**
 * The device's preferred language tag (language + optional region, e.g. French/France), read
 * natively per platform — a local
 * expect/actual like [currentYear] because commonMain has no locale API and pulling a datetime/
 * locale library in for one read is not worth it. Consumed through [cardEntryLanguage], which
 * normalizes the tag and owns the English fallback.
 */
internal expect fun systemLocaleLanguage(): String
