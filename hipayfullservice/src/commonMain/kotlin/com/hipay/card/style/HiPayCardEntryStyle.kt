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
 * All parameters default to the [hipayDefault] look, so an integrator overrides only what
 * their branding needs: `HiPayCardEntryStyle(borderColor = 0xFF0055FF, cornerRadius = 4f)`.
 *
 * @property textColor entered-text color (ARGB).
 * @property placeholderColor placeholder and floating-label color (ARGB).
 * @property iconColor tint for monochrome glyphs (neutral card icon, CVV info glyph) and the
 *  unselected network chips; brand network logos are never re-tinted when selected (ARGB).
 * @property invalidTextColor inline validation-error text color (ARGB).
 * @property fontFamily reserved for a future custom-font release — must stay null for now
 *  (null = platform system font; custom-font loading is not implemented).
 * @property fontSize entered-text size in scalable points (sp / Dynamic-Type points).
 * @property fontStyle entered-text style.
 * @property fontWeight entered-text weight.
 * @property borderColor field outline color (ARGB).
 * @property borderWidth field outline width in density-independent units.
 * @property cornerRadius field corner radius in density-independent units.
 * @property backgroundColor field container color (ARGB).
 * @property fieldHeight field height in density-independent units.
 *
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
