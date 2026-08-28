package com.hipay.core.gateway.model

import platform.UIKit.UIDevice

internal actual fun platformBrand(): String = "ios"

/** `UIDevice.systemVersion` — the same value the previous iOS SDK reported in `brand_version`. */
internal actual fun platformVersion(): String = UIDevice.currentDevice.systemVersion

/**
 * A complete mobile Safari User-Agent for this device.
 *
 * Synthesized rather than read from a `WKWebView` as the previous iOS SDK did: spinning up a web view
 * and awaiting a JavaScript evaluation costs a lifecycle, the main thread and memory, and makes a
 * payment field asynchronous — for a string that is derivable. This is deterministic and testable.
 *
 * iOS has no system HTTP User-Agent to read: what `URLSession` sends looks like
 * `App/1.0 CFNetwork/1494 Darwin/23.4.0`, which names the kernel and not the device, so nothing would
 * identify an iPhone. Hence a browser-shaped string, which is also what the previous SDK ended up with.
 *
 * **Every token here is either derived or frozen upstream — none is an approximation.**
 * - `Mozilla/5.0` opens every browser User-Agent ever shipped, Safari included. It identifies nothing
 *   and is not a version of anything; it is a compatibility relic and is always literal.
 * - The platform token and `Version/` come from [platformVersion]. Note the two spellings Apple
 *   actually uses: an iPhone says `CPU iPhone OS 18_2`, an iPad says `CPU OS 18_2` — with the OS
 *   version in underscores there, and in dots for `Version/`.
 * - `AppleWebKit/605.1.15`, `Mobile/15E148` and `Safari/604.1` are constants **in real devices too**.
 *   Apple froze them in the Safari User-Agent to reduce fingerprinting, and they have not moved since
 *   iOS 12 regardless of the OS version. Deriving them from anything would make this string *less*
 *   faithful than hard-coding them, so they are hard-coded on purpose.
 */
internal actual fun platformUserAgent(): String? {
    val device = UIDevice.currentDevice
    val model = device.model                                    // "iPhone" / "iPad"
    val version = device.systemVersion
    // iPad drops the model from the CPU token — Apple's own inconsistency, and a User-Agent parser
    // matches on the exact shape, so it has to be reproduced rather than normalised.
    val cpu = if (model == "iPad") "CPU OS" else "CPU $model OS"
    return "Mozilla/5.0 ($model; $cpu ${version.replace('.', '_')} like Mac OS X) " +
        "AppleWebKit/605.1.15 (KHTML, like Gecko) " +
        "Version/$version Mobile/15E148 Safari/604.1"
}
