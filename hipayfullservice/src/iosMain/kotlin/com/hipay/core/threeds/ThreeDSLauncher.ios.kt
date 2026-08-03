// PCI: a forward URL and a transaction reference are order data — never log here.
package com.hipay.core.threeds

import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject

/**
 * The iOS 3DS presenter: an in-app `ASWebAuthenticationSession` bound to the app's callback scheme.
 * The session captures the `scheme://` redirect itself and dismisses, so the host wires nothing.
 *
 * Only the in-app mode exists here. An external-browser challenge would need the host to forward the
 * return URL into a long-lived controller, which the one-shot wallet entry point has nowhere to keep.
 */
public fun defaultThreeDSLauncher(): ThreeDSLauncher = WebAuthenticationLauncher()

private class WebAuthenticationLauncher : ThreeDSLauncher {
    // The session must outlive `launchInApp`: dropping it cancels the challenge.
    private var session: ASWebAuthenticationSession? = null
    private val contextProvider = AnchorProvider()

    override fun launchInApp(url: String, callbackScheme: String, onResult: (String?) -> Unit) {
        val nsUrl = NSURL(string = url)
        val started = ASWebAuthenticationSession(
            uRL = nsUrl,
            callbackURLScheme = callbackScheme,
            // A cancelled session yields no callback URL — the resolver re-reads the state from the
            // gateway rather than assuming the customer abandoned the challenge.
            completionHandler = { callbackURL, _ -> onResult(callbackURL?.absoluteString) },
        )
        started.presentationContextProvider = contextProvider
        started.prefersEphemeralWebBrowserSession = false
        session = started
        started.start()
    }
}

/** Supplies the anchor window the challenge session is presented from. */
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
