package com.hipay.core.gateway.model

import com.hipay.core.redactPanLike
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

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
        // The Gateway sends "" where an absent sub-object would be expected
        // (e.g. `"threeDSecure": ""` on a 3DS-challenge order, captured live
        // 2026-06-13) and legacy mappers show `reason` can be either a string
        // or a {code, message} object — sanitize those shapes before decoding.
        internal fun fromJson(body: String): Transaction =
            fromJsonObject(gatewayJson.parseToJsonElement(body).jsonObject)

        internal fun fromJsonObject(root: JsonObject): Transaction {
            val cleaned = JsonObject(
                root.mapValues { (key, value) ->
                    when {
                        key in OBJECT_FIELDS && value !is JsonObject -> JsonNull
                        // `reason` may be a string or a {code, message} object;
                        // keep description text, PAN-redacted (PCI, NFR2 — the
                        // value is server-controlled and publicly exposed).
                        key == "reason" && value is JsonObject ->
                            (value["message"] as? JsonPrimitive)
                                ?.let { JsonPrimitive(redactPanLike(it.content)) } ?: JsonNull
                        key == "reason" && value is JsonPrimitive && value.isString ->
                            JsonPrimitive(redactPanLike(value.content))
                        else -> value
                    }
                }
            )
            return gatewayJson.decodeFromJsonElement(serializer(), cleaned)
        }

        private val OBJECT_FIELDS = setOf("threeDSecure", "order", "paymentMethod")
    }
}

private val gatewayJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}
