# HiPay Payments Mobile SDK

Card payments for Android, iOS and Compose Multiplatform, from a single Kotlin codebase — successor
to the legacy native iOS/Android SDKs. Android and KMP ship through Maven Central
(`com.hipay.payments:core` / `:card` / `:card-cmp`); iOS ships through Swift Package Manager.

**Integration guides — start here:**

| Your app | Guide | What you depend on |
|---|---|---|
| Native **Android** (Jetpack Compose) | [Android](docs/integration/android.md) | `com.hipay.payments:card` |
| Native **iOS** (SwiftUI) | [iOS](docs/integration/ios.md) | SPM products `HiPayCard` / `HiPayCore` |
| **Kotlin / Compose Multiplatform** | [Compose Multiplatform](docs/integration/cmp.md) | `com.hipay.payments:card-cmp` |

Also: [documentation home](docs/index.md) · [changelog](CHANGELOG.md) ·
[contributing](CONTRIBUTING.md) · [report an issue](https://github.com/hipay/hipay-payments-sdk-kmp/issues)

The guides live next to the code, so the version you are reading always matches the version you
depend on. A rendered, versioned site is planned; until it is deployed, read them here.

## Layout

- `hipayfullservice/` — the KMP library
  - `com.hipay.core` — configuration, HTTP/auth, Gateway (orders,
    transactions), 3DS callback parsing
  - `com.hipay.card` — card validation, network rules, Secure Vault
    tokenization (PCI boundary: card data never leaves this module)
- `HiPay_Payments_SDK_iOS/` — local SPM package (`HiPayCore` / `HiPayCard` products): the
  hand-written Swift facade that IS the public iOS API, backed by the
  `HiPayFullservice` XCFramework (git-ignored build artifact)
- `scripts/build-xcframework.sh` — rebuilds the XCFramework and refreshes the
  package (see its header for the edit-Kotlin → run-demo loop)
- Demo app: separate repo `../HiPay_Payments_Demo_iOS`

Co-branding (CB/BCMC): all three card components — Android
(`:hipaycard`), iOS (`HiPayCard`) and Compose Multiplatform (`:hipaycard-cmp`)
— resolve the offered network set through the backend, so a co-branded card
offers both networks with the domestic one default-selected.

i18n (fr/en/it): all three card components follow the device
locale, or the `localeOverride` parameter to force a language; English is the
fallback for unsupported languages.

Styling: the card component accepts an optional
`HiPayCardEntryStyle` (shared platform-neutral contract: colors, typography,
field metrics) via the `style` parameter — rendered on CMP-iOS, on iOS-native
(`HiPayCardTheme(style:)` bridges it to SwiftUI), on Android-native, and on
CMP-Android (which delegates to the native Android component). The default
look is light-mode: dark-mode hosts should pass a
dark-adapted style until dedicated dark-theme support ships.

## Customizing the card component

### Styling — `HiPayCardEntryStyle`

A shared, platform-neutral contract: ARGB `Long` colors (`0xAARRGGBB`), `Float`
metrics, and font enums (`fontFamily` is reserved — system font only in this
release). Values are validated at construction: an out-of-range color/metric
throws `IllegalArgumentException` rather than rendering wrong. Omit `style` for
`HiPayCardEntryStyle.hipayDefault`.

Android / Compose Multiplatform:

```kotlin
val style = HiPayCardEntryStyle(
    textColor = 0xFF1A1A1A,
    placeholderColor = 0xFF9E9E9E,
    iconColor = 0xFF6200EE,
    borderColor = 0xFFBDBDBD,
    borderWidth = 1f,
    cornerRadius = 12f,
    backgroundColor = 0xFFFFFFFF,
    fieldHeight = 42f,        // a MINIMUM (heightIn); grows under large font scales
)
HiPayCardEntry(controller = controller, style = style)
```

iOS (SwiftUI) — start from `hipayDefault` and override per property (Kotlin
default arguments aren't exported to Swift; the theme setters enforce the same
fail-fast bounds):

```swift
var theme = HiPayCardTheme.hipayDefault
theme.iconColor = UIColor(red: 0.38, green: 0, blue: 0.93, alpha: 1)
theme.cornerRadius = 12
HiPayCardEntryView(controller: controller, theme: theme)
```

The custom placeholder color applies from iOS 17 (iOS 15/16 keep the system gray). The
default baseline is light-mode — pass a dark-adapted style for dark hosts.

### Localization — `HiPaySettings` / `localeOverride`

By default every component follows the device locale (fr/en/it; English is the
fallback) and re-localizes automatically when the app language changes — no
re-init. To **force** a language, set it once on `HiPaySettings` (attached to
your config); it is observable, so changing it at runtime re-localizes every
card live. Matching is case-insensitive and region-tolerant (`"FR"`/`"fr-FR"` →
`"fr"`).

```kotlin
val settings = HiPaySettings()                       // Android / CMP
val config = HiPayConfig(user, pass, env, settings = settings)
// later, anywhere — the cards update live:
settings.setLocaleOverride("fr")                     // or null to follow the device
```

```swift
let settings = HiPaySettings(localeOverride: nil)    // iOS — the same shared type
let config = HiPayConfiguration(username: user, password: pass,
                                environment: .stage, settings: settings)
settings.setLocaleOverride(Locale(identifier: "fr")) // Locale convenience; live, no re-init
```

Resolution precedence: the per-component override → `HiPaySettings` → device.
A per-component override still wins for one-off cases:

- Android / CMP: `HiPayCardEntry(controller, localeOverride = "fr")`
- iOS-native: `HiPayCardStrings.localeOverride = Locale(identifier: "fr")`

## Build & test

```sh
./gradlew build                      # KMP library + Android AAR + all tests
./scripts/build-xcframework.sh       # iOS binary for the SPM package
```

Targets: `android`, `iosArm64`, `iosSimulatorArm64` (Apple Silicon only — no
x86_64 slice, architecture decision D6).

The test suite runs on both platforms (`iosSimulatorArm64Test`,
`testAndroidHostTest`) and includes an anti-logging gate over the card module
(`scripts/check-no-logging.sh`, wired into `check`). Tests gated on real stage
credentials skip silently when `.hipay_stage_env` is absent.

## Security model (v1)

- The library **never computes HS signatures** — the secret passphrase must
  stay on the merchant backend.
- Card data (PAN/CVC) is confined to the card module, never logged, and
  cleared after tokenization; `HiPayException` messages are SDK-synthesized
  (no backend text echo).

## Distribution

One version number covers both channels.

| Channel | Artifacts |
|---|---|
| Maven Central | `com.hipay.payments:core`, `:card`, `:card-cmp` |
| Swift Package Manager | products `HiPayCore` and `HiPayCard`, from `hipay-payments-sdk-ios` |

Every published artifact is GPG-signed; the SPM binary is pinned by checksum. See the
[integration guides](docs/index.md) to add it to a project.
