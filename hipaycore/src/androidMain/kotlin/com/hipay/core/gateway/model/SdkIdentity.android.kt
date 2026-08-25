package com.hipay.core.gateway.model

import android.os.Build

internal actual fun platformBrand(): String = "android"

/** `Build.VERSION.RELEASE` — the same value the previous Android SDK reported. */
internal actual fun platformVersion(): String = Build.VERSION.RELEASE ?: ""

/**
 * The device's own HTTP User-Agent, read from the `http.agent` system property — the same source the
 * previous Android SDK used. Nothing here is synthesized, and nothing is invented when it is missing.
 *
 * Android builds the value itself, so every part of it is the running device's:
 * `Dalvik/2.1.0 (Linux; U; Android 14; Pixel 7 Build/UP1A.231005.007)`. The `Dalvik/2.1.0` prefix is a
 * historical artefact — Dalvik was the runtime before ART, and the OS kept the token for compatibility;
 * it identifies the HTTP client, while `Android 14; Pixel 7` identifies the device.
 *
 * **Null when the property is unset, and that is deliberate.** The gateway feeds this field to risk
 * scoring, so a malformed value is not a harmless approximation — it gets the payment DECLINED, while
 * omitting the field entirely completes normally. That is measured, not assumed: on a JVM host
 * `http.agent` is null, and an earlier version of this function fell back to
 * `Dalvik/2.1.0 (Linux; U; Android )` with an empty version, which turned a real stage order from
 * COMPLETED into DECLINED. Reporting nothing costs the device attribution; reporting nonsense costs
 * the payment.
 */
internal actual fun platformUserAgent(): String? =
    System.getProperty("http.agent")?.takeIf { it.isNotBlank() }
