# Changelog — HiPay Fullservice KMP SDK

Pre-1.0: the API may still move; per SemVer pre-1.0 a **minor** bump (0.1.0 → 0.2.0) can carry
breaking changes. `version` is the single source of truth in `gradle.properties` (inherited by every
module + the iOS SPM/xcframework).

## 0.3.0 — Styling

### Added

- **Card-field styling: `HiPayCardEntryStyle`** (`com.hipay.card.style`, core module — exported
  to Swift as `HiPayCardEntryStyle`). One shared, platform-neutral contract (ARGB `Long`
  colors, `Float` metrics, font enums, `fontFamily` reserved/null = system font) with
  `HiPayCardEntryStyle.hipayDefault` as the default look. New optional `style` parameter on the
  CMP and native-Android `HiPayCardEntry` — additive with a default, so existing call sites are
  source-compatible. Applied by the shared renderer on CMP-iOS, by the iOS-native SwiftUI
  component, and by the native Android Compose component in this release; CMP-Android now
  inherits the styled look by delegating to that native Android component. Style values are
  validated at construction
  (`IllegalArgumentException` on out-of-range colors/metrics) rather than rendered wrong;
  from Swift, constructing the Kotlin style with invalid values terminates the process
  (Kotlin initializer exceptions are not catchable from Swift) — prefer overriding on
  `HiPayCardTheme`, whose metric setters enforce the same bounds fail-fast.
  iOS-native: `HiPayCardEntryView`'s `theme` parameter is now a real appearance —
  `HiPayCardTheme(style:)` bridges the shared contract to SwiftUI (`.default` is deprecated
  in favor of `.hipayDefault`), and the theme's mutable properties give Swift per-property
  overrides (Kotlin default arguments are not exported, so from Swift start from
  `hipayDefault`). The custom placeholder color applies from iOS 17 (iOS 15/16 keep the
  system placeholder gray, which the default matches).
  Note: `hipayDefault` deliberately unifies small historical per-platform visual differences
  into one cross-platform baseline — no behavioural change, but expect visual normalization:
  corner radius and border/label colors are unified, the neutral card placeholder takes
  `iconColor` (brand-network chips keep their alpha dimming — brand marks are never
  re-tinted), and the text cursor follows `textColor` instead of the theme accent. The
  baseline is LIGHT-mode on every platform — iOS previously inherited adaptive system
  colors, so dark-mode hosts should pass a dark-adapted style until dedicated dark-theme
  support ships.

- **One-click payments / saved cards — 🚧 EXPERIMENTAL (work-in-progress, opt-in).** A returning
  payer can pay with a previously-saved card token without re-entering the PAN/CVV. **Off by
  default** — enable per component with `oneClickEnabled = true` on the controller; when off, no
  card store is created and nothing changes. Surface: `pay(…, saveCard = true)` offers to save on a
  successful payment (with in-component consent); `payWithSavedCard(…)` pays from a stored token;
  `savedCards` / `selectSavedCard` / `selectNewCard` / `deleteSavedCard` / `refreshSavedCards` drive
  the saved-card list; `saveCardOptIn`, `lastSaveOutcome` and `lastOneClickError` expose the state
  and outcome. Tokens are held in platform secure storage (Android Keystore + DataStore, iOS
  Keychain); the PAN/CVV are never stored. **Status: WIP for 0.3.0 — the API and UX may still
  change; the consent/legal copy, the out-of-checkout delete API, and the demos/docs/analytics are
  not final. Not recommended for production yet.** Additive only: every new parameter defaults to
  the off/false state, so existing integrations are unaffected.

### Fixed

- **iOS `HiPayCard`: CVV help dismissal.** The inline CVV explanation now closes when focus
  moves to another entry field or a saved card is selected — it could previously stay open
  (or re-appear unprompted after collapsing the entry fields). Focusing the CVV field itself
  keeps the help open.

- **CMP `hipaycard-cmp`: co-branding by backend network detection.** `CmpCardController` now
  resolves the network set through the backend (`resolveCardInfo`) once the entered number is
  complete and Luhn-valid — exactly like the native Android/iOS components — so a co-branded
  card (CB+Visa, CB+Mastercard…) offers both networks with the domestic CB/BCMC chip
  default-selected, as it always should have on CMP. Resolution failures degrade to the
  locally-detected network and never block entry. No integrator change required:
  `CmpCardController` gains an optional `scope` constructor parameter (defaulted) and
  `dispose()` now cancels the controller-owned scope.

- **CMP `hipaycard-cmp`: locale-aware strings (fr/en/it).** The shared card component now
  resolves its labels/placeholders/messages per locale — the device language, or the
  `localeOverride` parameter of `HiPayCardEntry`, which existed but was ignored on the iOS
  target (always English). Wording is copied verbatim from the validated native Android/iOS
  catalogs; English stays the baseline and the fallback for unsupported languages.
  **Behavioural change to be aware of:** an app running in French or Italian now shows the CMP
  card component in that language instead of English. No API change — `localeOverride` keeps
  its exact signature; pass `localeOverride = "en"` to keep the previous English-only rendering.

- **iOS-native `HiPayCard`: device-locale strings.** The native iOS card component now follows the
  device language (fr/en/it) too — it previously always rendered English because the SwiftUI
  resource bundle's localization was capped to the package's development region rather than the
  device locale. With this, **localized strings work across all targets** (iOS-native,
  native-Android, and CMP). English stays the fallback for unsupported languages;
  `HiPayCardStrings.localeOverride` still forces a specific language.

- **Card fields: uniform compact height.** The security-code (CVV) and card-number fields no longer
  render taller than the others. Their affordances — the CVV `ⓘ` help toggle and the
  network/co-brand chips — are now overlaid inside the field instead of occupying Material's
  trailing-icon slot, which had forced a 48dp floor. All entry fields now honor the styled
  `fieldHeight` (compact 42dp by default) uniformly on native-Android and CMP; the tap affordances
  keep a round 42dp target.

### Deprecated

- iOS `HiPayCardTheme.default` → use `HiPayCardTheme.hipayDefault` (renamed). `.default` still
  compiles, with a deprecation warning.

### Compatibility

- **No breaking API changes vs 0.2.0 — source-compatible.** Every 0.3.0 addition (the `style`
  parameter; one-click `oneClickEnabled` / `saveCard` / `payWithSavedCard` and the new saved-card
  types) is additive with defaults, and `.default` is only deprecated, not removed — code that
  compiled against 0.2.0 still compiles.
  - Minor source note (not an API break): the new `HiPayErrorCode.CARD_NO_LONGER_VALID` (Kotlin) /
    `HiPayError.cardNoLongerValid` (Swift) enum case can require a new branch in a Kotlin exhaustive
    `when` used as an expression (a Swift `switch` on the non-frozen enum only warns). No public
    symbol was removed, renamed, or re-typed.
  - Behavioural changes to expect (not API breaks): CMP now follows the device locale and shows
    co-branding; the default field look is unified to a light-mode baseline (see Added).

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
