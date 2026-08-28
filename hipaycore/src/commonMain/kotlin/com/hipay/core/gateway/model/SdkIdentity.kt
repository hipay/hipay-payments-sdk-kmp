package com.hipay.core.gateway.model

import com.hipay.core.HIPAY_SDK_VERSION
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The `source` field every order carries: which integration produced this transaction.
 *
 * `CSDK` is the gateway's value for a **client** SDK — one running on the customer's device, as
 * opposed to `SSDK` for a server-side one. The platform reads it to attribute the transaction, and the
 * back office renders it: without it a mobile payment is reported under the default profile, which is
 * why one showed up as a desktop transaction. Both previous-generation SDKs sent it and this one did
 * not, so this is a restored regression rather than a new feature.
 *
 * `integration_version` is the part worth having: it says exactly which SDK build a merchant is
 * running, which is the first question asked when triaging a payment reported from the field.
 *
 * Not configurable: it identifies the SDK, so letting an integrator set it would make the value
 * worthless for the only two purposes it serves.
 */
internal fun sdkSourceField(): String = JsonObject(
    mapOf(
        "source" to JsonPrimitive(CLIENT_SDK),
        "brand" to JsonPrimitive(platformBrand()),
        "brand_version" to JsonPrimitive(platformVersion()),
        "integration_version" to JsonPrimitive(HIPAY_SDK_VERSION),
    ),
).toString()

/** The gateway's origin value for an SDK running on the customer's device. */
private const val CLIENT_SDK = "CSDK"

/** `android` or `ios` — the platform this build runs on, matching the previous SDKs' values. */
internal expect fun platformBrand(): String

/** The OS version of the device, as the previous SDKs reported it in `brand_version`. */
internal expect fun platformVersion(): String

/**
 * The device's HTTP `User-Agent`, sent as the order's `http_user_agent`.
 *
 * Both previous-generation SDKs sent this and went to real trouble for it — the iOS one instantiated a
 * `WKWebView` purely to read `navigator.userAgent`. Parsing the User-Agent is how a payment console
 * determines the device a transaction came from, so with the field absent a mobile payment is reported
 * under the platform's default profile.
 *
 * A native app declaring a browser User-Agent is a representation choice, and a deliberate one: it is
 * what the previous SDKs did, and it is consistent with `device_channel` staying at the gateway's
 * default of 2 (Browser) — this SDK does present its 3DS challenge in a real browser (a Custom Tab on
 * Android, an `ASWebAuthenticationSession` on iOS).
 *
 * Its companion field `http_accept` is deliberately NOT sent yet: the previous SDKs sent both, but the
 * device attribution this restores depends on the User-Agent alone, and changing one field at a time is
 * what makes the result readable.
 *
 * Nullable on purpose: the gateway scores this field, so a platform that cannot produce a credible
 * value must report nothing rather than a placeholder — see the Android implementation for the measured
 * consequence of getting that wrong.
 */
internal expect fun platformUserAgent(): String?
