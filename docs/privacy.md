# Privacy and store declarations

What the SDK sends on your behalf, and what you have to declare when you publish. None of it is
configurable, and none of it changes your integration — but you will be asked about it, by your
security review, your DPO, or a submission form.

## What leaves the device

**On every card payment.** The card number, expiry date, holder name and CVV go to HiPay's Secure
Vault and come back as a token — once to resolve the card network while the payer types, once to
tokenize. Then the order itself: your order id, the amount, the currency, the description, the
language, the return URLs, and the device's User-Agent, which the platform uses to attribute the
transaction and score it against fraud.

**Only if you supply them.** Customer and shipping details — name, street address, city, postcode,
country, email, phone — plus your own customer id, the payer's IP address, and any custom data you
attach to the order. The SDK collects none of this on its own: it sends exactly what you pass it.

**On Apple Pay, no card data reaches your application at all.** Apple hands over a payload encrypted
for HiPay's certificate, which only HiPay can open.

**A saved card never leaves the device.** One-click stores a HiPay token, the masked number, the
network, the expiry and the holder name in the platform's own secure store — Keychain on iOS,
Keystore-backed storage on Android. Only the token is sent, at payment time.

The SDK writes no logs on the card path, and contacts HiPay endpoints only. The one exception is a
3D Secure challenge, which by definition opens your payer's bank page, in a browser rather than
inside your application.

## What you declare on the App Store

| Data type | When | Purpose | Linked to the user |
|---|---|---|---|
| Payment Info | card entry | App Functionality | yes, if you tie the order to an account |
| Purchases | always | App Functionality | same |
| Contact Info | only if you pass customer or shipping details | App Functionality | yes |
| Identifiers → User ID | only if you pass a customer id | App Functionality | yes |

**Used for tracking: no**, in every case. Nothing is shared with a data broker or an advertising
network.

Two points worth knowing. Apple exempts payment information *entered outside your app when you never
have access to it* — that covers **Apple Pay**, so an Apple-Pay-only integration declares no payment
info. It does **not** cover the card field, where the payer types inside your application, even
though your own code never sees the number.

## What you declare on the Play Store

| Data type | When |
|---|---|
| Financial info → Payment info | card entry |
| Financial info → Purchase history | always |
| Personal info → Name, Email address, Phone number, Address | only if you pass customer or shipping details |
| Personal info → User IDs | only if you pass a customer id |

Collected: **yes**. Shared: **no** — HiPay processes these data as your payment provider, which the
form does not count as sharing. Encrypted in transit: **yes**. Whether a user can request deletion
is your own answer, not ours.

Store taxonomies are revised regularly. The categories above are the right ones; find their current
labels on the form rather than copying these words.

## In your `PrivacyInfo.xcprivacy` (iOS)

Under `NSPrivacyAccessedAPITypes`: the saved-card store reads and writes one boolean in
`UserDefaults`, which Apple lists as a required-reason API. The reason to declare is **`CA92.1`** —
access to information from the app itself only, shared with nothing else. No card data is involved:
the boolean only tells a fresh install from a reinstall, since the Keychain survives an uninstall
and the saved cards have to be purged when the app comes back.

## Telemetry

The SDK reports which integration and which SDK version produced a payment, so HiPay knows which
releases are deployed in the field. Three events per payment journey, carrying the SDK's identity
and version, the platform and its OS version, the payment's status, the card network and country of
issue, the timing of each step, and a random id tying one journey's events together.

**Nothing in them identifies anyone** — no card data, no payer identity, no device identifier, no
order id, no transaction reference, no amount, not even your application's identifier. Declare it as
usage data collected for analytics, **not linked to the user** and **not used for tracking**.

Each call is best effort: a short deadline, no retry, the response ignored, every failure silent. It
runs after the step it reports and can neither delay a payment nor cause one to fail.
