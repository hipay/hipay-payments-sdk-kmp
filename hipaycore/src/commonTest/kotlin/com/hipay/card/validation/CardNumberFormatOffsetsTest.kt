package com.hipay.card.validation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `CardNetworks.formatWithOffsets` — the single source for the card-number VisualTransformation
 * on every platform (story 11.1). Offset-mapping correctness (the classic off-by-one) is tested
 * here once; the per-platform wrappers are trivial. Pure, offline.
 */
class CardNumberFormatOffsetsTest {

    @Test
    fun visaGroupsByFourWithCorrectOffsets() {
        val f = CardNetworks.formatWithOffsets("4111111111111111", CardNetwork.VISA)
        assertEquals("4111 1111 1111 1111", f.text)
        assertEquals(0, f.originalToTransformed[0])
        assertEquals(5, f.originalToTransformed[4])   // caret after 4 digits sits after the space
        assertEquals(19, f.originalToTransformed[16]) // end (16 digits + 3 spaces)
        assertEquals(4, f.transformedToOriginal[5])
        assertEquals(16, f.transformedToOriginal[19])
    }

    @Test
    fun amexGroups465() {
        val f = CardNetworks.formatWithOffsets("378282246310005", CardNetwork.AMEX)
        assertEquals("3782 822463 10005", f.text)
        assertEquals(5, f.originalToTransformed[4])
        assertEquals(12, f.originalToTransformed[10]) // after "3782 822463 "
        assertEquals(17, f.originalToTransformed[15]) // end
    }

    @Test
    fun partialNumberHasNoTrailingSpace() {
        val f = CardNetworks.formatWithOffsets("1234", CardNetwork.VISA)
        assertEquals("1234", f.text)
        assertEquals(4, f.originalToTransformed[4])
    }

    @Test
    fun formatDelegatesToFormatWithOffsets() {
        assertEquals("4111 1111 1111 1111", CardNetworks.format("4111111111111111"))
        assertEquals("3782 822463 10005", CardNetworks.format("378282246310005"))
        assertEquals("", CardNetworks.format(""))
    }
}
