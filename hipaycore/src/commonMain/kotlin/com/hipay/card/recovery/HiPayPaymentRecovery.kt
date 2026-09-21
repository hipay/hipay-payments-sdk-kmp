// PCI: com.hipay.card path — NEVER log here.
package com.hipay.card.recovery

import com.hipay.core.HiPayConfig
import com.hipay.core.HiPayException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Find payments this device launched but never got to report — after a background, an abnormal
 * error, or a process death. Not part of the card component, because after a process death there is
 * no component left to ask.
 *
 * Read-only: the store it reads holds no card token, so no path from here re-submits an order.
 *
 * **Payment finality still belongs to the webhook.** This exists so an app can re-open the right
 * screen and never conclude "failed" from an interruption.
 *
 * Every call suspends and runs on one background thread: platform secure storage blocks and the store
 * is not thread-safe. Expiry is evaluated on read, which is why the lifetimes are set here.
 */
public class HiPayPaymentRecovery internal constructor(
    // Built on first use, on the dispatcher: platform secure storage opens blocking, and a host may
    // construct this from the main thread at launch.
    private val openResolver: () -> PendingPaymentResolver,
    // Serial on purpose: the store is a read-modify-write with no locking of its own.
    private val storeDispatcher: CoroutineDispatcher,
) {
    private var resolver: PendingPaymentResolver? = null

    private fun resolver(): PendingPaymentResolver = resolver ?: openResolver().also { resolver = it }

    /**
     * What the host has not acknowledged, newest first. A payment that reached a terminal state is
     * still listed: after a process death that is the only way the host learns the outcome it missed.
     */
    public suspend fun unresolvedPayments(): List<HiPayPendingPayment> =
        withContext(storeDispatcher) { resolver().unresolvedPayments() }

    /**
     * Where [orderId] stands, or null if this device never launched it. An order that was never
     * answered comes back unchanged and without a network call — nothing links it to a transaction.
     *
     * Pass the [signature] your backend computed for that order when your account signs its orders:
     * such an account refuses an unsigned read. The snapshot carries the amount and currency so the
     * signature can be recomputed from it.
     *
     * Throws rather than invent an outcome; the entry is kept, so the call can be retried.
     */
    @Throws(HiPayException::class, CancellationException::class)
    public suspend fun refreshPayment(orderId: String, signature: String? = null): HiPayPendingPayment? =
        withContext(storeDispatcher) { resolver().refreshPayment(orderId, signature) }

    /**
     * Drops the entry for [orderId], once the host has recorded the outcome. Nothing else removes one
     * before its lifetime runs out: a host may still die between learning an outcome and writing it
     * down, which is the case this exists for.
     */
    public suspend fun acknowledge(orderId: String): Boolean =
        withContext(storeDispatcher) { resolver().acknowledge(orderId) }
}

/** Each surface passes its own [openStore], called once on the store's own thread. */
public fun hiPayPaymentRecovery(
    config: HiPayConfig,
    openStore: () -> PendingPaymentStore,
): HiPayPaymentRecovery = HiPayPaymentRecovery(
    openResolver = { PendingPaymentResolver(config, openStore()) },
    storeDispatcher = recoveryDispatcher(),
)

/** A serial dispatcher for the store, the same confinement the saved-card store gets. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun recoveryDispatcher(): CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
