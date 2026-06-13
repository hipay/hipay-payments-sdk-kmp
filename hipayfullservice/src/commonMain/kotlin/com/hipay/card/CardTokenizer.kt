package com.hipay.card

import com.hipay.card.model.CardInfo
import com.hipay.card.model.CardToken
import com.hipay.card.validation.ensureValidForTokenization
import com.hipay.core.HiPayConfig
import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import com.hipay.core.http.HipayHttpClient
import com.hipay.core.http.defaultHttpClientEngine
import io.ktor.client.engine.HttpClientEngine
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json

/**
 * Secure Vault tokenization client (FR10) — the only way card data leaves the
 * card package, as a token. Lives in `com.hipay.card` (one-way dependency on
 * `core`, FR18). Zero logging in this package (PCI, enforced by
 * `scripts/check-no-logging.sh`).
 */
public class CardTokenizer internal constructor(
    private val config: HiPayConfig,
    engine: HttpClientEngine,
) {
    public constructor(config: HiPayConfig) : this(config, defaultHttpClientEngine())

    private val http = HipayHttpClient(config, engine)

    /**
     * Tokenizes the card against Secure Vault POST `token/create`.
     *
     * Inputs are validated locally first (FR11) — an invalid input throws
     * [HiPayErrorCode.VALIDATION] without any network call. Card data is held
     * in memory only for the duration of the call (NFR2).
     */
    // K/N rule: @Throws on a suspend function must also list CancellationException.
    @Throws(HiPayException::class, CancellationException::class)
    public suspend fun generateToken(
        cardNumber: String,
        expiryMonth: String,
        expiryYear: String,
        holder: String,
        cvc: String,
        multiUse: Boolean,
    ): CardToken {
        ensureValidForTokenization(cardNumber, expiryMonth, expiryYear, holder, cvc)
        val body = http.postForm(
            url = config.environment.secureVaultV2Url + "token/create",
            fields = tokenRequestFields(cardNumber, expiryMonth, expiryYear, holder, cvc, multiUse),
        )
        val token = try {
            vaultJson.decodeFromString(CardToken.serializer(), body)
        } catch (e: CancellationException) {
            throw e // never swallow cooperative cancellation
        } catch (e: Exception) {
            // The body may echo request fields — never attach it (or the
            // parsing exception, which embeds the input) to the error (PCI).
            throw HiPayException(
                code = HiPayErrorCode.SERVER,
                message = "Unusable Secure Vault response (token/create)",
            )
        }
        if (token.token.isEmpty()) {
            // A 2xx with no usable token is a backend contract violation —
            // never hand the caller an empty token.
            throw HiPayException(
                code = HiPayErrorCode.SERVER,
                message = "Secure Vault returned an empty token (token/create)",
            )
        }
        return token
    }

    /**
     * Resolves the card's network(s) from the Secure Vault (POST `token`) once
     * the entered number is complete — the authoritative source for the brand
     * and any domestic co-brand (CB / BCMC). No CVC/holder is sent; the call is
     * a lightweight resolution, not the final tokenization.
     *
     * Drives the network icons / co-brand selection in the entry component.
     */
    @Throws(HiPayException::class, CancellationException::class)
    public suspend fun resolveCardInfo(
        cardNumber: String,
        expiryMonth: String,
        expiryYear: String,
    ): CardInfo {
        val body = http.postForm(
            url = config.environment.secureVaultV2Url + "token",
            fields = linkedMapOf(
                "card_number" to cardNumber,
                "card_expiry_month" to expiryMonth,
                "card_expiry_year" to expiryYear,
            ),
        )
        return try {
            vaultJson.decodeFromString(CardInfo.serializer(), body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw HiPayException(
                code = HiPayErrorCode.SERVER,
                message = "Unusable Secure Vault response (token lookup)",
            )
        }
    }
}

/** Exact wire keys of the legacy iOS mapper — parity-locked by the golden file. */
internal fun tokenRequestFields(
    cardNumber: String,
    expiryMonth: String,
    expiryYear: String,
    holder: String,
    cvc: String,
    multiUse: Boolean,
): Map<String, String> = linkedMapOf(
    "card_number" to cardNumber,
    "card_expiry_month" to expiryMonth,
    "card_expiry_year" to expiryYear,
    "card_holder" to holder,
    "cvc" to cvc,
    "multi_use" to if (multiUse) "1" else "0",
)

private val vaultJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}
