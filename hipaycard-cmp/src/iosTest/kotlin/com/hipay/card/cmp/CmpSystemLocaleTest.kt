package com.hipay.card.cmp

import com.hipay.card.validation.CardEntryStringKey
import kotlin.test.Test
import kotlin.test.assertTrue

/** The iOS actual of the system-locale accessor, on a real simulator (NSLocale-backed). */
class CmpSystemLocaleTest {

    @Test
    fun system_language_is_readable_and_resolves_to_a_catalog_language() {
        val tag = systemLocaleLanguage()
        assertTrue(tag.isNotBlank(), "system language tag must not be blank")
        assertTrue(cardEntryLanguage(tag) in setOf("en", "fr", "it"))
    }

    @Test
    fun full_resolution_path_returns_a_catalog_value_for_the_device_language() {
        assertTrue(cmpString(CardEntryStringKey.LABEL_NUMBER, systemLocaleLanguage()).isNotBlank())
        assertTrue(cmpString(CardEntryStringKey.LABEL_NUMBER, resolvedCardEntryLanguage(null)).isNotBlank())
    }
}
