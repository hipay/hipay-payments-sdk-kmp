package com.hipay.card.cmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hipay.card.store.SavedCard
import com.hipay.card.store.SavedCardOutcome
import com.hipay.card.validation.CardNetwork
import com.hipay.core.HiPayConfig
import com.hipay.core.gateway.model.CustomerInfo
import com.hipay.core.gateway.model.Transaction

/**
 * iOS actual (story 10.2, slice A) — renders the shared Compose-Multiplatform card UI (Skia),
 * driven by the commonMain [CmpCardController]. Design (i): iOS cannot wrap the Swift
 * `HiPayCard`, so the card is rendered in Compose-MP. (The native SwiftUI `HiPayCard` stays
 * for native iOS merchants.)
 *
 * Slice A: working entry + tokenize/pay over the headless core; EN strings; basic UI.
 * i18n FR/EN/IT + full a11y/tooltip = slice B. PAN/token never leave the controller (PCI/NFR2).
 */
actual class HiPayCardController actual constructor(
    config: HiPayConfig,
    allowedNetworks: List<CardNetwork>,
    oneClickEnabled: Boolean,
) {
    internal val impl = CmpCardController(config, allowedNetworks, oneClickEnabled)

    actual val canPay: Boolean get() = impl.canPay
    actual val isProcessing: Boolean get() = impl.isProcessing

    actual val savedCards: List<SavedCard> get() = impl.savedCards
    actual val selectedSavedCard: SavedCard? get() = impl.selectedSavedCard
    actual val saveCardOptIn: Boolean get() = impl.saveCardOptIn
    actual fun selectSavedCard(card: SavedCard) = impl.selectSavedCard(card)
    actual fun selectNewCard() = impl.selectNewCard()
    actual fun onSaveCardOptInChange(optIn: Boolean) = impl.onSaveCardOptInChange(optIn)
    actual suspend fun refreshSavedCards() = impl.refreshSavedCards()
    actual suspend fun deleteSavedCard(card: SavedCard) = impl.deleteSavedCard(card)

    actual suspend fun pay(
        orderId: String,
        amount: String,
        currency: String,
        description: String,
        language: String,
        redirectScheme: String,
        authenticationIndicator: Int,
        signature: String?,
        customer: CustomerInfo?,
        shipping: CustomerInfo?,
        threeDS: HiPayThreeDSMode,
        saveCard: Boolean,
    ): Transaction = impl.pay(
        orderId = orderId,
        amount = amount,
        currency = currency,
        description = description,
        language = language,
        redirectScheme = redirectScheme,
        authenticationIndicator = authenticationIndicator,
        signature = signature,
        customer = customer,
        shipping = shipping,
        threeDS = threeDS,
        saveCard = saveCard,
    )

    actual suspend fun payWithSavedCard(
        card: SavedCard,
        orderId: String,
        amount: String,
        currency: String,
        description: String,
        language: String,
        redirectScheme: String,
        authenticationIndicator: Int,
        signature: String?,
        customer: CustomerInfo?,
        shipping: CustomerInfo?,
        threeDS: HiPayThreeDSMode,
    ): Transaction = impl.payWithSavedCard(
        card = card,
        orderId = orderId,
        amount = amount,
        currency = currency,
        description = description,
        language = language,
        redirectScheme = redirectScheme,
        authenticationIndicator = authenticationIndicator,
        signature = signature,
        customer = customer,
        shipping = shipping,
        threeDS = threeDS,
    )

    actual val lastSaveOutcome: SavedCardOutcome? get() = impl.lastSaveOutcome

    // iOS 3DS = in-app ASWebAuthenticationSession (self-captures the callback); no host wiring.
    actual fun resume3DS(url: String) = impl.resume3DS(url)

    actual fun dispose() = impl.dispose()
}

@Composable
actual fun HiPayCardEntry(
    controller: HiPayCardController,
    modifier: Modifier,
    setsAccessibilityOrder: Boolean,
    localeOverride: String?,
) {
    CmpCardEntry(
        controller = controller.impl,
        modifier = modifier,
        setsAccessibilityOrder = setsAccessibilityOrder,
        localeOverride = localeOverride,
    )
}
