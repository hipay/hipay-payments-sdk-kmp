// PCI: com.hipay.card path — never log card data here.
package com.hipay.card.cmp

import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
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

/**
 * The palette actually used by the component.
 *
 * A style the integrator did NOT customise resolves from the HOST's Material theme, so the default
 * look follows light/dark on its own: an embedded payment form has no business painting an opaque
 * white box on a dark screen, and the floating label straddling the field's top edge made that seam
 * impossible to miss. Because the whole palette is derived, the texts drawn OUTSIDE the field — the
 * save-card label, the consent line, the section headers — become readable on a dark host too; they
 * were painted with `textColor`, a near-black chosen for the light field, and vanished.
 *
 * A CUSTOMISED style is honoured verbatim. Once an integrator sets colours, adapting them per theme
 * is their call: second-guessing it would fight their branding, and they are the only ones who know
 * what their surface looks like. The comparison is on value equality — passing `hipayDefault`
 * explicitly means "give me the default look", which is now the theme-aware one.
 */
@Composable
internal fun resolveCardStyle(requested: HiPayCardEntryStyle): HiPayCardEntryStyle {
    if (requested != HiPayCardEntryStyle.hipayDefault) return requested
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        fun Color.argb(): Long = toArgb().toLong() and 0xFFFFFFFFL
        HiPayCardEntryStyle.hipayDefault.copy(
            textColor = scheme.onSurface.argb(),
            placeholderColor = scheme.onSurfaceVariant.argb(),
            iconColor = scheme.onSurfaceVariant.argb(),
            invalidTextColor = scheme.error.argb(),
            borderColor = scheme.outline.argb(),
            // `surfaceContainerHighest`, not `surface`: the latter is usually the exact colour of the
            // screen behind the component, which leaves the field indistinguishable from it but for
            // its border. This is also Material3's own container choice for a text field.
            backgroundColor = scheme.surfaceContainerHighest.argb(),
        )
    }
}


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

/** The floated label's share of the entered-text size — Material3's own bodySmall-to-body proportion
 *  (12sp against a 16sp body), so the label reads as a label at any `fontSize`. */
private const val LABEL_FLOATED_RATIO = 0.75f

/** Material3's content-padding start, which is where both the resting and the floated label sit, so
 *  the float is purely vertical and the text never slides sideways. */
private val LABEL_START_PADDING = 16.dp

/** The floated label's line box at the default `fontSize` — 16sp × [LABEL_FLOATED_RATIO] ⇒ a 12sp
 *  line, which measures about this. */
private val FLOATED_LABEL_LINE = 16.dp

/**
 * Gap kept between the bottom of the floated label and the field's border.
 *
 * This is the knob for how high the label lands: the label is drawn at the top of the reserve, so the
 * clearance is what stands between it and the border. At 0 the label sits flush on the border, which
 * reads as resting on it rather than floating above it. Every dp here also adds a dp to the row's
 * total height and half a dp to the trailing overlays' recentring, both of which follow automatically
 * from [FLOATING_LABEL_RESERVE].
 */
private val LABEL_BORDER_CLEARANCE = 4.dp

/**
 * Room kept ABOVE the field's border for the floated label to land in.
 *
 * The label lands above the border rather than on it. A label centred on the border spans the page
 * above it and the field below, and nothing put behind it hides that it sits on two backgrounds at
 * once: a fill bands, a backdrop leaves a tab above the edge, and rounding that backdrop exposes the
 * page in its upper corners (an outline can only remove area). Landing clear of the border removes the
 * straddle instead of painting over it.
 *
 * Sized for the floated line at the default `fontSize` and deliberately NOT derived from the style's
 * own — [overlaidOnFieldInput] needs it as a constant to recentre the trailing overlays. A much larger
 * `fontSize` therefore floats a label taller than its landing area, which reads as a tighter gap
 * rather than as clipping (the reserve is padding, not a clip).
 */
internal val FLOATING_LABEL_RESERVE = FLOATED_LABEL_LINE + LABEL_BORDER_CLEARANCE

/**
 * The field's placeholder, drawn by the SDK on the input line.
 *
 * Material's own placeholder slot is left empty. Its visibility is driven by an opacity transition
 * keyed on the framework's input phase, and in this configuration — no label in the label slot, the
 * label drawn as a sibling — it did not surface. Rather than depend on a mechanism that cannot be
 * observed from the test suites available here (the CMP tests are plain unit tests with no Compose UI
 * runtime), the rule is written out: show it when the label has left the input line and the field is
 * empty. That also reads the RAW value rather than the visually transformed one, so a field whose
 * transformation formats its content is judged on what the payer actually typed.
 *
 * Positioned by CENTRING it exactly where the entered text will appear, not by padding it down from
 * the top: Material centres a `singleLine` input vertically in the field
 * (`Alignment.CenterVertically`), so a fixed top offset only coincides with it at one field height and
 * drifts as soon as the field grows — under a large font scale, for instance. `matchParentSize` minus
 * the label's landing area is exactly the container's box, and centring in it puts the placeholder on
 * the same baseline as the value that replaces it.
 *
 * Styled through the composition locals so the call sites keep passing a bare `Text`: the style's
 * entered-text typography, in [HiPayCardEntryStyle.placeholderColor]. Same typography as the input, so
 * the two also share their metrics.
 */
@Composable
private fun BoxScope.FieldPlaceholder(placeholder: @Composable () -> Unit) {
    val style = LocalHiPayCardStyle.current
    CompositionLocalProvider(
        LocalContentColor provides cmpColor(style.placeholderColor),
        LocalTextStyle provides style.entryTextStyle(style.placeholderColor),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(top = FLOATING_LABEL_RESERVE, start = LABEL_START_PADDING),
            contentAlignment = Alignment.CenterStart,
        ) { placeholder() }
    }
}

/**
 * The field's label, animating between resting INSIDE the input box and floated ABOVE the border.
 *
 * Rendered here rather than through Material's label slot: that slot centres the floated label on the
 * container's top border, which is exactly the straddle to avoid, and material3 does not expose the
 * float progress to it (`TextFieldLabelPosition` — which would do all of this natively — lands in a
 * later version than the one resolved here). So the progress is animated locally and both the offset
 * and the text size are interpolated from it.
 *
 * `maxLines = 1` + no soft-wrap protect the field height: a label longer than its field would
 * otherwise wrap and inflate that field, breaking the Expiry/CVC row symmetry. It cannot wrap at any
 * size — a label too wide overflows horizontally instead. The narrow CVC field is the one to watch,
 * which is also why its label is the "CVV" acronym in every language.
 */
@Composable
private fun FloatingFieldLabel(
    text: String,
    floated: Boolean,
    enabled: Boolean,
    restingTop: Dp,
) {
    val style = LocalHiPayCardStyle.current
    val target = if (floated) 1f else 0f
    // WCAG 2.3.3: no travel at all when the payer asked for reduced motion — the label jumps between
    // the two positions instead of sliding. Nothing is removed, only the animation.
    val progress = if (reduceMotionEnabled()) target else animateFloatAsState(target).value
    val color = cmpColor(style.placeholderColor).let { if (enabled) it else it.dimmedDisabled() }
    Text(
        text = text,
        color = color,
        fontSize = (style.fontSize + (style.fontSize * LABEL_FLOATED_RATIO - style.fontSize) * progress).sp,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
        modifier = Modifier.padding(start = LABEL_START_PADDING, top = lerp(restingTop, 0.dp, progress)),
    )
}

/**
 * Overlays a control on the trailing edge of a [HiPayStyledField]'s INPUT BOX, reporting zero height
 * so the field keeps its compact `fieldHeight` instead of growing to fit the overlay.
 *
 * The half-reserve shift is what makes it land on the input box and not on the whole field: the CMP
 * field pads [FLOATING_LABEL_RESERVE] above its border to keep skiko from clipping the floated label,
 * and that padding counts in the measured height — so a plain `align(CenterEnd)` centres on
 * `reserve + fieldHeight`, i.e. half a reserve too high. The native Android field has no such reserve,
 * which is why it needs no equivalent and why the two surfaces drifted apart visually.
 */
internal fun Modifier.overlaidOnFieldInput(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val recentre = FLOATING_LABEL_RESERVE.roundToPx() / 2
    layout(placeable.width, 0) { placeable.place(0, -placeable.height / 2 + recentre) }
}

/**
 * The styled card field: `BasicTextField` + `OutlinedTextFieldDefaults.DecorationBox`/
 * `Container` — the documented Material3 route to a custom border thickness and a custom
 * height while keeping the focus handling and accessibility semantics that the high-level
 * `OutlinedTextField` does not expose. No border cutout is drawn: the label slot is always empty and
 * [FloatingFieldLabel] paints the label clear of the border. The focused border
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
    label: String,
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
    // Material floats the label on focus or on non-empty content, and does not expose that state to
    // the label slot — but the field knows both.
    val isFocused by interactionSource.collectIsFocusedAsState()
    val labelFloated = isFocused || value.isNotEmpty()
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
    // The label overlays the field rather than living inside its decoration, so the two are wrapped.
    // The caller's `modifier` stays ON the text field: it carries the test tag, the focus requester and
    // the blur tracking, all of which must resolve to the input node, not to the wrapper.
    Box {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        // The reserve is padding OUTSIDE the container, so the border sits below it and the floated
        // label has somewhere to land clear of it — see [FLOATING_LABEL_RESERVE].
        modifier = modifier
            .padding(top = FLOATING_LABEL_RESERVE)
            .heightIn(min = style.fieldHeight.dp)
            // The accessible name. Material's label slot used to feed it to this node's merged
            // semantics; the label is now a sibling, so the name is set here explicitly. Without it a
            // field the payer has typed into would be an unnamed edit box.
            .semantics { contentDescription = label },
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
            // No label slot: [FloatingFieldLabel] draws it. Material would centre it on the
            // container's top border, which is the straddle this whole arrangement exists to avoid.
            label = null,
            // Empty slot: [FieldPlaceholder] draws it, on the same explicit terms as the label.
            placeholder = null,
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
    // Before the label so the label wins the overlap while the two cross during the float.
    if (labelFloated && value.isEmpty()) {
        FieldPlaceholder(placeholder)
    }
    FloatingFieldLabel(
        text = label,
        floated = labelFloated,
        enabled = enabled,
        restingTop = FLOATING_LABEL_RESERVE + verticalPadding,
    )
    }
}
