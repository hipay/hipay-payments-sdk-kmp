package com.hipay.card.cmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The iOS actual of the system-locale accessor, on a real simulator (NSLocale-backed). */
class CmpSystemLocaleTest {

    @Test
    fun preferred_languages_come_back_as_plausible_language_tags() {
        val tags = systemLocaleLanguages()
        assertTrue(tags.isNotEmpty(), "NSLocale.preferredLanguages must not be empty")
        // Every tag must be shaped like a language tag (2-3 letter primary subtag, optional
        // rest) — the shape the normalization in cardEntryLanguage relies on. A broken actual
        // returning empty strings or non-locale garbage fails here.
        val tagShape = Regex("^[a-zA-Z]{2,3}([-_].*)?$")
        for (tag in tags) {
            assertTrue(tagShape.matches(tag), "not a language tag: '$tag'")
        }
    }

    @Test
    fun null_override_resolution_consults_the_device_preference_list() {
        assertEquals(
            firstSupportedLanguage(systemLocaleLanguages()),
            resolvedCardEntryLanguage(null),
        )
    }
}
