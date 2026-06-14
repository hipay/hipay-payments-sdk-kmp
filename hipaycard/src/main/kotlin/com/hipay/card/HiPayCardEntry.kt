// PCI (NFR2): com.hipay.card anti-logging path — never log card data here.
package com.hipay.card

import android.content.res.Configuration
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hipay.card.validation.CardEntryStringKey
import java.util.Locale

/** Stable test/semantics tags shared with the UI-test harness (story 7.1/7.2). */
public object HiPayCardEntryTags {
    public const val HOLDER: String = "hipay.card.holder"
    public const val NUMBER: String = "hipay.card.number"
    public const val EXPIRY: String = "hipay.card.expiry"
    public const val CVC: String = "hipay.card.cvc"
    public fun network(code: String): String = "hipay.card.network.$code"
}

/**
 * The native Jetpack Compose card-entry component, the behavioral mirror of the
 * iOS `HiPayCardEntryView`. Drives [controller] state; consumes the shared
 * commonMain contract. Strings resolve from `strings.xml` (FR/EN/IT, default EN).
 *
 * Accessibility (story 7.3): each field exposes its localized label as the
 * accessible name; the network chips are accessible buttons announcing the brand
 * + selected state; the component sets the RELATIVE TalkBack traversal order of
 * its own fields (holder → number → expiry → CVC) via [traversalIndex] inside a
 * traversal group — unless [setsAccessibilityOrder] is false, in which case the
 * host controls order (D12: relative only, never absolute).
 *
 * Inline error UI, the CVV tooltip, the "network not authorized" message and
 * polite error announcements are story 7.4.
 *
 * Embedding from an XML/Fragment host via `ComposeView`:
 * ```
 * val controller = HiPayCardEntryController(config, allowedNetworks)
 * composeView.setContent { HiPayCardEntry(controller) }
 * ```
 */
@Composable
public fun HiPayCardEntry(
    controller: HiPayCardEntryController,
    modifier: Modifier = Modifier,
    setsAccessibilityOrder: Boolean = true,
    /** Optional ISO language override ("fr"/"en"/"it"); null → device locale (D11). */
    localeOverride: String? = null,
) {
    if (localeOverride == null) {
        CardEntryContent(controller, modifier, setsAccessibilityOrder)
        return
    }
    val base = LocalContext.current
    val localized = remember(localeOverride, base) {
        val cfg = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(localeOverride)) }
        base.createConfigurationContext(cfg)
    }
    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides localized.resources.configuration,
    ) {
        CardEntryContent(controller, modifier, setsAccessibilityOrder)
    }
}

@Composable
private fun CardEntryContent(
    controller: HiPayCardEntryController,
    modifier: Modifier,
    setsAccessibilityOrder: Boolean,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .then(if (setsAccessibilityOrder) Modifier.semantics { isTraversalGroup = true } else Modifier),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = controller.holder,
            onValueChange = controller::onHolderChange,
            label = { Text(cardString(CardEntryStringKey.LABEL_HOLDER)) },
            placeholder = { Text(cardString(CardEntryStringKey.PLACEHOLDER_HOLDER)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
                .testTag(HiPayCardEntryTags.HOLDER)
                .order(setsAccessibilityOrder, 0f),
        )

        OutlinedTextField(
            value = controller.cardNumber,
            onValueChange = controller::onNumberChange,
            label = { Text(cardString(CardEntryStringKey.LABEL_NUMBER)) },
            placeholder = { Text(cardString(CardEntryStringKey.PLACEHOLDER_NUMBER)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            trailingIcon = { NetworkChips(controller) },
            modifier = Modifier.fillMaxWidth()
                .testTag(HiPayCardEntryTags.NUMBER)
                .order(setsAccessibilityOrder, 1f),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = controller.expiry,
                onValueChange = controller::onExpiryChange,
                label = { Text(cardString(CardEntryStringKey.LABEL_EXPIRY)) },
                placeholder = { Text(cardString(CardEntryStringKey.PLACEHOLDER_EXPIRY)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
                    .testTag(HiPayCardEntryTags.EXPIRY)
                    .order(setsAccessibilityOrder, 2f),
            )

            val cvcLabel =
                if (controller.isCvcRequired) cardString(CardEntryStringKey.LABEL_CVV)
                else "${cardString(CardEntryStringKey.LABEL_CVV)} (${cardString(CardEntryStringKey.CVV_OPTIONAL)})"
            OutlinedTextField(
                value = controller.cvc,
                onValueChange = controller::onCvcChange,
                label = { Text(cvcLabel) },
                placeholder = { Text(cardString(CardEntryStringKey.PLACEHOLDER_CVV)) },
                singleLine = true,
                enabled = controller.isCvcRequired,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
                    .testTag(HiPayCardEntryTags.CVC)
                    .order(setsAccessibilityOrder, 3f),
            )
        }
    }
}

/** Relative traversal order (D12): lower index announced earlier; no-op when opted out. */
private fun Modifier.order(enabled: Boolean, index: Float): Modifier =
    if (enabled) this.semantics { traversalIndex = index } else this

/** Tappable network/co-brand chips. Each is one focusable a11y node: "<brand>, selected". */
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
                val isSel = net == controller.selectedNetwork
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .clickable { controller.selectNetwork(net) }
                        .testTag(HiPayCardEntryTags.network(net.code))
                        // One merged node so TalkBack announces "<brand>, selected".
                        .semantics(mergeDescendants = true) {
                            contentDescription = net.displayName
                            selected = isSel
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(net.drawableRes),
                        contentDescription = null, // described by the parent node
                        modifier = Modifier.size(width = 32.dp, height = 20.dp)
                            .alpha(if (isSel) 1f else 0.35f),
                    )
                }
            }
        }
    }
}
