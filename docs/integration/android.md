# Android integration

Native Jetpack Compose card-entry + headless payment, consuming the shared KMP core directly in
Kotlin (no facade). `pay()` presents the 3DS challenge itself through Custom Tabs, which needs
`androidx.browser` and an `intent-filter` — see [3DS presentation](#3ds-presentation-turnkey-default-on).

## Requirements

- Kotlin **2.2.20**, AGP **8.13.0**, Gradle **8.14.3**, Compose BOM **2025.06.01**
- `minSdk 24`, `compileSdk 36`; `android.useAndroidX=true`
- Two artifacts on Maven Central: `com.hipay.payments:core` (headless) and
  `com.hipay.payments:card` (Compose UI, which api-exposes the core)

## Add the SDK

```kotlin
// settings.gradle.kts — mavenCentral() is usually already there
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}

// app/build.gradle.kts
dependencies {
    implementation("com.hipay.payments:card:1.1.0")
}
```

That single line is enough: the POM pulls the headless core, Ktor, Compose UI/Foundation/Material 3,
`androidx.browser` (3DS Custom Tabs) and DataStore transitively. Add
`com.hipay.payments:core:1.1.0` on its own only if you want the headless core without the UI.

## Use the component

```kotlin
import com.hipay.core.HiPayConfig
import com.hipay.core.Environment
import com.hipay.card.HiPayCardEntry
import com.hipay.card.HiPayCardEntryController

val config = HiPayConfig(username = "…", password = "…", environment = Environment.STAGE)
val controller = HiPayCardEntryController(config, allowedNetworks = emptyList())

setContent { HiPayCardEntry(controller) }   // or host in an XML/Fragment via ComposeView
```

Pay (suspend; the card token never leaves the controller):

```kotlin
// Production: get `signature` from YOUR backend. For a first STAGE test, compute it locally
// with the StageSignature helper below (⚠️ stage/test only).
val signature = StageSignature.compute(orderId, amount = "10.00", currency = "EUR")
val tx = controller.pay(
    orderId = orderId, amount = "10.00", currency = "EUR",
    description = "Order …", redirectScheme = "yourscheme",
    authenticationIndicator = 0,            // 0 bypass / 1 if-available / 2 mandatory 3DS
    signature = signature,
    // autoPresent3DS = true is the default — see "3DS presentation" below
)
when (tx.state) {                           // tx is the FINAL state (3DS already handled)
    TransactionState.COMPLETED -> { /* success */ }
    TransactionState.PENDING -> { /* pending */ }
    TransactionState.DECLINED -> { /* declined */ }
    else -> { /* ERROR (FORWARDING only if you set autoPresent3DS = false) */ }
}
```

> **The card fields lock themselves while `pay()` is in flight** — the SDK exposes
> `controller.isProcessing` and `HiPayCardEntry` disables its fields on it (no parameter to wire).
> Disable your own Pay button the same way: `enabled = controller.canPay && !controller.isProcessing`.
> After a successful order the SDK also clears the card (PCI), so `canPay` is false — a new payment
> needs a fresh card entry.

## Accepted card networks — the account decides

The component asks your HiPay account which card products it is contracted for as soon as it appears
on screen, and only offers those. You do not configure this, and you cannot widen it: the optional
`allowedNetworks` you pass **narrows** that set.

```kotlin
val controller = HiPayCardEntryController(
    config,
    // Optional. Narrows what the account already accepts — never widens it.
    allowedNetworks = listOf(HiPayCardNetwork.VISA, HiPayCardNetwork.MASTERCARD),
    // Optional (default "EUR"). A contract can differ per currency, so pass the currency the
    // order will be created in.
    currency = "EUR",
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

`HiPayCardEntryStyle` is a shared contract: ARGB `Long` colors (`0xAARRGGBB`), `Float` metrics, font
enums (`fontFamily` reserved = system font). Validated at construction (`IllegalArgumentException` on
out-of-range values). Omit `style` for `hipayDefault`.

```kotlin
val style = HiPayCardEntryStyle(
    textColor = 0xFF1A1A1A,
    iconColor = 0xFF6200EE,
    borderColor = 0xFFBDBDBD,
    borderWidth = 1f,
    cornerRadius = 12f,
    backgroundColor = 0xFFFFFFFF,
    fieldHeight = 42f,      // a MINIMUM (heightIn); grows under large font scales
)
HiPayCardEntry(controller = controller, style = style)
```

Default baseline is light-mode — pass a dark-adapted style for dark hosts.

## Localization

Follows the device locale (fr/en/it; English fallback). Set the display language once for the whole
SDK via `HiPaySettings` on the config:

```kotlin
val settings = HiPaySettings()
val config = HiPayConfig(user, pass, env, settings = settings)
settings.setLocaleOverride("fr")   // once for the whole SDK; live, case-insensitive; null = follow device
```

`HiPaySettings` is observable — changing the locale re-localizes every card live, no re-init. A
per-component `localeOverride` still wins. For a one-off, force a language on a single component:

```kotlin
HiPayCardEntry(controller = controller, localeOverride = "fr")
```

## One-click / saved cards

A returning payer pays with a card saved on a previous purchase — no card number, no security code.
**Off by default**; nothing is stored and no card store is created until you enable it.

```kotlin
val controller = HiPayCardEntryController(
    config,
    oneClickEnabled = true,
    // Optional (default 3, clamped 1..10): how many cards show before "Show more".
    savedCardsDisplayCount = 3,
)
```

**Give the component a scrollable host.** `HiPayCardEntry` renders a plain `Column` and never scrolls
on its own. With one-click enabled the payer can reveal every stored card at once via "Show more" (up
to 20 are kept), so the component can grow past a screen height. Put it inside a `verticalScroll`
container — otherwise "Show less", "New card" and your own Pay button end up off-screen with no way
back:

```kotlin
Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
    HiPayCardEntry(controller)
    // your Pay button
}
```

Offer to save on a successful payment — the component asks the payer for consent:

```kotlin
val tx = controller.pay(/* … */, saveCard = true)
controller.lastSaveOutcome   // SAVED / NOT_ELIGIBLE / STORAGE_FAILED
```

**Paying with a saved card needs no new call.** When the payer selects one, your existing `pay(...)`
routes through the stored token by itself, so your Pay button stays a single touch-point:

```kotlin
controller.savedCards          // List<SavedCard>: maskedPan, network, holder, expiry
controller.selectSavedCard(card)
controller.selectNewCard()     // back to card entry
controller.deleteSavedCard(card)
controller.refreshSavedCards()
```

`payWithSavedCard(...)` exists for headless hosts that drive the choice themselves.

The payer's card list is filtered by the same account rules as a new entry: a stored card on a
network your account no longer accepts is dropped from the list.

Only a **token** is stored — never the card number, never the security code. On Android the token blob is AES/GCM-encrypted with a non-exportable Keystore key and kept in the
SDK's DataStore file. **Recommended hardening:** exclude `datastore/hipay_saved_cards.preferences_pb`
from backup in your app's `dataExtractionRules` / `fullBackupContent`, so a device transfer cannot
carry an undecryptable blob.

A stored token can stop being accepted: the card expired, was replaced, or the issuer revoked it.
The payment then fails with `HiPayErrorCode.CARD_NO_LONGER_VALID`, the card is dropped from the list, and the payer must enter a
card again. Handle that case explicitly — it is the one one-click failure that is not worth retrying.

## 3DS presentation (turnkey, default on)

By default `pay(...)` **presents the 3DS challenge in Chrome Custom Tabs** and returns the
**final**, server-confirmed transaction — you don't open a browser or call `getTransaction`. The one
piece of host wiring (Custom Tabs returns via your app's URL scheme): forward the deep-link return
once to `resume3DS(...)`.

```xml
<!-- AndroidManifest.xml — host activity, launchMode="singleTop" -->
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="yourscheme" android:host="hipay-payments" />
</intent-filter>
```

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    intent.data?.let { controller.resume3DS(it.toString()) }   // SDK confirms + resumes pay()
}
```

`pay()` suspends across the challenge and returns the final tx (confirmed via `getTransaction`, FR9 —
redirect params never trusted). Pass `autoPresent3DS = false` to get the raw `FORWARDING` transaction
and handle the redirect yourself (advanced / headless).

> **Abort & indeterminate returns.** If the user dismisses the Custom Tab without finishing, the SDK
> never assumes an abort — it reconciles with the server: a payment actually completed comes back
> `COMPLETED`, a genuine abort stays `FORWARDING`. If the server is **unreachable** during that check,
> `pay()` returns an indeterminate **`PENDING`** ("verification required") rather than a false abort or
> a thrown exception — re-query `getTransaction` later to resolve it.

## First stage test — local signature (⚠️ STAGE / TEST ONLY)

> **The HS signature MUST be computed on your backend in production.** Never ship the stage
> passphrase inside an app, and the SDK never computes signatures. The helper below exists **only**
> so you can get a first end-to-end **stage** payment working from copy-paste, before wiring your
> backend. Delete it before release.
>
> **The hash algorithm must match your HiPay account** (Stage ▸ Integration ▸ Security ▸ signature
> algorithm): this helper uses **SHA-1** — if your account is set to SHA-256/512 the request is
> rejected (HTTP 401), so change `"SHA-1"` accordingly. The signed string must also **exactly** equal
> the `orderId` / `amount` / `currency` you pass to `pay(...)` (e.g. amount `"10.00"`, not `"10"`).
> This is the SDK's HS auth (`Authorization: HS base64(username:signature)`) — the signature replaces
> the password on the wire, so a wrong algorithm fails authentication.

```kotlin
import java.security.MessageDigest

// ⚠️ STAGE / TEST ONLY — replace with a backend call for production.
object StageSignature {
    // HiPay Stage account ▸ Integration ▸ Security ▸ "Secret passphrase".
    private const val PASSPHRASE = "<your-stage-passphrase>"

    // HiPay HS scheme: SHA-1(orderId + amount + currency + passphrase), lowercase hex.
    fun compute(orderId: String, amount: String, currency: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest((orderId + amount + currency + PASSPHRASE).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
```

## Manual 3DS confirmation (headless / advanced — FR9)

Only if you set `autoPresent3DS = false` (or use the headless `GatewayClient`): register the
redirect scheme as above, then confirm yourself with the **captured** reference:

```kotlin
import com.hipay.core.callback.CallbackUrlParser
import com.hipay.core.gateway.GatewayClient

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    val uri = intent.data ?: return
    val cb = CallbackUrlParser.parse(uri.toString())              // orderId, status, queryParams
    val reference = capturedReference ?: cb.queryParams["reference"]   // prefer the captured one (FR9)
    lifecycleScope.launch {
        val tx = GatewayClient(config).getTransaction(reference!!, signature) // authoritative outcome
        // render COMPLETED / PENDING / DECLINED
    }
}
```

A complete, runnable example is the demo at `src/HiPay-SDK-android-Demo` (`PaymentViewModel` + `MainActivity`).

## Upgrading from 1.0.0

**Required, and silent if you miss it: the return deep link changed host.** It is now
`{yourScheme}://hipay-payments/gateway/orders/{orderId}/{status}` — `hipay-fullservice` is gone.
Update the `intent-filter` that catches the return:

```xml
<data android:scheme="yourscheme" android:host="hipay-payments" />
```

Leave the old host in place and the browser returns to a URL nothing handles: the payment simply never
resumes, with no error raised and nothing logged. Nothing changes on the gateway side — the SDK sends
these URLs per order.

One source break, and it can only reach your **UI tests**: `HiPayCardEntryTags.SAVED_CARDS_HEADER`
is gone. The collapsible "Saved cards" header it identified no longer exists — the list now shows
the most recent cards with a "Show more" control (`HiPayCardEntryTags.SHOW_MORE`), and the
expand/collapse state moved onto the "New card" row. Application code is unaffected.

Behaviour to expect: opening the new-card form no longer collapses the saved-card list.

## Notes

- **Localization**: FR/EN/IT (default EN) ship in the card module's `strings.xml`; device locale by default, overridable SDK-wide via `HiPaySettings` on the config or per component via `HiPayCardEntry(..., localeOverride = "fr")` (which wins).
- **Accessibility**: TalkBack labels/state, relative traversal order (opt-out `setsAccessibilityOrder = false`), inline errors announced politely.
- **PCI**: the raw PAN and the vault token never leave the controller; never log card data.
- **Testing**: instrumented Compose UI tests must run on an **API ≤ 35** emulator (Compose ui-test 1.8.3 does not attach on API 37); your own app runs on any supported API.

---

**Other integration paths:** [iOS](ios.md) · [Compose Multiplatform](cmp.md)
**See also:** [Overview](../index.md) · [Changelog](../changelog.md) · [Report an issue](https://github.com/hipay/hipay-payments-sdk-kmp/issues)
