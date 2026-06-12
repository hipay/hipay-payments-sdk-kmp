package com.hipay.card

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
        return try {
            vaultJson.decodeFromString(CardToken.serializer(), body)
        } catch (e: Exception) {
            // The body may echo request fields — never attach it (or the
            // parsing exception, which embeds the input) to the error (PCI).
            throw HiPayException(
                code = HiPayErrorCode.SERVER,
                message = "Unusable Secure Vault response (token/create)",
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
