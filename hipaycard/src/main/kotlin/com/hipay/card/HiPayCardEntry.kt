// PCI (NFR2): com.hipay.card anti-logging path — never log card data here.
package com.hipay.card

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hipay.card.validation.CardEntryStringKey

/** Stable test/semantics tags shared with the UI-test harness (story 7.1/7.2). */
public object HiPayCardEntryTags {
    public const val HOLDER: String = "hipay.card.holder"
    public const val NUMBER: String = "hipay.card.number"
    public const val EXPIRY: String = "hipay.card.expiry"
    public const val CVC: String = "hipay.card.cvc"
    public fun network(code: String): String = "hipay.card.network.$code"
}

/**
 * The native Jetpack Compose card-entry component (story 7.2), the behavioral
 * mirror of the iOS `HiPayCardEntryView`. Drives [controller] state; consumes
 * the shared commonMain contract. Inline error UI, the CVV tooltip and the
 * "network not authorized" message are story 7.4; deep TalkBack semantics and
 * FR/EN/IT localization are story 7.3 (strings are temporary English here).
 *
 * Embedding from an XML/Fragment host via `ComposeView`:
 * ```
 * val controller = HiPayCardEntryController(config, allowedNetworks)
 * composeView.setContent { HiPayCardEntry(controller) }
 * // call controller.pay(...) from a coroutine when the host's Pay button is tapped.
 * ```
 */
@Composable
public fun HiPayCardEntry(
    controller: HiPayCardEntryController,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = controller.holder,
            onValueChange = controller::onHolderChange,
            label = { Text(s(CardEntryStringKey.LABEL_HOLDER)) },
            placeholder = { Text(s(CardEntryStringKey.PLACEHOLDER_HOLDER)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(HiPayCardEntryTags.HOLDER),
        )

        OutlinedTextField(
            value = controller.cardNumber,
            onValueChange = controller::onNumberChange,
            label = { Text(s(CardEntryStringKey.LABEL_NUMBER)) },
            placeholder = { Text(s(CardEntryStringKey.PLACEHOLDER_NUMBER)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            trailingIcon = { NetworkChips(controller) },
            modifier = Modifier.fillMaxWidth().testTag(HiPayCardEntryTags.NUMBER),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = controller.expiry,
                onValueChange = controller::onExpiryChange,
                label = { Text(s(CardEntryStringKey.LABEL_EXPIRY)) },
                placeholder = { Text(s(CardEntryStringKey.PLACEHOLDER_EXPIRY)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).testTag(HiPayCardEntryTags.EXPIRY),
            )

            val cvcLabel =
                if (controller.isCvcRequired) s(CardEntryStringKey.LABEL_CVV)
                else "${s(CardEntryStringKey.LABEL_CVV)} (${s(CardEntryStringKey.CVV_OPTIONAL)})"
            OutlinedTextField(
                value = controller.cvc,
                onValueChange = controller::onCvcChange,
                label = { Text(cvcLabel) },
                placeholder = { Text(s(CardEntryStringKey.PLACEHOLDER_CVV)) },
                singleLine = true,
                enabled = controller.isCvcRequired,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).testTag(HiPayCardEntryTags.CVC),
            )
        }
    }
}

/** Tappable network/co-brand chips (right of the number field). Neutral = decorative when empty. */
@Composable
private fun NetworkChips(controller: HiPayCardEntryController) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val nets = controller.networks
        if (nets.isEmpty()) {
            Image(
                painter = painterResource(R.drawable.hp_card_neutral),
                contentDescription = null, // decorative
                modifier = Modifier.size(width = 32.dp, height = 20.dp).alpha(0.35f),
            )
        } else {
            nets.forEach { net ->
                val selected = net == controller.selectedNetwork
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .clickable { controller.selectNetwork(net) }
                        .testTag(HiPayCardEntryTags.network(net.code))
                        .semantics { this.selected = selected },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(net.drawableRes),
                        contentDescription = net.displayName,
                        modifier = Modifier.size(width = 32.dp, height = 20.dp)
                            .alpha(if (selected) 1f else 0.35f),
                    )
                }
            }
        }
    }
}

private fun s(key: CardEntryStringKey): String = HiPayCardStrings.get(key)
