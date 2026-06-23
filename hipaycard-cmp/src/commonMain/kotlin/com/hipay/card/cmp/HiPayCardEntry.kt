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

    /** Tokenizes the entered card and creates the order; returns the resulting transaction (FR9). */
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
    ): Transaction

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
