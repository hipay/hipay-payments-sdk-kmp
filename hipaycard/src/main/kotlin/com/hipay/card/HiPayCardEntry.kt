// PCI (NFR2): com.hipay.card anti-logging path — never log card data here.
package com.hipay.card

import android.content.res.Configuration
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hipay.card.HiPayCardEntryController.Field
import com.hipay.card.store.SavedCard
import com.hipay.card.store.savedCardDisplay
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
    public const val SAVED_CARDS_HEADER: String = "hipay.card.savedcards.header"
    public const val NEW_CARD: String = "hipay.card.newcard"
    public const val SAVE_SWITCH: String = "hipay.card.saveswitch"
    public const val CONSENT: String = "hipay.card.consent"
    /** One tag per saved-card cell (0 = most-recent) — never a single duplicated id across cells. */
    public fun savedCard(index: Int): String = "hipay.card.savedcard.$index"
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
    // Bind the host Activity context so the controller can present 3DS in Custom Tabs (story 11.13).
    // Captured here from the REAL LocalContext (before any locale-override wrapper below) and cleared
    // on dispose so the controller never outlives the screen holding a stale Activity.
    val hostContext = LocalContext.current
    DisposableEffect(controller, hostContext) {
        controller.bindPresentationContext(hostContext)
        onDispose { controller.bindPresentationContext(null) }
    }
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
    // Lock all fields while a payment is in flight — driven by the SDK itself (story 11.14): the
    // controller sets isProcessing across pay() (incl. the 3DS round-trip), no host wiring needed.
    val enabled = !controller.isProcessing
    // With a saved card selected, the entry fields are not rendered — their values stay in the
    // controller (nothing is cleared until a payment succeeds).
    val showEntryFields = !(controller.oneClickEnabled && controller.selectedSavedCard != null)
    // Focus auto-advance on field completion (story 11.10, parity with iOS). Keyed on the
    // completion booleans → fires only on the incomplete→complete edge, so editing a complete
    // field never rips focus. Gated on showEntryFields: the FocusRequesters below are attached
    // only to the composed entry fields, so requesting focus while a saved card is selected
    // (fields out of composition, e.g. a programmatic field setter) would crash.
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

    // One-click: load the saved card once the presentation context is bound (fail-soft before).
    if (controller.oneClickEnabled) {
        LaunchedEffect(controller) { controller.refreshSavedCards() }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            // Animate the expand/collapse only when one-click is on — an opted-out integrator must
            // see no new animation of pre-existing size changes (errors, tooltip).
            .then(if (controller.oneClickEnabled) Modifier.animateContentSize() else Modifier)
            .then(if (setsAccessibilityOrder) Modifier.semantics { isTraversalGroup = true } else Modifier),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (controller.oneClickEnabled && controller.savedCards.isNotEmpty()) {
            FieldGroup(setsAccessibilityOrder, -1f) {
                SavedCardsSections(controller, enabled)
            }
        }
        if (showEntryFields) {
        // Holder
        FieldGroup(setsAccessibilityOrder, 0f) {
            OutlinedTextField(
                value = controller.holder,
                onValueChange = controller::onHolderChange,
                label = { FieldLabel(cardString(CardEntryStringKey.LABEL_HOLDER)) },
                placeholder = { Text(cardString(CardEntryStringKey.PLACEHOLDER_HOLDER)) },
                singleLine = true,
                enabled = enabled,
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
                label = { FieldLabel(cardString(CardEntryStringKey.LABEL_NUMBER)) },
                placeholder = { Text(cardString(CardEntryStringKey.PLACEHOLDER_NUMBER)) },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                trailingIcon = { NetworkChips(controller) },
                // Raw digits as value; spaces rendered by the transformation → caret stays correct (11.1).
                visualTransformation = CardNumberVisualTransformation(controller.network),
                modifier = Modifier.fillMaxWidth().testTag(HiPayCardEntryTags.NUMBER)
                    .blurring(controller, Field.NUMBER),
            )
            // Network-not-authorized takes precedence over the number's own error (D1).
            ErrorSlot(controller.numberSlotErrorKey, HiPayCardEntryTags.error("number"))
        }

        // The CVV info text is toggled by the "ⓘ" and rendered full width below the row (11.2).
        var showCvvInfo by remember { mutableStateOf(false) }
        // Reset the help when CVC stops being required so it never re-shows unprompted on return (review 11.2).
        LaunchedEffect(controller.isCvcRequired) { if (!controller.isCvcRequired) showCvvInfo = false }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Expiry
            FieldGroup(setsAccessibilityOrder, 2f, Modifier.weight(1f)) {
                OutlinedTextField(
                    value = controller.expiry,
                    onValueChange = controller::onExpiryChange,
                    label = { FieldLabel(cardString(CardEntryStringKey.LABEL_EXPIRY)) },
                    placeholder = { Text(cardString(CardEntryStringKey.PLACEHOLDER_EXPIRY)) },
                    singleLine = true,
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    // Raw digits as value; "/" rendered by the transformation → caret stays correct (11.8).
                    visualTransformation = ExpiryVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag(HiPayCardEntryTags.EXPIRY)
                        .focusRequester(expiryFocus)
                        .blurring(controller, Field.EXPIRY),
                )
            }

            // CVC (+ tap-to-toggle "ⓘ" when required)
            FieldGroup(setsAccessibilityOrder, 3f, Modifier.weight(1f)) {
                // CVC is required (enabled) or not-applicable (disabled) — never a true "optional"
                // enterable state — so the label stays the short "Security code" with no suffix
                // (story 11.3 review): keeps it one line and avoids the half-width overflow.
                OutlinedTextField(
                    value = controller.cvc,
                    onValueChange = controller::onCvcChange,
                    label = { FieldLabel(cardString(CardEntryStringKey.LABEL_CVV)) },
                    placeholder = { Text(cardString(CardEntryStringKey.PLACEHOLDER_CVV)) },
                    singleLine = true,
                    enabled = enabled && controller.isCvcRequired,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    // Info affordance only when the CVC is required (no overlay on a disabled field).
                    trailingIcon = if (controller.isCvcRequired) ({ CvvInfoIcon { showCvvInfo = !showCvvInfo } }) else null,
                    modifier = Modifier.fillMaxWidth().testTag(HiPayCardEntryTags.CVC)
                        .focusRequester(cvcFocus)
                        .blurring(controller, Field.CVC),
                )
            }
        }
        // Expiry/CVC errors are FULL WIDTH below the row (11.2), between the fields and the info.
        ErrorSlot(controller.expiryErrorKey, HiPayCardEntryTags.error("expiry"))
        ErrorSlot(controller.cvcErrorKey, HiPayCardEntryTags.error("cvc"))
        // CVV help as full-width inline text (no popup, 11.2), toggled by the "ⓘ".
        if (controller.isCvcRequired && showCvvInfo) CvvInfoText()
        // In-frame save switch + one-line consent — the new-card branch of one-click only.
        if (controller.oneClickEnabled) SaveCardSwitch(controller, enabled)
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
 * "Saved cards" header gains its own chevron to re-expand the full list (so the payer can switch
 * card without losing what they typed). With a single saved card there is no collapse affordance.
 */
@Composable
private fun SavedCardsSections(controller: HiPayCardEntryController, enabled: Boolean) {
    val cards = controller.savedCards
    if (cards.isEmpty()) return
    // Second, independent expand/collapse axis for the LIST itself (distinct from the "New card"
    // fields expand): only meaningful in the new-card branch with more than one card.
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
            SavedCardsCollapsibleHeader(expanded = savedCardsExpanded, enabled = enabled) {
                savedCardsExpanded = !savedCardsExpanded
            }
        } else {
            SectionHeader(cardString(CardEntryStringKey.LABEL_SAVED_CARDS))
        }
        visibleCards.forEachIndexed { index, card ->
            SavedCardCell(controller, card, index, enabled)
        }
        NewCardHeader(controller, enabled)
    }
}

/** One saved-card cell: 2-line masked display, border-only selection (no radio), merged a11y node. */
@Composable
private fun SavedCardCell(
    controller: HiPayCardEntryController,
    card: SavedCard,
    index: Int,
    enabled: Boolean,
) {
    val display = remember(card) { savedCardDisplay(card) }
    val platformNetwork = display.network?.let { HiPayCardNetwork.from(it) }
    val a11yLabel = stringResource(
        HiPayCardStrings.resFor(CardEntryStringKey.A11Y_SAVED_CARD),
        platformNetwork?.displayName ?: card.network,
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
            .testTag(HiPayCardEntryTags.savedCard(index))
            // One merged node: "<Network> finishing 1111, expires MM / YYYY, selected" —
            // the bullet glyphs are never announced.
            .semantics(mergeDescendants = true) { contentDescription = a11yLabel }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Image(
            painter = painterResource(platformNetwork?.drawableRes ?: R.drawable.hp_card_neutral),
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

/** "New card": an actionable BUTTON whose expanded/collapsed state (not a radio selection) carries
 *  the meaning; the chevron mirrors it (decorative). */
@Composable
private fun NewCardHeader(controller: HiPayCardEntryController, enabled: Boolean) {
    val expanded = controller.selectedSavedCard == null
    val expandState = cardString(
        if (expanded) CardEntryStringKey.A11Y_EXPANDED else CardEntryStringKey.A11Y_COLLAPSED,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button) { controller.selectNewCard() }
            .testTag(HiPayCardEntryTags.NEW_CARD)
            .semantics(mergeDescendants = true) { stateDescription = expandState },
    ) {
        SectionHeader(
            text = cardString(CardEntryStringKey.LABEL_NEW_CARD),
            modifier = Modifier.weight(1f),
        )
        ChevronGlyph(expanded)
    }
}

/** "Saved cards" header, collapsible in the new-card branch: a button re-expanding the full list. */
@Composable
private fun SavedCardsCollapsibleHeader(expanded: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val expandState = cardString(
        if (expanded) CardEntryStringKey.A11Y_EXPANDED else CardEntryStringKey.A11Y_COLLAPSED,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button) { onToggle() }
            .testTag(HiPayCardEntryTags.SAVED_CARDS_HEADER)
            .semantics(mergeDescendants = true) { stateDescription = expandState },
    ) {
        SectionHeader(
            text = cardString(CardEntryStringKey.LABEL_SAVED_CARDS),
            modifier = Modifier.weight(1f),
        )
        ChevronGlyph(expanded)
    }
}

/** Decorative expand/collapse chevron (the row's state description carries the meaning for a11y). */
@Composable
private fun ChevronGlyph(expanded: Boolean) {
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
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
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
private fun SaveCardSwitch(controller: HiPayCardEntryController, enabled: Boolean) {
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
            .testTag(HiPayCardEntryTags.SAVE_SWITCH)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(
            text = cardString(CardEntryStringKey.LABEL_SAVE_CARD),
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
        text = cardString(CardEntryStringKey.CONSENT_SAVE_CARD),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag(HiPayCardEntryTags.CONSENT),
    )
}

/**
 * Field label forced onto a single line at the smaller (floating) type size (story 11.3).
 * Keeping the resting label at the floating size — plus `maxLines = 1` / no soft-wrap — stops the
 * longer localized labels ("Code de sécurité (facultatif)", "Codice di sicurezza") from wrapping to
 * two lines, which previously inflated the CVC field height and broke row symmetry with Expiry.
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

/** CVV info "ⓘ" affordance — toggles the full-width inline help text (story 11.2; no popup). */
@Composable
private fun CvvInfoIcon(onToggle: () -> Unit) {
    val tooltip = cardString(CardEntryStringKey.CVV_TOOLTIP)
    Text(
        text = "ⓘ", // circled "i"
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .clickable { onToggle() }
            .testTag(HiPayCardEntryTags.CVC_INFO)
            .semantics { contentDescription = tooltip },
    )
}

/** CVV help as a full-width inline text under the expiry/CVV row (story 11.2), announced when shown. */
@Composable
private fun CvvInfoText() {
    Text(
        text = cardString(CardEntryStringKey.CVV_TOOLTIP),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp)
            .testTag(HiPayCardEntryTags.CVC_TOOLTIP)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
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
