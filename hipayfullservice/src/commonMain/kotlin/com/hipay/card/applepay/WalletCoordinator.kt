// PCI: com.hipay.card path — NEVER log here (paymentData / token must never be logged).
package com.hipay.card.applepay

import com.hipay.card.model.CardToken
import com.hipay.card.validation.CardNetwork
import com.hipay.card.validation.CardNetworks
import com.hipay.core.HiPayConfig
import com.hipay.core.HiPayException
import com.hipay.core.gateway.GatewayClient
import com.hipay.core.gateway.model.OrderRequest
import com.hipay.core.gateway.model.Transaction
import com.hipay.core.http.defaultHttpClientEngine
import io.ktor.client.engine.HttpClientEngine
import kotlin.coroutines.cancellation.CancellationException

/**
 * Orchestrates a nominal Apple Pay payment (shared, no UI): wallet-tokenize → order → transaction.
 * The per-channel presentation layer captures the `PKPayment` token and calls [pay]; this engine is
 * platform-agnostic (a later Google Pay wallet can reuse it).
 *
 * Nominal path — biometric = SCA, so `eci=7` + `authentication_indicator=0` and no 3DS challenge.
 * The `forwarding`/step-up path is 17.4. When [HiPayApplePayConfig.applePayUsername] is present it
 * routes BOTH the tokenize and the order through that account (fixing the legacy asymmetry).
 */
public class WalletCoordinator internal constructor(
    private val config: HiPayConfig,
    private val engine: HttpClientEngine,
) {
    public constructor(config: HiPayConfig) : this(config, defaultHttpClientEngine())

    /**
     * Runs the payment for an already-authorized Apple Pay token.
     *
     * @param paymentData the JSON string of `PKPayment.token.paymentData`.
     * @param applePayConfig the merchant Apple Pay config (credentials + optional dedicated account).
     * @param orderId,amount,currency,description,*Url,language the order fields (same contract as a
     *   card order; `amount` is a 2-decimal string).
     */
    @Throws(HiPayException::class, CancellationException::class)
    public suspend fun pay(
        paymentData: String,
        applePayConfig: HiPayApplePayConfig,
        orderId: String,
        amount: String,
        currency: String,
        description: String,
        acceptUrl: String,
        declineUrl: String,
        pendingUrl: String,
        exceptionUrl: String,
        cancelUrl: String,
        language: String = "en_GB",
    ): Transaction {
        // Route tokenize + order through the dedicated Apple Pay account when configured, else the
        // classic account (same credentials on both calls — no legacy asymmetry).
        val effectiveConfig = applePayConfig.applePayUsername
            ?.let { HiPayConfig(it, config.password, config.environment, config.settings) }
            ?: config

        val token = ApplePayTokenizer(effectiveConfig, engine)
            .tokenize(paymentData, applePayConfig.privateKeyPassword)

        val order = OrderRequest(
            orderId = orderId,
            paymentProduct = walletPaymentProduct(token),
            amount = amount,
            currency = currency,
            description = description,
            language = language,
            acceptUrl = acceptUrl,
            declineUrl = declineUrl,
            pendingUrl = pendingUrl,
            exceptionUrl = exceptionUrl,
            cancelUrl = cancelUrl,
            cardToken = token.token,
            eci = 7,
            authenticationIndicator = 0,
        )
        return GatewayClient(effectiveConfig, engine).requestNewOrder(order)
    }
}

/**
 * The `payment_product` for a wallet order, derived from the token's **resolved brand** (AC5):
 * prefer a known domestic co-brand (`domestic_network`, e.g. CB/BCMC) over the international brand,
 * mirroring the web `formatTokenizeResponse`. Only the one resolved network is transmitted; the
 * non-selected co-brand is not. Unknown/absent → `"visa"` (the SDK's existing fallback convention).
 */
internal fun walletPaymentProduct(token: CardToken): String {
    val resolved = token.domesticNetwork?.takeIf { CardNetworks.fromApiBrand(it) != null }
        ?: token.brand
    return when (CardNetworks.fromApiBrand(resolved)) {
        CardNetwork.VISA -> "visa"
        CardNetwork.MASTERCARD -> "mastercard"
        CardNetwork.AMEX -> "american-express"
        CardNetwork.MAESTRO -> "maestro"
        CardNetwork.CB -> "cb"
        CardNetwork.BCMC -> "bcmc"
        CardNetwork.UNKNOWN, null -> "visa"
    }
}
