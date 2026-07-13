package com.hipay.card.cmp

import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

// First user-preferred language (device-ordered, language-plus-region tag) — the same signal Android
// resource resolution follows, so a French device gets French even when the host app itself
// ships fewer localizations. NSBundle.preferredLocalizations would instead cap the component
// to the HOST app's declared localizations, diverging from the Android component's behaviour.
internal actual fun systemLocaleLanguage(): String =
    (NSLocale.preferredLanguages.firstOrNull() as? String) ?: "en"
