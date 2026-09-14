package com.hipay.core.gateway.model

import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Optional gateway parameters for an order, attached with [OrderRequest.withOptions]:
 *
 * ```
 * val options = OrderOptions.Builder().notifyUrl("https://backend.example/notify").build()
 * gateway.requestNewOrder(order.withOptions(options), signature)
 * ```
 *
 * Grows by methods, never by parameters: Kotlin default arguments are not exported to Swift, so a new
 * constructor parameter breaks every Swift caller while a new method breaks none. A gateway parameter
 * this SDK does not model goes through [Builder.custom]; your own data goes through
 * [Builder.customData]. Neither a missing parameter nor a merchant-specific field blocks an integrator.
 *
 * Every value is rejected by [Builder.build], never by the setter that took it: an exception out of a
 * non-`@Throws` function does not cross the Kotlin/Native boundary, it terminates the Swift host. Same
 * rule as [OrderRequest], which validates in `toFields()` rather than in its constructor.
 *
 * @since 1.2.0
 */
public class OrderOptions private constructor(
    internal val fields: Map<String, String>,
) {
    /** Accumulates the parameters; each method overwrites its own key if called twice. */
    public class Builder {
        private val fields = linkedMapOf<String, String>()

        /** Entries for the gateway's `custom_data`, serialized together by [build]. */
        private val customDataEntries = linkedMapOf<String, String>()

        /** First rejection seen, raised by [build]. Kept rather than thrown so a bad value cannot
         *  terminate a Swift host — see the note on [OrderOptions]. */
        private var rejection: String? = null

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
                reject("notifyUrl: must be an http:// or https:// URL")
            } else {
                fields["notify_url"] = trimmed
            }
        }

        /** Bank-statement descriptor. Acquirers truncate and normalise it, so treat length as advisory. */
        public fun softDescriptor(descriptor: String): Builder =
            apply { put("soft_descriptor", descriptor, label = "softDescriptor") }

        /** A longer description, where [OrderRequest.description] is the short one. */
        public fun longDescription(description: String): Builder =
            apply { put("long_description", description, label = "longDescription") }

        /** Shopping-cart JSON, as the gateway documents it. Passed through verbatim — this SDK does
         *  not model the basket, so its shape is yours to get right. */
        public fun basket(json: String): Builder =
            apply { put("basket", json, label = "basket") }

        /**
         * One entry of your own data, carried in the gateway's `custom_data` and shown on the
         * transaction in the back office. Call once per entry; the keys are yours to choose.
         *
         * This — not [custom] — is where data the gateway does not model belongs: the order schema
         * accepts no arbitrary top-level parameter, so an invented wire name is refused, while
         * `custom_data` takes any key.
         */
        public fun customData(name: String, value: String): Builder = apply {
            val key = name.trim()
            when {
                key.isEmpty() -> reject("customData: the entry name must not be blank")
                value.isBlank() -> reject("customData: '$key' must not be blank")
                else -> customDataEntries[key] = value
            }
        }

        /**
         * A gateway parameter the SDK does not model yet, by its exact wire name — `shipping`, `tax`
         * and `browser_info` are examples. Sent verbatim: a wrong name or shape surfaces as a gateway
         * rejection, not a local error.
         *
         * NOT for your own data: the order schema is closed, so a name of your invention is refused.
         * Use [customData] for that.
         *
         * Refuses [RESERVED_FIELDS] and the `shipto_` prefix — those have typed parameters on
         * [OrderRequest], and two sources of truth for one field is a payment that fails invisibly.
         */
        public fun custom(name: String, value: String): Builder = apply {
            val key = name.trim()
            when {
                key.isEmpty() -> reject("custom: the parameter name must not be blank")
                key in RESERVED_FIELDS || key.startsWith(SHIPPING_PREFIX) ->
                    reject("custom: '$key' is set by the SDK — use the matching OrderRequest parameter")
                else -> put(key, value)
            }
        }

        /** The immutable set, or [HiPayException] with [HiPayErrorCode.VALIDATION] for the first value
         *  any setter rejected. Declared `@Throws` so the failure is catchable from Swift. */
        @Throws(HiPayException::class, CancellationException::class)
        public fun build(): OrderOptions {
            rejection?.let { throw HiPayException(code = HiPayErrorCode.VALIDATION, message = it) }
            val all = LinkedHashMap(fields)
            // A JSON string, not an object: that is the shape the order schema declares for this one.
            if (customDataEntries.isNotEmpty()) {
                all["custom_data"] =
                    JsonObject(customDataEntries.mapValues { JsonPrimitive(it.value) }).toString()
            }
            return OrderOptions(all)
        }

        private fun put(key: String, value: String, label: String = key) {
            if (value.isBlank()) reject("$label: must not be blank") else fields[key] = value
        }

        /** Keeps the FIRST rejection: the earliest mistake is the one the caller can act on. */
        private fun reject(message: String) {
            if (rejection == null) rejection = message
        }
    }

    public companion object {
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
            // custom_data is reachable, but only through Builder.customData: one way in, so two
            // callers cannot each believe they own the field.
            "cid", "ipaddr", "custom_data",
            // Derived from the running device, so a supplied value could only be less accurate.
            "source", "http_user_agent",
            // Customer block; the shipping block is covered by the shipto_ prefix.
            "city", "country", "email", "firstname", "lastname", "phone", "recipientinfo", "state",
            "streetaddress", "streetaddress2", "zipcode",
        )
    }
}
