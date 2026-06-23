package com.hipay.card.cmp

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.hipay.card.validation.CardNetwork
import com.hipay.card.validation.CardNetworks

/**
 * Thin Compose-Multiplatform wrapper (story 11.1): the value stays raw digits, the spacing +
 * caret offset maps come from the shared `CardNetworks.formatWithOffsets` (single source, tested
 * in the core). The Android `:hipaycard` has the same wrapper over Jetpack Compose.
 */
internal class CardNumberVisualTransformation(
    private val network: CardNetwork,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val f = CardNetworks.formatWithOffsets(text.text, network)
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                f.originalToTransformed[offset.coerceIn(0, f.originalToTransformed.lastIndex)]

            override fun transformedToOriginal(offset: Int): Int =
                f.transformedToOriginal[offset.coerceIn(0, f.transformedToOriginal.lastIndex)]
        }
        return TransformedText(AnnotatedString(f.text), mapping)
    }
}
