package com.hipay.core.http

import io.ktor.http.Parameters
import io.ktor.http.formUrlEncode

/**
 * POST body encoding for HiPay endpoints — isolated here so the A2 verdict
 * touches exactly one file (architecture D8/D10).
 *
 * A2 VERDICT (2026-06-12): form-urlencoded.
 * Pre-established by reading the legacy request-building code:
 * `HPFHTTPClient.m:172` builds `HTTPBody = queryStringForDictionary` (an
 * application/x-www-form-urlencoded query string); responses are JSON
 * (`Accept: application/json`). The story-2.2 traffic capture confirms this.
 */
internal fun encodeFormBody(fields: Map<String, String>): String =
    Parameters.build {
        fields.forEach { (key, value) -> append(key, value) }
    }.formUrlEncode()
