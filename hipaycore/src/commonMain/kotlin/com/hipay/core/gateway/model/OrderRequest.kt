package com.hipay.core.gateway.model

import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Order creation request (FR4/FR12). Serialized as a form-urlencoded field
 * map (A2) with the exact legacy-mapper keys
 * (`HPFOrderRelatedRequestSerializationMapper.m`) — parity-locked against the
 * golden `order_request.json`.
 *
 * Amounts are 2-decimal strings, never doubles (architecture format pattern).
 */
public class OrderRequest(
    public val orderId: String,
    public val paymentProduct: String,
    public val amount: String,
    public val description: String,
    public val acceptUrl: String,
    public val declineUrl: String,
    public val pendingUrl: String,
    public val exceptionUrl: String,
    public val cancelUrl: String,
    public val operation: Operation = Operation.SALE,
    public val currency: String = "EUR",
    public val language: String = "en_GB",
    public val customerId: String? = null,
    public val ipAddress: String? = null,
    public val customer: CustomerInfo? = null,
    public val shippingAddress: CustomerInfo? = null,
    public val customData: Map<String, String> = emptyMap(),
    // Card payment fields (FR12) — appended only when a token is present.
    public val cardToken: String? = null,
    public val eci: Int = 7,
    public val authenticationIndicator: Int = 0,
    // This order takes part in a one-click flow: either it enrols a card-on-file (the payer asked
    // for the card to be saved, so the token is multi-use) or it pays FROM one already enrolled.
    // Both are declared, per the gateway contract — "including the first transaction and the
    // subsequent ones, you have to inform the one_click parameter at true value during the Order
    // request". It is NOT a recurring payment either way: the payer is present and initiates it, so
    // ECI stays 7 (9 is recurring/MIT) and `recurring_payment` is never sent.
    public val oneClick: Boolean = false,
) {
    // Amount is validated in toFields() (called inside the @Throws suspend
    // requestNewOrder), NOT in init: a Kotlin constructor that throws is not a
    // catchable error across the Kotlin/Native boundary — it would crash the
    // Swift host (same reason CallbackUrlParser.parse is @Throws). Validating
    // at field-build time surfaces a catchable HiPayException(VALIDATION).
    internal fun toFields(): Map<String, String> {
        requireAmountFormat(amount)
        val fields = linkedMapOf(
            "orderid" to orderId,
            "payment_product" to paymentProduct,
            "operation" to operation.wireValue,
            "amount" to amount,
            "currency" to currency,
            "description" to description,
            "language" to language,
            "accept_url" to acceptUrl,
            "decline_url" to declineUrl,
            "pending_url" to pendingUrl,
            "exception_url" to exceptionUrl,
            "cancel_url" to cancelUrl,
        )
        customerId?.let { fields["cid"] = it }
        ipAddress?.let { fields["ipaddr"] = it }
        if (customData.isNotEmpty()) {
            fields["custom_data"] = JsonObject(customData.mapValues { JsonPrimitive(it.value) }).toString()
        }
        customer?.let { fields.putAll(it.toFields()) }
        shippingAddress?.let { fields.putAll(it.toFields(prefix = "shipto_", personalInfoOnly = true)) }
        if (cardToken != null) {
            fields["cardtoken"] = cardToken
            fields["eci"] = eci.toString()
            fields["authentication_indicator"] = authenticationIndicator.toString()
            if (oneClick) {
                fields["one_click"] = "1"
            }
        }
        return fields
    }

}

/**
 * The gateway amount contract: a 2-decimal string. Shared so a caller that must reject a bad amount
 * BEFORE the order is built validates against this exact rule instead of a copy of it — a wallet
 * sheet mints a single-use token, so an amount the order would refuse has to fail before the
 * customer authorizes.
 */
internal fun requireAmountFormat(amount: String) {
    if (!amount.matches(AMOUNT_FORMAT)) {
        throw HiPayException(
            code = HiPayErrorCode.VALIDATION,
            message = "amount: must be a 2-decimal string (e.g. \"10.00\")",
        )
    }
}

private val AMOUNT_FORMAT = Regex("""\d+\.\d{2}""")
