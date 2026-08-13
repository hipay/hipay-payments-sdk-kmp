# Apple Pay

Apple Pay is **iOS-only**. On Android the button renders nothing, so the same shared code can run on
both platforms without a conditional.

Most of the work happens **outside the SDK**, in your Apple Developer account. Do that first — the
SDK cannot be configured without it.

## 1. Apple Developer account

Create a **Merchant ID** at *Identifiers → Merchant IDs*, in reverse-domain form
`merchant.com.hipay.{yourcompany}`. Create **separate identifiers for production and staging**.

Generate an **Apple Pay Payment Processing Certificate** for that Merchant ID: create a CSR
following Apple's instructions, upload it, download the `.cer`, and add it to your Keychain.

Export it from Keychain as a **`.p12` file with a strong password**. Keep that password — the SDK
needs it, and it is the only Apple Pay secret your app carries.

On your **App ID**, enable the *Apple Pay Payment Processing* capability and attach the Merchant
ID(s). Then **regenerate your provisioning profiles**, otherwise the entitlement is missing at
runtime and the sheet never opens.

## 2. Send the certificate to HiPay

Email the **`.p12` file and its password** to `support@hipay.com`.

The certificate is **yours**, not HiPay's: the gateway uses it to decrypt the payment tokens your app
produces. Nothing works until it is installed on your account.

## 3. HiPay account

In most cases your **existing HiPay account is enough** — the same credentials you already use for
card payments, and the same signature passphrase. Apple Pay does **not** add a passphrase.

Some accounts are set up with a **dedicated Apple Pay account**. Only then do you supply its
username, through `applePayUsername`. Your account manager will confirm whether yours needs one; if
you are unsure, leave it out and try — a missing dedicated account surfaces as an authentication
error, not as a silent wrong result.

## 4. Configure the SDK

```kotlin
val applePay = HiPayApplePayConfig(
    merchantIdentifier = "merchant.com.hipay.yourcompany",
    privateKeyPassword = "…",              // the .p12 export password from step 1
    merchantDisplayName = "Your Company",  // shown at the top of the Apple Pay sheet
    // Only when your account manager told you to use a dedicated Apple Pay account.
    applePayUsername = null,
    // Optional. Narrows what the account already accepts — never widens it.
    allowedNetworks = emptyList(),
)
```

`privateKeyPassword` is the password **you chose when exporting the `.p12`**. It is not a HiPay
credential and has nothing to do with your account passphrase — a common and costly confusion.

```swift
let applePay = HiPayApplePayConfiguration(
    merchantIdentifier: "merchant.com.hipay.yourcompany",
    privateKeyPassword: "…",              // the .p12 export password from step 1
    merchantDisplayName: "Your Company",  // shown on the Apple Pay sheet's total line
    applePayUsername: nil                 // only for a dedicated Apple Pay account
)
```

Ship the staging Merchant ID in your staging build and the production one in production: a
certificate mismatch is only visible at payment time.

**The entitlement holds a *list*.** `com.apple.developer.in-app-payments` is an array, so one build can
be entitled to several Merchant IDs and choose at runtime — but only among those it was signed for.
PassKit refuses any identifier absent from the entitlement, and the entitlement is fixed at build time.

## 5. Show the button — only when it can pay

```kotlin
HiPayApplePayButton(
    onTap = { /* start the payment */ },
    style = HiPayApplePayButtonStyle.AUTOMATIC,
    type = HiPayApplePayButtonType.BUY,
)
```

```swift
HiPayApplePayButton(style: .automatic, type: .buy) {
    // start the payment
}
```

**Do not decide availability on `canMakePayments()` alone.** Three conditions must hold: the device
can pay, your HiPay account is contracted for a network Apple Pay can route, and your optional
restriction leaves at least one of them. Ask the SDK:

```swift
let availability = try await HiPayApplePayPayment.availability(
    configuration: hipayConfiguration,
    currency: "EUR"
)
if availability.isAvailable { /* show the button */ }
```

```kotlin
// Compose Multiplatform
val eligibility = resolveHiPayApplePayAvailability(
    config = hipayConfig,
    currency = "EUR",
)
if (eligibility.state == ApplePayEligibilityState.AVAILABLE) { /* show the button */ }
```

When it is unavailable you also get a **reason**. Use it: a button that silently disappears is the
hardest Apple Pay problem to diagnose, for you and for your support team.

**Read the reason together with the network list.** `DEVICE_NO_USABLE_CARD` covers two different
causes, told apart by whether the resolved networks are empty:

| Networks | Meaning |
|---|---|
| empty | The **device** said no — `canMakePayments()` is false. Nothing to do with cards or your account. |
| non-empty | Your account routes **only those** networks and the Wallet holds no card on any of them. |

The second one catches people out: a Wallet full of sandbox Visa and Mastercard cards is still
unavailable if your account only routes **CB**. And note **Amex is never routable via Apple Pay at
HiPay** — it is excluded by the platform, not by your account.

A thrown error is a **third** case, and not the same as unavailable: it means the check itself failed
(bad credentials, gateway unreachable). Do not collapse it into "unavailable", or a configuration
mistake looks like a phone without a card.

## 6. Pay

**The SDK presents the sheet.** You do not touch PassKit: one call presents it, captures the
authorized token, tokenizes it, creates the order, resolves any authentication step-up and reports the
outcome.

```swift
let outcome = try await HiPayApplePayPayment.pay(
    configuration: hipayConfiguration,
    applePay: applePay,
    order: HiPayApplePayOrder(
        orderId: "ORDER-123",
        amount: "10.00",
        currency: "EUR",
        countryCode: "FR",         // your merchant country, required by PassKit
        description: "…",
        redirectScheme: "yourscheme",
        signature: signature       // computed by YOUR BACKEND, as for a card payment
    )
)
switch outcome {
case .completed:    break  // paid
case .declined:     break  // the wallet authorized, the gateway refused
case .pending:      break  // re-query the transaction to settle it
case .notCompleted: break
case .cancelled:    break  // the payer closed the sheet — NOT an error
}
```

```kotlin
// Compose Multiplatform
val result = runHiPayApplePayPayment(
    config = hipayConfig,
    applePayConfig = applePay,
    order = ApplePayOrder(
        orderId = "ORDER-123",
        amount = "10.00",
        currency = "EUR",
        countryCode = "FR",
        description = "…",
        redirectScheme = "yourscheme",
        signature = signature,
    ),
)
```

On Android `runHiPayApplePayPayment` throws `UnsupportedOperationException` — gate on
`hiPayApplePaySupported()`.

**Sign the order exactly as you sign a card order.** `ApplePayOrder.signature` is computed by your
backend; the SDK never computes one and your passphrase must never reach the app. If your account
requires signed orders, an unsigned wallet order is refused just like an unsigned card order.

**A closed sheet is not an error.** `cancelled` is the payer's normal way out; only invalid input and
an unreachable gateway raise. A second call while one is in flight fails immediately without
presenting a second sheet, so a double tap cannot create two orders.

**The order is submitted once and never retried.** If the outcome cannot be determined, the SDK says
so rather than resubmitting — a silent retry is the one way it could authorize twice. Reconcile on
the same `orderId`.

## Troubleshooting

| Symptom | Cause |
|---|---|
| The sheet never opens | Capability missing, or provisioning profiles not regenerated after enabling it |
| The button never appears | Eligibility is unavailable — read the reason AND the network list (see §5) |
| Unavailable with a Wallet full of cards | Your account routes only networks you hold no card for — often **CB** only. Amex never counts |
| Unavailable on the Simulator | `canMakePayments()` is false until a test card is added in Settings ▸ Wallet |
| `merchantIdentifier` rejected at sheet time | It is not in the app's entitlement, or the profile was not regenerated |
| Payment fails at tokenization | Wrong `.p12` password, or the certificate is not installed on your HiPay account |
| Works in staging, fails in production | Production Merchant ID and certificate not created, or the staging one shipped |

---

**Other integration paths:** [Android](android.md) · [iOS](ios.md) · [Compose Multiplatform](cmp.md)
**See also:** [Overview](../index.md) · [Changelog](../changelog.md) · [Report an issue](https://github.com/hipay/hipay-payments-sdk-kmp/issues)
