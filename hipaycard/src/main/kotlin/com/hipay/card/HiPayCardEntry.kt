// PCI (NFR2): com.hipay.card anti-logging path — never log card data here.
package com.hipay.card

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.hipay.card.HiPayCardEntryController.Field
import com.hipay.card.validation.CardEntryStringKey
import java.util.Locale

/** Stable test/semantics tags shared with the UI-test harness (story 7.1/7.2). */
public object HiPayCardEntryTags {
    public const val HOLDER: String = "hipay.card.holder"
    public const val NUMBER: String = "hipay.card.number"
    public const val EXPIRY: String = "hipay.card.expiry"
    public const val CVC: String = "hipay.card.cvc"
    public const val CVC_INFO: String = "hipay.card.cvc.info"
    public const val CVC_TOOLTIP: String = "hipay.card.cvc.tooltip"
    public fun network(code: String): String = "hipay.card.network.$code"
    public fun error(field: String): String = "hipay.card.error.$field"
}

/**
 * The native Jetpack Compose card-entry component, the behavioral mirror of the
 * iOS `HiPayCardEntryView`. Drives [controller] state; consumes the shared
 * commonMain contract. Strings resolve from `strings.xml` (FR/EN/IT, default EN).
 *
 * Accessibility (7.3/7.4): localized labels are the accessible names; relative
 * TalkBack traversal order holder→number→expiry→CVC (opt-out via
 * [setsAccessibilityOrder], D12); inline errors appear on blur as icon+text
 * (non-colour) and are announced via a polite `liveRegion`; the CVV info reveals
 * a tooltip. The "network not authorized" message takes precedence in the number
 * slot. Tokenization keeps the token internal (7.2).
 *
 * Embedding from an XML/Fragment host via `ComposeView`:
 * ```
 * val controller = HiPayCardEntryController(config, allowedNetworks)
 * composeView.setContent { HiPayCardEntry(controller) }
 * ```
 *
 * Lifecycle: if you let the controller own its coroutine scope (the default — no `scope`
 * passed), dispose it when the component leaves composition to avoid leaking it:
 * ```
 * DisposableEffect(controller) { onDispose { controller.dispose() } }
 * ```
 * (No-op if you supplied your own `scope`.)
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
    // Read the Configuration via LocalConfiguration (not base.resources.configuration):
    // it recomposes when the device Configuration changes (lint LocalContextConfigurationRead).
    val baseConfig = LocalConfiguration.current
    val localized = remember(localeOverride, base, baseConfig) {
        val cfg = Configuration(baseConfig).apply { setLocale(Locale.forLanguageTag(localeOverride)) }
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Holder
        FieldGroup(setsAccessibilityOrder, 0f) {
            OutlinedTextField(
                value = controller.holder,
                onValueChange = controller::onHolderChange,
                label = { Text(cardString(CardEntryStringKey.LABEL_HOLDER)) },
                placeholder = { Text(cardString(CardEntryStringKey.PLACEHOLDER_HOLDER)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(HiPayCardEntryTags.HOLDER)
                    .blurring(controller, Field.HOLDER),
            )
            ErrorSlot(controller.holderErrorKey, HiPayCardEntryTags.error("holder"))
        }

        // Card number (+ network chips)
        FieldGroup(setsAccessibilityOrder, 1f) {
            OutlinedTextField(
                value = controller.cardNumber,
                onValueChange = controller::onNumberChange,
                label = { Text(cardString(CardEntryStringKey.LABEL_NUMBER)) },
                placeholder = { Text(cardString(CardEntryStringKey.PLACEHOLDER_NUMBER)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                trailingIcon = { NetworkChips(controller) },
                modifier = Modifier.fillMaxWidth().testTag(HiPayCardEntryTags.NUMBER)
                    .blurring(controller, Field.NUMBER),
            )
            // Network-not-authorized takes precedence over the number's own error (D1).
            ErrorSlot(controller.numberSlotErrorKey, HiPayCardEntryTags.error("number"))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Expiry
            FieldGroup(setsAccessibilityOrder, 2f, Modifier.weight(1f)) {
                OutlinedTextField(
                    value = controller.expiry,
                    onValueChange = controller::onExpiryChange,
                    label = { Text(cardString(CardEntryStringKey.LABEL_EXPIRY)) },
                    placeholder = { Text(cardString(CardEntryStringKey.PLACEHOLDER_EXPIRY)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag(HiPayCardEntryTags.EXPIRY)
                        .blurring(controller, Field.EXPIRY),
                )
                ErrorSlot(controller.expiryErrorKey, HiPayCardEntryTags.error("expiry"))
            }

            // CVC (+ info tooltip when required)
            FieldGroup(setsAccessibilityOrder, 3f, Modifier.weight(1f)) {
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
                    // Info affordance only when the CVC is required (no overlay on a disabled field).
                    trailingIcon = if (controller.isCvcRequired) ({ CvvInfo() }) else null,
                    modifier = Modifier.fillMaxWidth().testTag(HiPayCardEntryTags.CVC)
                        .blurring(controller, Field.CVC),
                )
                ErrorSlot(controller.cvcErrorKey, HiPayCardEntryTags.error("cvc"))
            }
        }
    }
}

/** A field + its error slot, carrying the relative traversal index so the error follows its field. */
@Composable
private fun FieldGroup(
    setsAccessibilityOrder: Boolean,
    index: Float,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.then(
            if (setsAccessibilityOrder) Modifier.semantics { traversalIndex = index } else Modifier,
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

/** Calls `markBlurred(field)` on a focused→unfocused transition (not on first composition). */
@Composable
private fun Modifier.blurring(controller: HiPayCardEntryController, field: Field): Modifier {
    var wasFocused by remember { mutableStateOf(false) }
    return this.onFocusChanged { state ->
        if (wasFocused && !state.isFocused) controller.markBlurred(field)
        wasFocused = state.isFocused
    }
}

/** Inline error: collapses to nothing when [key] is null; icon + text (non-colour); polite live region. */
@Composable
private fun ErrorSlot(key: CardEntryStringKey?, tag: String) {
    if (key == null) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .padding(start = 4.dp)
            .testTag(tag)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        // A glyph (not colour) carries the error meaning for non-colour accessibility (WCAG 1.4.1).
        Text("⚠", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Text(
            text = cardString(key),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
        )
    }
}

/** CVV info affordance + tap-to-reveal tooltip (story 7.4 / iOS 5.6). */
@Composable
private fun CvvInfo() {
    var show by remember { mutableStateOf(false) }
    val tooltip = cardString(CardEntryStringKey.CVV_TOOLTIP)
    Box {
        Text(
            text = "ⓘ", // circled "i"
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .clickable { show = !show }
                .testTag(HiPayCardEntryTags.CVC_INFO)
                .semantics { contentDescription = tooltip },
        )
        if (show) {
            Popup(
                alignment = Alignment.TopEnd,
                onDismissRequest = { show = false },
                properties = PopupProperties(focusable = false),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                ) {
                    Text(
                        text = tooltip,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        modifier = Modifier.widthIn(max = 280.dp).padding(8.dp).testTag(HiPayCardEntryTags.CVC_TOOLTIP),
                    )
                }
            }
        }
    }
}

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
