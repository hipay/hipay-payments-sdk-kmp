// PCI: com.hipay.card path — never log here.
package com.hipay.card.applepay

import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import kotlin.coroutines.cancellation.CancellationException

/**
 * The merchant order fields for an Apple Pay payment (same contract as a card order). Bundled so the
 * payment entry point stays readable. `amount` is a 2-decimal string (e.g. "12.00"); `countryCode`
 * is the ISO country for the `PKPaymentRequest`.
 *
 * @property orderId the merchant order id. **Reuse the same value when retrying a payment whose
 *   outcome is unknown**: the SDK never resubmits an order on its own, so a retry is always the host's
 *   deliberate act, and only a constant order id lets the gateway recognize it as the same payment
 *   instead of authorizing twice.
 * @property redirectScheme the app's URL scheme. The five gateway redirect URLs are derived from it,
 *   and an authentication challenge captures its return on it — which is why the SDK owns their shape
 *   rather than taking them as free-form parameters: only
 *   `{scheme}://hipay-fullservice/gateway/orders/{orderId}/{status}` can be read back by the SDK.
 */
public class ApplePayOrder(
    public val orderId: String,
    public val amount: String,
    public val currency: String,
    public val countryCode: String,
    public val description: String,
    public val redirectScheme: String,
    public val language: String = "en_GB",
)

/** The gateway redirect URLs for this order, in the one shape the SDK can parse back. The scheme is
 *  trimmed to match what the validation accepted, so the derived URLs and the challenge's callback
 *  scheme can never differ by stray whitespace. */
internal val ApplePayOrder.callbackBaseUrl: String
    get() = "${redirectScheme.trim()}://hipay-fullservice/gateway/orders/$orderId"

/**
 * Validates the order fields the sheet's own validation does not cover. Called before the sheet can
 * open: the Apple Pay token is single-use, so an input the order would later reject has to fail while
 * the customer can still retry — once they have authorized, the token is spent.
 */
@Throws(HiPayException::class, CancellationException::class)
internal fun ApplePayOrder.ensureValid() {
    if (orderId.isBlank()) {
        throw HiPayException(
            code = HiPayErrorCode.VALIDATION,
            message = "Apple Pay order: orderId is required",
        )
    }
    // A URL scheme is letters, digits, '+', '-' and '.', starting with a letter (RFC 3986). Anything
    // else builds redirect URLs the gateway rejects, and a challenge return that cannot be captured.
    val scheme = redirectScheme.trim()
    val validScheme = scheme.isNotEmpty() &&
        scheme.first().isLetter() &&
        scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
    if (!validScheme) {
        throw HiPayException(
            code = HiPayErrorCode.VALIDATION,
            message = "Apple Pay order: redirectScheme must be a valid URL scheme",
        )
    }
}
