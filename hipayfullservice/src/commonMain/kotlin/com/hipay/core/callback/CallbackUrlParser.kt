package com.hipay.core.callback

import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import io.ktor.http.Url

/**
 * Parses the HiPay return deep link (FR8) —
 * `{scheme}://hipay-fullservice/gateway/orders/{orderId}/{status}?…` —
 * into a typed [CallbackResult]. Any merchant scheme is accepted.
 *
 * Checkout-channel-agnostic by design (G3): nothing here is card-specific;
 * future redirect products (PayPal, …) reuse this parser untouched.
 */
public object CallbackUrlParser {

    private const val EXPECTED_HOST = "hipay-fullservice"

    // @Throws: without it a Kotlin exception crashes through the ObjC boundary.
    @Throws(HiPayException::class)
    public fun parse(url: String): CallbackResult {
        val parsed = try {
            Url(url)
        } catch (_: Exception) {
            throw malformed("not a parseable URL")
        }
        if (parsed.host != EXPECTED_HOST) {
            throw malformed("host is not $EXPECTED_HOST")
        }
        // decoded, non-empty path segments: gateway / orders / {orderId} / {status}
        val segments = parsed.segments
        if (segments.size != 4 || segments[0] != "gateway" || segments[1] != "orders") {
            throw malformed("path is not gateway/orders/{orderId}/{status}")
        }
        val status = when (segments[3]) {
            "accept" -> CallbackStatus.ACCEPT
            "decline" -> CallbackStatus.DECLINE
            "pending" -> CallbackStatus.PENDING
            "exception" -> CallbackStatus.EXCEPTION
            "cancel" -> CallbackStatus.CANCEL
            else -> throw malformed("unknown status segment")
        }
        val queryParams = buildMap {
            parsed.parameters.forEach { key, values ->
                values.firstOrNull()?.let { put(key, it) }
            }
        }
        return CallbackResult(orderId = segments[2], status = status, queryParams = queryParams)
    }

    // Value-free message (never echoes URL content — it may carry order data).
    private fun malformed(reason: String): HiPayException = HiPayException(
        code = HiPayErrorCode.VALIDATION,
        message = "Callback URL rejected: $reason",
    )
}
