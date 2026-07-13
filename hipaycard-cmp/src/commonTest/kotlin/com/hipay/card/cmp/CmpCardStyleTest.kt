package com.hipay.card.cmp

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hipay.card.style.HiPayCardEntryStyle
import com.hipay.card.style.HiPayFontStyle
import com.hipay.card.style.HiPayFontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Style-contract → Compose mapping (colors, typography, shape) — pure, no composition. */
class CmpCardStyleTest {

    @Test
    fun argb_long_maps_to_the_same_compose_color() {
        assertEquals(Color(0xFF112233), cmpColor(0xFF112233))
        assertEquals(Color(0x808E8E93), cmpColor(0x808E8E93)) // alpha preserved
        assertEquals(Color(0xFFFFFFFF), cmpColor(0xFFFFFFFF))
    }

    @Test
    fun entry_text_style_maps_every_typography_primitive() {
        val style = HiPayCardEntryStyle(
            textColor = 0xFF112233,
            fontSize = 18f,
            fontStyle = HiPayFontStyle.ITALIC,
            fontWeight = HiPayFontWeight.SEMIBOLD,
        ).entryTextStyle()
        assertEquals(Color(0xFF112233), style.color)
        assertEquals(18.sp, style.fontSize)
        assertEquals(FontStyle.Italic, style.fontStyle)
        assertEquals(FontWeight.SemiBold, style.fontWeight)
        // System font in v1: the contract's null fontFamily must stay unresolved.
        assertNull(style.fontFamily)
    }

    @Test
    fun every_font_weight_constant_has_a_compose_mapping() {
        val expected = mapOf(
            HiPayFontWeight.REGULAR to FontWeight.Normal,
            HiPayFontWeight.MEDIUM to FontWeight.Medium,
            HiPayFontWeight.SEMIBOLD to FontWeight.SemiBold,
            HiPayFontWeight.BOLD to FontWeight.Bold,
        )
        assertEquals(HiPayFontWeight.entries.toSet(), expected.keys)
        for ((contract, compose) in expected) {
            assertEquals(
                compose,
                HiPayCardEntryStyle(fontWeight = contract).entryTextStyle().fontWeight,
                contract.name,
            )
        }
    }

    @Test
    fun color_override_parameter_recolors_the_text_style() {
        val style = HiPayCardEntryStyle.hipayDefault
        assertEquals(Color(0xFFD32F2F), style.entryTextStyle(style.invalidTextColor).color)
    }

    @Test
    fun field_shape_uses_the_corner_radius() {
        assertEquals(RoundedCornerShape(12.dp), HiPayCardEntryStyle.hipayDefault.fieldShape())
        assertEquals(RoundedCornerShape(0.dp), HiPayCardEntryStyle(cornerRadius = 0f).fieldShape())
    }
}
