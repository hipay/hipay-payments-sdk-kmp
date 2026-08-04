// PCI (NFR2): com.hipay.card anti-logging path — never log card data here.
package com.hipay.card

import android.content.res.Configuration
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
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
import com.hipay.card.store.OneClickError
import com.hipay.card.store.OneClickErrorSurface
import com.hipay.card.store.SavedCard
import com.hipay.card.store.messageKey
import com.hipay.card.store.oneClickErrorSurface
import com.hipay.card.store.savedCardDisplay
import com.hipay.card.style.HiPayCardEntryStyle
import com.hipay.card.validation.CardEntryStringKey
import com.hipay.core.resolveLanguage
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
    public const val CONFIRM_DELETE: String = "hipay.card.delete.confirm"
    public const val CONFIRM_CANCEL: String = "hipay.card.delete.cancel"
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
    /** Visual customization — colors/typography/metrics only; behaviours are untouched.
     *  Defaults to the SDK look ([HiPayCardEntryStyle.hipayDefault]). @since 0.3.0 */
    style: HiPayCardEntryStyle = HiPayCardEntryStyle.hipayDefault,
) {
    // Bind the host Activity context so the controller can present 3DS in Custom Tabs (story 11.13).
    // Captured here from the REAL LocalContext (before any locale-override wrapper below) and cleared
    // on dispose so the controller never outlives the screen holding a stale Activity.
    val hostContext = LocalContext.current
    DisposableEffect(controller, hostContext) {
        controller.bindPresentationContext(hostContext)
        onDispose { controller.bindPresentationContext(null) }
    }
    // Effective language: per-component localeOverride → SDK-wide HiPaySettings → device locale
    // (null result = follow the device). collectAsState() makes a runtime HiPaySettings change
    // re-localize the component live, with no re-init.
    val settingsLang = controller.settingsLocale.collectAsState().value
    val effectiveLocale = resolveLanguage(localeOverride, settingsLang, device = null)
    if (effectiveLocale == null) {
        CardEntryContent(controller, modifier, setsAccessibilityOrder, style)
        return
    }
    val base = LocalContext.current
    // Read the Configuration via LocalConfiguration (not base.resources.configuration):
    // it recomposes when the device Configuration changes (lint LocalContextConfigurationRead).
    val baseConfig = LocalConfiguration.current
    val localized = remember(effectiveLocale, base, baseConfig) {
        val cfg = Configuration(baseConfig).apply { setLocale(Locale.forLanguageTag(effectiveLocale)) }
        base.createConfigurationContext(cfg)
    }
    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides localized.resources.configuration,
    ) {
        CardEntryContent(controller, modifier, setsAccessibilityOrder, style)
    }
}

@Composable
private fun CardEntryContent(
    controller: HiPayCardEntryController,
    modifier: Modifier,
    setsAccessibilityOrder: Boolean,
    style: HiPayCardEntryStyle,
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
    // Scope for the saved-card delete: hoisted here (this composable outlives the saved-cards
    // subtree) so deleting the LAST card — which drops the list to empty and removes that subtree
    // — does not cancel the in-flight delete/reload mid-way.
    val savedCardsScope = rememberCoroutineScope()
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

    // Account network ceiling: resolved on composition, so what may be offered at all is known before
    // the payer has typed a BIN — a brand icon must never be shown for a network the account refuses.
    LaunchedEffect(controller) { controller.loadAccountNetworksIfNeeded() }

    // One-click: load the saved card once the presentation context is bound (fail-soft before).
    if (controller.oneClickEnabled) {
        LaunchedEffect(controller) { controller.refreshSavedCards() }
    }

    CompositionLocalProvider(LocalHiPayCardStyle provides style) {
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
        // Also composed when the list just emptied with a section-level one-click error to show
        // (the last card was purged as no longer valid) — the payer must learn why it vanished.
        val showSavedSections = controller.oneClickEnabled && (
            controller.savedCards.isNotEmpty() ||
                oneClickErrorSurface(controller.lastOneClickError, controller.savedCards) ==
                OneClickErrorSurface.SECTION
            )
        if (showSavedSections) {
            FieldGroup(setsAccessibilityOrder, -1f) {
                SavedCardsSections(controller, enabled, savedCardsScope)
            }
        }
        if (showEntryFields) {
        // Holder
        FieldGroup(setsAccessibilityOrder, 0f) {
            HiPayStyledField(
                value = controller.holder,
                onValueChange = controller::onHolderChange,
                label = { FieldLabel(cardString(CardEntryStringKey.LABEL_HOLDER)) },
                placeholder = { Text(cardString(CardEntryStringKey.PLACEHOLDER_HOLDER)) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag(HiPayCardEntryTags.HOLDER)
                    .blurring(controller, Field.HOLDER),
                isError = controller.holderErrorKey != null,
            )
            ErrorSlot(controller.holderErrorKey, HiPayCardEntryTags.error("holder"))
        }

        // Card number (+ network chips)
        FieldGroup(setsAccessibilityOrder, 1f) {
            // The network chips are OVERLAID on the field's trailing edge rather than passed to
            // Material's trailingIcon slot (which floors the field to 48dp): in a plain Box, with the
            // chips reporting zero height, the field keeps its compact fieldHeight and the chips sit
            // visually inside its right edge — same technique as the CVC "ⓘ". (A full card number does
            // not reach the chips at default type sizes; at very large font scales it can, see note.)
            Box {
                HiPayStyledField(
                    value = controller.cardNumber,
                    onValueChange = controller::onNumberChange,
                    label = { FieldLabel(cardString(CardEntryStringKey.LABEL_NUMBER)) },
                    placeholder = { Text(cardString(CardEntryStringKey.PLACEHOLDER_NUMBER)) },
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    // Raw digits as value; spaces rendered by the transformation → caret stays correct (11.1).
                    visualTransformation = CardNumberVisualTransformation(controller.network),
                    modifier = Modifier.fillMaxWidth().testTag(HiPayCardEntryTags.NUMBER)
                        .blurring(controller, Field.NUMBER),
                    isError = controller.numberSlotErrorKey != null,
                )
                NetworkChips(
                    controller,
                    Modifier.align(Alignment.CenterEnd).layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, 0) { placeable.place(0, -placeable.height / 2) }
                    },
                )
            }
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
                HiPayStyledField(
                    value = controller.expiry,
                    onValueChange = controller::onExpiryChange,
                    label = { FieldLabel(cardString(CardEntryStringKey.LABEL_EXPIRY)) },
                    placeholder = { Text(cardString(CardEntryStringKey.PLACEHOLDER_EXPIRY)) },
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    // Raw digits as value; "/" rendered by the transformation → caret stays correct (11.8).
                    visualTransformation = ExpiryVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag(HiPayCardEntryTags.EXPIRY)
                        .focusRequester(expiryFocus)
                        .blurring(controller, Field.EXPIRY),
                    isError = controller.expiryErrorKey != null,
                )
            }

            // CVC (+ tap-to-toggle "ⓘ" when required)
            FieldGroup(setsAccessibilityOrder, 3f, Modifier.weight(1f)) {
                // The "ⓘ" is OVERLAID on the field's trailing edge rather than passed to Material's
                // trailingIcon slot: a real trailing affordance forces the decoration up to a 48dp
                // touch-target floor, making the CVC field taller than the icon-less expiry beside
                // it. As an overlay in a plain Box — with the icon reporting zero height (see
                // CvvInfoIcon) — the field keeps its compact styled fieldHeight and both fields match,
                // while the "ⓘ" still sits visually inside the field with a full 48dp tap area.
                Box {
                    // CVC is required (enabled) or not-applicable (disabled) — never a true "optional"
                    // enterable state — so the label stays the short "Security code" with no suffix
                    // (story 11.3 review): keeps it one line and avoids the half-width overflow.
                    HiPayStyledField(
                        value = controller.cvc,
                        onValueChange = controller::onCvcChange,
                        label = { FieldLabel(cardString(CardEntryStringKey.LABEL_CVV)) },
                        placeholder = { Text(cardString(CardEntryStringKey.PLACEHOLDER_CVV)) },
                        enabled = enabled && controller.isCvcRequired,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().testTag(HiPayCardEntryTags.CVC)
                            .focusRequester(cvcFocus)
                            .blurring(controller, Field.CVC),
                        isError = controller.cvcErrorKey != null,
                    )
                    // Info affordance only when the CVC is required (no overlay on a disabled field).
                    if (controller.isCvcRequired) {
                        CvvInfoIcon(Modifier.align(Alignment.CenterEnd)) { showCvvInfo = !showCvvInfo }
                    }
                }
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
    } // LocalHiPayCardStyle
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
private fun SavedCardsSections(
    controller: HiPayCardEntryController,
    enabled: Boolean,
    scope: CoroutineScope,
) {
    val cards = controller.savedCards
    // The shared policy point decides where (and whether) the one-click error surfaces:
    // inline on the affected cell while listed, section-level when purged as no longer valid.
    val oneClickError = controller.lastOneClickError
    val errorSurface = oneClickErrorSurface(oneClickError, cards)
    if (cards.isEmpty()) {
        // The last card vanished as no longer valid mid-checkout: the list is gone but the
        // payer still needs to know why — the section message alone, above the open fields.
        if (errorSurface == OneClickErrorSurface.SECTION && oneClickError != null) {
            ErrorSlot(oneClickError.reason.messageKey(), HiPayCardEntryTags.error("oneclick.section"))
        }
        return
    }
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

    // Delete is a gesture (long-press) / a11y-action affordance — no visible button. The pending
    // card drives the confirmation dialog; it lives in the UI, not the controller.
    var cardPendingDelete by remember { mutableStateOf<SavedCard?>(null) }
    // Drop a pending confirmation if its card vanishes from the list underneath the open dialog
    // (a concurrent refresh on app-foreground, or an expiry purge) — otherwise the payer would
    // confirm deleting a card they can no longer see.
    LaunchedEffect(cards) { cardPendingDelete?.let { if (it !in cards) cardPendingDelete = null } }

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
        if (errorSurface == OneClickErrorSurface.SECTION && oneClickError != null) {
            ErrorSlot(oneClickError.reason.messageKey(), HiPayCardEntryTags.error("oneclick.section"))
        }
        visibleCards.forEachIndexed { index, card ->
            SavedCardCell(
                controller,
                card,
                index,
                enabled,
                error = oneClickError?.takeIf {
                    errorSurface == OneClickErrorSurface.INLINE_CARD && it.matches(card)
                },
            ) { cardPendingDelete = it }
        }
        NewCardHeader(controller, enabled)
    }

    cardPendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { cardPendingDelete = null },
            text = { Text(cardString(CardEntryStringKey.CONFIRM_DELETE_CARD)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { controller.deleteSavedCard(pending) }
                        cardPendingDelete = null
                    },
                    modifier = Modifier.testTag(HiPayCardEntryTags.CONFIRM_DELETE),
                ) { Text(cardString(CardEntryStringKey.LABEL_DELETE_CARD)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { cardPendingDelete = null },
                    modifier = Modifier.testTag(HiPayCardEntryTags.CONFIRM_CANCEL),
                ) { Text(cardString(CardEntryStringKey.LABEL_CANCEL)) }
            },
        )
    }
}

/** One saved-card cell: 2-line masked display, border-only selection (no radio), merged a11y node.
 *  With a one-click [error] targeting this card, an inline error renders under the cell (the field
 *  errorSlot pattern — icon + text, polite announce) and joins the cell's merged description. */
@Composable
private fun SavedCardCell(
    controller: HiPayCardEntryController,
    card: SavedCard,
    index: Int,
    enabled: Boolean,
    error: OneClickError? = null,
    onRequestDelete: (SavedCard) -> Unit,
) {
    val display = remember(card) { savedCardDisplay(card) }
    val platformNetwork = display.network?.let { HiPayCardNetwork.from(it) }
    val baseA11yLabel = stringResource(
        HiPayCardStrings.resFor(CardEntryStringKey.A11Y_SAVED_CARD),
        platformNetwork?.displayName ?: card.network,
        display.last4,
        display.displayExpiry,
    )
    // The error is part of the merged cell node: focusing the cell reads why it failed.
    val a11yLabel = error?.let { "$baseA11yLabel, ${cardString(it.reason.messageKey())}" } ?: baseA11yLabel
    val deleteLabel = cardString(CardEntryStringKey.LABEL_DELETE_CARD)
    val isSelected = controller.selectedSavedCard == card
    // Cells follow the style's metrics (corner radius, border width) so they agree with the
    // entry fields; the SELECTED cell keeps the platform accent — a thicker accent border
    // (borderWidth + 1, the fields' focus treatment) and an accent tint layered OVER the
    // style's background — as the selection affordance (no accent slot in the contract yet).
    val style = LocalHiPayCardStyle.current
    val cellShape = RoundedCornerShape(style.cornerRadius.dp)
    val border =
        if (isSelected) BorderStroke((style.borderWidth + 1f).dp, MaterialTheme.colorScheme.primary)
        else BorderStroke(style.borderWidth.dp, styleColor(style.borderColor))
    // The cell + its inline error travel as one visual unit (the field errorSlot spacing).
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .border(border, cellShape)
                .background(styleColor(style.backgroundColor), cellShape)
                .then(
                    if (isSelected) {
                        Modifier.background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                            cellShape,
                        )
                    } else {
                        Modifier
                    },
                )
                // Tap selects; long-press requests delete (no visible delete button, PM decision).
                .combinedClickable(
                    enabled = enabled,
                    onClick = { controller.selectSavedCard(card) },
                    onLongClick = { onRequestDelete(card) },
                )
                .testTag(HiPayCardEntryTags.savedCard(index))
                // One merged node: "<Network> finishing 1111, expires MM / YYYY, selected" —
                // the bullet glyphs are never announced. The mandatory "Delete" custom action makes
                // deletion reachable to TalkBack (the long-press gesture is invisible to it).
                .semantics(mergeDescendants = true) {
                    contentDescription = a11yLabel
                    selected = isSelected
                    // Gated on enabled: while a payment is in flight the delete must be unreachable to
                    // screen readers too — the long-press is already gated via combinedClickable, and
                    // this custom action is the only other delete entry point.
                    customActions =
                        if (enabled) {
                            listOf(CustomAccessibilityAction(deleteLabel) { onRequestDelete(card); true })
                        } else {
                            emptyList()
                        }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Image(
                painter = painterResource(platformNetwork?.drawableRes ?: R.drawable.hp_card_neutral),
                contentDescription = null, // described by the merged cell node
                // Brand marks stay full-color; only the neutral fallback glyph takes iconColor.
                colorFilter = if (platformNetwork == null) {
                    ColorFilter.tint(styleColor(style.iconColor))
                } else {
                    null
                },
                modifier = Modifier.size(width = 32.dp, height = 20.dp),
            )
            Column {
                Text(
                    display.maskedNumber,
                    style = MaterialTheme.typography.bodyLarge,
                    color = styleColor(style.textColor),
                )
                Text(
                    text = "${card.holder}  ·  ${display.displayExpiry}",
                    style = MaterialTheme.typography.bodySmall,
                    color = styleColor(style.placeholderColor),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ErrorSlot(error?.reason?.messageKey(), HiPayCardEntryTags.error("savedcard.$index"))
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
        else styleColor(LocalHiPayCardStyle.current.iconColor),
        modifier = Modifier.clearAndSetSemantics {},
    )
}

/** The shared one-click section-header treatment. */
@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = styleColor(LocalHiPayCardStyle.current.placeholderColor),
        maxLines = 1,
        modifier = modifier,
    )
}

/** In-frame "save this card" switch (consent, default OFF) + one-line consent text. */
@Composable
private fun SaveCardSwitch(controller: HiPayCardEntryController, enabled: Boolean) {
    // Style drives the label + consent text; the switch track/thumb keep the platform accent
    // (the contract has no accent slot yet) — mirrors CMP.
    val style = LocalHiPayCardStyle.current
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
            color = styleColor(style.textColor),
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
        color = styleColor(style.placeholderColor),
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
        val errorColor = styleColor(LocalHiPayCardStyle.current.invalidTextColor)
        Text("⚠", color = errorColor, style = MaterialTheme.typography.bodySmall)
        Text(
            text = cardString(key),
            color = errorColor,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
        )
    }
}

/** CVV info "ⓘ" affordance — toggles the full-width inline help text (story 11.2; no popup). */
@Composable
private fun CvvInfoIcon(modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val tooltip = cardString(CardEntryStringKey.CVV_TOOLTIP)
    val iconColor = styleColor(LocalHiPayCardStyle.current.iconColor)
    // ≥48dp touch target for the "ⓘ", overlaid on the field's trailing edge. A plain 48dp box would
    // grow the row to 48; the `layout` below measures the 48dp target normally but reports ZERO
    // height to its parent, so the icon overflows (vertically centered) over the compact field
    // instead of inflating it — the field keeps its styled fieldHeight and matches expiry, while the
    // tap area stays a full 48×48. (This works because the parent is a plain Box, not Material3's
    // decoration, which reserves a 48dp floor for any trailing-slot content regardless of its size.)
    Box(
        modifier = modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, 0) { placeable.place(0, -placeable.height / 2) }
            }
            // 42dp round tap area = the field height, so the tap ripple stays a circle INSIDE the
            // field instead of a 48dp square overflowing it. `clip` before `clickable` bounds the
            // ripple to the circle.
            .size(42.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button) { onToggle() }
            .testTag(HiPayCardEntryTags.CVC_INFO)
            .semantics { contentDescription = tooltip },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "ⓘ", // circled "i"
            style = MaterialTheme.typography.titleMedium,
            color = iconColor,
        )
    }
}

/** CVV help as a full-width inline text under the expiry/CVV row (story 11.2), announced when shown. */
@Composable
private fun CvvInfoText() {
    Text(
        text = cardString(CardEntryStringKey.CVV_TOOLTIP),
        style = MaterialTheme.typography.bodySmall,
        color = styleColor(LocalHiPayCardStyle.current.textColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp)
            .testTag(HiPayCardEntryTags.CVC_TOOLTIP)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Tappable network/co-brand chips. Each is one focusable a11y node: "<brand>, selected". */
@Composable
private fun NetworkChips(controller: HiPayCardEntryController, modifier: Modifier = Modifier) {
    // Unselected brand chips dim to 0.35 alpha (never tinted — several brand marks sit on an
    // OPAQUE plate: tinting them monochrome flattens the plate into an unreadable block). Only the
    // neutral glyph (a true silhouette) takes the style's iconColor. Mirrors CMP.
    val iconColor = LocalHiPayCardStyle.current.iconColor
    val iconTint = remember(iconColor) { ColorFilter.tint(styleColor(iconColor)) }
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val nets = controller.networks
        if (nets.isEmpty()) {
            Image(
                painter = painterResource(R.drawable.hp_card_neutral),
                contentDescription = null, // decorative
                colorFilter = iconTint,
                modifier = Modifier.size(width = 32.dp, height = 20.dp),
            )
        } else {
            nets.forEach { net ->
                val isSel = net == controller.selectedNetwork
                Box(
                    // 42dp round tap area = the field height, so the co-brand selection ripple stays a
                    // circle inside the field instead of a 48dp square overflowing it (the whole Row is
                    // overlaid at zero reported height by the caller). `clip` before `clickable` rounds
                    // the ripple.
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
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
