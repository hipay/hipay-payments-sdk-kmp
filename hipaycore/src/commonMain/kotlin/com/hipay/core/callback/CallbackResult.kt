package com.hipay.core.callback

/** Typed result of a parsed return deep link (FR8). */
public class CallbackResult(
    public val orderId: String,
    public val status: CallbackStatus,
    public val queryParams: Map<String, String>,
)
