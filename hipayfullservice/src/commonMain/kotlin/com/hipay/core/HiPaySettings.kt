package com.hipay.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Optional, SDK-wide settings shared by every HiPay component built from the same [HiPayConfig].
 *
 * Not a global/singleton (architecture D7): build ONE instance, put it on your [HiPayConfig], and
 * the components created from that config share it — so cross-cutting preferences like the display
 * language are set in one place instead of at every call site.
 *
 * The language is **observable and mutable at runtime**: change [setLocaleOverride] and every card
 * sharing this instance re-localizes immediately, with no config rebuild and no component re-init.
 * The core is Compose-free, so the observable is a [StateFlow] (read on Compose via
 * `collectAsState()`); the iOS-native facade mirrors it with an `ObservableObject`.
 *
 * v1 carries only the locale; the type is intended to grow with future cross-cutting settings.
 *
 * @since 0.3.0
 */
public class HiPaySettings(localeOverride: String? = null) {

    private val _localeOverride = MutableStateFlow(normalizeLanguage(localeOverride))
    private val listeners = mutableListOf<(String?) -> Unit>()

    /**
     * Forced UI language as a lowercased ISO-639 code (e.g. `"fr"`), or `null` to follow the device
     * locale. Observe it to re-localize live (Compose reads it via `collectAsState()`). A
     * per-component `localeOverride` still takes precedence. @since 0.3.0
     */
    public val localeOverride: StateFlow<String?> = _localeOverride.asStateFlow()

    /** The current forced language (or `null`). A plain getter for hosts that observe via
     *  [addLocaleListener] rather than the flow (e.g. the iOS-native facade). @since 0.3.0 */
    public val localeOverrideValue: String? get() = _localeOverride.value

    /**
     * Set or clear the forced UI language at runtime. Case-insensitive and region-tolerant
     * (`"FR"` / `"fr-FR"` → `"fr"`); `null` or blank clears it (follow the device locale).
     * Call on the main thread. @since 0.3.0
     */
    public fun setLocaleOverride(tag: String?) {
        val normalized = normalizeLanguage(tag)
        _localeOverride.value = normalized
        listeners.toList().forEach { it(normalized) }
    }

    /**
     * Register a change listener and get back a cancel handle (invoke it to unsubscribe). Used by
     * hosts that cannot collect the [localeOverride] flow directly — e.g. the iOS-native facade,
     * which bridges this to a SwiftUI re-render. @since 0.3.0
     */
    public fun addLocaleListener(listener: (String?) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }
}
