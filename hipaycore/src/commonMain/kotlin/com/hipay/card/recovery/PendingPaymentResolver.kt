// PCI: com.hipay.card path — NEVER log here.
package com.hipay.card.recovery

import com.hipay.core.HiPayConfig
import com.hipay.core.HiPayException
import com.hipay.core.gateway.GatewayClient
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reads back the state of payments this device launched, from the host's own order id.
 *
 * Read-only by construction: it asks the gateway what happened, it never re-submits an order. The
 * store it reads holds no card token, so no path from here could.
 *
 * The host never handles a HiPay transaction reference — it asks with the id it created, and the
 * correspondence is kept inside. Authentication is the account's own credentials, the same ones that
 * created the order; nothing secret is persisted for it.
 */
public class PendingPaymentResolver internal constructor(
    private val store: PendingPaymentStore,
    private val gateway: GatewayClient,
) {
    public constructor(config: HiPayConfig, store: PendingPaymentStore) : this(store, GatewayClient(config))

    /** Everything launched and not yet acknowledged, newest first. Reads storage, not the network. */
    public fun unresolvedPayments(): List<HiPayPendingPayment> = store.unresolvedPayments()

    /** Drops the entry for [orderId] once the host has recorded the outcome. */
    public fun acknowledge(orderId: String): Boolean = store.acknowledge(orderId)

    /**
     * Asks the gateway where [orderId] stands and returns its refreshed snapshot, or null if this
     * device never launched it.
     *
     * A payment whose order was never answered carries no reference, so there is nothing to ask about:
     * its snapshot comes back unchanged, without a network call. That window is observable, not
     * recoverable — the bridge from an order id to a transaction exists on the merchant's backend, in
     * the webhook keyed on that same id.
     *
     * A terminal answer does NOT delete the entry: only [acknowledge] or its lifetime does, so a host
     * that dies between this call and its own bookkeeping can ask again.
     *
     * Throws on an unreachable gateway rather than inventing an outcome. The entry is kept, so the
     * call can simply be retried — an exception here says the question could not be asked, never that
     * the payment failed.
     */
    @Throws(HiPayException::class, CancellationException::class)
    public suspend fun refreshPayment(orderId: String): HiPayPendingPayment? {
        val known = store.unresolvedPayments().firstOrNull { it.orderId == orderId } ?: return null
        val reference = store.referenceFor(orderId) ?: return known
        val transaction = gateway.getTransaction(reference)
        store.complete(orderId, reference, transaction.state)
        return store.unresolvedPayments().firstOrNull { it.orderId == orderId }
    }
}
