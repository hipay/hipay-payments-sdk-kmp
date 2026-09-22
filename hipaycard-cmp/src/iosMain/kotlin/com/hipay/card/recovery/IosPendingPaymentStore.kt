// PCI: com.hipay.card path — NEVER log here.
package com.hipay.card.recovery

import com.hipay.card.store.IosSecureCardStore
import com.hipay.core.HiPayConfig
import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970

/** Its own flag, armed only after a confirmed purge, so a transient failure retries on the next launch. */
private const val PENDING_PAYMENTS_LAUNCHED_KEY = "com.hipay.pendingpayments.launched"

/**
 * Assemble a ready [PendingPaymentStore] on iOS, on the same Keychain primitive the saved cards use,
 * under its own namespace.
 *
 * Runs a first-launch purge for the same reason the saved-card store does: the Keychain survives an
 * uninstall, so without it a reinstall would list payments from a previous install. It covers THIS
 * configuration's namespace, not every one — deleting by service, as the saved-card purge does, would
 * also wipe the payer's saved cards when an app upgrades from a version that already armed their flag.
 *
 * [IosSecureCardStore.clear] throws on an unexpected Keychain status, so the guard below is real.
 */
public fun createPendingPaymentStore(
    config: HiPayConfig,
    pendingTtlMillis: Long = DEFAULT_PENDING_PAYMENT_TTL_MILLIS,
    resolvedTtlMillis: Long = DEFAULT_RESOLVED_PAYMENT_TTL_MILLIS,
): PendingPaymentStore {
    val raw = IosSecureCardStore(pendingPaymentNamespace(config))
    val defaults = NSUserDefaults.standardUserDefaults
    if (!defaults.boolForKey(PENDING_PAYMENTS_LAUNCHED_KEY)) {
        if (runCatching { raw.clear() }.isSuccess) {
            defaults.setBool(true, PENDING_PAYMENTS_LAUNCHED_KEY)
        }
    }
    return PendingPaymentStore(
        raw = raw,
        now = { (NSDate().timeIntervalSince1970 * 1000).toLong() },
        pendingTtlMillis = pendingTtlMillis,
        resolvedTtlMillis = resolvedTtlMillis,
    )
}
