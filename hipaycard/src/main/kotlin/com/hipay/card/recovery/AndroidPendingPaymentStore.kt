// PCI: com.hipay.card path — NEVER log here.
package com.hipay.card.recovery

import android.content.Context
import android.os.Looper
import com.hipay.card.store.AndroidSecureCardStore
import com.hipay.core.HiPayConfig

/**
 * Assemble a ready [PendingPaymentStore] on Android, on the same Keystore+DataStore primitive the
 * saved cards use, under its own namespace so the two never see each other.
 *
 * No fresh-install purge here, unlike the saved-card store: Android drops an app's data on uninstall,
 * so nothing from a previous install can come back.
 *
 * MUST be called from a background thread: it performs blocking disk I/O, and so does the store it
 * returns.
 */
public fun createPendingPaymentStore(
    context: Context,
    config: HiPayConfig,
    pendingTtlMillis: Long = DEFAULT_PENDING_PAYMENT_TTL_MILLIS,
    resolvedTtlMillis: Long = DEFAULT_RESOLVED_PAYMENT_TTL_MILLIS,
): PendingPaymentStore {
    check(Looper.myLooper() != Looper.getMainLooper()) {
        "createPendingPaymentStore performs blocking disk I/O — call it from a background thread."
    }
    return PendingPaymentStore(
        raw = AndroidSecureCardStore(context.applicationContext, pendingPaymentNamespace(config)),
        now = { System.currentTimeMillis() },
        pendingTtlMillis = pendingTtlMillis,
        resolvedTtlMillis = resolvedTtlMillis,
    )
}
