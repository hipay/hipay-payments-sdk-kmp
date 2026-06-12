package com.hipay.core.gateway

import com.hipay.core.HiPayConfig
import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import com.hipay.core.gateway.model.OrderRequest
import com.hipay.core.gateway.model.Transaction
import com.hipay.core.http.HipayHttpClient
import com.hipay.core.http.defaultHttpClientEngine
import io.ktor.client.engine.HttpClientEngine
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

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
