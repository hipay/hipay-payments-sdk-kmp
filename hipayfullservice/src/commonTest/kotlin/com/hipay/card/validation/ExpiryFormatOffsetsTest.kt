package com.hipay.card.validation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `formatExpiryWithOffsets` — the single source for the expiry VisualTransformation on every
 * platform (story 11.8). Offset-mapping correctness around the display-only `/` is tested here
 * once; the per-platform wrappers are trivial. Pure, offline.
 */
class ExpiryFormatOffsetsTest {

    @Test
    fun emptyAndPartialNoSeparatorYet() {
        formatExpiryWithOffsets("").let {
            assertEquals("", it.text)
            assertEquals(0, it.originalToTransformed[0])
        }
        formatExpiryWithOffsets("1").let { assertEquals("1", it.text) }
        formatExpiryWithOffsets("12").let {
            assertEquals("12", it.text)                 // no trailing '/' until the year starts
            assertEquals(2, it.originalToTransformed[2]) // caret after MM sits before where '/' will go
        }
    }

    @Test
    fun separatorAppearsWithTheYearDigits() {
        formatExpiryWithOffsets("123").let {
            assertEquals("12/3", it.text)
            assertEquals(3, it.originalToTransformed[2]) // 3rd digit pushed past the '/'
        }
        formatExpiryWithOffsets("1299").let {
            assertEquals("12/99", it.text)
            assertEquals(5, it.originalToTransformed[4]) // end (4 digits + '/')
            assertEquals(4, it.transformedToOriginal[5]) // inverse at end
            assertEquals(2, it.transformedToOriginal[2]) // before the '/'
        }
    }

    @Test
    fun nonDigitsStrippedAndCappedToFour() {
        formatExpiryWithOffsets("1a2/9 9 9").let {
            assertEquals("12/99", it.text) // filtered to "1299", capped at 4
        }
    }
}
