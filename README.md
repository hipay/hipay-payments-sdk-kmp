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

## Publication

Not published yet. Before the first release: fix POM license/developers/scm
placeholders, adopt a real versioning scheme, and gate
`.github/workflows/publish.yml` (see the deferred-work register in the
planning workspace).
