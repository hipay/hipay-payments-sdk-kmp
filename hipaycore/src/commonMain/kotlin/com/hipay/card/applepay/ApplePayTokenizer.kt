// PCI: com.hipay.card path — NEVER log here (paymentData / token must never be logged).
package com.hipay.card.applepay

import com.hipay.card.model.CardToken
import com.hipay.core.HiPayConfig
import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import com.hipay.core.http.HipayHttpClient
import com.hipay.core.http.defaultHttpClientEngine
import io.ktor.client.engine.HttpClientEngine
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json

/**
 * Secure Vault wallet tokenization (Apple Pay). Exchanges the opaque `PKPayment` token payload for a
 * HiPay card token via `POST {secure-vault-v2}apple-pay/token.json`, mirroring the web hosted-fields
 * flow (`apple-pay-api.js`) and the legacy iOS client. Lives on the `com.hipay.card` PCI path — the
 * `paymentData` and the resulting token are NEVER logged.
 */
public class ApplePayTokenizer internal constructor(
    private val config: HiPayConfig,
    engine: HttpClientEngine,
) {
    public constructor(config: HiPayConfig) : this(config, defaultHttpClientEngine())

    private val http = HipayHttpClient(config, engine)

    /**
     * Tokenizes the Apple Pay [paymentData] (the JSON string of `PKPayment.token.paymentData`).
     * [privateKeyPassword] is the merchant `.p12` password sent as `private_key_pass`. Returns the
     * HiPay [CardToken] (its brand / domestic network drives the order's `payment_product`).
     */
    @Throws(HiPayException::class, CancellationException::class)
    public suspend fun tokenize(paymentData: String, privateKeyPassword: String): CardToken {
        val body = http.postForm(
            url = config.environment.secureVaultV2Url + "apple-pay/token.json",
            fields = linkedMapOf(
                "apple_pay_token" to paymentData,
                "private_key_pass" to privateKeyPassword,
            ),
        )
        val token = try {
            walletJson.decodeFromString(CardToken.serializer(), body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The body may echo request context — never attach it (or the parsing exception) to the
            // error (PCI).
            throw HiPayException(
                code = HiPayErrorCode.SERVER,
                message = "Unusable Secure Vault response (apple-pay/token)",
            )
        }
        if (token.token.isBlank()) {
            throw HiPayException(
                code = HiPayErrorCode.SERVER,
                message = "Secure Vault returned an empty token (apple-pay/token)",
            )
        }
        return token
    }
}

private val walletJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}
