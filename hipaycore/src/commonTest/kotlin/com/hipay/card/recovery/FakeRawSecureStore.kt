package com.hipay.card.recovery

import com.hipay.card.store.RawSecureStore

/** In-memory [RawSecureStore] fake — the whole store logic runs in commonTest with no platform. */
internal class FakeRawSecureStore(
    var blob: String? = null,
    private val failRead: Boolean = false,
    private val failWrite: Boolean = false,
) : RawSecureStore {
    override fun read(): String? {
        if (failRead) throw RuntimeException("storage unavailable")
        return blob
    }

    override fun write(value: String) {
        if (failWrite) throw RuntimeException("write failed")
        blob = value
    }

    override fun clear() {
        blob = null
    }
}
