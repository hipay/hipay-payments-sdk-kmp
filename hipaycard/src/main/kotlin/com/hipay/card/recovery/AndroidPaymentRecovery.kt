// PCI: com.hipay.card path — NEVER log here.
package com.hipay.card.recovery

import android.content.Context
import com.hipay.core.HiPayConfig

/**
 * The recovery entry point on Android. Build it at launch — it is cheap, and the secure store behind
 * it opens on first use, on a background thread.
 *
 * A [Context] is required and cannot come from the card component: after a process death there is no
 * component on screen, which is exactly when this is asked.
 */
public fun hiPayPaymentRecovery(
    context: Context,
    config: HiPayConfig,
    pendingTtlMillis: Long = DEFAULT_PENDING_PAYMENT_TTL_MILLIS,
    resolvedTtlMillis: Long = DEFAULT_RESOLVED_PAYMENT_TTL_MILLIS,
): HiPayPaymentRecovery {
    val app = context.applicationContext
    return hiPayPaymentRecovery(config) {
        createPendingPaymentStore(app, config, pendingTtlMillis, resolvedTtlMillis)
    }
}
