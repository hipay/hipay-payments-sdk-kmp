package com.hipay.card.style

/**
 * Font style for the card-entry text, platform-neutral.
 *
 * @since 0.3.0
 */
public enum class HiPayFontStyle { NORMAL, ITALIC }

/**
 * Font weight for the card-entry text, platform-neutral. Maps to Compose
 * `FontWeight.Normal/Medium/SemiBold/Bold` and SwiftUI `.regular/.medium/.semibold/.bold`.
 *
 * @since 0.3.0
 */
public enum class HiPayFontWeight { REGULAR, MEDIUM, SEMIBOLD, BOLD }

/**
 * Visual customization of the card-entry component — one shared, platform-neutral contract
 * consumed by every renderer (Compose Multiplatform, Android, iOS). Deliberately built on
 * primitives only (no Compose/SwiftUI types): each renderer maps them to its native
 * color/typography/shape types.
 *
 * Colors are ARGB packed in a [Long] (e.g. `0xFF111111` — alpha in the high byte), chosen over
 * hex strings so there is nothing to parse at render time and the value exports cleanly to
 * Swift as `Int64`.
 *
 * All parameters default to the [hipayDefault] look, so a Kotlin integrator overrides only
 * what their branding needs: `HiPayCardEntryStyle(borderColor = 0xFF0055FF, cornerRadius = 4f)`.
 * Kotlin default arguments are not exported to Swift — from Swift, start from [hipayDefault];
 * a Swift-side per-property override API ships with the iOS-native styling release.
 *
 * Values are validated at construction and rejected with [IllegalArgumentException] rather
 * than rendered wrong: colors must fit in 32 ARGB bits (`0xAARRGGBB` — mind the alpha byte:
 * `0x112233` is fully transparent, write `0xFF112233`), [textColor] and [invalidTextColor]
 * must not be fully transparent (the cardholder must be able to read what they type and why
 * it is rejected), sizes must be finite ([fontSize] and [fieldHeight] positive, [borderWidth]
 * and [cornerRadius] zero or more), and [fontFamily] must stay null.
 *
 * @property textColor entered-text color (ARGB, alpha > 0).
 * @property placeholderColor placeholder and floating-label color (ARGB).
 * @property iconColor tint for monochrome glyphs (neutral card icon, CVV info glyph) and the
 *  unselected network chips; brand network logos are never re-tinted when selected (ARGB).
 *  Pick a tint clearly distinct from the card networks' brand colors — the monochrome-vs-color
 *  contrast is the visible cue distinguishing the selected chip.
 * @property invalidTextColor inline validation-error text color (ARGB, alpha > 0).
 * @property fontFamily reserved for a future custom-font release — must stay null for now
 *  (null = platform system font; custom-font loading is not implemented).
 * @property fontSize entered-text size in scalable points (sp / Dynamic-Type points).
 * @property fontStyle entered-text style.
 * @property fontWeight entered-text weight.
 * @property borderColor field outline color (ARGB).
 * @property borderWidth field outline width in density-independent units.
 * @property cornerRadius field corner radius in density-independent units.
 * @property fieldHeight minimum field height in density-independent units — the field grows
 *  beyond it when content needs the room (e.g. large accessibility font scales), so entered
 *  card data is never clipped.
 * @property backgroundColor field container color (ARGB).
 *
 * @throws IllegalArgumentException when a value is outside the documented bounds.
 * @since 0.3.0
 */
public data class HiPayCardEntryStyle(
    val textColor: Long = 0xFF111111,
    val placeholderColor: Long = 0xFF8E8E93,
    val iconColor: Long = 0xFF8E8E93,
    val invalidTextColor: Long = 0xFFD32F2F,
    val fontFamily: String? = null,
    val fontSize: Float = 16f,
    val fontStyle: HiPayFontStyle = HiPayFontStyle.NORMAL,
    val fontWeight: HiPayFontWeight = HiPayFontWeight.REGULAR,
    val borderColor: Long = 0xFFC7C7CC,
    val borderWidth: Float = 1f,
    val cornerRadius: Float = 12f,
    val backgroundColor: Long = 0xFFFFFFFF,
    val fieldHeight: Float = 42f,
) {
    init {
        requireArgb("textColor", textColor)
        requireArgb("placeholderColor", placeholderColor)
        requireArgb("iconColor", iconColor)
        requireArgb("invalidTextColor", invalidTextColor)
        requireArgb("borderColor", borderColor)
        requireArgb("backgroundColor", backgroundColor)
        requireOpaqueEnough("textColor", textColor)
        requireOpaqueEnough("invalidTextColor", invalidTextColor)
        require(fontFamily == null) {
            "fontFamily is reserved for a future release and must stay null (null = platform system font)"
        }
        requireFinite("fontSize", fontSize, atLeast = MUST_BE_POSITIVE)
        requireFinite("fieldHeight", fieldHeight, atLeast = MUST_BE_POSITIVE)
        requireFinite("borderWidth", borderWidth, atLeast = 0f)
        requireFinite("cornerRadius", cornerRadius, atLeast = 0f)
    }

    public companion object {
        /**
         * The SDK's default look: a basic light-mode appearance close to what the component
         * rendered before styling existed, deliberately unifying the small historical
         * per-platform differences (corner radius, system-derived borders) into one identical
         * cross-platform baseline — with a more compact field height (42) than the Material3
         * 56 minimum the component used to inherit.
         *
         * @since 0.3.0
         */
        public val hipayDefault: HiPayCardEntryStyle = HiPayCardEntryStyle()
    }
}

// Fail-closed on construction: a bad styling value must surface at integration time, not
// render an unusable payment form (invisible text, collapsed fields, corrupt colors).

private const val MUST_BE_POSITIVE = Float.MIN_VALUE

private fun requireArgb(name: String, value: Long) {
    require(value in 0x0..0xFFFFFFFFL) {
        "$name must be a 32-bit ARGB value in 0x00000000..0xFFFFFFFF (0xAARRGGBB), got 0x${value.toString(16)}"
    }
}

private fun requireOpaqueEnough(name: String, value: Long) {
    require(value and 0xFF000000L != 0L) {
        "$name is fully transparent (alpha byte 0x00) — did you mean 0xFF${value.toString(16).padStart(6, '0')}?"
    }
}

private fun requireFinite(name: String, value: Float, atLeast: Float) {
    require(value.isFinite() && value >= atLeast) {
        val bound = if (atLeast > 0f) "> 0" else ">= 0"
        "$name must be finite and $bound, got $value"
    }
}
