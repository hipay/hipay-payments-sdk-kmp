# Accessibility

What the card component already does for screen-reader, motor and low-vision users — and the few
things your host can break without noticing. Everything here is implemented identically on the three
components (Android `:hipaycard`, Compose Multiplatform `:hipaycard-cmp`, iOS `HiPayCard`); where a
platform differs, it is called out.

You do not configure any of this. There is one opt-out (`setsAccessibilityOrder`) and one opt-in
(`confirmCardDeletion`), both described below.

## What you get

**Every control has a localized accessible name.** Field labels, placeholders, error messages, the
network brand names and the delete action all resolve from the same FR/EN/IT catalogs as the visible
text, so a screen reader speaks the payer's language and not an English fallback. The brand names are
proper nouns and are deliberately not translated.

**Errors are announced, not just coloured.** An inline error appears as an icon plus text — never
colour alone — and only once the field has lost focus, so the payer is not interrupted mid-typing. It
is announced through a polite live region, which speaks without stealing focus from the field being
corrected.

**Reading order follows the form, not the view tree.** The component sets the *relative* traversal
order of its own fields (holder → number → expiry → CVV), with each error grouped after the field it
belongs to. It sets only relative priorities, so your surrounding content keeps its own order.

**Touch targets meet the platform minimum**: 48dp on Android and Compose Multiplatform, 44pt on iOS,
including the network chips, the CVV help affordance, the "Show more" control and the delete action.

**Motion respects the system setting.** When the payer has asked for reduced motion (WCAG 2.3.3), the
expand/collapse of the saved-card list, the reveal of the delete action and the field transitions all
drop to instant instead of animating. Nothing is removed — only the animation.

**The detected card network is announced** as soon as it is known, and the co-brand chips are exposed
as buttons carrying a selected state, so a payer using a screen reader can hear which network the
payment will be routed on and change it.

## Saved cards, and the one path that needs your attention

A saved card is a button announcing its brand, its last four digits and its expiry, plus a selected
state. Deleting one is where the platforms and the assistive path deliberately diverge:

- **By gesture** — a left-swipe or a long-press reveals a trash affordance; tapping it deletes, and
  swiping the row back cancels. Two deliberate steps, so no confirmation dialog is shown by default.
- **By screen reader** — each card exposes a **"Delete card" custom action**, because neither the
  swipe nor the long-press is discoverable without sight. That action is a *single* step: there is no
  trash to aim at and no reverse swipe to undo it. It therefore **always shows the confirmation
  dialog**, whatever `confirmCardDeletion` is set to.

That asymmetry is intentional. If you turn the confirmation on with `confirmCardDeletion = true`, both
paths confirm; if you leave it off, only the assistive path does. There is no configuration that
removes the confirmation from the screen-reader path.

Deletion is also unreachable while a payment is in flight — on every path, including the custom
action, which is emptied rather than merely ignored so a screen reader does not announce an action
that would silently do nothing.

## What your host can break

**Wrapping the component in a single accessibility element.** Some hosts merge a whole section into
one node for tidiness. Doing that to the card component collapses every field, error and saved card
into a single unreadable label, and the custom delete action disappears with it.

**Overriding the traversal order.** If your screen needs to own the reading order end to end, pass
`setsAccessibilityOrder = false` — the component then emits neutral priorities and yields to yours.
Setting your own priorities *without* that flag makes the two orders compete, and the result is
platform-dependent.

**Styling that removes contrast.** `HiPayCardEntryStyle` lets you set text, placeholder, icon, border
and background colours. The SDK validates ranges, not contrast: a low-contrast pair is accepted and
will fail WCAG 1.4.3. The default palette is a light-mode baseline — pass a dark-adapted style for
dark hosts rather than relying on inversion.

**Not giving the component a scrollable host.** With one-click enabled the payer can reveal up to 20
saved cards, and the component never scrolls itself. Without a scroll container the controls below the
list become unreachable — which for a screen-reader user means reachable in the reading order but
impossible to activate.

## Verifying it yourself

Turn on VoiceOver (iOS) or TalkBack (Android) and check five things on your own screen: every field is
announced with its label; an error is spoken when you leave an invalid field; the reading order matches
the visual order; a saved card announces brand, last four and selected state; and the "Delete card"
action appears in the actions list and reaches a confirmation.

Then enable "Reduce Motion" and confirm the list expands instantly rather than animating.

The SDK's own suites cover the semantics (labels, states, custom actions, target sizes, live regions)
on all three platforms. What they cannot cover is screen-reader *activation* — a UI test taps, it does
not activate a VoiceOver element — so double-tap behaviour on a saved card is worth one manual check
after you embed the component.
