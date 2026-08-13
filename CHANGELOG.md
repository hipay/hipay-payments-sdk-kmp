# Changelog — HiPay Payments Mobile SDK

One version number covers the three delivery channels: the Android artifacts, the KMP artifacts and
the iOS XCFramework/SPM package.

<!-- Editing this file? Read CONTRIBUTING.md, section "Changelog — maintainer conventions".
     A version heading must be EXACTLY "## x.y.z" — no date, no title. The release pipeline matches
     that line verbatim, so any suffix produces empty release notes. Put the date on the line below. -->

## Unreleased

### Added

- **Apple Pay on iOS.** A ready-made button, an eligibility check that answers *why* it is
  unavailable, and the full payment — the SDK presents the sheet, so you never touch PassKit. Ships as
  a separate artifact — add it only if you want it.
  - **Swift:** `HiPayApplePayPayment.pay(configuration:applePay:order:)`,
    `.isAvailable(configuration:currency:)` and `.availability(configuration:currency:)` — the last
    returning the reason plus the networks the sheet would offer, so an absent button is explainable.
    With `HiPayApplePayConfiguration`, `HiPayApplePayOrder` and `HiPayApplePayOutcome`.
  - **Compose Multiplatform:** `runHiPayApplePayPayment(...)`,
    `resolveHiPayApplePayAvailability(...)` and `hiPayApplePaySupported()`. Apple Pay is iOS-only, so
    the Android side of the shared code throws rather than pretending to work.
  - `ApplePayOrder` now carries a **`signature`**, computed by your backend exactly as for a card
    order. An account that requires signed orders refuses an unsigned wallet order.
- **One-click payments are generally available.** A returning payer pays from a card saved on a
  previous purchase, with no card number and no security code. Still off by default.
- Compose Multiplatform can now set the **currency** its account restrictions are resolved for, and
  how many saved cards show before "Show more". The two native platforms already could.

### Changed

- One-click is no longer flagged experimental and is documented as a supported feature.
- **The saved-card list no longer collapses** when the payer opens the new-card form, and the
  "Saved cards" header is no longer a toggle. A "Show more" control now reveals the cards beyond the
  first few, and the expand state moved onto the "New card" row.

### Removed

- **`HiPayCardEntryTags.SAVED_CARDS_HEADER`** → use `HiPayCardEntryTags.SHOW_MORE`. The collapsible
  header it identified no longer exists. Android UI tests are the only code that can reference it;
  application code is unaffected.

## 1.0.0

_First public release._

The `0.1.0` to `0.3.0` tags were internal developer previews, never published to Maven Central or
SPM. There is no 0.x migration path. Integration steps and API reference: see the documentation for
this version.

### Payment

- A single call runs the whole payment — tokenization, order, authentication and confirmation — and
  returns the final result as confirmed by the server.
- Card numbers and security codes stay inside the component. They never reach your code and are
  never stored.
- A headless mode remains available if you prefer to drive each step yourself.
- The card fields lock themselves while a payment is in flight, so a double tap cannot start two.

### 3-D Secure

- The SDK opens and handles the authentication challenge itself, in-app on iOS and in a Chrome
  Custom Tab on Android. Android hosts declare one redirect intent-filter.
- If the payer closes the challenge, or returns without a callback, the SDK asks the server for the
  real state rather than assuming a cancellation — a captured payment is never reported as aborted.
- When the server cannot be reached at that moment, the result is explicitly undetermined rather
  than a wrong answer.
- Authentication is not requested by default; the policy is chosen per payment.

### Card networks and validation

- The merchant account decides which card networks are accepted. A restriction set by the integrator
  can only narrow that list, never extend it.
- A card the account refuses is now rejected in the form — no brand icon, no way to submit — instead
  of failing at the gateway once the payer has filled everything in.
- If the account cannot be queried, card entry stays open rather than blocking the payer.
- Co-branded cards (CB with Visa or Mastercard, Bancontact) offer both networks, with the domestic
  one preselected.
- Field errors appear as soon as they are certain, and wait for the server verdict as long as the
  card could still turn out to be valid.

### Appearance and language

- The colors, sizes and fonts of the card fields are set through one shared style, identical on the
  three platforms.
- The default look is light-mode. Dark-mode apps should supply their own colors until dark theme
  ships.
- French, English and Italian, following the device language, with English as the fallback.
- The language can be set once for the whole SDK and changed at runtime — every visible card
  updates, with no re-initialization.

### Distribution

- Android and KMP through Maven Central, iOS through Swift Package Manager. Apache-2.0.
