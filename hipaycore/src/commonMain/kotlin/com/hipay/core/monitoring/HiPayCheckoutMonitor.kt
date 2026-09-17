package com.hipay.core.monitoring

import com.hipay.core.HiPayConfig

/**
 * Raises the `init` event of the checkout funnel. Held by the SDK's own payment surfaces, never by an
 * integrator: it reports no value the host supplies and takes no argument.
 *
 * Public only because Kotlin `internal` does not cross module boundaries — the card surfaces live in
 * their own modules, and the SwiftUI one outside Kotlin entirely.
 */
@HiPayInternalApi
public class HiPayCheckoutMonitor internal constructor(private val sender: CheckoutDataSender) {

    public constructor(config: HiPayConfig) : this(CheckoutDataSender(config.environment))

    /** A payment surface is being created: opens the session and stamps `date_init`. */
    public fun paymentSurfaceCreated() {
        CheckoutSession.start()
    }

    /**
     * The surface is on screen: raises `init`, carrying the creation-to-display delay. Only the first
     * render of a session reports, so a component that recomposes — or an Android
     * Compose-Multiplatform surface delegating to the native one — raises it once.
     */
    public fun paymentSurfaceDisplayed() {
        val session = CheckoutSession.current()
        if (session.displayReported) return
        session.displayReported = true
        sender.send(
            CheckoutData(
                event = CheckoutEvent.INIT,
                id = session.id,
                monitoring = Monitoring(dateInit = session.dateInit, dateDisplay = utcTimestamp()),
            ),
        )
    }
}

/**
 * The wallet surface has no controller to hold a monitor, so it reports from the eligibility call —
 * the moment that decides whether a button can be shown. The guard runs before the sender is built,
 * so a surface that is already reported allocates nothing.
 */
internal fun reportPaymentSurfaceDisplayed(config: HiPayConfig) {
    if (CheckoutSession.current().displayReported) return
    @OptIn(HiPayInternalApi::class)
    HiPayCheckoutMonitor(config).paymentSurfaceDisplayed()
}
