# Changelog — HiPay Payments Mobile SDK

One version number covers the three delivery channels: the Android artifacts, the KMP artifacts and
the iOS XCFramework/SPM package.

<!-- Editing this file? Read CONTRIBUTING.md, section "Changelog — maintainer conventions".
     A version heading must be EXACTLY "## x.y.z" — no date, no title. The release pipeline matches
     that line verbatim, so any suffix produces empty release notes. Put the date on the line below. -->

## 1.1.0

### Added

- **Apple Pay on iOS.** A ready-made button, an eligibility check that answers *why* it is
  unavailable, and the full payment — the SDK presents the sheet, so you never touch PassKit. Ships as
  a separate artifact — add it only if you want it.
  - **Swift:** `HiPayApplePayPayment.pay(configuration:applePay:order:customerCountry:)`,
    `.isAvailable(...)` and `.availability(...)` — the last returning the reason **and** the networks the
    sheet would offer, so an absent button is explainable rather than mysterious. With
    `HiPayApplePayConfiguration`, `HiPayApplePayOrder`, `HiPayApplePayOutcome` and
    `HiPayApplePayNetwork`.
  - **Restricting networks** is expressed with `HiPayApplePayConfiguration.allowedNetworks`, applied to
    both the availability answer and the sheet so the button and the sheet always agree. The type covers
    only what Apple Pay can route at HiPay (Visa, Mastercard, Maestro, CB), so a restriction that could
    never match is not expressible.
  - **`HiPayApplePayOutcome.unknown`** is returned for an outcome this version does not model. Never
    treat it as success or as a cancellation — reconcile on the order id.
  - **Compose Multiplatform:** `runHiPayApplePayPayment(...)`,
    `resolveHiPayApplePayAvailability(...)` and `hiPayApplePaySupported()`. Apple Pay is iOS-only: on
    Android a payment attempt throws rather than pretending to work, and availability always answers
    unavailable — gate on `hiPayApplePaySupported()` rather than reading the reason as a device verdict.
  - `ApplePayOrder` now carries a **`signature`**, computed by your backend exactly as for a card
    order. An account that requires signed orders refuses an unsigned wallet order.
- **One-click payments are generally available.** A returning payer pays from a card saved on a
  previous purchase, with no card number and no security code. Still off by default.
- Compose Multiplatform can now set the **currency** its account restrictions are resolved for, and
  how many saved cards show before "Show more". The two native platforms already could.

### Changed

- **BREAKING — the payment-return deep link now uses the host `hipay-payments`** instead of
  `hipay-fullservice`, dropping the last trace of the previous SDK's name from this one. The full shape
  is `{yourScheme}://hipay-payments/gateway/orders/{orderId}/{status}`.
  - **Android / Compose Multiplatform hosts:** update the `intent-filter` that catches the return —
    `<data android:scheme="yourscheme" android:host="hipay-payments" />`. Miss this and the browser
    comes back to a URL nothing handles: the payment never resumes, with no error and nothing logged.
  - **Headless callers** that build `acceptUrl` / `declineUrl` / `pendingUrl` / `exceptionUrl` /
    `cancelUrl` themselves must use the new host. Read it from `HIPAY_CALLBACK_HOST`, or build the
    prefix with `hipayCallbackBase(scheme, orderId)` (Swift: `CallbackHostKt`), rather than retyping
    the string — both are now public for exactly that.
  - Nothing changes on the gateway side: these URLs are sent per order by the SDK, not configured in
    your account.
- One-click is no longer flagged experimental and is documented as a supported feature.
- **Apple Pay availability must be supplied to the button**, as a required argument — there is no
  default. The only value the SDK could have picked is
  `PKPaymentAuthorizationController.canMakePayments()`, which is `true` on any Apple-Pay-capable device
  *even with no card provisioned*, so a default would show a button that cannot pay. Resolve
  availability with `availability(...)` / `resolveHiPayApplePayAvailability(...)`, hold it in your own
  state starting at "unavailable", and pass it in. The integration guide shows the shape.
- **The saved-card list no longer collapses** when the payer opens the new-card form, and the
  "Saved cards" header is no longer a toggle. A "Show more" control now reveals the cards beyond the
  first few, and the expand state moved onto the "New card" row.
- **Up to 20 cards are now kept on the device, instead of 3.** A valid card is no longer evicted just
  because the payer saved another one; eviction (least recently used) starts only at the 20th. Only
  tokens are stored, in the same encrypted store as before — but a payer can now accumulate noticeably
  more of them, which is worth knowing if your data-retention policy has something to say about it.
  Downgrading to an SDK with the lower ceiling is not supported: the older build truncates the list on
  its next write.
- **The saved-card component expects a scrollable host.** It never scrolled itself, but until now the
  list could not exceed three cards, so it fit anywhere. Now that "Show more" can reveal up to 20, place
  the component inside a scroll container — the integration guides show the shape for each platform.
- **Deleting a saved card no longer asks for confirmation by default.** Both gestures — a left-swipe
  and a long-press — now do the same thing: they reveal a trash affordance on the row. Tapping that
  trash is what deletes; swiping the row back cancels. Reaching the trash therefore already takes two
  deliberate steps, which is why a dialog on top of it was dropped. Set `confirmCardDeletion = true`
  on the controller to put it back.
  - **The confirmation is always shown for a screen-reader request**, whatever the flag says. The
    "Delete card" accessibility action is a single step with no trash to aim at and no reverse swipe to
    undo it, so it keeps its safety net.
  - The long-press previously opened the confirmation directly. If your tests drive deletion, they now
    need the trash tap.
- **A long-press on a saved card now plays the platform's long-press haptic** at the moment the trash
  appears, on all three components, so the gesture announces that it changed meaning while the finger
  is still down.

- **The default appearance now follows the host's light/dark theme.** Leave `style` (iOS: `theme`)
  unset and the component derives its palette from your `MaterialTheme.colorScheme` — on iOS, from the
  system's semantic colours — instead of the fixed light palette it used before. A form embedded in a
  dark screen no longer renders as an opaque white box, and the texts drawn outside the fields (the
  save-card label, the consent line, the section headers) stop being near-black on near-black.
  - **A style you supply is used verbatim, in both appearances.** Adapting your own colours per theme
    is yours to do — the SDK will not second-guess your branding. Nothing changes for you.
  - Only the colours are derived. Font size, border width, corner radius and field height still come
    from the shared contract, so the geometry is identical on every platform either way.
  - Your host must actually provide a dark scheme for this to show. A bare `MaterialTheme { }` always
    resolves to the light one — pass `colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else
    lightColorScheme()`.
  - **The derived field colour is `surfaceContainerHighest`, not `surface`.** `surface` is usually the
    exact colour of the screen the component sits on, which left the field indistinguishable from it
    but for its border. This is Material3's own container choice for a text field.
- **The field labels now float ABOVE the field instead of onto its border**, on Android and Compose
  Multiplatform. The animation is unchanged: the label still rises from inside the field on focus, it
  just lands clear of the border rather than centred on it. iOS is unaffected — its labels have always
  been prompts inside the fields.
  - This exists because a label centred on the border spans two backgrounds at once: the screen above
    it and the field below. With a filled field, that shows as a band of your screen's colour behind
    the label, and nothing paintable fixes it — a fill bands, a patch leaves a tab above the edge, and
    rounding that patch exposes the screen in its corners. Landing clear of the border removes the
    overlap instead of covering it.
  - **`backgroundColor` is consequently free of any constraint** — fill the field with whatever makes
    it read as an input area, contrasting with your screen or not.
  - **`placeholderColor` is the label colour, and a floated label sits on YOUR background.** That is
    the pairing to check for contrast, not the label against the field's fill.
  - Each field row is slightly taller: the landing area sits above the field. The gap between rows was
    reduced to compensate, so the form as a whole grows very little. The field's own geometry — height,
    font size, border width, corner radius — is untouched, so it still matches iOS exactly.
  - The float respects "reduce motion": the label jumps between its two positions instead of sliding.
  - Material3 will do all of this natively through `TextFieldLabelPosition.Above` in a later version
    than the one this release resolves; the local implementation is meant to be replaced by it.

### Fixed

- **The trailing controls inside the Android card fields were vertically off-centre** — the network
  brand icons on the card-number field and the "ⓘ" on the security-code field sat a few dp above the
  middle of the input line. They are positioned as overlays centred on the field's measured height,
  which includes the space kept above the border for the floating label; Compose Multiplatform
  discounted that space and Android did not. Both surfaces now use the same recentring, so the two
  render identically.
- **One-click could not pay with a saved co-branded card.** The card was stored with the brand the
  tokenize response reported, while the payment had been routed on the network the payer actually
  selected — on a co-branded card (Mastercard/CB, for instance) those differ. The later one-click order
  re-sent the stored brand as its `payment_product`, so the gateway was asked to route a network that
  had never carried a successful payment for that token, and refused it. The first payment succeeded,
  every subsequent one-click failed, and no 3DS challenge appeared because the order was rejected
  before authentication. The routed product is now what gets stored.
  - **Cards saved by an earlier build keep the wrong network**, and it cannot be repaired
    retroactively — nothing records which network their first payment used. A payer whose co-branded
    card is affected must delete it and save it again. Mono-network cards were never affected: brand
    and routed product are the same string for them.

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
