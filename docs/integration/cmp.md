# KMP / Compose-Multiplatform integration

Add the **shared Compose-Multiplatform card UI** to your KMP app: one `@Composable HiPayCardEntry`
from `commonMain` renders the HiPay card-entry component on **Android and iOS**, backed by
`hipaycard-cmp`. The card token stays inside the controller (PCI); your host owns the Pay button and
calls `pay(...)`, which presents the 3DS challenge and returns the final transaction.


> Prefer to build your own UI (or drive payments from a backend)? A **headless core** is available
> for full control — see **[Headless core (advanced)](#headless-core-advanced)** at the end.

A runnable reference consumer lives in the separate **`HiPay-SDK-CMP-Demo`** repo — its
`shared/commonMain` renders `HiPayCardEntry` and pays on Android + iOS.

## Requirements

- Kotlin **2.2.20**, AGP **8.13.0**, Gradle **8.14.3**; targets `androidTarget`, `iosArm64`, `iosSimulatorArm64`.
- The Compose-Multiplatform plugin/runtime your app already uses for shared UI.
- Artifact (KMP, resolved per-target via Gradle Module Metadata):
  - **`com.hipay.payments:card-cmp`** — the Compose-Multiplatform card-entry UI
    (`@Composable HiPayCardEntry` + `HiPayCardController`), shared Android **and** iOS. It **brings
    the headless core transitively** — this is the only dependency you add for the UI integration.

## Add the SDK (commonMain)

```kotlin
// settings.gradle.kts — mavenCentral() is usually already there
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}

// build.gradle.kts (your KMP/CMP module)
kotlin {
    androidTarget(); iosArm64(); iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            implementation("com.hipay.payments:card-cmp:1.0.0")   // card UI (+ core, transitively)
            // Needed to LAUNCH the suspend API (coroutines are `implementation` in the SDK).
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
    }
}
```

## Use the card UI (shared Android + iOS)

Render one `@Composable` from `commonMain`. The card token stays inside the controller (PCI); the
host owns the Pay button and calls `pay(...)`.

```kotlin
// commonMain — shared screen (renders on Android + iOS)
import androidx.compose.runtime.*
import com.hipay.card.cmp.HiPayCardController
import com.hipay.card.cmp.HiPayCardEntry
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import com.hipay.core.gateway.model.TransactionState
import kotlinx.coroutines.launch

@Composable
fun CardScreen() {
    val config = remember { HiPayConfig(username = "…", password = "…", environment = Environment.STAGE) }
    val controller = remember { HiPayCardController(config) }   // allowedNetworks: [] by default
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    val scope = rememberCoroutineScope()

    HiPayCardEntry(controller)                                  // the shared card fields

    var status by remember { mutableStateOf<TransactionState?>(null) }

    // The card fields lock themselves while pay() runs (controller.isProcessing) — mirror it on
    // your own Pay button; no isPaying flag to maintain.
    Button(enabled = controller.canPay && !controller.isProcessing, onClick = {
        scope.launch {
            val signature = StageSignature.compute("ORD-1", "10.00", "EUR")   // ⚠️ stage-only, see below
            // The SDK presents any 3DS itself (iOS in-app session / Android Custom Tabs) and
            // returns the FINAL, server-confirmed tx — see "3DS presentation" below.
            val tx = controller.pay(
                orderId = "ORD-1", amount = "10.00", currency = "EUR",
                description = "Order ORD-1", redirectScheme = "yourscheme",
                authenticationIndicator = 0, signature = signature,
            )
            status = tx.state                       // COMPLETED / PENDING / DECLINED / ERROR
        }
    }) { Text("Pay") }
}
```

> **The card fields lock themselves while `pay()` is in flight** — the SDK exposes
> `controller.isProcessing` and `HiPayCardEntry` disables its fields on it (no parameter to wire);
> mirror it on your own Pay button. After a successful order the SDK clears the card (PCI), so
> `canPay` is false — a new payment needs a fresh card entry.

## Accepted card networks — the account decides

The component asks your HiPay account which card products it is contracted for as soon as it is
composed, and only offers those. You do not configure this, and you cannot widen it: the optional
`allowed` list you pass **narrows** that set.

```kotlin
val controller = HiPayCardController(
    config,
    // Optional. Narrows what the account already accepts — never widens it.
    allowedNetworks = listOf(CardNetwork.VISA, CardNetwork.MASTERCARD),
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

**Headless callers** of `AllowedNetworks` should note that its `allowed` parameter is now nullable:
`null` means "no restriction", an EMPTY list means "authorizes nothing". The two used to be the same
value, which is what let a component accept networks its account could not process.

## Styling

`HiPayCardEntryStyle` is a shared contract from `commonMain`: ARGB `Long` colors (`0xAARRGGBB`),
`Float` metrics, font enums (`fontFamily` reserved = system font). Validated at construction
(`IllegalArgumentException` on out-of-range values). Omit `style` for `hipayDefault`.

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
HiPayCardEntry(controller = controller, style = style)   // shared expect/actual, Android + iOS
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
val controller = HiPayCardController(
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

Only a **token** is stored — never the card number, never the security code. On Android it is
AES/GCM-encrypted with a non-exportable Keystore key and kept in the SDK's DataStore file; on iOS it
is held in the Keychain. **Android hardening:** exclude
`datastore/hipay_saved_cards.preferences_pb` from backup in your app's `dataExtractionRules` /
`fullBackupContent`, so a device transfer cannot carry an undecryptable blob.

A stored token can stop being accepted: the card expired, was replaced, or the issuer revoked it.
The payment then fails with `HiPayErrorCode.CARD_NO_LONGER_VALID`, the card is dropped from the list, and the payer must enter a
card again. Handle that case explicitly — it is the one one-click failure that is not worth retrying.

## 3DS presentation (turnkey, default on)

By default `pay(...)` presents the 3DS challenge and returns the **final**, server-confirmed
transaction (confirmed via `getTransaction`, FR9). Choose the mode with
`threeDS = HiPayThreeDSMode.IN_APP_SESSION` (default) or `EXTERNAL_BROWSER`:

- **iOS** `IN_APP_SESSION`: an in-app `ASWebAuthenticationSession` — it self-captures the
  `yourscheme://` callback and auto-dismisses. **No host wiring.** `EXTERNAL_BROWSER` opens Safari →
  forward the return via `resume3DS` from the iOS host's `.onOpenURL` (+ register the URL scheme).
- **Android** (both modes): Chrome Custom Tabs — forward the deep-link return once (host `Activity.onNewIntent`):

```kotlin
// Android host only (the iOS in-app session needs nothing). Register the scheme in the manifest.
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    intent.data?.let { controller.resume3DS(it.toString()) }   // SDK confirms + resumes pay()
}
```

On **any** non-callback return (sheet dismissed / Custom Tab or Safari closed without finishing), the
SDK never assumes an abort — it reconciles with the authoritative server state: a completed payment is
reported `COMPLETED`, a genuine abort stays `FORWARDING` (not-completed). If the server is
**unreachable** during that check, `pay()` returns an indeterminate **`PENDING`** ("verification
required") rather than a false abort or a thrown error — re-query `getTransaction` later to resolve it.

## First stage test — local signature (⚠️ STAGE / TEST ONLY)

> **The HS signature MUST be computed on your backend in production** — never ship the stage
> passphrase, and the SDK never computes it. For a first **stage** test from `commonMain`, an
> `expect`/`actual` helper computes SHA-1 per target (`MessageDigest` on Android, `CC_SHA1` on iOS).
> Delete it before release.
>
> **The hash algorithm must match your HiPay account** (Stage ▸ Integration ▸ Security ▸ signature
> algorithm): this helper uses **SHA-1** — if your account is set to SHA-256/512 the request is
> rejected (HTTP 401), so change both `actual`s accordingly. The signed string must also **exactly**
> equal the `orderId` / `amount` / `currency` you pass to `pay(...)` (e.g. amount `"10.00"`). This is
> the SDK's HS auth (`Authorization: HS base64(username:signature)`) — the signature replaces the
> password on the wire, so a wrong algorithm fails authentication.

```kotlin
// commonMain
internal expect fun sha1Hex(input: String): String

// ⚠️ STAGE / TEST ONLY
object StageSignature {
    private const val PASSPHRASE = "<your-stage-passphrase>"   // Stage ▸ Integration ▸ Security
    // HiPay HS scheme: SHA-1(orderId + amount + currency + passphrase), lowercase hex.
    fun compute(orderId: String, amount: String, currency: String): String =
        sha1Hex(orderId + amount + currency + PASSPHRASE)
}
```

```kotlin
// androidMain
import java.security.MessageDigest
internal actual fun sha1Hex(input: String): String =
    MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
```

```kotlin
// iosMain
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
import kotlinx.cinterop.*
import platform.CoreCrypto.CC_SHA1
import platform.CoreCrypto.CC_SHA1_DIGEST_LENGTH

internal actual fun sha1Hex(input: String): String {
    val bytes = input.encodeToByteArray()
    val out = UByteArray(CC_SHA1_DIGEST_LENGTH)
    bytes.usePinned { b -> out.usePinned { d ->
        CC_SHA1(b.addressOf(0), bytes.size.convert(), d.addressOf(0))
    } }
    return out.joinToString("") { it.toString(16).padStart(2, '0') }
}
```

## Upgrading from 1.0.0

No source break. One behaviour change: opening the new-card form no longer collapses the saved-card
list, and the "Saved cards" header is no longer a toggle — a "Show more" control reveals the cards
beyond the display count.

The controller also accepts `currency` and `savedCardsDisplayCount`, both optional with defaults, so
existing call sites compile unchanged.

## Notes

- **Localization / accessibility**: the shared card UI carries FR/EN/IT strings and the same
  accessibility behaviours as the native components.
- **Signature** — `controller.pay(…, signature)` takes a **backend-computed** HS signature; the SDK
  never computes it (see the stage-only helper above for a first test).
- **PCI** — the raw PAN never leaves the controller; never log card data.
- **Version** — `1.0.0`; pin the same number as the iOS SPM tag / Android AARs (single-version policy across platforms).

---

## Headless core (advanced)

If you don't want the SDK card UI — you build your own UI, or drive payments from shared/backend
logic — use the **headless core** directly. It has **no UI** and never opens a browser: `pay`/order
returns `forwardUrl` as **data** and you present + confirm 3DS yourself.

Add the headless artifact instead of (or alongside) the card UI:

```kotlin
// commonMain — headless only
implementation("com.hipay.payments:core:1.0.0")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
```

```kotlin
import com.hipay.core.HiPayConfig
import com.hipay.core.Environment
import com.hipay.card.CardTokenizer
import com.hipay.core.gateway.GatewayClient
import com.hipay.core.gateway.model.OrderRequest
import com.hipay.core.gateway.model.TransactionState

val config = HiPayConfig(username = "…", password = "…", environment = Environment.STAGE)
val tokenizer = CardTokenizer(config)   // the host owns these (no singleton/DI)
val gateway = GatewayClient(config)

suspend fun pay(): TransactionState {
    val token = tokenizer.generateToken(
        cardNumber = "…", expiryMonth = "12", expiryYear = "2030",
        holder = "…", cvc = "…", multiUse = false,
    )                                       // PAN → Secure Vault token (never persisted)
    val tx = gateway.requestNewOrder(
        OrderRequest(
            orderId = "ORD-1", paymentProduct = "visa", amount = "10.00",
            description = "Order ORD-1",
            acceptUrl = "myapp://hipay-fullservice/gateway/orders/ORD-1/accept",
            declineUrl = "myapp://hipay-fullservice/gateway/orders/ORD-1/decline",
            pendingUrl = "myapp://hipay-fullservice/gateway/orders/ORD-1/pending",
            exceptionUrl = "myapp://hipay-fullservice/gateway/orders/ORD-1/exception",
            cancelUrl = "myapp://hipay-fullservice/gateway/orders/ORD-1/cancel",
            cardToken = token.token,
        ),
        signature = StageSignature.compute("ORD-1", "10.00", "EUR"),
    )
    // capture tx.transactionReference BEFORE 3DS (FR9)
    return tx.state
}
```

### 3DS the headless way (FR9)

The core never opens a browser — `forwardUrl` is exposed as **data**:

```kotlin
import com.hipay.core.callback.CallbackUrlParser

// 1. when tx.state == FORWARDING → the HOST opens tx.forwardUrl (Custom Tab / SFSafariViewController)
// 2. on the return deep link:
val cb = CallbackUrlParser.parse(returnUrl)               // orderId, status, queryParams
val reference = capturedReference ?: cb.queryParams["reference"]
val finalTx = gateway.getTransaction(reference!!, signature) // authoritative outcome
```

> The headless core is also what the per-platform native UIs (Android `:hipaycard`, iOS `HiPayCard`)
> and the CMP card UI build on — you can always drop to it for full control.

---

**Other integration paths:** [Android](android.md) · [iOS](ios.md)
**See also:** [Overview](../index.md) · [Changelog](../changelog.md) · [Report an issue](https://github.com/hipay/hipay-payments-sdk-kmp/issues)
