package com.hipay.card

/**
 * Where a payment currently is, for a host that wants to show progress. `null` means idle.
 *
 * Observable on the card controllers, and read-only: it reports the flow, it never steers it. The
 * phases are the ones the SDK genuinely crosses — a payer-facing label like "contacting your bank"
 * is the host's wording to choose, not the SDK's.
 */
public enum class PaymentPhase {
    /** Exchanging the entered card for a vault token. Skipped when paying from a saved card. */
    TOKENIZING,

    /** The order is being created at the gateway. */
    CREATING_ORDER,

    /** The payer is in the 3DS challenge, outside the app. */
    AUTHENTICATING,

    /** The challenge came back and the outcome is being confirmed server-side. */
    CONFIRMING,
}
