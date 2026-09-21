// PCI: com.hipay.card path — NEVER log here.
package com.hipay.card.recovery

import com.hipay.card.store.RawSecureStore
import com.hipay.core.gateway.model.TransactionState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Bumped only on a breaking envelope change; an unknown version reads as an empty store. */
internal const val PENDING_PAYMENTS_VERSION = 1

/**
 * What a launched payment leaves behind. No PAN, no CVV, no card token, and no HS signature: the
 * absence of a token is not only data minimisation, it makes replaying the payment structurally
 * impossible from this store.
 */
@Serializable
internal class StoredPendingPayment(
    val orderId: String,
    val reference: String? = null,
    val amount: String,
    val currency: String,
    val state: String,
    val createdAt: Long,
    val resolvedAt: Long? = null,
)

@Serializable
internal class PendingPaymentsEnvelope(
    val version: Int = PENDING_PAYMENTS_VERSION,
    val payments: List<StoredPendingPayment> = emptyList(),
)

// Strict on purpose, like the saved-card envelope: this store owns its shape, so an unexpected one is
// corruption. `encodeDefaults` is NOT optional here — without it the version equals its default and is
// dropped from the blob, so a future reader would take an old envelope for one of its own and the
// version gate below would never fire. Resolved through a getter to dodge the release-framework
// file-initializer issue that left a file-level `val` null when reached through the ObjC bridge.
private inline val storeJson: Json get() = Json { encodeDefaults = true }

/**
 * Remembers what was launched, so an interrupted payment can be found again.
 *
 * Read-only recovery: it records and it reads, it never re-submits an order — which is why it holds
 * no card token. An entry survives its own resolution and stays until the host acknowledges it or its
 * lifetime runs out: deleting on resolution would lose exactly the case this exists for, a host that
 * dies between receiving the outcome and recording it.
 *
 * Fail-soft throughout, like the saved-card store: a storage or parse failure reads as "nothing
 * pending" and never throws, because a recovery convenience must not be able to break a payment.
 *
 * NOT thread-safe: every mutator is a read-modify-write with no locking, and platform stores may do
 * blocking I/O. Confine it to a single background thread.
 *
 * @param raw the platform secure-storage primitive, the same one the saved-card store uses.
 * @param now epoch milliseconds, injected so the core carries no date dependency.
 * @param pendingTtlMillis how long an unanswered payment is kept.
 * @param resolvedTtlMillis how long a terminal payment is kept after it resolved.
 */
public class PendingPaymentStore(
    private val raw: RawSecureStore,
    private val now: () -> Long,
    private val pendingTtlMillis: Long = DEFAULT_PENDING_PAYMENT_TTL_MILLIS,
    private val resolvedTtlMillis: Long = DEFAULT_RESOLVED_PAYMENT_TTL_MILLIS,
) {

    /**
     * Everything this device launched and the host has not acknowledged, newest first, expired
     * entries already dropped. A terminal payment is still listed — that is the point: after a
     * process death the host has no other way to learn the outcome it missed.
     */
    public fun unresolvedPayments(): List<HiPayPendingPayment> =
        load().payments.sortedByDescending { it.createdAt }.map { it.toPublic() }

    /** Drops the entry for [orderId]. Returns whether anything was removed. */
    public fun acknowledge(orderId: String): Boolean {
        val env = load()
        val kept = env.payments.filterNot { it.orderId == orderId }
        if (kept.size == env.payments.size) return false
        return persist(PendingPaymentsEnvelope(payments = kept))
    }

    /** The stored reference for [orderId], or null when the order was never answered. */
    internal fun referenceFor(orderId: String): String? =
        load().payments.firstOrNull { it.orderId == orderId }?.reference

    /**
     * Records a payment about to be submitted, before the order leaves the device — the window where
     * a crash would otherwise leave nothing at all. Replaces any entry with the same [orderId]: the
     * gateway refuses a duplicate order id, so a repeat can only be a retry of the same payment.
     *
     * The SDK's own payment paths call this; a host only needs it when it builds and submits orders
     * itself rather than through a [com.hipay.core.gateway.GatewayClient] given this store.
     */
    public fun record(orderId: String, amount: String, currency: String): Boolean {
        val env = load()
        val entry = StoredPendingPayment(
            orderId = orderId,
            amount = amount,
            currency = currency,
            state = TransactionState.PENDING.name,
            createdAt = now(),
        )
        return persist(PendingPaymentsEnvelope(payments = env.payments.filterNot { it.orderId == orderId } + entry))
    }

    /**
     * Completes the entry once the order has been answered: the reference arrives here and nowhere
     * else. Silently does nothing for an unknown [orderId] — a payment the store never saw is not an
     * error, only one it cannot help with.
     */
    public fun complete(orderId: String, reference: String?, state: TransactionState): Boolean {
        val env = load()
        val existing = env.payments.firstOrNull { it.orderId == orderId } ?: return false
        val updated = StoredPendingPayment(
            orderId = existing.orderId,
            reference = reference ?: existing.reference,
            amount = existing.amount,
            currency = existing.currency,
            state = state.name,
            createdAt = existing.createdAt,
            resolvedAt = if (state.isTerminal()) now() else null,
        )
        return persist(
            PendingPaymentsEnvelope(payments = env.payments.filterNot { it.orderId == orderId } + updated),
        )
    }

    // --- persistence ---

    private fun load(): PendingPaymentsEnvelope {
        val blob = runCatching { raw.read() }.getOrNull() ?: return PendingPaymentsEnvelope()
        val env = runCatching { storeJson.decodeFromString(PendingPaymentsEnvelope.serializer(), blob) }
            .getOrNull() ?: return PendingPaymentsEnvelope()
        if (env.version != PENDING_PAYMENTS_VERSION) return PendingPaymentsEnvelope()
        val live = env.payments.filterNot { it.isExpired() }
        if (live.size == env.payments.size) return env
        val pruned = PendingPaymentsEnvelope(payments = live)
        persist(pruned)
        return pruned
    }

    private fun persist(env: PendingPaymentsEnvelope): Boolean =
        runCatching { raw.write(storeJson.encodeToString(PendingPaymentsEnvelope.serializer(), env)) }.isSuccess

    private fun StoredPendingPayment.isExpired(): Boolean {
        val current = now()
        val resolved = resolvedAt
        // A clock that moved backwards must never destroy an entry the host has not seen.
        if (current < createdAt) return false
        return if (resolved != null) current - resolved >= resolvedTtlMillis
        else current - createdAt >= pendingTtlMillis
    }
}

private fun StoredPendingPayment.toPublic() = HiPayPendingPayment(
    orderId = orderId,
    lastState = TransactionState.fromWire(state),
    amount = amount,
    currency = currency,
    createdAt = createdAt,
    referenceKnown = reference != null,
)
