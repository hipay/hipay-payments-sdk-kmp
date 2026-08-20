package com.hipay.core.http

import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import com.hipay.core.redactPanLikeOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The single place where HTTP outcomes become [HiPayException]s (D3).
 *
 * Mirrors the legacy contract (HPFAbstractClient.m / HPFHTTPClient.m):
 * - 4xx carrying a parseable `{"code", "message"[, "description"]}` body -> API
 * - other 4xx -> CLIENT
 * - 5xx -> SERVER; a parseable structured body still populates the api*
 *   fields (the gateway answers some business rejections on a 500 — e.g.
 *   "Unknown Token" — and callers need the code to identify the verdict)
 * - transport failure -> NETWORK
 * VALIDATION is reserved for local validators (card path).
 */
internal fun mapErrorResponse(status: Int, body: String): HiPayException {
    if (status in 400..499) {
        val api = parseApiError(body)
        if (api != null) {
            return HiPayException(
                code = HiPayErrorCode.API,
                message = "HiPay API error (code ${api.code}, HTTP $status)",
                httpStatus = status,
                apiCode = api.code,
                // Defense in depth: backend error text is exposed verbatim
                // through these properties (a host may surface the decline
                // reason). Redact PAN-like digit runs so a backend that ever
                // echoes a card number cannot leak it here (PCI, NFR2).
                apiMessage = redactPanLikeOrNull(api.message),
                apiDescription = redactPanLikeOrNull(api.description),
            )
        }
        return HiPayException(
            code = HiPayErrorCode.CLIENT,
            message = "HTTP $status from HiPay API",
            httpStatus = status,
        )
    }
    val api = parseApiError(body)
    return HiPayException(
        code = HiPayErrorCode.SERVER,
        message = "HTTP $status from HiPay API",
        httpStatus = status,
        apiCode = api?.code,
        apiMessage = api?.message?.let(::redactPanLikeOrNull),
        apiDescription = api?.description?.let(::redactPanLikeOrNull),
    )
}

internal fun mapNetworkFailure(cause: Throwable): HiPayException =
    HiPayException(
        code = HiPayErrorCode.NETWORK,
        message = "Network failure while calling the HiPay API",
        cause = cause,
    )

private class ApiError(val code: Int, val message: String, val description: String?)

private val lenientJson = Json { ignoreUnknownKeys = true }

private fun parseApiError(body: String): ApiError? = try {
    val obj = lenientJson.parseToJsonElement(body).jsonObject
    // HiPay codes are integers; the wire sends them as a number or a string.
    // `.content` yields the raw text for both, and toIntOrNull keeps it exact
    // (no Double round-trip: no truncation of "409.9", no precision loss
    // past 2^53). A non-integer code is not a structured API error -> CLIENT.
    val code = obj["code"]?.jsonPrimitive?.content?.toIntOrNull()
    val message = obj["message"]?.jsonPrimitive?.content
    if (code != null && !message.isNullOrEmpty()) {
        ApiError(code, message, obj["description"]?.jsonPrimitive?.content)
    } else {
        null
    }
} catch (_: Exception) {
    null
}
