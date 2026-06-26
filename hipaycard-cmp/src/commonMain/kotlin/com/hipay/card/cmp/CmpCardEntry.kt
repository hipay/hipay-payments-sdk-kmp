// PCI (NFR2): com.hipay.card path — never log card data here.
package com.hipay.card.cmp

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hipay.card.validation.CardEntryStringKey
import com.hipay.card.validation.CardNetwork
import org.jetbrains.compose.resources.painterResource

/**
 * Shared Compose-Multiplatform card-entry UI (story 10.2, slice A) — rendered on iOS via the
 * iOS `actual` (and reusable on Android). Mirrors the Android `:hipaycard` `HiPayCardEntry`
 * fields-only contract: it renders the entry fields + inline errors; the host owns the pay
 * button and calls [CmpCardController.pay].
 *
 * Slice-A: functional fields, local network chips (text), inline errors on blur, CVC shown-
 * disabled when not required. Strings are EN ([cmpString]); FR/EN/IT + a11y/tooltip = slice B.
 */
@Composable
internal fun CmpCardEntry(
    controller: CmpCardController,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") setsAccessibilityOrder: Boolean = true,
    @Suppress("UNUSED_PARAMETER") localeOverride: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Holder
        OutlinedTextField(
            value = controller.holder,
            onValueChange = controller::onHolderChange,
            label = { FieldLabel(cmpString(CardEntryStringKey.LABEL_HOLDER)) },
            placeholder = { Text(cmpString(CardEntryStringKey.PLACEHOLDER_HOLDER)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorText(controller.holderErrorKey)

        // Card number (+ local network chips)
        OutlinedTextField(
            value = controller.cardNumber,
            onValueChange = controller::onNumberChange,
            label = { FieldLabel(cmpString(CardEntryStringKey.LABEL_NUMBER)) },
            placeholder = { Text(cmpString(CardEntryStringKey.PLACEHOLDER_NUMBER)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            // Raw digits as value; spaces rendered by the transformation → caret stays correct (11.1).
            visualTransformation = CardNumberVisualTransformation(controller.network),
            modifier = Modifier.fillMaxWidth(),
        )
        NetworkChips(controller)
        // Network-not-authorized takes precedence over the number's own error (D1).
        ErrorText(controller.numberSlotErrorKey)

        // The CVV info text is toggled by the "ⓘ" and rendered full width below the row (11.2).
        var showCvvInfo by remember { mutableStateOf(false) }
        // Reset the help when CVC stops being required so it never re-shows unprompted on return (review 11.2).
        LaunchedEffect(controller.isCvcRequired) { if (!controller.isCvcRequired) showCvvInfo = false }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Expiry
            Column(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = controller.expiry,
                    onValueChange = controller::onExpiryChange,
                    label = { FieldLabel(cmpString(CardEntryStringKey.LABEL_EXPIRY)) },
                    placeholder = { Text(cmpString(CardEntryStringKey.PLACEHOLDER_EXPIRY)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    // Raw digits as value; "/" rendered by the transformation → caret stays correct (11.8).
                    visualTransformation = ExpiryVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // CVC — shown-disabled when not required; "ⓘ" toggles the full-width info text (11.2).
            Column(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = controller.cvc,
                    onValueChange = controller::onCvcChange,
                    enabled = controller.isCvcRequired,
                    label = { FieldLabel(cmpString(CardEntryStringKey.LABEL_CVV)) },
                    placeholder = { Text(cmpString(CardEntryStringKey.PLACEHOLDER_CVV)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    trailingIcon = if (controller.isCvcRequired) ({
                        val cvvHelp = cmpString(CardEntryStringKey.CVV_TOOLTIP)
                        Text(
                            "ⓘ",
                            modifier = Modifier
                                .semantics { contentDescription = cvvHelp }
                                .clickable { showCvvInfo = !showCvvInfo },
                        )
                    }) else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        // Expiry/CVC errors full width below the row (11.2), between the fields and the info.
        ErrorText(controller.expiryErrorKey)
        ErrorText(controller.cvcErrorKey)
        // CVV help as a full-width inline text (no popup, 11.2), toggled by "ⓘ".
        if (controller.isCvcRequired && showCvvInfo) {
            Text(
                text = cmpString(CardEntryStringKey.CVV_TOOLTIP),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Field label forced onto a single line at the smaller (floating) type size (story 11.3) — keeps
 * longer localized CVC labels from wrapping to two lines and inflating the field height. Mirrors
 * the Android `:hipaycard` `FieldLabel`.
 */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun NetworkChips(controller: CmpCardController) {
    // Show a brand icon whenever a network is offered — including a single one (11.4); a dimmed
    // neutral card when none. Mirrors the Android `:hipaycard` NetworkChips (icons, 48dp tap,
    // selected full / others 0.35, merged semantics).
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val nets: List<CardNetwork> = controller.networks
        if (nets.isEmpty()) {
            Image(
                painter = painterResource(neutralCardIcon),
                contentDescription = null, // decorative
                modifier = Modifier.size(width = 32.dp, height = 20.dp).alpha(0.35f),
            )
        } else {
            nets.forEach { net ->
                val isSel = net == controller.selectedNetwork
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .clickable { controller.selectNetwork(net) }
                        // One merged node so screen readers announce "<brand>, selected".
                        .semantics(mergeDescendants = true) {
                            contentDescription = net.displayName()
                            selected = isSel
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(net.iconResource()),
                        contentDescription = null, // described by the parent node
                        modifier = Modifier.size(width = 32.dp, height = 20.dp)
                            .alpha(if (isSel) 1f else 0.35f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorText(key: CardEntryStringKey?) {
    if (key != null) {
        Text(text = cmpString(key), color = MaterialTheme.colorScheme.error)
    }
}
