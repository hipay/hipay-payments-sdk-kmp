// PCI (NFR2): com.hipay.card path — never log card data here.
package com.hipay.card

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.hipay.card.validation.formatExpiryWithOffsets

/**
 * Thin Jetpack-Compose wrapper (story 11.8): the value stays raw digits (`MMYY`), the `/` + caret
 * offset maps come from the shared `formatExpiryWithOffsets` (single source, tested in the core).
 * The CMP `:hipaycard-cmp` has the same wrapper over Compose-Multiplatform.
 */
internal class ExpiryVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val f = formatExpiryWithOffsets(text.text)
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                f.originalToTransformed[offset.coerceIn(0, f.originalToTransformed.lastIndex)]

            override fun transformedToOriginal(offset: Int): Int =
                f.transformedToOriginal[offset.coerceIn(0, f.transformedToOriginal.lastIndex)]
        }
        return TransformedText(AnnotatedString(f.text), mapping)
    }
}
