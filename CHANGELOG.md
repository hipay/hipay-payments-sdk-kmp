# Changelog — HiPay Fullservice KMP SDK

Pre-1.0: the API may still move; per SemVer pre-1.0 a **minor** bump (0.1.0 → 0.2.0) can carry
breaking changes. `version` is the single source of truth in `gradle.properties` (inherited by every
module + the iOS SPM/xcframework).

## 0.2.0 — SDK-managed 3DS

### ⚠️ BREAKING CHANGES (vs the tagged 0.1.0)

**`pay(...)` now presents the 3DS challenge itself and returns the FINAL, server-confirmed
transaction.** In 0.1.0 `pay()` returned a `FORWARDING` transaction and the host opened `forwardUrl`,
caught the redirect, and called `getTransaction`. From 0.2.0 the SDK does all of that — existing
`pay()` call sites change behaviour.

- **iOS `HiPayCardEntryController.pay`**: new `threeDS: HiPayThreeDSMode = .inAppSession`
  (`.inAppSession` = in-app `ASWebAuthenticationSession`; `.externalBrowser` = external Safari).
- **Android `:hipaycard` `HiPayCardEntryController.pay`**: new `autoPresent3DS: Boolean = true`
  (Chrome Custom Tabs). New transitive dep **`androidx.browser`**. Host must add the redirect
  `intent-filter` (`VIEW`+`BROWSABLE`, scheme + host `hipay-fullservice`) and `launchMode="singleTop"`.
- **CMP `hipaycard-cmp` `HiPayCardController.pay`**: new `threeDS: HiPayThreeDSMode = IN_APP_SESSION`
  (enum `HiPayThreeDSMode { IN_APP_SESSION, EXTERNAL_BROWSER }`).
- **New `resume3DS(url)`** — the single host touch-point for browser returns (iOS `.externalBrowser`
  + all Android/CMP), called from `.onOpenURL` / `onNewIntent`. iOS in-app needs no wiring.

### Added (non-breaking)

- **`isProcessing`** (read-only, observable) on every controller: the card-entry view/component
  locks its own fields while `pay()` is in flight (replaces the short-lived, never-tagged `enabled`
  param). Mirror it on your Pay button (`!canPay || isProcessing`).
- **Abort/return reconciliation** (FR9): on any non-callback return (in-app dismiss, Custom Tab
  close, external-Safari back — every mode, every platform), the SDK queries `getTransaction` and
  returns the authoritative state — never a false "aborted" when the payment was actually captured.
  If the server is unreachable during reconciliation, it returns an indeterminate `PENDING` snapshot
  (`Transaction.verificationPending`, "verification required") instead of a false abort or a thrown
  error — re-query `getTransaction` to resolve.
- iOS `HiPayFullservice.xcframework` regenerated from the current KMP core (incl. the co-brand-aware
  CVC / per-network length / formatting refinements).

### Behavioral notes (not source-breaking)

- **No public symbol removed or renamed** vs 0.1.0 — only additions; the break is the changed
  default behaviour of `pay()`. Adding `threeDS`/`autoPresent3DS` is source-compatible (defaulted).
- Card fields self-lock during `pay()` (`isProcessing`) — UX difference, no code change needed.
- **Android/CMP only:** read-only `controller.expiry` now exposes raw `MMYY` (was `"MM/YY"`; story
  11.8, post-0.1.0). Low impact. iOS does not expose `expiry` publicly.

### Unchanged

- Headless core (`GatewayClient`/`CardTokenizer`) still returns `forwardUrl` as data (FR9) — the
  manual path is fully preserved. HS auth (backend-computed signature), PCI boundary, Apache-2.0.

### Migration

- Remove your `forwardUrl` open + manual `getTransaction`; `pay()` returns the final tx.
- Wire `resume3DS(url)` for browser returns (iOS `.externalBrowser` + all Android/CMP).
- Android: add the `intent-filter` + `singleTop` (`androidx.browser` comes transitively).
- Drop any `isPaying` flag — fields self-lock via `isProcessing`.
- Need full manual control? iOS: use the headless core. Android: `pay(autoPresent3DS = false)`.

## 0.1.0 — Initial developer preview (tagged)

Headless core + native card UI (iOS SwiftUI / Android Compose) + shared CMP card UI. `pay()`
returned `FORWARDING` for host-driven 3DS.
