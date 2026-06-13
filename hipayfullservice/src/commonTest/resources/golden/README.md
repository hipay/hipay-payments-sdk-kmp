# Golden files — sanitization policy

These JSON files are reference captures of REAL HiPay stage traffic (Secure
Vault `token/create` and Gateway `order`), used by the parity harness to lock
the wire contract. They are **test fixtures committed to the repository** and
must contain **no real card data and no real PII**.

## What is in here (all synthetic / sanitized)

- **PAN**: the canonical industry test number `4111111111111111` only. The
  response `pan` is backend-masked — note the two faithful masking conventions
  are reproduced verbatim from the real API: the **vault** masks with `x`
  (`411111xxxxxx1111`), the **gateway** masks with `*` (`411111******1111`).
- **CVC**: a dummy value (`123`) on the request shape only — never a real CSC.
- **Token / card_id / authorizationCode / transactionReference / mid**:
  fabricated, format-preserving placeholders (`f0e1…`, zeroed ids, etc.).
- **Holder / email / customerId**: synthetic (`Test`, empty) — no real person.
- **Dates**: fixed `2026-06-12T…` stamps, not capture-time values.

Every key and every value *type* matches the real wire contract (e.g. expiry
month/year are STRINGS, `multi_use` is a number, `forbidden_issuer_country` is
a boolean, transaction keys are camelCase) — only the *values* are scrubbed.

## If you re-capture

When refreshing a golden from new stage traffic, scrub before committing:
replace `card_number`, `cvc`, every token/`card_id`, `transactionReference`,
`mid`, `authorizationCode`, dates, and any customer `email`/`customerId` with
synthetic values of the **same type and format**. Keep the masked-PAN
convention of whichever API produced the response. Then update the Kotlin
mirror (`commonTest/kotlin/com/hipay/golden/GoldenFiles.kt`) to match — a
gated guard test (`androidHostTest`) fails the build if the two diverge.

This is a documented control, not just convention: it backs the PCI posture
(no sensitive authentication data stored in source — PCI-DSS Req. 3.2).
