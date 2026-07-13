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
import com.hipay.core.gateway.model.Transaction
import com.hipay.card.HiPayCardEntry as NativeCardEntry
import com.hipay.card.HiPayCardEntryController as NativeController

/**
 * Android actual (story 10.1) — delegates to the native `:hipaycard` component, so the
 * Android UX/validation/accessibility/i18n are exactly the native ones (reused, not
 * reimplemented). Compose-Multiplatform unifies with Jetpack Compose on Android, so the
 * native `@Composable` is called directly.
 */
actual class HiPayCardController actual constructor(
    config: HiPayConfig,
    allowedNetworks: List<CardNetwork>,
    oneClickEnabled: Boolean,
) {
    internal val delegate = NativeController(
        config = config,
        // Public commonMain API uses the shared CardNetwork; map to the Android enum
        // (UNKNOWN / unmappable → dropped).
        allowedNetworks = allowedNetworks.mapNotNull { HiPayCardNetwork.from(it) },
        oneClickEnabled = oneClickEnabled,
    )

    actual val canPay: Boolean get() = delegate.canPay
    actual val isProcessing: Boolean get() = delegate.isProcessing

    actual val savedCards: List<SavedCard> get() = delegate.savedCards
    actual val selectedSavedCard: SavedCard? get() = delegate.selectedSavedCard
    actual val saveCardOptIn: Boolean get() = delegate.saveCardOptIn
    actual fun selectSavedCard(card: SavedCard) = delegate.selectSavedCard(card)
    actual fun selectNewCard() = delegate.selectNewCard()
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
        // Android presents 3DS in Chrome Custom Tabs for both modes (no external/in-app distinction,
        // no soft-lock); the host forwards the return via resume3DS either way.
        autoPresent3DS = true,
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
    )

    actual val lastSaveOutcome: SavedCardOutcome? get() = delegate.lastSaveOutcome

    // Android 3DS = native :hipaycard Custom Tabs (story 11.13); the host forwards onNewIntent here.
    actual fun resume3DS(url: String) = delegate.resume3DS(url)

    actual fun dispose() = delegate.dispose()
}

@Composable
actual fun HiPayCardEntry(
    controller: HiPayCardController,
    modifier: Modifier,
    setsAccessibilityOrder: Boolean,
    localeOverride: String?,
    // Not forwarded yet: the native :hipaycard renderer this actual delegates to gains its
    // style parameter in the native-styling release; the shared contract lands here first so
    // integrator call sites are source-stable across both targets.
    @Suppress("UNUSED_PARAMETER") style: HiPayCardEntryStyle,
) {
    NativeCardEntry(
        controller = controller.delegate,
        modifier = modifier,
        setsAccessibilityOrder = setsAccessibilityOrder,
        localeOverride = localeOverride,
    )
}
