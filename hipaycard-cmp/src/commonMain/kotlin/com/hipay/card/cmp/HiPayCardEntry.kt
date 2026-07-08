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
 * CMP card-entry controller (Epic 10) — the shared, Compose-Multiplatform contract a
 * CMP merchant drives from `commonMain`. Mirrors the native `:hipaycard`
 * `HiPayCardEntryController`: the host renders its own pay button and calls [pay];
 * the card token stays internal and the host receives only a [Transaction]
 * (PAN never leaves the component — PCI boundary).
 *
 * Design (i): the Android actual delegates to `:hipaycard`; the iOS actual renders in
 * Compose-MP (placeholder until story 10.2). Types are all from the shared core.
 */
expect class HiPayCardController(
    config: HiPayConfig,
    allowedNetworks: List<CardNetwork> = emptyList(),
    /** Explicit integrator opt-in for the one-click (saved cards) UI — off by default: without it
     *  the component renders and behaves exactly as before and no card store is ever created. */
    oneClickEnabled: Boolean = false,
) {
    /** True when every required field is valid, or a saved card is selected (Compose-observable). */
    val canPay: Boolean

    /** The saved cards offered for one-click, most recent first; empty when none or not loaded. */
    val savedCards: List<SavedCard>

    /** The active selection: a saved card, or null = the new-card branch (fields expanded). */
    val selectedSavedCard: SavedCard?

    /** The in-frame "save this card" switch state (consent) — default OFF, reset after each save. */
    val saveCardOptIn: Boolean

    /** Select [card] (collapses the entry fields — their values are preserved). Ignored when the
     *  card is not one of [savedCards]. */
    fun selectSavedCard(card: SavedCard)

    /** Select the new-card branch (expands the entry fields). */
    fun selectNewCard()

    /** Save-switch handler. */
    fun onSaveCardOptInChange(optIn: Boolean)

    /** (Re)loads [savedCards] and resets the selection (no-op unless one-click opted in). */
    suspend fun refreshSavedCards()

    /** True while a [pay] is in flight (set by the SDK, story 11.14); the card UI locks its fields
     *  on this and the host disables its Pay button with `!canPay || isProcessing`. */
    val isProcessing: Boolean

    /**
     * Tokenizes the entered card and creates the order. On a `FORWARDING` outcome the SDK presents
     * the 3DS challenge itself and returns the FINAL, server-confirmed [Transaction] (FR9). The
     * [threeDS] mode (iOS): [HiPayThreeDSMode.IN_APP_SESSION] (default) = in-app
     * `ASWebAuthenticationSession` (self-captures the callback, no host wiring);
     * [HiPayThreeDSMode.EXTERNAL_BROWSER] = external Safari (host forwards the return via
     * [resume3DS]). On Android both modes use Chrome Custom Tabs + [resume3DS] from `onNewIntent`.
     */
    suspend fun pay(
        orderId: String,
        amount: String,
        currency: String = "EUR",
        description: String,
        language: String = "en_GB",
        redirectScheme: String,
        authenticationIndicator: Int = 0,
        signature: String? = null,
        customer: CustomerInfo? = null,
        shipping: CustomerInfo? = null,
        threeDS: HiPayThreeDSMode = HiPayThreeDSMode.IN_APP_SESSION,
        saveCard: Boolean = false,
    ): Transaction

    /**
     * One-click payment with a previously saved card: the order is created directly from the
     * stored reusable token — no card re-entry, no CVV, no tokenization round-trip. 3DS behaves
     * exactly as in [pay]. On a final `COMPLETED` the card's recency is bumped. If the gateway
     * reports the stored token as no longer usable, the card is purged from local storage and a
     * `HiPayException` with `HiPayErrorCode.CARD_NO_LONGER_VALID` is thrown — fall back to card
     * entry. A declined payment is returned as a normal `DECLINED` transaction.
     *
     * Android: requires the card component rendered (it binds the presentation context) or an
     * explicitly bound context; fails fast with `IllegalStateException` otherwise.
     */
    suspend fun payWithSavedCard(
        card: SavedCard,
        orderId: String,
        amount: String,
        currency: String = "EUR",
        description: String,
        language: String = "en_GB",
        redirectScheme: String,
        authenticationIndicator: Int = 0,
        signature: String? = null,
        customer: CustomerInfo? = null,
        shipping: CustomerInfo? = null,
        threeDS: HiPayThreeDSMode = HiPayThreeDSMode.IN_APP_SESSION,
    ): Transaction

    /**
     * Result of the most recent `pay(saveCard = true)` save attempt (observable), for the host to
     * react to (e.g. a "card saved" / "card not saved" notice). Null when no save was attempted.
     */
    val lastSaveOutcome: SavedCardOutcome?

    /**
     * Forward the 3DS deep-link return here (Android: from the host Activity's `onNewIntent`) so the
     * SDK confirms + resumes the suspended [pay]. No-op on iOS (the in-app session self-captures) and
     * when no 3DS is pending. Story 11.13.
     */
    fun resume3DS(url: String)

    /** Releases the controller's resources (owned coroutine scope). */
    fun dispose()
}

/**
 * Shared card-entry composable. Call from your CMP `commonMain`.
 *
 * @param localeOverride optional ISO language ("fr"/"en"/"it"); null → device locale (D11).
 */
@Composable
expect fun HiPayCardEntry(
    controller: HiPayCardController,
    modifier: Modifier = Modifier,
    setsAccessibilityOrder: Boolean = true,
    localeOverride: String? = null,
)
