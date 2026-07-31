package com.hipay.core.gateway

import com.hipay.core.HiPayConfig
import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import com.hipay.card.validation.CardNetwork
import com.hipay.card.validation.CardNetworks
import com.hipay.core.gateway.model.OrderRequest
import com.hipay.core.gateway.model.Transaction
import com.hipay.core.http.HipayHttpClient
import com.hipay.core.http.defaultHttpClientEngine
import io.ktor.client.engine.HttpClientEngine
import io.ktor.http.Parameters
import io.ktor.http.formUrlEncode
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Gateway client (FR4/FR6): order creation and transaction fetch.
 *
 * The optional `signature` parameter switches authentication to the legacy
 * HS scheme (`Authorization: HS base64(username:signature)`); the signature
 * is SHA-1(orderId+amount+currency+secretPassphrase) computed by the
 * MERCHANT BACKEND — this library never computes it (the secret passphrase
 * must never enter the SDK).
 */
public class GatewayClient internal constructor(
    private val config: HiPayConfig,
    engine: HttpClientEngine,
) {
    public constructor(config: HiPayConfig) : this(config, defaultHttpClientEngine())

    private val http = HipayHttpClient(config, engine)

    /** Creates an order (POST `{gateway-v1}/order`) and returns the resulting transaction. */
    @Throws(HiPayException::class, CancellationException::class)
    public suspend fun requestNewOrder(order: OrderRequest, signature: String? = null): Transaction {
        val body = http.postForm(
            url = config.environment.gatewayV1Url + "order",
            fields = order.toFields(),
            signature = signature,
        )
        return parseTransaction(body)
    }

    /**
     * Fetches the current transaction state (GET `{gateway-v1}/transaction/{ref}`)
     * — FR9 confirmation. The endpoint wraps its payload in a `transaction`
     * key (object or array — legacy `HPFTransactionDetailsMapper`).
     */
    @Throws(HiPayException::class, CancellationException::class)
    public suspend fun getTransaction(reference: String, signature: String? = null): Transaction {
        val body = http.get(
            url = config.environment.gatewayV1Url + "transaction/" + reference,
            signature = signature,
        )
        return parseTransaction(body, unwrap = true)
    }

    /**
     * Queries the account's available card payment products (GET
     * `{gateway-v2}/available-payment-products.json`) and returns the set of card networks the
     * account accepts. Sent with `eci=7, operation=4` (the SALE/ECI-7 route Apple Pay rides) and
     * `with_options=true`, mirroring the web hosted-fields flow. `customerCountry` is optional — an
     * empty value lets the account decide (the web SDK sends it empty by default).
     *
     * The response is a JSON array of product objects; each `code` is mapped through
     * [CardNetworks.fromApiBrand], so non-card / unrecognized products are ignored.
     */
    @Throws(HiPayException::class, CancellationException::class)
    public suspend fun getAvailablePaymentProducts(
        paymentProducts: List<String>,
        currency: String,
        customerCountry: String? = null,
    ): Set<CardNetwork> {
        val query = Parameters.build {
            append("eci", "7")
            append("operation", "4")
            append("customer_country", customerCountry ?: "")
            append("currency", currency)
            append("payment_product", paymentProducts.joinToString(","))
            append("with_options", "true")
        }.formUrlEncode()
        val body = http.get(
            url = config.environment.gatewayV2Url + "available-payment-products.json?" + query,
        )
        return parseAcceptedCards(body)
    }

    private fun parseAcceptedCards(body: String): Set<CardNetwork> {
        // An empty 200 body simply means no products, not a malformed response.
        if (body.isBlank()) return emptySet()
        return try {
            val root = Json.parseToJsonElement(body)
            // The gateway returns a bare array; tolerate an object wrapper (first array-valued entry).
            val products = (root as? JsonArray)
                ?: (root as? JsonObject)?.values?.firstNotNullOfOrNull { it as? JsonArray }
                ?: JsonArray(emptyList())
            products.mapNotNullTo(mutableSetOf()) { element ->
                // Skip any product whose `code` is missing or not a plain string — an unrecognized
                // shape is ignored, never fatal to the whole set.
                val code = ((element as? JsonObject)?.get("code") as? JsonPrimitive)?.content
                CardNetworks.fromApiBrand(code)
            }
        } catch (e: Exception) {
            // Never echo the body (may carry request context) into the error.
            throw HiPayException(
                code = HiPayErrorCode.SERVER,
                message = "Unusable Gateway response",
            )
        }
    }

    private fun parseTransaction(body: String, unwrap: Boolean = false): Transaction = try {
        Transaction.fromJson(if (unwrap) unwrapTransaction(body) else body)
    } catch (e: HiPayException) {
        throw e
    } catch (e: Exception) {
        // Never attach the body or the parsing exception (both may echo
        // request fields) to the error.
        throw HiPayException(
            code = HiPayErrorCode.SERVER,
            message = "Unusable Gateway response",
        )
    }

    /** Tolerates both `{"transaction": {...}}`, `{"transaction": [{...}]}` and a bare object. */
    private fun unwrapTransaction(body: String): String {
        val root = Json.parseToJsonElement(body)
        val wrapped = (root as? JsonObject)?.get("transaction") ?: return body
        val single = (wrapped as? JsonArray)?.firstOrNull() ?: wrapped
        return single.toString()
    }
}
