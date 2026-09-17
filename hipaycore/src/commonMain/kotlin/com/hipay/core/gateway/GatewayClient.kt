package com.hipay.core.gateway

import com.hipay.card.validation.CardNetwork
import com.hipay.card.validation.CardNetworks
import com.hipay.core.HiPayConfig
import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import com.hipay.core.gateway.model.OrderRequest
import com.hipay.core.gateway.model.Transaction
import com.hipay.core.http.HipayHttpClient
import com.hipay.core.http.defaultHttpClientEngine
import com.hipay.core.monitoring.CheckoutData
import com.hipay.core.monitoring.CheckoutDataSender
import com.hipay.core.monitoring.CheckoutEvent
import com.hipay.core.monitoring.CheckoutSession
import com.hipay.core.monitoring.Monitoring
import com.hipay.core.monitoring.utcTimestamp
import io.ktor.client.engine.HttpClientEngine
import io.ktor.http.Parameters
import io.ktor.http.formUrlEncode
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Gateway client: order creation and transaction fetch.
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
    private val monitoring: CheckoutDataSender,
) {
    public constructor(config: HiPayConfig) :
        this(config, defaultHttpClientEngine(), CheckoutDataSender(config.environment))

    // An injected engine also carries the analytics, so no integration path reports less than the
    // default one does — and a test intercepts the event instead of reaching the network.
    internal constructor(config: HiPayConfig, engine: HttpClientEngine) :
        this(config, engine, CheckoutDataSender(config.environment, engine))

    private val http = HipayHttpClient(config, engine)

    /** Creates an order (POST `{gateway-v1}/order`) and returns the resulting transaction. */
    @Throws(HiPayException::class, CancellationException::class)
    public suspend fun requestNewOrder(order: OrderRequest, signature: String? = null): Transaction {
        val requestedAt = utcTimestamp()
        val body = http.postForm(
            url = config.environment.gatewayV1Url + "order",
            fields = order.toFields(),
            signature = signature,
        )
        val transaction = parseTransaction(body)
        reportOrder(order, transaction, requestedAt)
        return transaction
    }

    /**
     * Reports which SDK build produced this transaction, best effort and after the fact. A refused
     * order is reported like any other; one that never reached the gateway has nothing to attribute.
     */
    private fun reportOrder(order: OrderRequest, transaction: Transaction, requestedAt: String) {
        val session = CheckoutSession.current()
        monitoring.send(
            CheckoutData(
                event = CheckoutEvent.REQUEST,
                id = session.id,
                status = transaction.status,
                paymentMethod = transaction.paymentProduct ?: order.paymentProduct,
                cardCountry = transaction.paymentMethod?.country,
                monitoring = Monitoring(dateRequest = requestedAt, dateResponse = utcTimestamp()),
            ),
        )
    }

    /**
     * Fetches the current transaction state (GET `{gateway-v1}/transaction/{ref}`)
     * — confirmation. The endpoint wraps its payload in a `transaction`
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
     * The card networks this ACCOUNT is contracted for (GET `{gateway-v2}/available-payment-products.json`).
     *
     * This is the authoritative ceiling for what a card component may offer: what the merchant
     * accepts is a property of their HiPay contract, not of the integration. An integrator-supplied
     * restriction can only narrow this set — see `AllowedNetworks.effectiveAllowed`. Apple Pay
     * eligibility resolves against the same set (the SALE/ECI-7 route it rides).
     *
     * The query mirrors the web integration's card path exactly (`eci=7`, `operation=4` = sale,
     * `with_options=true`), so both channels resolve the same set for the same account.
     * `customerCountry` is optional — an empty value lets the account decide (the web SDK sends it
     * empty by default).
     *
     * The response is a JSON array of product objects; each `code` is mapped through
     * [CardNetworks.fromApiBrand], so non-card and unrecognized products are ignored.
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
        // A BARE JSON array — the shape the gateway actually answers — is the only readable answer,
        // and `[]` is a legitimate one meaning "no card product". Anything else (blank body, an
        // object, unparseable content) is a failure, NOT an empty account: read as a verdict it
        // would refuse every card on the account, which is far worse than a caller treating the
        // ceiling as unknown. Deliberately NOT tolerant of an object wrapper: "the first
        // array-valued member" would happily read `{"errors":[…]}` as a product list, i.e. produce
        // the refuse-everything verdict from an error payload.
        val products = try {
            if (body.isBlank()) null else Json.parseToJsonElement(body) as? JsonArray
        } catch (e: Exception) {
            null
        } ?: throw unusableResponse()
        val accepted = products.mapNotNullTo(mutableSetOf<CardNetwork>()) { element ->
            // Skip any product whose `code` is missing or not a plain string — an unrecognized
            // shape is ignored, never fatal to the whole set.
            val code = ((element as? JsonObject)?.get("code") as? JsonPrimitive)?.content
            CardNetworks.fromApiBrand(code)
        }
        // Products WERE listed, and not one of them mapped to a card network this SDK knows. That is
        // a mapping gap on our side, not an account without cards — reporting it as a verdict would
        // refuse every payer on a renamed or unmapped product code.
        if (accepted.isEmpty() && products.isNotEmpty()) throw unusableResponse()
        return accepted
    }

    /** Never echo the body (it may carry request context) into the error. */
    private fun unusableResponse() = HiPayException(
        code = HiPayErrorCode.SERVER,
        message = "Unusable Gateway response",
    )

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
