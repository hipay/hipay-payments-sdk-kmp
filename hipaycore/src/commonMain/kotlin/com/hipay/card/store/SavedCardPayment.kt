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
 * Returns null (fail-soft, nothing persisted) when the card cannot be
 * represented: the masked pan or expiry is missing (no identity), OR the brand
 * is absent/unrecognized. A recognizable network is required because the later
 * one-click order derives its `payment_product` from the stored brand —
 * persisting a card whose brand we can't map would force a guessed product and
 * risk a token/product mismatch at pay time. The holder is display-only and
 * defaults to empty. A null return is surfaced to the host as
 * [SavedCardOutcome.NOT_ELIGIBLE].
 */
public fun savedCardFromToken(token: CardToken): SavedCard? {
    val maskedPan = token.pan ?: return null
    val expiryMonth = token.cardExpiryMonth ?: return null
    val expiryYear = token.cardExpiryYear ?: return null
    val brand = token.brand ?: return null
    if (CardNetworks.fromApiBrand(brand) == null) return null // no mappable payment_product
    return SavedCard(
        token = token.token,
        maskedPan = maskedPan,
        network = brand,
        holder = token.cardHolder.orEmpty(),
        expiryMonth = expiryMonth,
        expiryYear = expiryYear,
    )
}

/**
 * Outcome of a save requested via `pay(saveCard = true)`, exposed as observable state by the card
 * controllers so a host can react — e.g. a "card saved" confirmation or a "this card can't be
 * saved" notice (the notice UI itself is out of the pay-path scope). It is null until a save is
 * attempted (the payment did not complete, or `saveCard` was false).
 */
public enum class SavedCardOutcome {
    /** The card was persisted for one-click. */
    SAVED,

    /**
     * The payment succeeded but the card could not be represented for storage — the token lacked a
     * masked pan / expiry, or its brand is not a recognizable network. Not saved; retrying will not
     * help (the host may inform the user the card is not eligible for one-click).
     */
    NOT_ELIGIBLE,

    /**
     * The payment succeeded and the card was eligible, but the secure store rejected the write.
     * Not saved; the failure is transient-ish and the host may retry or surface a soft error.
     */
    STORAGE_FAILED,
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
