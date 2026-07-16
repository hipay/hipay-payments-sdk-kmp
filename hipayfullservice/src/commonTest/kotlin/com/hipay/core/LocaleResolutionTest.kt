package com.hipay.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocaleResolutionTest {

    @Test
    fun normalize_lowercases_and_strips_region() {
        assertEquals("fr", normalizeLanguage("fr"))
        assertEquals("fr", normalizeLanguage("FR"))
        assertEquals("fr", normalizeLanguage("fr-FR"))
        assertEquals("fr", normalizeLanguage("fr_FR"))
        assertEquals("fr", normalizeLanguage("FR-ca"))
        assertEquals("fr", normalizeLanguage("  fr  "))
        assertEquals("en", normalizeLanguage("en-US"))
        assertEquals("it", normalizeLanguage("IT"))
    }

    @Test
    fun normalize_blank_or_null_is_null() {
        assertNull(normalizeLanguage(null))
        assertNull(normalizeLanguage(""))
        assertNull(normalizeLanguage("   "))
        assertNull(normalizeLanguage("-FR")) // no primary subtag
    }

    @Test
    fun resolve_precedence_component_then_settings_then_device() {
        // component wins
        assertEquals("fr", resolveLanguage(component = "FR", settings = "it", device = "en"))
        // settings when no component
        assertEquals("it", resolveLanguage(component = null, settings = "IT-it", device = "en"))
        // device when neither
        assertEquals("en", resolveLanguage(component = null, settings = null, device = "en-GB"))
        // all null → null (caller follows device/EN fallback)
        assertNull(resolveLanguage(component = null, settings = null, device = null))
        // blanks are treated as absent, not as a match
        assertEquals("it", resolveLanguage(component = "  ", settings = "it", device = "en"))
    }
}
