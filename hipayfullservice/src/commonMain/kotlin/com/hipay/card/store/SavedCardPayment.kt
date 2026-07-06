// PCI: com.hipay.card path — NEVER log here, never expose the raw PAN or token.
package com.hipay.card.store

import com.hipay.card.model.CardToken
import com.hipay.card.validation.CardNetwork
import com.hipay.card.validation.CardNetworks
import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException

/**
 * Builds the [SavedCard] to persist after a successful payment from the
 * `token/create` response.
 *
 * The tokenize response is the ONLY valid source: its `pan` is x-masked
 * ("411111xxxxxx1111") while the transaction's `paymentMethod.pan` is
 * `*`-masked ("411111******1111") — the store's overwrite identity keeps
 * `[0-9x]` characters, so mixing sources would give the same card two
 * different identities and break update-on-match.
 *
 * Returns null (fail-soft, nothing persisted) when the masked pan or the
 * expiry is missing — without them the card has no identity. Display-only
 * fields (network, holder) default to empty.
 */
public fun savedCardFromToken(token: CardToken): SavedCard? {
    val maskedPan = token.pan ?: return null
    val expiryMonth = token.cardExpiryMonth ?: return null
    val expiryYear = token.cardExpiryYear ?: return null
    return SavedCard(
        token = token.token,
        maskedPan = maskedPan,
        network = token.brand.orEmpty(),
        holder = token.cardHolder.orEmpty(),
        expiryMonth = expiryMonth,
        expiryYear = expiryYear,
    )
}

/**
 * The wire `payment_product` code for a saved-card order, derived from the
 * brand persisted at save time. Unknown or missing brands fall back to
 * `"visa"` — the same convention the entry controllers apply when no network
 * is selected.
 */
public fun savedCardPaymentProduct(card: SavedCard): String =
    when (CardNetworks.fromApiBrand(card.network)) {
        CardNetwork.VISA -> "visa"
        CardNetwork.MASTERCARD -> "mastercard"
        CardNetwork.AMEX -> "american-express"
        CardNetwork.MAESTRO -> "maestro"
        CardNetwork.CB -> "cb"
        CardNetwork.BCMC -> "bcmc"
        CardNetwork.UNKNOWN, null -> "visa"
    }

/**
 * Identifies a saved-card order rejection that means the stored token is no
 * longer usable, and converts it to a [HiPayErrorCode.CARD_NO_LONGER_VALID]
 * exception (the caller purges the card locally, then throws the result).
 *
 * Matches ONLY the definitive backend verdict — the structured business code
 * 3040001 "Unknown Token" (observed on stage as an HTTP 500 with a
 * `{code,message}` body, hence the SERVER class). Transport-class failures
 * (NETWORK/CLIENT) and unrecognized codes return null: a transient failure
 * must never destroy a valid saved card.
 *
 * Returns null when [error] is not a token-invalid rejection.
 */
public fun cardNoLongerValidOrNull(error: HiPayException): HiPayException? {
    val definitiveClass = error.code == HiPayErrorCode.API || error.code == HiPayErrorCode.SERVER
    if (!definitiveClass || error.apiCode != API_CODE_UNKNOWN_TOKEN) {
        return null
    }
    return HiPayException(
        code = HiPayErrorCode.CARD_NO_LONGER_VALID,
        message = "stored card token rejected by the gateway (api code ${error.apiCode})",
        cause = error,
        httpStatus = error.httpStatus,
        apiCode = error.apiCode,
        apiMessage = error.apiMessage,
        apiDescription = error.apiDescription,
    )
}

private const val API_CODE_UNKNOWN_TOKEN = 3040001
