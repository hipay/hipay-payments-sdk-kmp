package com.hipay.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HiPaySettingsTest {

    @Test
    fun default_is_null_follow_device() {
        assertNull(HiPaySettings().localeOverride.value)
    }

    @Test
    fun ctor_normalizes() {
        assertEquals("fr", HiPaySettings("fr-FR").localeOverride.value)
        assertEquals("en", HiPaySettings("EN").localeOverride.value)
    }

    @Test
    fun setLocaleOverride_normalizes_and_emits() {
        val settings = HiPaySettings()
        settings.setLocaleOverride("FR")
        assertEquals("fr", settings.localeOverride.value)
        settings.setLocaleOverride("it-IT")
        assertEquals("it", settings.localeOverride.value)
        settings.setLocaleOverride(null) // clear → follow device
        assertNull(settings.localeOverride.value)
        settings.setLocaleOverride("   ") // blank clears too
        assertNull(settings.localeOverride.value)
    }

    @Test
    fun listener_fires_normalized_and_stops_after_cancel() {
        val settings = HiPaySettings()
        val seen = mutableListOf<String?>()
        val cancel = settings.addLocaleListener { seen.add(it) }
        settings.setLocaleOverride("FR")
        settings.setLocaleOverride("it-IT")
        cancel()
        settings.setLocaleOverride("en") // no callback after cancel
        assertEquals(listOf<String?>("fr", "it"), seen)
    }

    @Test
    fun config_carries_settings_and_defaults_null() {
        assertNull(HiPayConfig("u", "p", Environment.STAGE).settings)
        val settings = HiPaySettings("fr")
        val config = HiPayConfig("u", "p", Environment.STAGE, settings)
        assertEquals("fr", config.settings?.localeOverride?.value)
    }
}
