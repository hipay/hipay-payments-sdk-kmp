package com.hipay.core.callback

/**
 * Host segment of the payment return deep link: `{yourScheme}://hipay-payments/gateway/orders/…`.
 *
 * Single-sourced here because BOTH sides of the contract must agree: the SDK builds the return URLs
 * it registers with the gateway, and [CallbackUrlParser] matches the URL the browser hands back. If
 * the two ever disagreed the browser would return and no handler would match — the payment would
 * simply never resume, with nothing logged and nothing to see outside a real device. Every builder
 * and the parser read this constant; none of them spell the host out.
 *
 * It is `public` because integrators need it too: an Android host declares it in the `intent-filter`
 * that catches the return, and a headless caller builds the accept/decline/pending/exception/cancel
 * URLs itself. Referencing this rather than retyping the string keeps those in step with the SDK.
 */
public const val HIPAY_CALLBACK_HOST: String = "hipay-payments"

/** The return-URL prefix for [orderId] under [scheme], without the trailing status segment. */
public fun hipayCallbackBase(scheme: String, orderId: String): String =
    "${scheme.trim()}://$HIPAY_CALLBACK_HOST/gateway/orders/$orderId"
