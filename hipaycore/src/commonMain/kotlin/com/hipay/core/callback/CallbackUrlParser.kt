package com.hipay.core.callback

import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import io.ktor.http.Url

/**
 * Parses the HiPay return deep link (FR8) —
 * `{scheme}://hipay-payments/gateway/orders/{orderId}/{status}?…` —
 * into a typed [CallbackResult]. Any merchant scheme is accepted.
 *
 * Checkout-channel-agnostic by design (G3): nothing here is card-specific;
 * future redirect products (PayPal, …) reuse this parser untouched.
 */
public object CallbackUrlParser {

    private const val EXPECTED_HOST = HIPAY_CALLBACK_HOST

    // @Throws: without it a Kotlin exception crashes through the ObjC boundary.
    // The WHOLE body is guarded — a non-HiPayException escaping any Ktor
    // accessor (segments/parameters) would also crash the host, so anything
    // unexpected is converted to a value-free HiPayException too.
    @Throws(HiPayException::class)
    public fun parse(url: String): CallbackResult {
        try {
            val parsed = Url(url)
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
        } catch (e: HiPayException) {
            throw e // keep the precise rejection reason
        } catch (_: Exception) {
            // Never let an unexpected throwable cross the boundary, never echo the URL.
            throw malformed("not a parseable URL")
        }
    }

    // Value-free message (never echoes URL content — it may carry order data).
    private fun malformed(reason: String): HiPayException = HiPayException(
        code = HiPayErrorCode.VALIDATION,
        message = "Callback URL rejected: $reason",
    )
}
