// PCI: a forward URL and a transaction reference are order data — never log here.
package com.hipay.core.threeds

import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

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
        val nsUrl = NSURL.URLWithString(url)
        val scheme = nsUrl?.scheme?.lowercase()
        if (nsUrl == null || (scheme != "http" && scheme != "https")) {
            // The session only accepts a parseable http(s) URL — handed anything else it is nil, and a
            // nil the Kotlin binding would turn into a crash. Answering `null` instead is what matters:
            // the caller is awaiting an already-authorized payment, and silence would hang it for good.
            onResult(null)
            return
        }
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
        // The session refuses to start when it has no usable anchor window, when something else is
        // still presented, or when the URL is not http(s) — and then never calls its completion
        // handler at all. Reporting the refusal is the difference between a reconcilable outcome and
        // a payment nobody can ever hear back about.
        if (!started.start()) {
            session = null
            onResult(null)
        }
    }

    override fun cancel() {
        val current = session ?: return
        session = null
        // Dismissing the session is UIKit work, while a cancellation handler can run on any thread.
        dispatch_async(dispatch_get_main_queue()) { current.cancel() }
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
