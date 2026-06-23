// PCI (NFR2): com.hipay.card path — never log card data here.
package com.hipay.card.cmp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hipay.card.validation.CardEntryStringKey
import com.hipay.card.validation.CardNetwork

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
            label = { Text(cmpString(CardEntryStringKey.LABEL_HOLDER)) },
            placeholder = { Text(cmpString(CardEntryStringKey.PLACEHOLDER_HOLDER)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorText(controller.holderErrorKey)

        // Card number (+ local network chips)
        OutlinedTextField(
            value = controller.cardNumber,
            onValueChange = controller::onNumberChange,
            label = { Text(cmpString(CardEntryStringKey.LABEL_NUMBER)) },
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

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Expiry
            Column(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = controller.expiry,
                    onValueChange = controller::onExpiryChange,
                    label = { Text(cmpString(CardEntryStringKey.LABEL_EXPIRY)) },
                    placeholder = { Text(cmpString(CardEntryStringKey.PLACEHOLDER_EXPIRY)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(controller.expiryErrorKey)
            }
            // CVC — shown-disabled when the network does not require it (e.g. Maestro).
            Column(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = controller.cvc,
                    onValueChange = controller::onCvcChange,
                    enabled = controller.isCvcRequired,
                    label = { Text(cmpString(CardEntryStringKey.LABEL_CVV)) },
                    placeholder = { Text(cmpString(CardEntryStringKey.PLACEHOLDER_CVV)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(controller.cvcErrorKey)
            }
        }
    }
}

@Composable
private fun NetworkChips(controller: CmpCardController) {
    if (controller.networks.size <= 1) return // single/none → no co-brand choice to offer
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        controller.networks.forEach { net: CardNetwork ->
            val selected = net == controller.selectedNetwork
            Text(
                text = net.name,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clickable { controller.selectNetwork(net) },
            )
        }
    }
}

@Composable
private fun ErrorText(key: CardEntryStringKey?) {
    if (key != null) {
        Text(text = cmpString(key), color = MaterialTheme.colorScheme.error)
    }
}
