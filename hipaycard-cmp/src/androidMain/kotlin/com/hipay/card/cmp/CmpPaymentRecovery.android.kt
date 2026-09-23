// PCI: com.hipay.card path — never log here.
package com.hipay.card.cmp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.hipay.card.recovery.HiPayPaymentRecovery
import com.hipay.card.recovery.PendingPaymentStore
import com.hipay.card.recovery.hiPayPaymentRecovery
import com.hipay.core.HiPayConfig

/** Android actual: the same entry point the native surface uses, on the composition's context. */
@Composable
public actual fun rememberHiPayPaymentRecovery(
    config: HiPayConfig,
    pendingTtlMillis: Long,
    resolvedTtlMillis: Long,
): HiPayPaymentRecovery {
    val context = LocalContext.current
    return remember(config, pendingTtlMillis, resolvedTtlMillis) {
        hiPayPaymentRecovery(context, config, pendingTtlMillis, resolvedTtlMillis)
    }
}

/**
 * Never called, unlike the entry point above: reading is what a host asks for directly, while writing
 * belongs to the controller — and on Android the CMP controller delegates to the native `:hipaycard`
 * one, which owns the Keystore-backed stores. Same rationale as [createCmpSecureCardStore].
 */
internal actual fun createCmpPendingPaymentStore(config: HiPayConfig): PendingPaymentStore =
    throw UnsupportedOperationException(
        "Android CMP payments go through the native :hipaycard controller",
    )
