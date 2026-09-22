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
     * [signature] is the same HS signature your backend computed for that order — measured on stage:
     * an account whose orders are signed refuses an unsigned read with `401`. The snapshot carries the
     * amount and currency precisely so the signature can be recomputed from it.
     *
     * A payment whose order was never answered carries no reference — and is still recoverable: the
     * gateway finds it from the order id, so the window between sending an order and losing its
     * response is not a dead end. The reference it returns is kept, so the next read goes direct.
     *
     * A terminal answer does NOT delete the entry: only [acknowledge] or its lifetime does, so a host
     * that dies between this call and its own bookkeeping can ask again.
     *
     * Throws on an unreachable gateway rather than inventing an outcome. The entry is kept, so the
     * call can simply be retried — an exception here says the question could not be asked, never that
     * the payment failed.
     */
    @Throws(HiPayException::class, CancellationException::class)
    public suspend fun refreshPayment(orderId: String, signature: String? = null): HiPayPendingPayment? {
        store.unresolvedPayments().firstOrNull { it.orderId == orderId } ?: return null
        val reference = store.referenceFor(orderId)
        val transaction = if (reference != null) {
            gateway.getTransaction(reference, signature)
        } else {
            gateway.getTransactionByOrderId(orderId, signature)
        }
        store.complete(orderId, transaction.transactionReference ?: reference, transaction.state)
        return store.unresolvedPayments().firstOrNull { it.orderId == orderId }
    }
}
