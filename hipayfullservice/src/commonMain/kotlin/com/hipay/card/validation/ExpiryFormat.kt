package com.hipay.card.validation

/**
 * Expiry formatting + caret offset maps (story 11.8) — the single source of truth for the
 * expiry `VisualTransformation` on every platform. The raw digits (`MMYY`, ≤4) are the value; the
 * `/` is display-only and the offset maps keep the caret correct. There is exactly one separator,
 * inserted before the year (after 2 digits), so `"12"` stays `"12"`, `"123"` → `"12/3"`,
 * `"1299"` → `"12/99"`. With a single fixed separator the maps are closed-form: every offset at or
 * past the `/` shifts by one.
 */
public fun formatExpiryWithOffsets(input: String): FormattedNumber {
    val digits = input.filter { it in '0'..'9' }.take(4)
    val hasSeparator = digits.length >= 3 // the "/" only appears once the year starts
    val text = if (hasSeparator) "${digits.substring(0, 2)}/${digits.substring(2)}" else digits
    // raw offset → display offset: +1 for everything at/after the separator (raw index 2)
    val originalToTransformed = IntArray(digits.length + 1) { o -> if (hasSeparator && o >= 2) o + 1 else o }
    // display offset → raw offset: −1 for everything past the separator (display index 2)
    val transformedToOriginal = IntArray(text.length + 1) { j -> if (hasSeparator && j >= 3) j - 1 else j }
    return FormattedNumber(text, originalToTransformed, transformedToOriginal)
}
