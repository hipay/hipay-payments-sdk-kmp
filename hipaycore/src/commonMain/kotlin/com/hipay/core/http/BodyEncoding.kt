package com.hipay.core.http

import io.ktor.http.Parameters
import io.ktor.http.formUrlEncode

/**
 * POST body encoding for HiPay endpoints — isolated here so the A2 verdict
 * touches exactly one file (architecture D8/D10).
 *
 * A2 VERDICT (2026-06-12): form-urlencoded — CONFIRMED on real stage traffic.
 * Pre-established by reading the legacy request-building code
 * (`HPFHTTPClient.m:172` builds `HTTPBody = queryStringForDictionary`), then
 * confirmed live the same day: form-urlencoded POSTs accepted by the stage
 * Secure Vault (201, token) and Gateway (200, completed order). Responses are
 * JSON (`Accept: application/json`). Golden files: commonTest/resources/golden/.
 */
internal fun encodeFormBody(fields: Map<String, String>): String =
    Parameters.build {
        fields.forEach { (key, value) -> append(key, value) }
    }.formUrlEncode()
