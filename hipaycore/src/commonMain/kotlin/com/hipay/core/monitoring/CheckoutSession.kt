package com.hipay.core.monitoring

import kotlin.concurrent.Volatile

/**
 * The correlation state shared by the events of one checkout: without it `init`, `tokenize` and
 * `request` cannot be recognised as the same payer's journey, which is the whole point of the funnel.
 *
 * Process-global on purpose. The three events are raised by objects the host builds separately — the
 * card component, the tokenizer and the gateway client — and threading a telemetry token through
 * their constructors would put it in the public API, which the reported identity must never be.
 * A session starts when a card component is created and lasts until the next one is.
 */
internal object CheckoutSession {

    @Volatile
    private var current: State? = null

    /** Opens a session for a card component being created, replacing any previous one. */
    fun start(): State = State(dateInit = utcTimestamp()).also { current = it }

    /** The open session, or a fresh one for a host paying without the card component. */
    fun current(): State = current ?: start()

    fun resetForTest() {
        current = null
    }

    class State(val dateInit: String) {
        val id: String = checkoutEventId()

        /** The `init` event is raised once per session, whichever surface renders first. */
        @Volatile
        var displayReported: Boolean = false
    }
}
