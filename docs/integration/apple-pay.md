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

Ship the staging Merchant ID in your staging build and the production one in production: a
certificate mismatch is only visible at payment time.

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

```kotlin
val eligibility = resolveApplePayEligibility(
    config = hipayConfig,
    device = deviceCapability,
    currency = "EUR",
    allowedNetworks = emptyList(),
)
if (eligibility.state == ApplePayEligibilityState.AVAILABLE) { /* show the button */ }
```

When it is unavailable you also get a **reason** — the device has no usable card, no routable
network, and so on. Use it: a button that silently disappears is the hardest Apple Pay problem to
diagnose, for you and for your support team.

## 6. Pay

The sheet returns an authorized payment token. Hand its `paymentData` to the SDK, which tokenizes it
and creates the order:

```kotlin
val transaction = WalletCoordinator(hipayConfig).pay(
    paymentData = paymentData,          // PKPayment.token.paymentData, as a JSON string
    applePayConfig = applePay,
    order = ApplePayOrder(
        orderId = "ORDER-123",
        amount = "10.00",
        currency = "EUR",
        description = "…",
        redirectScheme = "yourscheme",
    ),
)
```

**The order is submitted once and never retried.** If the outcome cannot be determined, the SDK says
so rather than resubmitting — a silent retry is the one way it could authorize twice. Reconcile on
the same `orderId`.

## Troubleshooting

| Symptom | Cause |
|---|---|
| The sheet never opens | Capability missing, or provisioning profiles not regenerated after enabling it |
| The button never appears | Eligibility is unavailable — read the reason instead of guessing |
| Payment fails at tokenization | Wrong `.p12` password, or the certificate is not installed on your HiPay account |
| Works in staging, fails in production | Production Merchant ID and certificate not created, or the staging one shipped |

---

**Other integration paths:** [Android](android.md) · [iOS](ios.md) · [Compose Multiplatform](cmp.md)
**See also:** [Overview](../index.md) · [Changelog](../changelog.md) · [Report an issue](https://github.com/hipay/hipay-payments-sdk-kmp/issues)
