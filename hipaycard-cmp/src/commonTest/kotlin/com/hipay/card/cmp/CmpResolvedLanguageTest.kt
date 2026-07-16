package com.hipay.card.cmp

import kotlin.test.Test
import kotlin.test.assertEquals

class CmpResolvedLanguageTest {

    @Test
    fun componentOverride_wins_and_is_normalized() {
        // per-component override beats settings; case-insensitive + region-tolerant.
        assertEquals("fr", resolvedCardEntryLanguage(localeOverride = "FR", settingsOverride = "it"))
        assertEquals("fr", resolvedCardEntryLanguage(localeOverride = "fr-FR", settingsOverride = "it"))
    }

    @Test
    fun settingsOverride_used_when_no_component_override() {
        assertEquals("it", resolvedCardEntryLanguage(localeOverride = null, settingsOverride = "IT-it"))
        assertEquals("en", resolvedCardEntryLanguage(localeOverride = "  ", settingsOverride = "EN"))
    }
}
