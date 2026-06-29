// PCI (NFR2): com.hipay.card path — never log here.
package com.hipay.card.cmp

/**
 * 3DS presentation mode for the CMP card controller (story 11.13 parity with native iOS):
 * - [IN_APP_SESSION] (default): in-app ASWebAuthenticationSession (iOS) / Custom Tabs (Android),
 *   self-captures the callback — no host wiring on iOS.
 * - [EXTERNAL_BROWSER]: opens the external browser (iOS Safari); the host forwards the return URL
 *   via `HiPayCardController.resume3DS(...)`. On Android both modes use Custom Tabs (no external
 *   distinction / no soft-lock there).
 */
public enum class HiPayThreeDSMode { IN_APP_SESSION, EXTERNAL_BROWSER }

/**
 * Platform 3DS presenter. On iOS, [launchInApp] presents an `ASWebAuthenticationSession` (invokes
 * [onResult] with the callback URL or null on cancel), and [launchExternal] opens external Safari
 * (the return arrives via the app URL scheme → `resume3DS`). On Android it is a no-op: the Android
 * CMP controller delegates 3DS to the native `:hipaycard` (Custom Tabs), so this launcher is unused.
 */
internal expect class CmpThreeDSLauncher() {
    fun launchInApp(url: String, callbackScheme: String, onResult: (String?) -> Unit)
    /**
     * Opens the external browser. Since external Safari gives no cancel callback, [onForegroundReturn]
     * fires (after a short delay) when the app comes back to the foreground, so the controller can
     * treat a return-without-`resume3DS` as a user abort (story 11.16). Call [stopExternalWatcher]
     * once a real callback arrives.
     */
    fun launchExternal(url: String, onForegroundReturn: () -> Unit)
    fun stopExternalWatcher()
}
