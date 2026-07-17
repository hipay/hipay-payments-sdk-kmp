package com.hipay.card.cmp

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hipay.card.style.HiPayCardEntryStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Contractual styling Gherkin scenario, verbatim from PI-6085:
 *
 *   Étant donné que le développeur instancie HiPayCardEntry avec HiPayCardEntryStyle.hipayDefault
 *   Quand la vue est affichée à l'écran sur iOS et Android
 *   Alors les champs affichent les valeurs visuelles définies dans le style par défaut
 *
 * Pins the exact visual primitives the CMP renderer consumes for [HiPayCardEntryStyle.hipayDefault]
 * — the values the fields display. Runs on the JVM host AND iosSimulatorArm64, covering the
 * scenario's "sur iOS et Android" for CMP. The pixel-level result is the manual G13 matrix;
 * the custom-style scenario is marked "non testable" in the ticket (demo toggle + G13).
 *
 * `fieldHeight` is 42 (a MINIMUM).
 */
class CmpCardStyleGherkinTest {

    // Scénario 1 : affichage avec le style par défaut.
    @Test
    fun defaultStyleCarriesTheDocumentedVisualValuesForTheRenderer() {
        // Étant donné — the component is instantiated with the default style.
        val style = HiPayCardEntryStyle.hipayDefault

        // Alors — colors, exactly as the renderer maps them to Compose.
        assertEquals(Color(0xFF111111), cmpColor(style.textColor))
        assertEquals(Color(0xFF8E8E93), cmpColor(style.placeholderColor))
        assertEquals(Color(0xFF8E8E93), cmpColor(style.iconColor))
        assertEquals(Color(0xFFD32F2F), cmpColor(style.invalidTextColor))
        assertEquals(Color(0xFFC7C7CC), cmpColor(style.borderColor))
        assertEquals(Color(0xFFFFFFFF), cmpColor(style.backgroundColor))

        // Alors — typography: platform system family, 16sp, normal, regular.
        val text = style.entryTextStyle()
        assertNull(text.fontFamily)
        assertEquals(16.sp, text.fontSize)
        assertEquals(FontStyle.Normal, text.fontStyle)
        assertEquals(FontWeight.Normal, text.fontWeight)

        // Alors — container: border 1, cornerRadius 12 via the field shape, min height 42.
        assertEquals(1f, style.borderWidth)
        assertEquals(RoundedCornerShape(12.dp), style.fieldShape())
        assertEquals(42f, style.fieldHeight)
    }
}
