package com.hipay.card.cmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hipay.card.HiPayCardNetwork
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
import com.hipay.card.HiPayCardEntry as NativeCardEntry
import com.hipay.card.HiPayCardEntryController as NativeController

/**
 * Android actual — delegates to the native `:hipaycard`, which Compose-Multiplatform lets us call
 * directly, so the UX, validation, accessibility and i18n are the native ones rather than copies.
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
        // The delegation below hides the surface, so it is declared before it is lost.
        @OptIn(HiPayInternalApi::class)
        HiPayIntegrationSurface.declareComposeMultiplatform()
    }

    internal val delegate = NativeController(
        config = config,
        // The shared CardNetwork mapped to the Android enum; unmappable values are dropped.
        allowedNetworks = allowedNetworks.mapNotNull { HiPayCardNetwork.from(it) },
        oneClickEnabled = oneClickEnabled,
        savedCardsDisplayCount = savedCardsDisplayCount,
        confirmCardDeletion = confirmCardDeletion,
        currency = currency,
    )

    actual val canPay: Boolean get() = delegate.canPay
    actual val isProcessing: Boolean get() = delegate.isProcessing

    actual val savedCards: List<SavedCard> get() = delegate.savedCards
    actual val selectedSavedCard: SavedCard? get() = delegate.selectedSavedCard
    actual val saveCardOptIn: Boolean get() = delegate.saveCardOptIn
    actual fun selectSavedCard(card: SavedCard) = delegate.selectSavedCard(card)
    actual fun selectNewCard() = delegate.selectNewCard()
    actual val savedCardsLoaded: Boolean get() = delegate.savedCardsLoaded
    actual val canCollapseNewCard: Boolean get() = delegate.canCollapseNewCard
    actual fun collapseNewCard() = delegate.collapseNewCard()
    actual fun onSaveCardOptInChange(optIn: Boolean) = delegate.onSaveCardOptInChange(optIn)
    actual suspend fun refreshSavedCards() = delegate.refreshSavedCards()
    actual suspend fun deleteSavedCard(card: SavedCard) = delegate.deleteSavedCard(card)

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
    ): Transaction = delegate.pay(
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
        // Both modes present 3DS in Custom Tabs, and the host forwards the return via resume3DS.
        autoPresent3DS = true,
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
    ): Transaction = delegate.payWithSavedCard(
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
        autoPresent3DS = true,
        options = options,
    )

    actual val paymentPhase: PaymentPhase? get() = delegate.paymentPhase

    actual val lastSaveOutcome: SavedCardOutcome? get() = delegate.lastSaveOutcome

    // The host forwards onNewIntent here, whatever the mode.
    actual fun resume3DS(url: String) = delegate.resume3DS(url)

    actual fun dispose() = delegate.dispose()
}

@Composable
actual fun HiPayCardEntry(
    controller: HiPayCardController,
    modifier: Modifier,
    setsAccessibilityOrder: Boolean,
    localeOverride: String?,
    style: HiPayCardEntryStyle,
) {
    // The native renderer applies the shared style, so this delegation inherits the styled look.
    NativeCardEntry(
        controller = controller.delegate,
        modifier = modifier,
        setsAccessibilityOrder = setsAccessibilityOrder,
        localeOverride = localeOverride,
        style = style,
    )
}
