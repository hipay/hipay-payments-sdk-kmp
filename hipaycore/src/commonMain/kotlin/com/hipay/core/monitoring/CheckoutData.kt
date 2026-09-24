package com.hipay.core.monitoring

import com.hipay.core.HIPAY_SDK_VERSION
import com.hipay.core.gateway.model.platformBrand
import com.hipay.core.gateway.model.platformVersion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One `checkout-data` event. No card data and no payer identity ever — but the merchant's order id
 * and HiPay's transaction reference do travel, on the `request` event, so a journey can be tied to
 * the transaction it produced. Nulls are omitted, not serialized.
 *
 * [domain] identifies the host application and is filled for every event; the order fields only
 * exist once the order has been answered, so the correlation [id] is what carries the earlier events
 * back to it.
 */
@Serializable
internal class CheckoutData(
    val event: String,
    val id: String? = null,
    val status: String? = null,
    @SerialName("payment_method") val paymentMethod: String? = null,
    @SerialName("card_country") val cardCountry: String? = null,
    val domain: String? = hostDomain(),
    @SerialName("order_id") val orderId: String? = null,
    @SerialName("transaction_id") val transactionId: String? = null,
    val amount: Double? = null,
    val currency: String? = null,
    val monitoring: Monitoring? = null,
    val components: Components = Components(),
)

/** The timestamps of the step this event reports; each event fills only its own. */
@Serializable
internal class Monitoring(
    @SerialName("date_init") val dateInit: String? = null,
    @SerialName("date_display") val dateDisplay: String? = null,
    @SerialName("date_pay") val datePay: String? = null,
    @SerialName("date_request") val dateRequest: String? = null,
    @SerialName("date_response") val dateResponse: String? = null,
)

/**
 * Which integration produced the transaction. `cms_version` is the SDK's own version;
 * `sdk_client_version` is the device's OS version, the companion of `sdk_client`.
 */
@Serializable
internal class Components(
    val cms: String = cmsIdentity(),
    @SerialName("cms_version") val cmsVersion: String = HIPAY_SDK_VERSION,
    @SerialName("sdk_client") val sdkClient: String = platformBrand(),
    @SerialName("sdk_client_version") val sdkClientVersion: String = platformVersion(),
)

internal object CheckoutEvent {
    const val INIT = "init"
    const val TOKENIZE = "tokenize"
    const val REQUEST = "request"
}

/**
 * A random UUID hashed with the host application, as the legacy SDKs composed it. Kept nowhere: it
 * ties one journey's events together and nothing else, and carries no payer identity either way.
 */
internal fun checkoutEventId(): String = sha256Hex(randomUuid() + ":" + (hostDomain() ?: ""))

internal val checkoutDataJson: Json = Json {
    explicitNulls = false
    encodeDefaults = true
}
