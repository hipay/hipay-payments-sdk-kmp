package com.hipay.card.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** The frozen style contract: default values, value semantics, enum coverage. */
class HiPayCardEntryStyleTest {

    @Test
    fun hipay_default_carries_the_documented_baseline_values() {
        val s = HiPayCardEntryStyle.hipayDefault
        assertEquals(0xFF111111, s.textColor)
        assertEquals(0xFF8E8E93, s.placeholderColor)
        assertEquals(0xFF8E8E93, s.iconColor)
        assertEquals(0xFFD32F2F, s.invalidTextColor)
        assertNull(s.fontFamily)
        assertEquals(16f, s.fontSize)
        assertEquals(HiPayFontStyle.NORMAL, s.fontStyle)
        assertEquals(HiPayFontWeight.REGULAR, s.fontWeight)
        assertEquals(0xFFC7C7CC, s.borderColor)
        assertEquals(1f, s.borderWidth)
        assertEquals(12f, s.cornerRadius)
        assertEquals(0xFFFFFFFF, s.backgroundColor)
        assertEquals(42f, s.fieldHeight)
    }

    @Test
    fun bare_constructor_equals_the_default_so_partial_overrides_start_from_it() {
        assertEquals(HiPayCardEntryStyle.hipayDefault, HiPayCardEntryStyle())
        assertEquals(
            HiPayCardEntryStyle.hipayDefault.copy(cornerRadius = 4f),
            HiPayCardEntryStyle(cornerRadius = 4f),
        )
    }

    @Test
    fun value_equality_distinguishes_every_property() {
        val base = HiPayCardEntryStyle.hipayDefault
        val variants = listOf(
            base.copy(textColor = 0xFF000000),
            base.copy(placeholderColor = 0xFF000000),
            base.copy(iconColor = 0xFF000000),
            base.copy(invalidTextColor = 0xFF000000),
            base.copy(fontFamily = "Serif"),
            base.copy(fontSize = 18f),
            base.copy(fontStyle = HiPayFontStyle.ITALIC),
            base.copy(fontWeight = HiPayFontWeight.BOLD),
            base.copy(borderColor = 0xFF000000),
            base.copy(borderWidth = 2f),
            base.copy(cornerRadius = 0f),
            base.copy(backgroundColor = 0xFF000000),
            base.copy(fieldHeight = 64f),
        )
        for (variant in variants) {
            assertNotEquals(base, variant)
        }
        // And an identical copy stays equal (value semantics, usable as a remember/state key).
        assertEquals(base, base.copy())
        assertEquals(base.hashCode(), base.copy().hashCode())
    }

    @Test
    fun font_enums_expose_exactly_the_contracted_constants() {
        assertEquals(listOf("NORMAL", "ITALIC"), HiPayFontStyle.entries.map { it.name })
        assertEquals(
            listOf("REGULAR", "MEDIUM", "SEMIBOLD", "BOLD"),
            HiPayFontWeight.entries.map { it.name },
        )
    }
}
