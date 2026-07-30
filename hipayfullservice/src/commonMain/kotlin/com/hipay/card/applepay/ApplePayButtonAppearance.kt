// PCI: com.hipay.card path — never log here.
package com.hipay.card.applepay

/**
 * Apple Pay button appearance — the shared contract both delivery channels expose so the Swift SPM
 * adapter and the Compose (CMP) adapter offer the SAME API. Apple forbids redrawing the button, so
 * these map 1:1 to PassKit's `PKPaymentButtonStyle` / `PKPaymentButtonType`; no custom styling is
 * possible or exposed. Each adapter translates these to the platform PassKit values.
 */

/**
 * Button style — maps 1:1 to `PKPaymentButtonStyle`.
 *
 * - [BLACK]: dark fill, for a light background.
 * - [WHITE]: white fill without a border, for a coloured/dark contrasting background.
 * - [WHITE_OUTLINE]: white fill with a black outline, for a light/white background.
 * - [AUTOMATIC]: follows the system light/dark mode (iOS 14+); the default.
 */
public enum class HiPayApplePayButtonStyle {
    BLACK,
    WHITE,
    WHITE_OUTLINE,
    AUTOMATIC,
}

/**
 * Button type (call-to-action label) — maps 1:1 to `PKPaymentButtonType`. Only the values relevant
 * to digital sales + account funding + support are exposed.
 *
 * - [PLAIN]: Apple Pay logo only, no call to action.
 * - [BUY]: "Buy with" (default).
 * - [CHECKOUT]: "Check out with".
 * - [BOOK]: "Book with".
 * - [SUBSCRIBE]: "Subscribe with".
 * - [ORDER]: "Order with".
 * - [CONTINUE]: "Continue with" (iOS 15+).
 * - [RELOAD] / [ADD_MONEY] / [TOP_UP]: account/balance funding.
 * - [TIP] / [DONATE] / [SUPPORT] / [CONTRIBUTE]: tipping, donations, support.
 */
public enum class HiPayApplePayButtonType {
    PLAIN,
    BUY,
    CHECKOUT,
    BOOK,
    SUBSCRIBE,
    ORDER,
    CONTINUE,
    RELOAD,
    ADD_MONEY,
    TOP_UP,
    TIP,
    DONATE,
    SUPPORT,
    CONTRIBUTE,
}
