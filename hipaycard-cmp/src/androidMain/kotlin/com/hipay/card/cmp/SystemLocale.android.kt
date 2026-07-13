package com.hipay.card.cmp

import android.os.LocaleList
import java.util.Locale

// On Android the published component delegates to the native :hipaycard composable (which
// localizes via resources + LocalConfiguration); this actual serves the shared composable
// when it is composed on Android directly (e.g. host-side tests). LocaleList mirrors what
// resource resolution walks; it does not see per-context overrides (createConfigurationContext)
// — a known limitation of this unpublished direct path.
internal actual fun systemLocaleLanguages(): List<String> =
    try {
        val locales = LocaleList.getDefault()
        (0 until locales.size()).map { locales.get(it).toLanguageTag() }
    } catch (_: RuntimeException) {
        // android.os.LocaleList is an unimplemented stub off-device (JVM unit tests):
        // degrade to the single JVM default locale.
        listOf(Locale.getDefault().toLanguageTag())
    }
