// PCI: com.hipay.card path — never log here.
package com.hipay.card.applepay

import com.hipay.core.gateway.model.Transaction
import com.hipay.core.gateway.model.TransactionState

/**
 * The outcome of an Apple Pay payment.
 *
 * A typed outcome rather than an exception, because a customer closing the sheet and an issuer
 * refusing the card are both ordinary results the host must handle, not errors: a cancel raises
 * nothing at all, and a decline comes back carrying its transaction. Exceptions stay reserved for what
 * genuinely went wrong — invalid input, or a gateway the SDK could not reach.
 */
public sealed class ApplePayPaymentResult {

    /** The gateway captured the payment. The only outcome that means the customer was charged. */
    public class Completed internal constructor(public val transaction: Transaction) : ApplePayPaymentResult()

    /** The issuer refused the authorization. A normal business outcome — the sheet showed a failure. */
    public class Declined internal constructor(public val transaction: Transaction) : ApplePayPaymentResult()

    /**
     * The outcome is not yet known. Either the gateway itself reported `pending`, or the SDK could not
     * confirm the state (a 3DS return it could not read back, or the payment ran past the deadline the
     * Apple Pay sheet allows). The payment may still be captured server-side, so it must never be
     * treated as a failure, and a retry must reuse the SAME [orderId].
     *
     * How to reconcile depends on what could be recovered: with a [Transaction.transactionReference]
     * the transaction can be re-queried directly; without one the order call never answered, so
     * [orderId] is the only handle on the payment and it has to be reconciled server-side (through the
     * merchant backend or the notification, since the gateway offers no lookup by order id).
     */
    public class Pending internal constructor(
        public val transaction: Transaction,
        public val orderId: String? = null,
    ) : ApplePayPaymentResult()

    /**
     * The gateway answered, but with neither a capture nor a decline: an authentication challenge the
     * customer abandoned, or a gateway-reported error. Inspect [Transaction.state] and
     * [Transaction.reason] for which.
     */
    public class NotCompleted internal constructor(public val transaction: Transaction) : ApplePayPaymentResult()

    /**
     * The customer closed the sheet without authorizing. No order was created and nothing failed — the
     * component is free for a new payment.
     */
    public object Cancelled : ApplePayPaymentResult()
}

/**
 * Maps a gateway transaction to its outcome. `FORWARDING` only reaches here once the challenge has
 * been resolved, where it means the customer abandoned it — a server-confirmed not-completed, which is
 * why it is never conflated with [ApplePayPaymentResult.Pending] (genuinely indeterminate).
 *
 * [orderId] is carried on the indeterminate outcome only: it is the one case where the host may have
 * nothing else to reconcile on.
 */
internal fun Transaction.toApplePayPaymentResult(orderId: String? = null): ApplePayPaymentResult = when (state) {
    TransactionState.COMPLETED -> ApplePayPaymentResult.Completed(this)
    TransactionState.DECLINED -> ApplePayPaymentResult.Declined(this)
    TransactionState.PENDING -> ApplePayPaymentResult.Pending(this, orderId)
    TransactionState.FORWARDING, TransactionState.ERROR -> ApplePayPaymentResult.NotCompleted(this)
}
