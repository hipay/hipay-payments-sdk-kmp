# HiPay Payments Mobile SDK

Card payments for Android, iOS and Compose Multiplatform, from a single Kotlin codebase —
successor to the legacy native iOS and Android SDKs. Pick your integration path below; on iOS a
Swift facade keeps the multiplatform types out of your code.

## Pick your integration

| You are building | Read | You depend on |
|---|---|---|
| A native **Android** app (Jetpack Compose) | [Android](integration/android.md) | `com.hipay.payments:card` |
| A native **iOS** app (SwiftUI) | [iOS](integration/ios.md) | SPM products `HiPayCard` / `HiPayCore` |
| A **Kotlin/Compose Multiplatform** app | [Compose Multiplatform](integration/cmp.md) | `com.hipay.payments:card-cmp` |

Headless integrations — no card UI, you drive tokenization and orders yourself — depend on
`com.hipay.payments:core` alone and are covered at the end of the KMP page.

## What the SDK gives you

A card-entry component with live validation, network detection and co-branding (CB/BCMC resolved
against the backend, the domestic network selected by default), FR/EN/IT localization following the
device locale, an optional visual style contract shared by the three surfaces, and turnkey 3DS.

The card data never leaves the SDK: your app receives a token, never a PAN, and nothing on the card
path is ever logged.

## Versions

One number covers everything — the Android artifacts, the iOS XCFramework and its SPM tag. Pin the
same version on every platform of a project.

These pages ship with the code, so the documentation you read is the documentation of the version
you depend on. Release candidates are internal and are not documented here; what changed between two
releases is in the [changelog](changelog.md), which covers every change shipped in between.

Deprecations are announced by the compiler, at your call site — `@Deprecated` in Kotlin,
`@available(*, deprecated:)` in Swift — with the replacement in the message. A deprecated API is kept
for at least one minor version before it is removed.
