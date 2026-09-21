// PCI: com.hipay.card path — NEVER log here.
package com.hipay.card.recovery

import com.hipay.core.HiPayConfig
import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import com.hipay.core.gateway.model.Transaction
import com.hipay.core.gateway.model.TransactionState

/**
 * A payment this device launched and that the host has not acknowledged yet.
 *
 * Keyed on [orderId] — the id the host passed to `pay()`, never a HiPay transaction reference. That
 * is the only identifier known before the order leaves the device, so it is the only one that
 * survives a lost response, and it is what the host already reconciles on.
 *
 * @property lastState the last state observed, `PENDING` while the order has not been answered.
 * @property referenceKnown whether HiPay answered with a transaction reference. When false the
 *   payment can be listed but not queried: nothing links this order to a transaction yet.
 */
public class HiPayPendingPayment(
    public val orderId: String,
    public val lastState: TransactionState,
    public val amount: String,
    public val currency: String,
    public val createdAt: Long,
    public val referenceKnown: Boolean,
) {
    // The reference is deliberately absent, and so is anything that could rebuild a payment.
    override fun toString(): String =
        "HiPayPendingPayment(orderId=$orderId, lastState=$lastState, amount=$amount $currency, " +
            "createdAt=$createdAt, referenceKnown=$referenceKnown)"
}

/** A payment left unanswered is kept this long — seven days. */
public const val DEFAULT_PENDING_PAYMENT_TTL_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

/** A payment that reached a terminal state is kept this long after it — forty-eight hours. */
public const val DEFAULT_RESOLVED_PAYMENT_TTL_MILLIS: Long = 48L * 60 * 60 * 1000

/**
 * Per-merchant, per-environment namespace for the recovery entry, built exactly like the saved-card
 * one so the two never collide and neither bleeds across accounts or environments.
 */
public fun pendingPaymentNamespace(config: HiPayConfig): String {
    val user = config.username
    return "com.hipay.pendingpayments.v1.${config.environment.name}.${user.length}.$user"
}

/** A state the gateway will not move away from on its own. */
internal fun TransactionState.isTerminal(): Boolean =
    this == TransactionState.COMPLETED || this == TransactionState.DECLINED || this == TransactionState.ERROR

/**
 * What to make of a failure raised while an order was being submitted.
 *
 * A transport failure says nothing about what the gateway did — an OS suspension dropping the
 * connection mid-flight looks exactly like never reaching it. Reporting a failure there is the one way
 * this SDK could make a host conclude "declined" on a payment that was captured, so the outcome is
 * reported as indeterminate and its recovery entry stays. Anything else — a refusal, a validation
 * error, an unusable response — is an answer, and stays one.
 */
@Throws(HiPayException::class)
public fun indeterminateOrRethrow(failure: HiPayException): Transaction {
    if (failure.code != HiPayErrorCode.NETWORK) throw failure
    return Transaction.verificationPending(null)
}
