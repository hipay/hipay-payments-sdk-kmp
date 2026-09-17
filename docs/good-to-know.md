# Good to know

Things the SDK does on its own, that you do not configure but may be asked about — by your security
review, your DPO, or a store submission form.

## Card data stays where it belongs

The card number, the CVV and the holder name never leave the card component except as a HiPay token,
and this SDK writes no logs on that path at all. A card saved for one-click is stored in the
platform's own secure store — Keychain on iOS, Keystore-backed storage on Android — and never leaves
the device.

The SDK contacts HiPay endpoints only. The one exception is a 3D Secure challenge, which by
definition opens your payer's bank page, in a browser rather than inside your application.

## Telemetry

The SDK reports which integration and which SDK version produced a payment, so HiPay knows which
releases are deployed in the field. Nothing to configure, nothing to call, no change to your
integration.

Three events per payment journey — the payment surface appearing, the card being tokenized, the
order being answered — carrying the SDK's identity and version, the platform and its OS version, the
payment's status, the card network and country of issue, the timing of each step, and a random id
tying one journey's events together.

**Nothing in them identifies anyone**: no card data, no payer identity, no device identifier, no
order id, no transaction reference, no amount, and not even your application's identifier. It is not
a collection of personal data, so it adds nothing to your own GDPR obligations.

Each call is best effort: a short deadline, no retry, the response ignored, every failure silent. It
runs after the step it reports and can neither delay a payment nor cause one to fail.

## What you declare, and where

Two points, both about what the SDK does inside your application. They go to two different places.

**In your App Privacy questionnaire** (App Store Connect), and in the Play Console *Data safety*
form: the telemetry above. These forms cover what leaves the device, whether or not it identifies a
user. Declare it as usage data collected for analytics, **not linked to the user** and **not used
for tracking**.

**In your application's `PrivacyInfo.xcprivacy`**, under `NSPrivacyAccessedAPITypes`: the saved-card
store reads and writes one boolean in `UserDefaults`, which Apple lists as a required-reason API.
The reason to declare is **`CA92.1`** — access to information from the app itself only, nothing
shared with anything else. No card data is involved: saved cards are HiPay tokens held in the
Keychain, and the boolean only tells a fresh install from a reinstall, since the Keychain survives
an uninstall and the cards have to be purged when the app comes back.
