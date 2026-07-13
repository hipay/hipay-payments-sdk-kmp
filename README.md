# HiPay Fullservice KMP SDK

Kotlin Multiplatform SDK for HiPay Fullservice card payments — successor to the
legacy native iOS/Android Fullservice SDKs. Single Kotlin codebase
(`:hipayfullservice`, coordinates `com.hipay.fullservice:fullservice-kmp`),
consumed on iOS through a local Swift package.

## Layout

- `hipayfullservice/` — the KMP library
  - `com.hipay.core` — configuration, HTTP/auth, Gateway (orders,
    transactions), 3DS callback parsing
  - `com.hipay.card` — card validation, network rules, Secure Vault
    tokenization (PCI boundary: card data never leaves this module)
- `swift/` — local SPM package (`HiPayCore` / `HiPayCard` products): the
  hand-written Swift facade that IS the public iOS API, backed by the
  `HiPayFullservice` XCFramework (git-ignored build artifact)
- `scripts/build-xcframework.sh` — rebuilds the XCFramework and refreshes the
  package (see its header for the edit-Kotlin → run-demo loop)
- Demo app: separate repo `../HiPay-SDK-ios-Demo`

Co-branding (CB/BCMC): since 0.3.0 all three card components — Android
(`:hipaycard`), iOS (`HiPayCard`) and Compose Multiplatform (`:hipaycard-cmp`)
— resolve the offered network set through the backend, so a co-branded card
offers both networks with the domestic one default-selected.

i18n (fr/en/it): since 0.3.0 all three card components follow the device
locale, or the `localeOverride` parameter to force a language; English is the
fallback for unsupported languages.

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
