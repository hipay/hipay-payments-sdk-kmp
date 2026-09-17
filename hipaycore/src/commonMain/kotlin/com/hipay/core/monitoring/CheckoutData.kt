package com.hipay.core.monitoring

import com.hipay.core.HIPAY_SDK_VERSION
import com.hipay.core.gateway.model.platformBrand
import com.hipay.core.gateway.model.platformVersion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One `checkout-data` event. Nothing here identifies a payer or a merchant: no card data, no
 * transaction reference, no host application. Nulls are omitted, not serialized.
 */
@Serializable
internal class CheckoutData(
    val event: String,
    val id: String? = null,
    val status: String? = null,
    @SerialName("payment_method") val paymentMethod: String? = null,
    @SerialName("card_country") val cardCountry: String? = null,
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

/** A random UUID, hashed. Kept nowhere: it ties one journey's events together and nothing else. */
internal fun checkoutEventId(): String = sha256Hex(randomUuid())

internal val checkoutDataJson: Json = Json {
    explicitNulls = false
    encodeDefaults = true
}
