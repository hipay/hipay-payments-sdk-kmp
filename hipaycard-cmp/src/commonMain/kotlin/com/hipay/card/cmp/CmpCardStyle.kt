// PCI: com.hipay.card path — never log card data here.
package com.hipay.card.cmp

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hipay.card.style.HiPayCardEntryStyle
import com.hipay.card.style.HiPayFontStyle
import com.hipay.card.style.HiPayFontWeight

// LOCKSTEP: :hipaycard's CardEntryStyleMapper.kt is a deliberate source mirror of this file (the
// two renderers must map the shared HiPayCardEntryStyle with identical math, and the mapper cannot
// be hoisted to a module both depend on). Any edit here must be mirrored there, and vice versa.

/**
 * The style the card component renders with — provided by [CmpCardEntry] from its `style`
 * parameter. Default [HiPayCardEntryStyle.hipayDefault] so any out-of-component composition
 * (tests, previews) renders the SDK look deterministically.
 */
internal val LocalHiPayCardStyle = staticCompositionLocalOf { HiPayCardEntryStyle.hipayDefault }

/** ARGB `Long` (the platform-neutral contract encoding) → Compose [Color]. */
internal fun cmpColor(argb: Long): Color = Color(argb)

/** The field container shape from the style's corner radius. */
internal fun HiPayCardEntryStyle.fieldShape(): RoundedCornerShape =
    RoundedCornerShape(cornerRadius.dp)

/**
 * The entered-text typography from the style's primitives. `fontFamily` is deliberately not
 * resolved (null contract value = platform system font; custom-font loading is a later
 * release).
 */
internal fun HiPayCardEntryStyle.entryTextStyle(colorArgb: Long = textColor): TextStyle =
    TextStyle(
        color = cmpColor(colorArgb),
        fontSize = fontSize.sp,
        fontStyle = when (fontStyle) {
            HiPayFontStyle.NORMAL -> FontStyle.Normal
            HiPayFontStyle.ITALIC -> FontStyle.Italic
        },
        fontWeight = when (fontWeight) {
            HiPayFontWeight.REGULAR -> FontWeight.Normal
            HiPayFontWeight.MEDIUM -> FontWeight.Medium
            HiPayFontWeight.SEMIBOLD -> FontWeight.SemiBold
            HiPayFontWeight.BOLD -> FontWeight.Bold
        },
    )

/** M3 disabled-content alpha, applied to style colors for the disabled field states. */
private const val DISABLED_ALPHA = 0.38f

/** Disabled variant of a style color — multiplies the existing alpha (a semi-transparent
 *  style color must get dimmer when disabled, never more opaque). */
internal fun Color.dimmedDisabled(): Color = copy(alpha = alpha * DISABLED_ALPHA)

/**
 * Vertical content padding centering the (~1.5 × fontSize) text line in the field — at
 * Material3's 56/16 geometry this yields exactly its 16dp padding, and it scales with both
 * knobs, flooring at 0 when the line outgrows the field. Units mix by design (dp height,
 * sp font): under large accessibility font scales the line consumes more than 1.5×fontSize
 * dp — the field's min-height constraint (not this padding) is what lets it grow unclipped.
 */
internal fun fieldVerticalPadding(fieldHeight: Float, fontSize: Float): Float =
    ((fieldHeight - fontSize * 1.5f) / 2f).coerceAtLeast(0f)

/**
 * The style's color set for the field decoration. Border colors are real here — the border
 * line itself (with its custom thickness) is drawn by `OutlinedTextFieldDefaults.Container`
 * in [HiPayStyledField]. Remembered per (style, theme defaults): the card fields recompose
 * on every keystroke and the color set is a flat value object.
 */
@Composable
internal fun HiPayCardEntryStyle.fieldColors(): TextFieldColors {
    val defaults = OutlinedTextFieldDefaults.colors()
    return remember(this, defaults) {
        val text = cmpColor(textColor)
        val hint = cmpColor(placeholderColor)
        val border = cmpColor(borderColor)
        val container = cmpColor(backgroundColor)
        val invalid = cmpColor(invalidTextColor)
        defaults.copy(
            focusedTextColor = text,
            unfocusedTextColor = text,
            disabledTextColor = text.dimmedDisabled(),
            cursorColor = text,
            focusedIndicatorColor = border,
            unfocusedIndicatorColor = border,
            disabledIndicatorColor = border.dimmedDisabled(),
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            disabledContainerColor = container,
            focusedPlaceholderColor = hint,
            unfocusedPlaceholderColor = hint,
            disabledPlaceholderColor = hint.dimmedDisabled(),
            focusedLabelColor = hint,
            unfocusedLabelColor = hint,
            disabledLabelColor = hint.dimmedDisabled(),
            // Invalid state: only the border switches to invalidTextColor — text,
            // label, placeholder and container keep their normal style colors (the
            // inline ⚠ message below the field carries the red text).
            errorTextColor = text,
            errorCursorColor = text,
            errorIndicatorColor = invalid,
            errorContainerColor = container,
            errorPlaceholderColor = hint,
            errorLabelColor = hint,
            errorTrailingIconColor = defaults.unfocusedTrailingIconColor,
        )
    }
}

/**
 * The styled card field: `BasicTextField` + `OutlinedTextFieldDefaults.DecorationBox`/
 * `Container` — the documented Material3 route to a custom border thickness and a custom
 * height while keeping the outlined floating-label cutout, focus handling and accessibility
 * semantics that the high-level `OutlinedTextField` does not expose. The focused border
 * thickens by 1dp over [HiPayCardEntryStyle.borderWidth], mirroring Material3's 1dp→2dp
 * focus cue with the style's own border color. `fieldHeight` is applied as a minimum: the
 * field grows when content needs more room (large accessibility font scales must never clip
 * the entered card data).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HiPayStyledField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    placeholder: @Composable () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
) {
    val style = LocalHiPayCardStyle.current
    val interactionSource = remember { MutableInteractionSource() }
    val colors = style.fieldColors()
    // Remembered per (style, enabled): these are rebuilt on every keystroke otherwise.
    // DecorationBox colors only the decorations; the input text itself is drawn by
    // BasicTextField, so the disabled dim is applied to its TextStyle here.
    val textStyle = remember(style, enabled) {
        style.entryTextStyle().let {
            if (enabled) it else it.copy(color = cmpColor(style.textColor).dimmedDisabled())
        }
    }
    val shape = remember(style) { style.fieldShape() }
    val cursorBrush = remember(style) { SolidColor(cmpColor(style.textColor)) }
    // Material3's default 16dp vertical content padding assumes the 56dp field: with a smaller
    // fieldHeight the input line gets clipped — see [fieldVerticalPadding].
    val verticalPadding = fieldVerticalPadding(style.fieldHeight, style.fontSize).dp
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = style.fieldHeight.dp),
        enabled = enabled,
        textStyle = textStyle,
        keyboardOptions = keyboardOptions,
        singleLine = true,
        visualTransformation = visualTransformation,
        interactionSource = interactionSource,
        cursorBrush = cursorBrush,
    ) { innerTextField ->
        OutlinedTextFieldDefaults.DecorationBox(
            value = value,
            innerTextField = innerTextField,
            enabled = enabled,
            singleLine = true,
            visualTransformation = visualTransformation,
            interactionSource = interactionSource,
            label = label,
            placeholder = placeholder,
            trailingIcon = trailingIcon,
            isError = isError,
            colors = colors,
            contentPadding = OutlinedTextFieldDefaults.contentPadding(
                top = verticalPadding,
                bottom = verticalPadding,
            ),
            container = {
                OutlinedTextFieldDefaults.Container(
                    enabled = enabled,
                    isError = isError,
                    interactionSource = interactionSource,
                    colors = colors,
                    shape = shape,
                    focusedBorderThickness = (style.borderWidth + 1f).dp,
                    unfocusedBorderThickness = style.borderWidth.dp,
                )
            },
        )
    }
}
