// PCI (NFR2): com.hipay.card path — never log here.
package com.hipay.card.cmp

/**
 * Platform 3DS presenter (story 11.13). On iOS it presents an in-app
 * `ASWebAuthenticationSession` and invokes [onResult] with the callback URL when the session
 * completes (or `null` if the user cancels). On Android it is a no-op: the Android CMP controller
 * delegates 3DS to the native `:hipaycard` (Custom Tabs), so this launcher is never used there.
 */
internal expect class CmpThreeDSLauncher() {
    fun launch(url: String, callbackScheme: String, onResult: (String?) -> Unit)
}
