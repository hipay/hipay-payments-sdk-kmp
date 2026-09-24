package com.hipay.card.cmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hipay.card.store.SavedCard
import com.hipay.card.store.SavedCardOutcome
import com.hipay.card.style.HiPayCardEntryStyle
import com.hipay.card.validation.CardNetwork
import com.hipay.core.HiPayConfig
import com.hipay.core.gateway.model.CustomerInfo
import com.hipay.card.PaymentPhase
import com.hipay.core.gateway.model.OrderOptions
import com.hipay.core.gateway.model.Transaction
import com.hipay.core.monitoring.HiPayIntegrationSurface
import com.hipay.core.monitoring.HiPayInternalApi

/**
 * iOS actual — renders the shared Compose-Multiplatform card UI (Skia) over [CmpCardController];
 * wrapping the SwiftUI `HiPayCard` is not possible from here, and it stays for native merchants.
 * Strings follow the device locale or `localeOverride`; PAN and token never leave the controller.
 */
actual class HiPayCardController actual constructor(
    config: HiPayConfig,
    allowedNetworks: List<CardNetwork>,
    oneClickEnabled: Boolean,
    savedCardsDisplayCount: Int,
    confirmCardDeletion: Boolean,
    currency: String,
) {
    init {
        // The shared Compose card UI, not the native SwiftUI one.
        @OptIn(HiPayInternalApi::class)
        HiPayIntegrationSurface.declareComposeMultiplatform()
    }

    internal val impl = CmpCardController(
        config = config,
        allowed = allowedNetworks,
        oneClickEnabled = oneClickEnabled,
        savedCardsDisplayCount = savedCardsDisplayCount,
        confirmCardDeletion = confirmCardDeletion,
        currency = currency,
    )

    actual val canPay: Boolean get() = impl.canPay
    actual val isProcessing: Boolean get() = impl.isProcessing

    actual val savedCards: List<SavedCard> get() = impl.savedCards
    actual val selectedSavedCard: SavedCard? get() = impl.selectedSavedCard
    actual val saveCardOptIn: Boolean get() = impl.saveCardOptIn
    actual fun selectSavedCard(card: SavedCard) = impl.selectSavedCard(card)
    actual fun selectNewCard() = impl.selectNewCard()
    actual val savedCardsLoaded: Boolean get() = impl.savedCardsLoaded
    actual val canCollapseNewCard: Boolean get() = impl.canCollapseNewCard
    actual fun collapseNewCard() = impl.collapseNewCard()
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
        options: OrderOptions?,
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
        options = options,
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
        options: OrderOptions?,
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
        options = options,
    )

    actual val paymentPhase: PaymentPhase? get() = impl.paymentPhase

    actual val lastSaveOutcome: SavedCardOutcome? get() = impl.lastSaveOutcome

    // ASWebAuthenticationSession captures the callback itself, so the host wires nothing.
    actual fun resume3DS(url: String) = impl.resume3DS(url)

    actual fun dispose() = impl.dispose()
}

@Composable
actual fun HiPayCardEntry(
    controller: HiPayCardController,
    modifier: Modifier,
    setsAccessibilityOrder: Boolean,
    localeOverride: String?,
    style: HiPayCardEntryStyle,
) {
    CmpCardEntry(
        controller = controller.impl,
        modifier = modifier,
        setsAccessibilityOrder = setsAccessibilityOrder,
        localeOverride = localeOverride,
        style = style,
    )
}
