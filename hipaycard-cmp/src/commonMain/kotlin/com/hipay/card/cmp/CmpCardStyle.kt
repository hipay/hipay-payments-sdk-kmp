// PCI: com.hipay.card path — never log card data here.
package com.hipay.card.cmp

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
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

/**
 * The style's color set for the field decoration. Border colors are real here — the border
 * line itself (with its custom thickness) is drawn by `OutlinedTextFieldDefaults.Container`
 * in [HiPayStyledField].
 */
@Composable
internal fun HiPayCardEntryStyle.fieldColors(): TextFieldColors {
    val text = cmpColor(textColor)
    val hint = cmpColor(placeholderColor)
    val border = cmpColor(borderColor)
    val container = cmpColor(backgroundColor)
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = text,
        unfocusedTextColor = text,
        disabledTextColor = text.copy(alpha = DISABLED_ALPHA),
        cursorColor = text,
        focusedBorderColor = border,
        unfocusedBorderColor = border,
        disabledBorderColor = border.copy(alpha = DISABLED_ALPHA),
        focusedContainerColor = container,
        unfocusedContainerColor = container,
        disabledContainerColor = container,
        focusedPlaceholderColor = hint,
        unfocusedPlaceholderColor = hint,
        disabledPlaceholderColor = hint.copy(alpha = DISABLED_ALPHA),
        focusedLabelColor = hint,
        unfocusedLabelColor = hint,
        disabledLabelColor = hint.copy(alpha = DISABLED_ALPHA),
    )
}

/**
 * The styled card field: `BasicTextField` + `OutlinedTextFieldDefaults.DecorationBox`/
 * `Container` — the documented Material3 route to a custom border thickness and a fixed
 * height while keeping the outlined floating-label cutout, focus handling and accessibility
 * semantics that the high-level `OutlinedTextField` does not expose. The focused border
 * thickens by 1dp over [HiPayCardEntryStyle.borderWidth], mirroring Material3's 1dp→2dp
 * focus cue with the style's own border color.
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
) {
    val style = LocalHiPayCardStyle.current
    val interactionSource = remember { MutableInteractionSource() }
    val colors = style.fieldColors()
    // DecorationBox colors only the decorations; the input text itself is drawn by
    // BasicTextField, so the disabled dim is applied to its TextStyle here.
    val textStyle = style.entryTextStyle().let {
        if (enabled) it else it.copy(color = cmpColor(style.textColor).copy(alpha = DISABLED_ALPHA))
    }
    // Material3's default 16dp vertical content padding assumes the 56dp field: with a smaller
    // fieldHeight the input line gets clipped. Center the (~1.5 × fontSize) text line in the
    // field instead — at 56/16 this yields exactly M3's 16dp, and it scales with both knobs.
    // Very large a11y font scales can still outgrow a deliberately small fieldHeight.
    val verticalPadding = ((style.fieldHeight - style.fontSize * 1.5f) / 2f).coerceAtLeast(0f).dp
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.height(style.fieldHeight.dp),
        enabled = enabled,
        textStyle = textStyle,
        keyboardOptions = keyboardOptions,
        singleLine = true,
        visualTransformation = visualTransformation,
        interactionSource = interactionSource,
        cursorBrush = SolidColor(cmpColor(style.textColor)),
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
            colors = colors,
            contentPadding = OutlinedTextFieldDefaults.contentPadding(
                top = verticalPadding,
                bottom = verticalPadding,
            ),
            container = {
                OutlinedTextFieldDefaults.Container(
                    enabled = enabled,
                    isError = false,
                    interactionSource = interactionSource,
                    colors = colors,
                    shape = style.fieldShape(),
                    focusedBorderThickness = (style.borderWidth + 1f).dp,
                    unfocusedBorderThickness = style.borderWidth.dp,
                )
            },
        )
    }
}
