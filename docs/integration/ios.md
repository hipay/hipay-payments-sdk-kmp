# iOS integration

SwiftUI card-entry + headless payment over a binary KMP `XCFramework`, exposed by a hand-written
100%-Swift facade split into two SPM products: **`HiPayCore`** (headless) and **`HiPayCard`** (UI).
`pay()` presents the 3DS challenge itself and returns the final, server-confirmed transaction.

## Requirements

- Xcode (the toolchain used to build the v1 demo), Swift 5.9+/6
- Targets: `iosArm64` (device) + `iosSimulatorArm64` (Apple-Silicon simulator)
- Two SPM products: `HiPayCore` (headless) and `HiPayCard` (UI), both resolved from the package below

> **Why isn't `HiPayCard` in the `.xcframework`?** The `.xcframework` is **only** the compiled
> KMP/Kotlin core (`HiPayFullservice`). `HiPayCore`/`HiPayCard` are the **hand-written Swift facade
> (D4)** — Swift *source*, shipped in the package, depending on the binary. They cannot live inside a
> KMP-compiled framework.

## Add the SDK (Swift Package Manager)

In Xcode: **File ▸ Add Package Dependencies…**, enter
`https://github.com/hipay/hipay-payments-sdk-ios`, choose **Up to Next Major Version** from
**1.0.0**, then add the **`HiPayCore`** and **`HiPayCard`** products to your target.

From a `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/hipay/hipay-payments-sdk-ios.git", from: "1.0.0")
],
targets: [
    .target(name: "YourApp", dependencies: [
        .product(name: "HiPayCore", package: "hipay-payments-sdk-ios"),
        .product(name: "HiPayCard", package: "hipay-payments-sdk-ios"),
    ])
]
```

> SwiftPM downloads the compiled `HiPayFullservice.xcframework` from the release assets and verifies
> it against the checksum recorded in the tag's manifest, so a tampered binary fails to resolve.

## Use the component

```swift
import HiPayCard
import HiPayCore

let config = HiPayConfiguration(username: "…", password: "…", environment: .stage)
@StateObject var card = HiPayCardEntryController(configuration: config) // allowedNetworks: [] by default

var body: some View { HiPayCardEntryView(controller: card) }
```

Pay (the card token never leaves the controller):

```swift
// Production: get `signature` from YOUR backend. For a first STAGE test, compute it locally
// with the StageSignature helper below (⚠️ stage/test only).
let signature = StageSignature.compute(orderId: orderId, amount: "10.00", currency: "EUR")
let tx = try await card.pay(
    orderId: orderId, amount: "10.00", currency: "EUR",
    description: "Order …", redirectScheme: "yourscheme",
    authenticationIndicator: 0,            // 0 bypass / 1 if-available / 2 mandatory 3DS
    signature: signature
    // threeDS: .inAppSession is the default — see "3DS presentation" below
)
switch tx.state {                          // tx is the FINAL state (3DS already handled)
case .completed: /* success */
case .pending:   /* pending */
case .declined:  /* declined */
default: /* error */
}
```

> **The card fields lock themselves while `pay()` is in flight** — the SDK exposes
> `controller.isProcessing` and `HiPayCardEntryView` disables its fields on it (no parameter to
> wire). Disable your own Pay button the same way: `.disabled(!card.canPay || card.isProcessing)`.
> The SDK also clears the card after tokenisation (PCI), so `canPay` is false after a successful
> payment — a new payment needs a fresh card entry.

## Accepted card networks — the account decides

The component asks your HiPay account which card products it is contracted for as soon as the view
appears, and only offers those. You do not configure this, and you cannot widen it: the optional
`allowedNetworks` you pass **narrows** that set.

```swift
let controller = HiPayCardEntryController(
    configuration: configuration,
    // Optional. Narrows what the account already accepts — never widens it.
    allowedNetworks: [.visa, .mastercard],
    // Optional (default "EUR"). A contract can differ per currency, so pass the currency the
    // order will be created in.
    currency: "EUR"
)
```

What the payer sees for a card the account does not accept: no brand icon, the inline
"Card type not allowed" message once the network is known, and a blocked pay button (`canPay` false).

Two behaviours worth knowing before you file a bug:

- **No brand icon is shown while that first answer is in flight.** Whether the detected network is
  offerable at all is exactly what is unknown at that moment.
- **If the query fails** (device offline, gateway unreachable), the ceiling is left OPEN and the
  component behaves exactly as it did before this feature existed — the locally detected icon is
  shown and nothing is refused. This is deliberate: a payment form must not be unusable because of a
  network hiccup. It also means that "the restriction does nothing" is usually a connectivity problem
  on the device, not a broken SDK — check that the device can reach the gateway first.

## Styling

`HiPayCardTheme` bridges the shared `HiPayCardEntryStyle` into Swift. Start from `.hipayDefault`
and override per property — Kotlin default args aren't exported to Swift, so you build the theme by
mutation, and the setters enforce fail-fast bounds:

```swift
var theme = HiPayCardTheme.hipayDefault
theme.iconColor = UIColor(red: 0.38, green: 0, blue: 0.93, alpha: 1)
theme.cornerRadius = 12
HiPayCardEntryView(controller: controller, theme: theme)
```

> **Notes.** A custom placeholder color applies from iOS 17 (iOS 15/16 keep the system gray). The
> default baseline is light-mode — pass a dark-adapted theme for dark hosts.

## Localization

The component follows the device language (fr/en/it; English fallback). The **recommended** way to
force a language is SDK-wide via `HiPaySettings` on the configuration:

```swift
let settings = HiPaySettings(localeOverride: nil)   // the same shared type as Android/CMP
let config = HiPayConfiguration(username: user, password: pass, environment: .stage, settings: settings)
settings.setLocaleOverride(Locale(identifier: "fr"))   // live, case-insensitive; nil = follow device
```

`HiPaySettings` is the **same shared type across all platforms** (Android/CMP/iOS) and is observable —
changing the locale re-localizes every card live, no re-init.

For a single surface you can still set the `HiPayCardStrings.localeOverride` static global once before
presenting — it wins over `HiPaySettings`:

```swift
HiPayCardStrings.localeOverride = Locale(identifier: "fr")
```

## One-click / saved cards

A returning payer pays with a card saved on a previous purchase — no card number, no security code.
**Off by default**; nothing is stored and no card store is created until you enable it.

```swift
@StateObject var card = HiPayCardEntryController(
    configuration: config,
    oneClickEnabled: true,
    // Optional (default 3, clamped 1...10): how many cards show before "Show more".
    savedCardsDisplayCount: 3
)
```

**Give the component a scrollable host.** `HiPayCardEntryView` renders a plain `VStack` and never
scrolls on its own. With one-click enabled the payer can reveal every stored card at once via "Show
more" (up to 20 are kept), so the view can grow past a screen height. Put it inside a `ScrollView` —
otherwise "Show less", "New card" and your own Pay button end up off-screen with no way back:

```swift
ScrollView {
    HiPayCardEntryView(controller: card)
    // your Pay button
}
```

Offer to save on a successful payment — the component asks the payer for consent:

```swift
let tx = try await card.pay(/* … */, saveCard: true)
card.lastSaveOutcome        // saved / notEligible / storageFailed
```

**Paying with a saved card needs no new call.** When the payer selects one, your existing `pay(...)`
routes through the stored token by itself, so your Pay button stays a single touch-point:

```swift
card.savedCards             // [HiPaySavedCard]: maskedPan, network, holder, expiry
card.selectSavedCard(saved)
card.selectNewCard()        // back to card entry
await card.deleteSavedCard(saved)
await card.refreshSavedCards()
```

`payWithSavedCard(...)` exists for headless hosts that drive the choice themselves.

The payer's card list is filtered by the same account rules as a new entry: a stored card on a
network your account no longer accepts is dropped from the list.

Only a **token** is stored — never the card number, never the security code. On iOS the token is held in the Keychain.

A stored token can stop being accepted: the card expired, was replaced, or the issuer revoked it.
The payment then fails with `HiPayError.cardNoLongerValid`, the card is dropped from the list, and the payer must enter a
card again. Handle that case explicitly — it is the one one-click failure that is not worth retrying.

## 3DS presentation (turnkey, default on)

By default `pay(...)` **presents the 3DS challenge itself** and returns the **final**,
server-confirmed transaction — you don't open a browser, parse the callback, or call
`getTransaction`. Two modes via the `threeDS:` parameter:

- **`.inAppSession`** (default): an in-app `ASWebAuthenticationSession`. It captures the
  `yourscheme://` redirect itself and auto-dismisses → **no soft-lock, zero host wiring**.
- **`.externalBrowser`** (opt-out): the previous external-Safari behaviour. The SDK still confirms
  internally, but the return comes back through your app's URL scheme, so you must forward it once:

```swift
// Only needed for .externalBrowser; harmless otherwise. Register the scheme in Info.plist.
.onOpenURL { url in card.resume3DS(url) }
```

`pay(..., threeDS: .externalBrowser)` then suspends until that forward arrives and returns the
final tx. Both modes confirm via `getTransaction` under the hood (FR9 — redirect params are never
trusted). For full manual control, use the headless `HiPayCore` path instead (see below).

> **Abort & indeterminate returns.** On **any** non-callback return (sheet/Safari dismissed without
> finishing), the SDK never assumes an abort — it reconciles with the server: a payment you actually
> completed comes back `.completed`, a genuine abort stays `.forwarding`. If the server is
> **unreachable** during that check, `pay()` returns an indeterminate **`.pending`** ("verification
> required") rather than a false abort or a thrown error — re-query `getTransaction` later to resolve it.

## First stage test — local signature (⚠️ STAGE / TEST ONLY)

> **The HS signature MUST be computed on your backend in production.** Never ship the stage
> passphrase inside an app, and the SDK never computes signatures. The helper below exists **only**
> so you can get a first end-to-end **stage** payment from copy-paste, before wiring your backend.
> Delete it before release.
>
> **The hash algorithm must match your HiPay account** (Stage ▸ Integration ▸ Security ▸ signature
> algorithm): this helper uses **SHA-1** — if your account is set to SHA-256/512 the request is
> rejected (HTTP 401), so swap `Insecure.SHA1` for `SHA256`/`SHA512` accordingly. The signed string
> must also **exactly** equal the `orderId` / `amount` / `currency` you pass to `pay(...)` (e.g.
> amount `"10.00"`, not `"10"`). This is the SDK's HS auth (`Authorization: HS base64(username:signature)`)
> — the signature replaces the password on the wire, so a wrong algorithm fails authentication.

```swift
import CryptoKit
import Foundation

// ⚠️ STAGE / TEST ONLY — replace with a backend call for production.
enum StageSignature {
    // HiPay Stage account ▸ Integration ▸ Security ▸ "Secret passphrase".
    private static let passphrase = "<your-stage-passphrase>"

    // HiPay HS scheme: SHA-1(orderId + amount + currency + passphrase), lowercase hex.
    static func compute(orderId: String, amount: String, currency: String) -> String {
        let input = orderId + amount + currency + passphrase
        return Insecure.SHA1.hash(data: Data(input.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}
```

## Manual 3DS confirmation (headless / advanced — FR9)

Only if you bypass the turnkey presentation above (headless `HiPayCore`, or your own browser):
register the redirect scheme in **Info.plist** (`CFBundleURLTypes` → `CFBundleURLSchemes` =
`yourscheme`), then confirm with the **captured** reference:

```swift
import HiPayCore

.onOpenURL { url in
    Task {
        let cb = try HiPay.parseCallback(url)                      // orderId, status, queryParams
        let reference = capturedReference ?? cb.queryParams["reference"]   // prefer the captured (FR9)
        let payment = HiPayPayment(configuration: config)
        let tx = try await payment.getTransaction(reference: reference, signature: signature) // authoritative
        // render .completed / .pending / .declined
    }
}
```

A complete, runnable example is the demo at `src/HiPay-SDK-ios-Demo` (`PaymentScreen.swift`).

## Upgrading from 1.0.0

No source break and no behaviour change on iOS. One-click is no longer flagged experimental, and
Apple Pay is new — see [Apple Pay](apple-pay.md).

## Notes

- **Localization**: FR/EN/IT (default EN) ship in the `HiPayCard` resource bundle; **device locale** (no per-view `localeOverride` on iOS — that knob is Android-only).
- **Accessibility**: VoiceOver labels/traits, relative sort priority (opt-out `setsAccessibilityOrder: false`), inline errors announced politely, CVV tooltip.
- **PCI**: the raw PAN and the vault token never leave the controller; never log card data.
- **Facade only (D4)**: integrate via `HiPayCore`/`HiPayCard` — do not `import HiPayFullservice` (the raw KMP) directly.

---

**Other integration paths:** [Android](android.md) · [Compose Multiplatform](cmp.md)
**See also:** [Overview](../index.md) · [Changelog](../changelog.md) · [Report an issue](https://github.com/hipay/hipay-payments-sdk-kmp/issues)
