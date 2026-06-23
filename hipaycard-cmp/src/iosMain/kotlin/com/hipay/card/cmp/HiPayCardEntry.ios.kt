package com.hipay.card.cmp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hipay.card.validation.CardNetwork
import com.hipay.core.HiPayConfig
import com.hipay.core.gateway.model.CustomerInfo
import com.hipay.core.gateway.model.Transaction

/**
 * iOS actual — PLACEHOLDER (story 10.1). The real Compose-Multiplatform rendering of
 * the card UI on iOS (design (i), Skia) lands in story 10.2. This compiles for the iOS
 * targets so the module builds and publishes, but is non-functional on iOS.
 *
 * Native iOS merchants use the SwiftUI `HiPayCard` (unchanged); this module is for CMP
 * merchants whose iOS support arrives in 10.2.
 */
actual class HiPayCardController actual constructor(
    @Suppress("UNUSED_PARAMETER") config: HiPayConfig,
    @Suppress("UNUSED_PARAMETER") allowedNetworks: List<CardNetwork>,
) {
    actual val canPay: Boolean = false

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
        // Catchable on purpose (IllegalStateException, not NotImplementedError/Error):
        // a CMP host shipping Android-first must fail gracefully on iOS, not hard-crash.
    ): Transaction = throw IllegalStateException("HiPay card UI is not yet available on iOS (story 10.2)")

    actual fun dispose() {}
}

@Composable
actual fun HiPayCardEntry(
    controller: HiPayCardController,
    modifier: Modifier,
    setsAccessibilityOrder: Boolean,
    localeOverride: String?,
) {
    Box(modifier = modifier.padding(16.dp)) {
        Text("HiPay card entry — iOS rendering arrives in story 10.2")
    }
}
