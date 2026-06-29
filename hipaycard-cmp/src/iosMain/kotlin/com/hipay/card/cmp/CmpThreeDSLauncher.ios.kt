// PCI (NFR2): com.hipay.card path — never log here.
package com.hipay.card.cmp

import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIWindow
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_SEC
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

/**
 * iOS actual (story 11.13). [launchInApp] presents the 3DS page in an in-app
 * `ASWebAuthenticationSession` bound to the app's callback scheme — it captures the `scheme://`
 * redirect itself and auto-dismisses (no soft-lock), then invokes [onResult] (callback URL, or null
 * if cancelled). [launchExternal] opens the page in external Safari (the previous turnkey behaviour);
 * the return comes back through the app's URL scheme → `HiPayCardController.resume3DS(...)`.
 */
internal actual class CmpThreeDSLauncher actual constructor() {
    private var session: ASWebAuthenticationSession? = null
    private val contextProvider = AnchorProvider()
    private var foregroundObserver: NSObjectProtocol? = null

    actual fun launchInApp(url: String, callbackScheme: String, onResult: (String?) -> Unit) {
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

    actual fun launchExternal(url: String, onForegroundReturn: () -> Unit) {
        val nsUrl = NSURL(string = url) ?: return
        stopExternalWatcher()
        // External Safari has no cancel callback → watch for the app returning to the foreground.
        foregroundObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
            usingBlock = { _ ->
                // Let a returning .onOpenURL → resume3DS win first; then signal the return.
                dispatch_after(
                    dispatch_time(DISPATCH_TIME_NOW, (0.6 * NSEC_PER_SEC.toDouble()).toLong()),
                    dispatch_get_main_queue(),
                ) { onForegroundReturn() }
            },
        )
        UIApplication.sharedApplication.openURL(nsUrl, options = emptyMap<Any?, Any?>(), completionHandler = null)
    }

    actual fun stopExternalWatcher() {
        foregroundObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        foregroundObserver = null
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
