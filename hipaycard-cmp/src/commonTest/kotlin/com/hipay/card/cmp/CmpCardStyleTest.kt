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
    fun every_font_style_constant_has_a_compose_mapping() {
        val expected = mapOf(
            HiPayFontStyle.NORMAL to FontStyle.Normal,
            HiPayFontStyle.ITALIC to FontStyle.Italic,
        )
        assertEquals(HiPayFontStyle.entries.toSet(), expected.keys)
        for ((contract, compose) in expected) {
            assertEquals(
                compose,
                HiPayCardEntryStyle(fontStyle = contract).entryTextStyle().fontStyle,
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
    fun vertical_padding_centers_the_text_line_in_the_field() {
        // Material3 geometry: 56dp field / 16sp font = its documented 16dp content padding.
        assertEquals(16f, fieldVerticalPadding(fieldHeight = 56f, fontSize = 16f))
        // The compact default: (42 − 24) / 2.
        assertEquals(9f, fieldVerticalPadding(fieldHeight = 42f, fontSize = 16f))
        // Floors at zero when the line outgrows the field — never negative padding.
        assertEquals(0f, fieldVerticalPadding(fieldHeight = 20f, fontSize = 16f))
    }

    @Test
    fun disabled_dim_multiplies_the_existing_alpha() {
        // A semi-transparent style color must get dimmer when disabled, never more opaque.
        // Tolerance: sRGB colors re-quantize the alpha to 8 bits on copy.
        val base = Color(0x80112233)
        assertEquals(base.alpha * 0.38f, base.dimmedDisabled().alpha, absoluteTolerance = 1f / 255f)
    }

    @Test
    fun field_shape_uses_the_corner_radius() {
        assertEquals(RoundedCornerShape(12.dp), HiPayCardEntryStyle.hipayDefault.fieldShape())
        assertEquals(RoundedCornerShape(0.dp), HiPayCardEntryStyle(cornerRadius = 0f).fieldShape())
    }
}
