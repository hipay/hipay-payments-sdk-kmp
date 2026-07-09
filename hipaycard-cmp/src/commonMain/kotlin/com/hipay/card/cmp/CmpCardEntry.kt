// PCI (NFR2): com.hipay.card path — never log card data here.
package com.hipay.card.cmp

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hipay.card.store.SavedCard
import com.hipay.card.store.savedCardDisplay
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
    // Lock all fields while a payment is in flight — driven by the SDK (story 11.14); no host param.
    val enabled = !controller.isProcessing
    // With a saved card selected, the entry fields are not rendered — their values stay in the
    // controller (nothing is cleared until a payment succeeds).
    val showEntryFields = !(controller.oneClickEnabled && controller.selectedSavedCard != null)
    // Focus auto-advance on field completion (story 11.10, parity with iOS + Android). Keyed on
    // the completion booleans → fires only on the incomplete→complete edge. Gated on
    // showEntryFields: the FocusRequesters are attached only to the composed entry fields, so
    // requesting focus while a saved card is selected (fields out of composition) would crash.
    val expiryFocus = remember { FocusRequester() }
    val cvcFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(controller.isNumberComplete) {
        if (showEntryFields && controller.isNumberComplete) expiryFocus.requestFocus()
    }
    LaunchedEffect(controller.isExpiryComplete) {
        if (showEntryFields && controller.isExpiryComplete) {
            if (controller.isCvcRequired) cvcFocus.requestFocus() else focusManager.clearFocus()
        }
    }
    val cvcJustFilled = controller.isCvcRequired && controller.cvc.length == controller.cvcMaxLength
    LaunchedEffect(cvcJustFilled) { if (showEntryFields && cvcJustFilled) focusManager.clearFocus() }

    // One-click: load the saved card on composition (no-op unless opted in — fail-soft).
    if (controller.oneClickEnabled) {
        LaunchedEffect(controller) { controller.refreshSavedCards() }
    }

    Column(
        // Animate the expand/collapse only when one-click is on — an opted-out integrator must
        // see no new animation of pre-existing size changes (errors, tooltip).
        modifier = modifier.fillMaxWidth().padding(16.dp)
            .then(if (controller.oneClickEnabled) Modifier.animateContentSize() else Modifier),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (controller.oneClickEnabled && controller.savedCards.isNotEmpty()) {
            CmpSavedCardsSections(controller, enabled)
        }
        if (showEntryFields) {
        // Holder
        OutlinedTextField(
            value = controller.holder,
            onValueChange = controller::onHolderChange,
            label = { FieldLabel(cmpString(CardEntryStringKey.LABEL_HOLDER)) },
            placeholder = { Text(cmpString(CardEntryStringKey.PLACEHOLDER_HOLDER)) },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorText(controller.holderErrorKey)

        // Card number — the network chips sit INSIDE the field as the trailing icon (right-aligned),
        // matching native Android/iOS (parity); not stacked below the field.
        OutlinedTextField(
            value = controller.cardNumber,
            onValueChange = controller::onNumberChange,
            label = { FieldLabel(cmpString(CardEntryStringKey.LABEL_NUMBER)) },
            placeholder = { Text(cmpString(CardEntryStringKey.PLACEHOLDER_NUMBER)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            enabled = enabled,
            // Raw digits as value; spaces rendered by the transformation → caret stays correct (11.1).
            visualTransformation = CardNumberVisualTransformation(controller.network),
            trailingIcon = { NetworkChips(controller) },
            modifier = Modifier.fillMaxWidth(),
        )
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
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    // Raw digits as value; "/" rendered by the transformation → caret stays correct (11.8).
                    visualTransformation = ExpiryVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().focusRequester(expiryFocus),
                )
            }
            // CVC — shown-disabled when not required; "ⓘ" toggles the full-width info text (11.2).
            Column(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = controller.cvc,
                    onValueChange = controller::onCvcChange,
                    enabled = enabled && controller.isCvcRequired,
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
                    modifier = Modifier.fillMaxWidth().focusRequester(cvcFocus),
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
        // In-frame save switch + one-line consent — the new-card branch of one-click only.
        if (controller.oneClickEnabled) CmpSaveCardSwitch(controller, enabled)
        } // showEntryFields
    }
}

/**
 * The two one-click zones sharing one section-header treatment: "Saved cards" (the list of ≤3
 * saved cards, most-recent first) and "New card" (an actionable header whose chevron shows the
 * expanded state). The cells form a single-selection group — exactly one selection at all times
 * (a card, or "New card"); no visual radio indicator by design.
 *
 * While the new-card branch is active the list collapses to just the most-recent card, and the
 * "Saved cards" header gains its own chevron to re-expand the full list. With a single saved card
 * there is no collapse affordance.
 */
@Composable
private fun CmpSavedCardsSections(controller: CmpCardController, enabled: Boolean) {
    val cards = controller.savedCards
    if (cards.isEmpty()) return
    var savedCardsExpanded by rememberSaveable { mutableStateOf(false) }
    val newCardBranch = controller.selectedSavedCard == null
    // Each fresh new-card entry starts collapsed: forget a manual re-expand once the branch is left,
    // so the collapse-to-MRU behaviour never silently stops after the payer expands the list once.
    LaunchedEffect(newCardBranch) { if (!newCardBranch) savedCardsExpanded = false }
    val collapsible = newCardBranch && cards.size > 1
    val showAllCards = !newCardBranch || savedCardsExpanded
    val visibleCards = if (showAllCards) cards else cards.take(1)

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().selectableGroup(),
    ) {
        if (collapsible) {
            CmpSavedCardsCollapsibleHeader(expanded = savedCardsExpanded, enabled = enabled) {
                savedCardsExpanded = !savedCardsExpanded
            }
        } else {
            CmpSectionHeader(cmpString(CardEntryStringKey.LABEL_SAVED_CARDS))
        }
        visibleCards.forEach { card ->
            CmpSavedCardCell(controller, card, enabled)
        }
        CmpNewCardHeader(controller, enabled)
    }
}

/** One saved-card cell: 2-line masked display, border-only selection (no radio), merged a11y node. */
@Composable
private fun CmpSavedCardCell(controller: CmpCardController, card: SavedCard, enabled: Boolean) {
    val display = remember(card) { savedCardDisplay(card) }
    val a11yLabel = cmpFormat(
        cmpString(CardEntryStringKey.A11Y_SAVED_CARD),
        display.network?.displayName() ?: card.network,
        display.last4,
        display.displayExpiry,
    )
    val selected = controller.selectedSavedCard == card
    val border =
        if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .border(border, RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(10.dp),
            )
            .selectable(selected = selected, enabled = enabled) { controller.selectSavedCard(card) }
            // One merged node: "<Network> finishing 1111, expires MM / YYYY, selected" —
            // the bullet glyphs are never announced.
            .semantics(mergeDescendants = true) { contentDescription = a11yLabel }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Image(
            painter = painterResource(display.network?.iconResource() ?: neutralCardIcon),
            contentDescription = null, // described by the merged cell node
            modifier = Modifier.size(width = 32.dp, height = 20.dp),
        )
        Column {
            Text(display.maskedNumber, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "${card.holder}  ·  ${display.displayExpiry}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "New card": an actionable BUTTON whose expanded/collapsed state carries the meaning. */
@Composable
private fun CmpNewCardHeader(controller: CmpCardController, enabled: Boolean) {
    val expanded = controller.selectedSavedCard == null
    val expandState = cmpString(
        if (expanded) CardEntryStringKey.A11Y_EXPANDED else CardEntryStringKey.A11Y_COLLAPSED,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button) { controller.selectNewCard() }
            .semantics(mergeDescendants = true) { stateDescription = expandState },
    ) {
        CmpSectionHeader(
            text = cmpString(CardEntryStringKey.LABEL_NEW_CARD),
            modifier = Modifier.weight(1f),
        )
        CmpChevronGlyph(expanded)
    }
}

/** "Saved cards" header, collapsible in the new-card branch: a button re-expanding the full list. */
@Composable
private fun CmpSavedCardsCollapsibleHeader(expanded: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val expandState = cmpString(
        if (expanded) CardEntryStringKey.A11Y_EXPANDED else CardEntryStringKey.A11Y_COLLAPSED,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button) { onToggle() }
            .semantics(mergeDescendants = true) { stateDescription = expandState },
    ) {
        CmpSectionHeader(
            text = cmpString(CardEntryStringKey.LABEL_SAVED_CARDS),
            modifier = Modifier.weight(1f),
        )
        CmpChevronGlyph(expanded)
    }
}

/** Decorative expand/collapse chevron (the row's state description carries the meaning for a11y). */
@Composable
private fun CmpChevronGlyph(expanded: Boolean) {
    Text(
        text = if (expanded) "▾" else "▸",
        style = MaterialTheme.typography.bodyMedium,
        color = if (expanded) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clearAndSetSemantics {},
    )
}

/** The shared one-click section-header treatment. */
@Composable
private fun CmpSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier,
    )
}

/** In-frame "save this card" switch (consent, default OFF) + one-line consent text. */
@Composable
private fun CmpSaveCardSwitch(controller: CmpCardController, enabled: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = controller.saveCardOptIn,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = controller::onSaveCardOptInChange,
            )
            .semantics(mergeDescendants = true) {},
    ) {
        Text(
            text = cmpString(CardEntryStringKey.LABEL_SAVE_CARD),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = controller.saveCardOptIn,
            onCheckedChange = null, // handled by the row's toggleable (one merged a11y node)
            enabled = enabled,
        )
    }
    Text(
        text = cmpString(CardEntryStringKey.CONSENT_SAVE_CARD),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
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
