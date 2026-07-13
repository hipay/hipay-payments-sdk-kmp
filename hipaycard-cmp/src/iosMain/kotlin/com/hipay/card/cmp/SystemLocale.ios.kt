package com.hipay.card.cmp

import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

// The user's ordered preferred languages (language-plus-region tags) — the same signal the
// native platforms' resource resolution walks, so the component follows the device preference
// even when the host app itself ships fewer localizations. NSBundle.preferredLocalizations
// would instead cap the component to the HOST app's declared localizations, diverging from
// the Android component's behaviour.
internal actual fun systemLocaleLanguages(): List<String> =
    NSLocale.preferredLanguages.mapNotNull { it as? String }
