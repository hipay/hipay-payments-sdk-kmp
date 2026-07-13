package com.hipay.card.cmp

import java.util.Locale

// On Android the published component delegates to the native :hipaycard composable (which
// localizes via resources + LocalConfiguration); this actual serves the shared composable
// when it is composed on Android directly (e.g. host-side tests).
internal actual fun systemLocaleLanguage(): String = Locale.getDefault().toLanguageTag()
