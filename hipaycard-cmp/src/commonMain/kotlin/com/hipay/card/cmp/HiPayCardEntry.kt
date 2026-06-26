package com.hipay.card.cmp

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
) {
    /** True when every required field is valid (Compose-observable). */
    val canPay: Boolean

    /** True while a [pay] is in flight (set by the SDK, story 11.14); the card UI locks its fields
     *  on this and the host disables its Pay button with `!canPay || isProcessing`. */
    val isProcessing: Boolean

    /**
     * Tokenizes the entered card and creates the order. With [autoPresent3DS] = true (default) and
     * a `FORWARDING` outcome, the SDK presents the 3DS challenge itself — iOS in an in-app
     * `ASWebAuthenticationSession` (self-captures the callback), Android in Chrome Custom Tabs (the
     * host forwards the deep-link return via [resume3DS]) — and returns the FINAL, server-confirmed
     * [Transaction] (FR9). With `false` (or if no presentation is possible) the raw `FORWARDING`
     * transaction is returned for manual handling (story 11.13).
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
        autoPresent3DS: Boolean = true,
    ): Transaction

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
