# HiPay Fullservice KMP SDK

**Integration guides:** [`docs/`](docs/index.md) — Android, iOS and Kotlin/Compose Multiplatform, plus the changelog. Published as a versioned site so you can read the documentation of the version you depend on.

Kotlin Multiplatform SDK for HiPay Fullservice card payments — successor to the
legacy native iOS/Android Fullservice SDKs. Single Kotlin codebase
(`:hipayfullservice`, coordinates `com.hipay.payments:core`),
consumed on iOS through a local Swift package.

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

Co-branding (CB/BCMC): since 0.3.0 all three card components — Android
(`:hipaycard`), iOS (`HiPayCard`) and Compose Multiplatform (`:hipaycard-cmp`)
— resolve the offered network set through the backend, so a co-branded card
offers both networks with the domestic one default-selected.

i18n (fr/en/it): since 0.3.0 all three card components follow the device
locale, or the `localeOverride` parameter to force a language; English is the
fallback for unsupported languages.

Styling: since 0.3.0 the card component accepts an optional
`HiPayCardEntryStyle` (shared platform-neutral contract: colors, typography,
field metrics) via the `style` parameter — rendered on CMP-iOS, on iOS-native
(`HiPayCardTheme(style:)` bridges it to SwiftUI), on Android-native, and on
CMP-Android (which delegates to the native Android component). The default
look is light-mode: dark-mode hosts should pass a
dark-adapted style until dedicated dark-theme support ships.

## Customizing the card component

### Styling — `HiPayCardEntryStyle` (since 0.3.0)

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

`HiPayCardTheme.default` is deprecated → use `.hipayDefault`. The custom
placeholder color applies from iOS 17 (iOS 15/16 keep the system gray). The
default baseline is light-mode — pass a dark-adapted style for dark hosts.

### Localization — `HiPaySettings` / `localeOverride` (since 0.3.0)

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

### One-click / saved cards — 🚧 experimental (WIP, opt-in)

**Off by default and not production-ready in 0.3.0** — the API/UX may still
change, and the consent/legal copy and the out-of-checkout delete API are not
final. To try it, enable it on the controller; card tokens are held in platform
secure storage (Android Keystore + DataStore, iOS Keychain) and the PAN/CVV are
never stored:

```kotlin
val controller = HiPayCardEntryController(config, oneClickEnabled = true)
controller.pay(/* … */, saveCard = true)     // offer to save on success (with consent)
controller.payWithSavedCard(/* … */)          // pay from a stored token
// list / manage: controller.savedCards, selectSavedCard(…), deleteSavedCard(…)
```

With `oneClickEnabled = false` (the default) no card store is created and
behavior is unchanged.

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
- One-click saved cards (Android): the card-token blob is AES/GCM-encrypted
  with a non-exportable Android Keystore key and stored in the SDK's DataStore
  file (`hipay_saved_cards`). A backup or device transfer cannot carry the key,
  so a restored blob is undecryptable and purged on first read. Recommended
  hardening: exclude `datastore/hipay_saved_cards.preferences_pb` from backup
  in your app's `dataExtractionRules` / `fullBackupContent`.

## Publication

Not published yet. Before the first release: fix POM license/developers/scm
placeholders, adopt a real versioning scheme, and gate
`.github/workflows/publish.yml` (see the deferred-work register in the
planning workspace).
