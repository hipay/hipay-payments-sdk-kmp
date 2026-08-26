package com.hipay.core.gateway.model

import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException

/**
 * Optional gateway parameters for an order, attached with [OrderRequest.withOptions]:
 *
 * ```
 * val options = OrderOptions.Builder().notifyUrl("https://backend.example/notify").build()
 * gateway.requestNewOrder(order.withOptions(options), signature)
 * ```
 *
 * Grows by methods, never by parameters: Kotlin default arguments are not exported to Swift, so a new
 * constructor parameter breaks every Swift caller while a new method breaks none. Parameters this SDK
 * does not model go through [Builder.custom], so a missing one never blocks an integrator.
 *
 * @since 1.2.0
 */
public class OrderOptions private constructor(
    internal val fields: Map<String, String>,
) {
    /** Accumulates the parameters; each method overwrites its own key if called twice. */
    public class Builder {
        private val fields = linkedMapOf<String, String>()

        /**
         * Overrides the back-office notification URL for this order only. Must be `http(s)` — HiPay's
         * servers call it.
         *
         * Not covered by the order signature, so a tampered app could redirect the notification.
         * Notifications stay signed, so nothing can be forged in your name, but yours can be
         * suppressed. Prefer the back-office setting in production.
         */
        public fun notifyUrl(url: String): Builder = apply {
            val trimmed = url.trim()
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                fail("notifyUrl: must be an http:// or https:// URL")
            }
            fields["notify_url"] = trimmed
        }

        /** Bank-statement descriptor. Acquirers truncate and normalise it, so treat length as advisory. */
        public fun softDescriptor(descriptor: String): Builder =
            apply { fields["soft_descriptor"] = requireNotBlank("softDescriptor", descriptor) }

        /** A longer description, where [OrderRequest.description] is the short one. */
        public fun longDescription(description: String): Builder =
            apply { fields["long_description"] = requireNotBlank("longDescription", description) }

        /**
         * One of the indexed reporting fields `cdata1`…`cdata10`, which the back office reports on
         * individually — unlike [OrderRequest.customData], a single JSON blob.
         *
         * @param index 1..[CDATA_MAX].
         */
        public fun cdata(index: Int, value: String): Builder = apply {
            if (index !in 1..CDATA_MAX) fail("cdata: index must be 1..$CDATA_MAX (got $index)")
            fields["cdata$index"] = requireNotBlank("cdata$index", value)
        }

        /** Shopping-cart JSON, as the gateway documents it. Passed through verbatim — this SDK does
         *  not model the basket, so its shape is yours to get right. */
        public fun basket(json: String): Builder =
            apply { fields["basket"] = requireNotBlank("basket", json) }

        /**
         * Any other gateway parameter, by its exact wire name. Sent verbatim: a wrong name or shape
         * surfaces as a gateway rejection, not a local error.
         *
         * Refuses [RESERVED_FIELDS] and the `shipto_` prefix — those have typed parameters on
         * [OrderRequest], and two sources of truth for one field is a payment that fails invisibly.
         */
        public fun custom(name: String, value: String): Builder = apply {
            val key = name.trim()
            if (key.isEmpty()) fail("custom: the parameter name must not be blank")
            if (key in RESERVED_FIELDS || key.startsWith(SHIPPING_PREFIX)) {
                fail("custom: '$key' is set by the SDK — use the matching OrderRequest parameter")
            }
            fields[key] = requireNotBlank(key, value)
        }

        /** Throws [HiPayException] with [HiPayErrorCode.VALIDATION] on any value rejected above. */
        public fun build(): OrderOptions = OrderOptions(LinkedHashMap(fields))

        private fun requireNotBlank(name: String, value: String): String {
            if (value.isBlank()) fail("$name: must not be blank")
            return value
        }

        private fun fail(message: String): Nothing =
            throw HiPayException(code = HiPayErrorCode.VALIDATION, message = message)
    }

    public companion object {
        /** Highest index accepted by [Builder.cdata]. */
        public const val CDATA_MAX: Int = 10

        private const val SHIPPING_PREFIX: String = "shipto_"

        /**
         * Wire names the SDK sets itself, refused by [Builder.custom]: signature-covered
         * (`orderid`/`amount`/`currency`), PCI (`cardtoken`), device-derived, or already a typed
         * parameter. Keep in step with [OrderRequest.toFields] — a name missing here degrades to
         * "silently ignored", never to a corrupted order, since options never overwrite a set field.
         */
        public val RESERVED_FIELDS: Set<String> = setOf(
            "orderid", "amount", "currency", "payment_product", "operation", "description", "language",
            // Return URLs: built from the integrator's scheme, and matched again on the way back.
            "accept_url", "decline_url", "pending_url", "exception_url", "cancel_url",
            "cardtoken", "eci", "authentication_indicator", "one_click",
            "cid", "ipaddr", "custom_data",
            // Derived from the running device, so a supplied value could only be less accurate.
            "source", "http_user_agent",
            // Customer block; the shipping block is covered by the shipto_ prefix.
            "city", "country", "email", "firstname", "lastname", "phone", "recipientinfo", "state",
            "streetaddress", "streetaddress2", "zipcode",
        )
    }
}
