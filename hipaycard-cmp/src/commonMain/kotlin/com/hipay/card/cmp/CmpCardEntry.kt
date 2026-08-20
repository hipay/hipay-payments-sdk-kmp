// PCI (NFR2): com.hipay.card path — never log card data here.
package com.hipay.card.cmp

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.hipay.card.cmp.CmpCardController.Field
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.hipay.card.store.OneClickError
import com.hipay.card.store.OneClickErrorSurface
import com.hipay.card.store.SavedCard
import com.hipay.card.store.messageKey
import com.hipay.card.store.oneClickErrorSurface
import com.hipay.card.store.savedCardDisplay
import com.hipay.card.style.HiPayCardEntryStyle
import com.hipay.card.validation.CardEntryStringKey
import com.hipay.card.validation.CardNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

/**
 * Shared Compose-Multiplatform card-entry UI (story 10.2, slice A) — rendered on iOS via the
 * iOS `actual` (and reusable on Android). Mirrors the Android `:hipaycard` `HiPayCardEntry`
 * fields-only contract: it renders the entry fields + inline errors; the host owns the pay
 * button and calls [CmpCardController.pay].
 *
 * Strings resolve per locale ([cmpString], fr/en/it): [localeOverride] when given, else the
 * device locale — parity with the native components' `values-*` / `.lproj` behaviour.
 * Appearance comes from [style] ([LocalHiPayCardStyle]); colors/typography/metrics only —
 * behaviours (formatting, focus, one-click, a11y) are untouched by styling.
 */
@Composable
internal fun CmpCardEntry(
    controller: CmpCardController,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") setsAccessibilityOrder: Boolean = true,
    localeOverride: String? = null,
    style: HiPayCardEntryStyle = HiPayCardEntryStyle.hipayDefault,
) {
    // Deliberately not remember-ed: resolution is a cheap string scan, and re-resolving on
    // every recomposition lets a host that opts out of activity recreation on locale changes
    // still pick up the new language on its next recomposition. collectAsState() additionally
    // re-localizes live when the SDK-wide HiPaySettings language is changed at runtime.
    val settingsLang = controller.settingsLocale.collectAsState().value
    val cardLanguage = resolvedCardEntryLanguage(localeOverride, settingsLang)
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
    // Scope for the saved-card delete (this composable outlives the saved-cards
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

    // One-click: load the saved card on composition (no-op unless opted in — fail-soft).
    if (controller.oneClickEnabled) {
        LaunchedEffect(controller) { controller.refreshSavedCards() }
    }

    val reduceMotion = reduceMotionEnabled()

    CompositionLocalProvider(
        LocalHiPayCardLanguage provides cardLanguage,
        LocalHiPayCardStyle provides resolveCardStyle(style),
    ) {
    Column(
        // Animate the expand/collapse only when one-click is on — an opted-out integrator must
        // see no new animation of pre-existing size changes (errors, tooltip). Suppressed under the
        // reduce-motion accessibility setting (WCAG 2.3.3): the size change then applies instantly.
        modifier = modifier.fillMaxWidth().padding(16.dp)
            .then(
                if (controller.oneClickEnabled && !reduceMotion) Modifier.animateContentSize()
                else Modifier,
            ),
        // A floating label rises into the top of its own field, which eats most of the visual gap
        // between two stacked fields: 8.dp read as cramped. 12.dp is the value the SwiftUI surface
        // already ships, so this also brings the three surfaces back into line.
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Also composed when the list just emptied with a section-level one-click error to show
        // (the last card was purged as no longer valid) — the payer must learn why it vanished.
        val showSavedSections = controller.oneClickEnabled && (
            controller.savedCards.isNotEmpty() ||
                oneClickErrorSurface(controller.lastOneClickError, controller.savedCards) ==
                OneClickErrorSurface.SECTION
            )
        if (showSavedSections) {
            CmpSavedCardsSections(controller, enabled, savedCardsScope)
        }
        if (showEntryFields) {
        // Holder
        HiPayStyledField(
            value = controller.holder,
            onValueChange = controller::onHolderChange,
            label = { FieldLabel(cmpString(CardEntryStringKey.LABEL_HOLDER)) },
            placeholder = { Text(cmpString(CardEntryStringKey.PLACEHOLDER_HOLDER)) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().blurring(controller, Field.HOLDER),
            isError = controller.holderErrorKey != null,
        )
        ErrorText(controller.holderErrorKey)

        // Card number — the network chips are OVERLAID on the field's trailing edge (not the Material
        // trailingIcon slot, which floors the field to 48dp): in a plain Box, reporting zero height,
        // the field keeps its compact fieldHeight and the chips sit visually inside its right edge —
        // same technique as the CVC "ⓘ" (lockstep with native Android/iOS).
        Box {
            HiPayStyledField(
                value = controller.cardNumber,
                onValueChange = controller::onNumberChange,
                label = { FieldLabel(cmpString(CardEntryStringKey.LABEL_NUMBER)) },
                placeholder = { Text(cmpString(CardEntryStringKey.PLACEHOLDER_NUMBER)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = enabled,
                // Raw digits as value; spaces rendered by the transformation → caret stays correct (11.1).
                visualTransformation = CardNumberVisualTransformation(controller.network),
                modifier = Modifier.fillMaxWidth().blurring(controller, Field.NUMBER),
                isError = controller.numberSlotErrorKey != null,
            )
            NetworkChips(
                controller,
                Modifier.align(Alignment.CenterEnd).overlaidOnFieldInput(),
            )
        }
        // Network-not-authorized takes precedence over the number's own error (D1).
        ErrorText(controller.numberSlotErrorKey)

        // The CVV info text is toggled by the "ⓘ" and rendered full width below the row (11.2).
        var showCvvInfo by remember { mutableStateOf(false) }
        // Reset the help when CVC stops being required so it never re-shows unprompted on return (review 11.2).
        LaunchedEffect(controller.isCvcRequired) { if (!controller.isCvcRequired) showCvvInfo = false }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Expiry
            Column(modifier = Modifier.weight(1f)) {
                HiPayStyledField(
                    value = controller.expiry,
                    onValueChange = controller::onExpiryChange,
                    label = { FieldLabel(cmpString(CardEntryStringKey.LABEL_EXPIRY)) },
                    placeholder = { Text(cmpString(CardEntryStringKey.PLACEHOLDER_EXPIRY)) },
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    // Raw digits as value; "/" rendered by the transformation → caret stays correct (11.8).
                    visualTransformation = ExpiryVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().focusRequester(expiryFocus)
                        .blurring(controller, Field.EXPIRY),
                    isError = controller.expiryErrorKey != null,
                )
            }
            // CVC — shown-disabled when not required; "ⓘ" toggles the full-width info text (11.2).
            Column(modifier = Modifier.weight(1f)) {
                // The "ⓘ" is OVERLAID on the field's trailing edge rather than passed to Material's
                // trailingIcon slot: a real trailing affordance forces the decoration up to a 48dp
                // touch-target floor, making the CVC field taller than the icon-less expiry. As an
                // overlay in a plain Box — with the icon reporting zero height (the `layout` below) —
                // the field keeps its compact styled fieldHeight and both fields match, while the "ⓘ"
                // still sits visually inside the field with a full 48dp tap area. (Lockstep w/ Android.)
                Box {
                    HiPayStyledField(
                        value = controller.cvc,
                        onValueChange = controller::onCvcChange,
                        enabled = enabled && controller.isCvcRequired,
                        label = { FieldLabel(cmpString(CardEntryStringKey.LABEL_CVV)) },
                        placeholder = { Text(cmpString(CardEntryStringKey.PLACEHOLDER_CVV)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().focusRequester(cvcFocus)
                            .blurring(controller, Field.CVC),
                        isError = controller.cvcErrorKey != null,
                    )
                    if (controller.isCvcRequired) {
                        val cvvHelp = cmpString(CardEntryStringKey.CVV_TOOLTIP)
                        // 48dp a11y tap target overlaid on the field's trailing edge; the `layout`
                        // reports zero height so it overflows (centered) instead of growing the field.
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .overlaidOnFieldInput()
                                // 42dp round tap area = field height, so the ripple stays a circle
                                // inside the field instead of a 48dp square overflowing it.
                                .size(42.dp)
                                .clip(CircleShape)
                                .semantics { contentDescription = cvvHelp }
                                .clickable(role = Role.Button) { showCvvInfo = !showCvvInfo },
                        ) {
                            Text("ⓘ", color = cmpColor(LocalHiPayCardStyle.current.iconColor))
                        }
                    }
                }
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
                color = cmpColor(LocalHiPayCardStyle.current.textColor),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // In-frame save switch + one-line consent — the new-card branch of one-click only.
        if (controller.oneClickEnabled) CmpSaveCardSwitch(controller, enabled)
        } // showEntryFields
    }
    } // LocalHiPayCardLanguage
}

/**
 * The two one-click zones sharing one section-header treatment: "Saved cards" (the most-recent
 * `savedCardsDisplayCount` cards, most-recent first) and "New card" (an actionable header whose
 * chevron shows the expanded state). The cells form a single-selection group — exactly one selection at all times
 * (a card, or "New card"); no visual radio indicator by design.
 *
 * The most-recent `savedCardsDisplayCount` cards are shown (MRU-first, the most recent pre-selected);
 * when more cards are stored a "Show more / Show less" toggle reveals or hides the rest.
 * The list force-expands (and "Show less" is disabled) while the selected card sits beyond the fold,
 * so the paying card is never hidden. Every saved card is retained by the store — the display count
 * only bounds what is shown by default.
 */
@Composable
private fun CmpSavedCardsSections(
    controller: CmpCardController,
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
            OneClickErrorText(oneClickError.reason.messageKey())
        }
        return
    }
    // Show the most-recent `displayCount` cards; a "Show more / Show less" toggle reveals or hides
    // the rest. This bounds only what is shown — every saved card is retained (store cap 20).
    val displayCount = controller.savedCardsDisplayCount
    val hasMore = cards.size > displayCount
    // If the selected card sits beyond the default fold, the list must stay open (the paying card is
    // never hidden): force-expand and disable "Show less" while that holds. -1 (no saved-card
    // selection, e.g. the new-card branch) is never beyond the fold.
    val selectedIndex = cards.indexOfFirst { it == controller.selectedSavedCard }
    val selectionBeyondFold = selectedIndex >= displayCount
    // Expansion is DERIVED, never latched: `showAll` holds the payer's own choice and nothing else,
    // so the forced expansion releases by itself once the selection returns within the fold. The
    // choice is also dropped outright once the list no longer overflows, otherwise deleting a card
    // down to the fold and saving a new one later would silently reopen the list unasked.
    var showAll by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(hasMore) { if (!hasMore) showAll = false }
    val expanded = showAll || selectionBeyondFold
    val visibleCards = if (expanded) cards else cards.take(displayCount)

    // Delete is a gesture (long-press) / a11y-action affordance. The pending card drives the
    // confirmation dialog; it lives in the UI, not the controller.
    var cardPendingDelete by remember { mutableStateOf<SavedCard?>(null) }
    // Drop a pending confirmation if its card vanishes from the list underneath the open dialog
    // (a concurrent refresh on app-foreground, or an expiry purge) — otherwise the payer would
    // confirm deleting a card they can no longer see.
    LaunchedEffect(cards) { cardPendingDelete?.let { if (it !in cards) cardPendingDelete = null } }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().selectableGroup(),
    ) {
        CmpSectionHeader(cmpString(CardEntryStringKey.LABEL_SAVED_CARDS))
        if (errorSurface == OneClickErrorSurface.SECTION && oneClickError != null) {
            OneClickErrorText(oneClickError.reason.messageKey())
        }
        visibleCards.forEachIndexed { index, card ->
            CmpSavedCardCell(
                controller,
                card,
                index,
                enabled,
                error = oneClickError?.takeIf {
                    errorSurface == OneClickErrorSurface.INLINE_CARD && it.matches(card)
                },
            ) { requested, viaAccessibility ->
                // Confirmation is opt-in (`confirmCardDeletion`), but always on for a screen-reader
                // request: the custom "Delete" action is a single step with no trash to aim at.
                if (controller.confirmCardDeletion || viaAccessibility) {
                    cardPendingDelete = requested
                } else {
                    scope.launch { controller.deleteSavedCard(requested) }
                }
            }
        }
        if (hasMore) {
            // Persistent disclosure toggle: it stays present across toggles so screen-reader focus is
            // not dropped and its expanded/collapsed state stays truthful. "Show less" is inert while
            // the selection sits beyond the fold (collapsing would hide the paying card).
            CmpShowMoreToggle(
                expanded = expanded,
                enabled = if (expanded) enabled && !selectionBeyondFold else enabled,
                collapseBlocked = selectionBeyondFold,
            ) { showAll = !showAll }
        }
        CmpNewCardHeader(controller, enabled)
    }

    cardPendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { cardPendingDelete = null },
            text = { Text(cmpString(CardEntryStringKey.CONFIRM_DELETE_CARD)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { controller.deleteSavedCard(pending) }
                    cardPendingDelete = null
                }) { Text(cmpString(CardEntryStringKey.LABEL_DELETE_CARD)) }
            },
            dismissButton = {
                TextButton(onClick = { cardPendingDelete = null }) {
                    Text(cmpString(CardEntryStringKey.LABEL_CANCEL))
                }
            },
        )
    }
}

/** One saved-card cell: 2-line masked display, border-only selection (no radio), merged a11y node.
 *  With a one-click [error] targeting this card, an inline error renders under the cell (icon +
 *  text, polite announce) and joins the cell's merged description. */
@Composable
private fun CmpSavedCardCell(
    controller: CmpCardController,
    card: SavedCard,
    index: Int,
    enabled: Boolean,
    error: OneClickError? = null,
    onRequestDelete: (SavedCard, viaAccessibility: Boolean) -> Unit,
) {
    val display = remember(card) { savedCardDisplay(card) }
    val haptics = LocalHapticFeedback.current
    val baseA11yLabel = cmpFormat(
        cmpString(CardEntryStringKey.A11Y_SAVED_CARD),
        display.network?.displayName() ?: card.network,
        display.last4,
        display.displayExpiry,
    )
    // The error is part of the merged cell node: focusing the cell reads why it failed.
    val a11yLabel = error?.let { "$baseA11yLabel, ${cmpString(it.reason.messageKey())}" } ?: baseA11yLabel
    val deleteLabel = cmpString(CardEntryStringKey.LABEL_DELETE_CARD)
    val isSelected = controller.selectedSavedCard == card
    // Cells follow the style's metrics (corner radius, border width) so they agree with the
    // entry fields; the SELECTED cell keeps the platform accent — a thicker accent border
    // (borderWidth + 1, the fields' focus treatment) and an accent tint layered OVER the
    // style's background — as the selection affordance (no accent slot in the contract yet).
    val style = LocalHiPayCardStyle.current
    val cellShape = RoundedCornerShape(style.cornerRadius.dp)
    val border =
        if (isSelected) BorderStroke((style.borderWidth + 1f).dp, MaterialTheme.colorScheme.primary)
        else BorderStroke(style.borderWidth.dp, cmpColor(style.borderColor))
    // The cell + its inline error travel as one visual unit (the field error spacing).
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Left-swipe reveals a trailing trash action; tapping it opens the delete confirmation. An
        // accidental swipe never deletes — only the trash tap (or the retained long-press / a11y
        // "Delete" action) requests deletion. Reverses the earlier no-visible-delete-button
        // behaviour.
        val actionWidthPx = with(LocalDensity.current) { 56.dp.toPx() }
        var swipeOffset by remember(card) { mutableStateOf(0f) }
        // The animated position is keyed to the card so a list reorder (after a deletion) starts the
        // row closed instead of animating the previous row's reveal onto whichever card now sits in
        // this slot.
        val reveal = remember(card) { Animatable(0f) }
        // Reduce-motion (WCAG 2.3.3): snap the reveal to its target instead of sliding it.
        val reduceMotion = reduceMotionEnabled()
        LaunchedEffect(swipeOffset) {
            if (reduceMotion) reveal.snapTo(swipeOffset) else reveal.animateTo(swipeOffset)
        }
        val revealOffset = reveal.value
        // A (re)selection, or the row becoming disabled (payment in flight), snaps the reveal shut.
        LaunchedEffect(isSelected, enabled) { if (isSelected || !enabled) swipeOffset = 0f }
        val swipeState = rememberDraggableState { delta ->
            swipeOffset = (swipeOffset + delta).coerceIn(-actionWidthPx, 0f)
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            // Trash revealed behind the row, pinned to the end — present only while revealed so it
            // never leaks into the a11y tree at rest (the custom "Delete" action covers a11y).
            // Gated on the target offset (not the animated one) so it disappears at once when the
            // reveal is dismissed — never left tappable during the close animation.
            if (swipeOffset < -1f) {
                IconButton(
                    onClick = { swipeOffset = 0f; onRequestDelete(card, false) },
                    enabled = enabled,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .testTag("hipay.card.savedcard.$index.delete"),
                ) {
                    Icon(
                        painter = painterResource(trashIcon),
                        contentDescription = deleteLabel,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(revealOffset.roundToInt(), 0) }
                    .draggable(
                        state = swipeState,
                        orientation = Orientation.Horizontal,
                        enabled = enabled,
                        onDragStopped = {
                            swipeOffset = if (swipeOffset < -actionWidthPx / 2f) -actionWidthPx else 0f
                        },
                    )
                    .heightIn(min = 48.dp)
                    .border(border, cellShape)
                    .background(cmpColor(style.backgroundColor), cellShape)
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
                    // Tap selects; long-press requests delete (kept as the quick/accessible path).
                    .combinedClickable(
                        enabled = enabled,
                        onClick = { controller.selectSavedCard(card) },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            // Long-press REVEALS the trash, exactly like a left-swipe — it never
                            // deletes. Both gestures land on the same state, the payer then either
                            // taps the trash (that tap IS the validation) or swipes back to cancel.
                            swipeOffset = -actionWidthPx
                        },
                    )
                    // One merged node: "<Network> finishing 1111, expires MM / YYYY, selected" — the
                    // bullet glyphs are never announced. The mandatory "Delete" custom action makes
                    // deletion reachable to screen readers (swipe + long-press are invisible to them).
                    .semantics(mergeDescendants = true) {
                        contentDescription = a11yLabel
                        selected = isSelected
                        customActions =
                            if (enabled) {
                                listOf(CustomAccessibilityAction(deleteLabel) { onRequestDelete(card, true); true })
                            } else {
                                emptyList()
                            }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Image(
                    painter = painterResource(display.network?.iconResource() ?: neutralCardIcon),
                    contentDescription = null, // described by the merged cell node
                    // Brand marks stay full-color; only the neutral fallback glyph takes iconColor.
                    colorFilter = if (display.network == null) {
                        ColorFilter.tint(cmpColor(style.iconColor))
                    } else {
                        null
                    },
                    modifier = Modifier.size(width = 32.dp, height = 20.dp),
                )
                Column {
                    Text(
                        display.maskedNumber,
                        style = MaterialTheme.typography.bodyLarge,
                        color = cmpColor(style.textColor),
                    )
                    Text(
                        text = "${card.holder}  ·  ${display.displayExpiry}",
                        style = MaterialTheme.typography.bodySmall,
                        color = cmpColor(style.placeholderColor),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        error?.let { OneClickErrorText(it.reason.messageKey()) }
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

/** "Show more / Show less" disclosure toggle: reveals or hides the saved cards beyond
 *  the display count. A centered button whose expanded/collapsed state carries the meaning for a11y
 *  (the chevron is decorative); it stays present across toggles so screen-reader focus is retained.
 *  When [enabled] is false while expanded, "Show less" is inert (the selection sits beyond the fold);
 *  [collapseBlocked] then adds the reason to the state description, so a screen-reader user is not
 *  left with an unexplained dimmed control.
 *  The test tag mirrors Android's `hipay.card.savedcards.showmore` for a shared UI-test identifier.
 *  It is a literal because `HiPayCardEntryTags` lives in the Android-only `:hipaycard` module and is
 *  unreachable from `commonMain`. */
@Composable
private fun CmpShowMoreToggle(
    expanded: Boolean,
    enabled: Boolean,
    collapseBlocked: Boolean,
    onToggle: () -> Unit,
) {
    val expandState = cmpString(
        if (expanded) CardEntryStringKey.A11Y_EXPANDED else CardEntryStringKey.A11Y_COLLAPSED,
    )
    val blockedReason = cmpString(CardEntryStringKey.A11Y_SHOW_LESS_BLOCKED)
    val stateText = if (collapseBlocked && expanded) "$expandState, $blockedReason" else expandState
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onToggle)
            .testTag("hipay.card.savedcards.showmore")
            .semantics(mergeDescendants = true) { stateDescription = stateText },
    ) {
        Text(
            text = cmpString(
                if (expanded) CardEntryStringKey.LABEL_SHOW_LESS else CardEntryStringKey.LABEL_SHOW_MORE,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        // Decorative direction cue — collapsed points down (reveal), expanded points up (hide).
        Text(
            text = if (expanded) "▴" else "▾",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/** Decorative expand/collapse chevron (the row's state description carries the meaning for a11y). */
@Composable
private fun CmpChevronGlyph(expanded: Boolean) {
    Text(
        text = if (expanded) "▾" else "▸",
        style = MaterialTheme.typography.bodyMedium,
        color = if (expanded) MaterialTheme.colorScheme.primary
        else cmpColor(LocalHiPayCardStyle.current.iconColor),
        modifier = Modifier.clearAndSetSemantics {},
    )
}

/** The shared one-click section-header treatment. */
@Composable
private fun CmpSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = cmpColor(LocalHiPayCardStyle.current.placeholderColor),
        maxLines = 1,
        modifier = modifier,
    )
}

/** In-frame "save this card" switch (consent, default OFF) + one-line consent text. The switch
 *  track/thumb keep the platform accent (the style contract has no accent slot yet) — the
 *  style drives the label and consent text. */
@Composable
private fun CmpSaveCardSwitch(controller: CmpCardController, enabled: Boolean) {
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
            .semantics(mergeDescendants = true) {},
    ) {
        Text(
            text = cmpString(CardEntryStringKey.LABEL_SAVE_CARD),
            style = MaterialTheme.typography.bodyMedium,
            color = cmpColor(style.textColor),
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
        color = cmpColor(style.placeholderColor),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Field label kept on a SINGLE LINE, at the size the decoration box chooses for its state: the full
 * text size while resting inside the field (where it reads as the placeholder's peer) and the smaller
 * floating size once it rises to the top. Overriding the style here would freeze it at the floating
 * size in both states.
 *
 * `maxLines = 1` + no soft-wrap are what actually protect the field height: a label longer than its
 * field used to wrap to two lines and inflate that field, breaking the Expiry/CVC row symmetry. It can
 * no longer wrap at any size — a label too wide for its field overflows horizontally instead. The
 * narrow CVC field is the one to watch, which is also why its label is now the "CVV" acronym in every
 * language. Mirrors the Android `FieldLabel`.
 */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
    )
}

@Composable
private fun NetworkChips(controller: CmpCardController, modifier: Modifier = Modifier) {
    // Show a brand icon whenever a network is offered — including a single one (11.4); a
    // neutral card glyph when none. Mirrors the Android `:hipaycard` NetworkChips (icons, 48dp
    // tap, merged semantics). Selection treatment: unselected brand chips dim to 0.35 alpha —
    // several brand marks sit on an OPAQUE plate (asset audit: amex, cb), so tinting them
    // monochrome flattens the plate into an unreadable block; brand logos are never re-tinted.
    // Only the neutral glyph (a true silhouette) takes the style's iconColor. Selected state
    // stays announced via semantics.
    val iconColor = LocalHiPayCardStyle.current.iconColor
    val iconTint = remember(iconColor) { ColorFilter.tint(cmpColor(iconColor)) }
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val nets: List<CardNetwork> = controller.networks
        if (nets.isEmpty()) {
            Image(
                painter = painterResource(neutralCardIcon),
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

/** Calls `markBlurred(field)` on a focused→unfocused transition (not on first composition) —
 *  the CMP mirror of the Android `blurring` modifier. */
@Composable
private fun Modifier.blurring(controller: CmpCardController, field: Field): Modifier {
    var wasFocused by remember { mutableStateOf(false) }
    return this.onFocusChanged { state ->
        if (wasFocused && !state.isFocused) controller.markBlurred(field)
        wasFocused = state.isFocused
    }
}

@Composable
private fun ErrorText(key: CardEntryStringKey?) {
    if (key != null) {
        Text(text = cmpString(key), color = cmpColor(LocalHiPayCardStyle.current.invalidTextColor))
    }
}

/** One-click error: a glyph (not colour) carries the meaning (WCAG 1.4.1) and the appearance is
 *  announced politely — the CMP mirror of the Android `ErrorSlot` / iOS `errorSlot` pattern. */
@Composable
private fun OneClickErrorText(key: CardEntryStringKey) {
    val errorColor = cmpColor(LocalHiPayCardStyle.current.invalidTextColor)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .padding(start = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text("⚠", color = errorColor, style = MaterialTheme.typography.bodySmall)
        Text(
            text = cmpString(key),
            color = errorColor,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
        )
    }
}
