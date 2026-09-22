// PCI: com.hipay.card path — never log here.
package com.hipay.card.cmp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.hipay.card.recovery.HiPayPaymentRecovery
import com.hipay.card.recovery.PendingPaymentStore
import com.hipay.card.recovery.createPendingPaymentStore
import com.hipay.card.recovery.hiPayPaymentRecovery
import com.hipay.core.HiPayConfig

/** iOS actual: no platform context to reach, only the Keychain-backed store. */
@Composable
public actual fun rememberHiPayPaymentRecovery(
    config: HiPayConfig,
    pendingTtlMillis: Long,
    resolvedTtlMillis: Long,
): HiPayPaymentRecovery =
    remember(config, pendingTtlMillis, resolvedTtlMillis) {
        hiPayPaymentRecovery(config) {
            createPendingPaymentStore(config, pendingTtlMillis, resolvedTtlMillis)
        }
    }

/** Lifetimes are the reader's business — the controller only writes. */
internal actual fun createCmpPendingPaymentStore(config: HiPayConfig): PendingPaymentStore =
    createPendingPaymentStore(config)
