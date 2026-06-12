package com.hipay.core

/**
 * Error category carried by every [HiPayException] (architecture D3).
 */
public enum class HiPayErrorCode {
    /** Transport-level failure: no usable HTTP response. */
    NETWORK,

    /** HTTP 4xx without a structured HiPay error payload. */
    CLIENT,

    /** HTTP 5xx or unusable backend response. */
    SERVER,

    /** Structured HiPay API error (code/message payload on a 4xx). */
    API,

    /** Local input validation failure — no network call was made. */
    VALIDATION,
}

/**
 * The single exception type thrown by the SDK (final — D3).
 *
 * [message] is always SDK-synthesized and safe to log: backend-provided text
 * is exposed only through [apiMessage]/[apiDescription], never via
 * [message]/`toString()`, so a host logging the exception can never capture
 * request data echoed by the backend (NFR2).
 */
public class HiPayException internal constructor(
    public val code: HiPayErrorCode,
    message: String,
    cause: Throwable? = null,
    public val httpStatus: Int? = null,
    public val apiCode: Int? = null,
    public val apiMessage: String? = null,
    public val apiDescription: String? = null,
) : Exception(message, cause)
