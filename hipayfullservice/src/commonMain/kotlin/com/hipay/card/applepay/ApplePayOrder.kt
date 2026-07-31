// PCI: com.hipay.card path — never log here.
package com.hipay.card.applepay

/**
 * The merchant order fields for an Apple Pay payment (same contract as a card order). Bundled so the
 * payment entry point stays readable. `amount` is a 2-decimal string (e.g. "12.00"); `countryCode`
 * is the ISO country for the `PKPaymentRequest`.
 */
public class ApplePayOrder(
    public val orderId: String,
    public val amount: String,
    public val currency: String,
    public val countryCode: String,
    public val description: String,
    public val acceptUrl: String,
    public val declineUrl: String,
    public val pendingUrl: String,
    public val exceptionUrl: String,
    public val cancelUrl: String,
    public val language: String = "en_GB",
)
