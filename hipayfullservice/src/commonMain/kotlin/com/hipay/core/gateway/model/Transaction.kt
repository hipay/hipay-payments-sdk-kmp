package com.hipay.core.gateway.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Transaction returned by the Gateway (FR5) — REAL camelCase contract
 * (golden `order_response.json`, captured 2026-06-12; the snake_case keys in
 * older docs were a transcription error). `status` and amounts are STRINGS on
 * the wire.
 */
@Serializable
public class Transaction(
    @SerialName("state") internal val stateRaw: String,
    @SerialName("status") public val status: String? = null,
    @SerialName("transactionReference") public val transactionReference: String? = null,
    @SerialName("forwardUrl") public val forwardUrl: String? = null,
    @SerialName("reason") public val reason: String? = null,
    @SerialName("message") public val message: String? = null,
    @SerialName("paymentProduct") public val paymentProduct: String? = null,
    @SerialName("authorizedAmount") public val authorizedAmount: String? = null,
    @SerialName("capturedAmount") public val capturedAmount: String? = null,
    @SerialName("eci") public val eci: String? = null,
    @SerialName("test") public val test: String? = null,
    @SerialName("dateCreated") public val dateCreated: String? = null,
    @SerialName("dateUpdated") public val dateUpdated: String? = null,
    @SerialName("threeDSecure") public val threeDSecure: ThreeDSecure? = null,
    @SerialName("order") public val order: Order? = null,
    @SerialName("paymentMethod") public val paymentMethod: PaymentMethod? = null,
) {
    /** Typed state — the only supported way to branch on a transaction. */
    public val state: TransactionState get() = TransactionState.fromWire(stateRaw)

    public companion object {
        internal fun fromJson(body: String): Transaction =
            gatewayJson.decodeFromString(serializer(), body)
    }
}

private val gatewayJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}
