package com.hipay.core.http

import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The single place where HTTP outcomes become [HiPayException]s (D3).
 *
 * Mirrors the legacy contract (HPFAbstractClient.m / HPFHTTPClient.m):
 * - 4xx carrying a parseable `{"code", "message"[, "description"]}` body -> API
 * - other 4xx -> CLIENT
 * - 5xx -> SERVER
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
                apiMessage = api.message,
                apiDescription = api.description,
            )
        }
        return HiPayException(
            code = HiPayErrorCode.CLIENT,
            message = "HTTP $status from HiPay API",
            httpStatus = status,
        )
    }
    return HiPayException(
        code = HiPayErrorCode.SERVER,
        message = "HTTP $status from HiPay API",
        httpStatus = status,
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
    val code = obj["code"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt()
    val message = obj["message"]?.jsonPrimitive?.content
    if (code != null && !message.isNullOrEmpty()) {
        ApiError(code, message, obj["description"]?.jsonPrimitive?.content)
    } else {
        null
    }
} catch (_: Exception) {
    null
}
