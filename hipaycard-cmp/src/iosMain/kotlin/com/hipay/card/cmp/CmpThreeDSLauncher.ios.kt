// PCI (NFR2): com.hipay.card path — never log here.
package com.hipay.card.cmp

import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject

/**
 * iOS actual (story 11.13): presents the 3DS page in an in-app `ASWebAuthenticationSession` bound
 * to the app's callback scheme. The session captures the `scheme://` redirect itself and
 * auto-dismisses — no external Safari, no swipe-back soft-lock — then invokes [onResult] with the
 * callback URL (or `null` if the user cancels). The session is retained for its lifetime; the
 * anchor is the current key window. Mirrors the native iOS `HiPayCardEntryController` path.
 */
internal actual class CmpThreeDSLauncher actual constructor() {
    private var session: ASWebAuthenticationSession? = null
    private val contextProvider = AnchorProvider()

    actual fun launch(url: String, callbackScheme: String, onResult: (String?) -> Unit) {
        val nsUrl = NSURL(string = url)
        val s = ASWebAuthenticationSession(
            uRL = nsUrl,
            callbackURLScheme = callbackScheme,
            completionHandler = { callbackURL, _ -> onResult(callbackURL?.absoluteString) },
        )
        s.presentationContextProvider = contextProvider
        s.prefersEphemeralWebBrowserSession = false
        session = s
        s.start()
    }
}

/** Supplies the anchor window for the in-app 3DS session (story 11.13). */
private class AnchorProvider :
    NSObject(),
    ASWebAuthenticationPresentationContextProvidingProtocol {
    override fun presentationAnchorForWebAuthenticationSession(
        session: ASWebAuthenticationSession,
    ): ASPresentationAnchor {
        @Suppress("UNCHECKED_CAST")
        val windows = UIApplication.sharedApplication.windows as List<UIWindow>
        return windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull() ?: UIWindow()
    }
}
