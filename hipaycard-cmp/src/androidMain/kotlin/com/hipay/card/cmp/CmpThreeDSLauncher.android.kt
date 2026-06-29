// PCI (NFR2): com.hipay.card path — never log here.
package com.hipay.card.cmp

/**
 * Android actual (story 11.13): a no-op. The Android CMP `HiPayCardController` delegates to the
 * native `:hipaycard` controller, which presents 3DS in Custom Tabs — so `CmpCardController` (and
 * therefore this launcher) is never instantiated on Android.
 */
internal actual class CmpThreeDSLauncher actual constructor() {
    actual fun launchInApp(url: String, callbackScheme: String, onResult: (String?) -> Unit) {
        onResult(null)
    }

    actual fun launchExternal(url: String, onForegroundReturn: () -> Unit) {
        // no-op
    }

    actual fun stopExternalWatcher() {
        // no-op
    }
}
