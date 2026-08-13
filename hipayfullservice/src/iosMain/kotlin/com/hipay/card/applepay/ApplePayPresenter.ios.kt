// PCI: com.hipay.card path — NEVER log here (paymentData / token must never be logged).
@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.hipay.card.applepay

import com.hipay.card.validation.CardNetwork
import com.hipay.core.HiPayConfig
import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import com.hipay.core.gateway.model.Transaction
import com.hipay.core.gateway.model.TransactionState
import com.hipay.core.threeds.ThreeDSResolver
import com.hipay.core.threeds.defaultThreeDSLauncher
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.PassKit.PKPayment
import platform.PassKit.PKPaymentAuthorizationController
import platform.PassKit.PKPaymentAuthorizationControllerDelegateProtocol
import platform.PassKit.PKPaymentAuthorizationResult
import platform.PassKit.PKPaymentAuthorizationStatus
import platform.PassKit.PKPaymentAuthorizationStatus.PKPaymentAuthorizationStatusFailure
import platform.PassKit.PKPaymentAuthorizationStatus.PKPaymentAuthorizationStatusSuccess
import platform.darwin.NSObject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Presents the native Apple Pay sheet and runs the payment — ONE implementation both delivery
 * channels (Swift SPM + CMP) call, so sheet behaviour never diverges.
 *
 * Flow: narrow the selectable networks by the merchant restriction, validate the config and the order
 * inputs, build the `PKPaymentRequest` (store name, selectable networks, no contact fields), present
 * the sheet, on biometric authorization capture `paymentData`, run the [WalletCoordinator]
 * (tokenize → order → transaction), complete the sheet and report the outcome.
 *
 * Every outcome the gateway can produce comes back as an [ApplePayPaymentResult] — a closed sheet is
 * [ApplePayPaymentResult.Cancelled] and raises nothing, a refused authorization is
 * [ApplePayPaymentResult.Declined] carrying its transaction. A [HiPayException] is reserved for
 * invalid input and for a gateway the SDK could not reach at all.
 *
 * When the gateway asks for an authentication step-up the sheet is completed FIRST and the challenge is
 * presented after it dismisses: a web challenge cannot appear over the system sheet. Apple's own result
 * type has no "pending" case, so a step-up is completed as a success — the wallet did authorize; it is
 * the gateway that still wants a challenge. Only a decline, an error or a missed deadline complete the
 * sheet as a failure.
 *
 * While a payment is in flight a further call fails immediately without presenting a second sheet, so
 * a double tap cannot create two orders — the guarantee lives here rather than in each host.
 */
@Throws(HiPayException::class, CancellationException::class)
public suspend fun runApplePayPayment(
    config: HiPayConfig,
    applePayConfig: HiPayApplePayConfig,
    resolvedNetworks: List<CardNetwork>,
    order: ApplePayOrder,
): ApplePayPaymentResult = withContext(Dispatchers.Main) {
    // PassKit and UIKit are main-thread only: the controller is built, delegated, presented and
    // dismissed here, and the delegate callbacks arrive on this same thread.
    order.ensureValid()
    val sheet = applePaySheetRequest(
        config = applePayConfig,
        resolvedNetworks = applePayConfig.selectableNetworks(resolvedNetworks),
        amount = order.amount,
        currencyCode = order.currency,
        countryCode = order.countryCode,
    )
    val request = buildPaymentRequest(sheet)

    if (!paymentInFlight.tryBegin()) {
        throw HiPayException(
            code = HiPayErrorCode.VALIDATION,
            message = "An Apple Pay payment is already in progress",
        )
    }
    try {
        val coordinator = WalletCoordinator(config)
        val resolver = coordinator.threeDSResolver(applePayConfig, defaultThreeDSLauncher())
        // The authorization job must survive a cancellation of THIS coroutine: PassKit owns the sheet
        // and must be answered exactly once, so the job gets its own scope instead of being a child
        // that cancellation would kill mid-order, leaving the sheet spinning forever.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        // Bounded on purpose. Every delegate path resumes the continuation, but PassKit is an external
        // process: if a callback never arrives, an unbounded wait would suspend here forever, the
        // `finally` below would never run, and the in-flight guard — global to the process — would stay
        // acquired, refusing every later payment until the app is restarted. The ceiling turns that
        // permanent lock into one failed attempt: the timeout cancels this coroutine, which dismisses
        // the sheet through `invokeOnCancellation`, releases the guard, and reports an outcome the host
        // can reconcile. It is far above the authorization budget, so it never pre-empts a live flow.
        val outcome = withTimeoutOrNull(SHEET_LIFETIME_CEILING_MS) {
            suspendCancellableCoroutine { continuation ->
                val delegate = PaymentDelegate(coordinator, resolver, applePayConfig, order, scope, continuation)
                val controller = PKPaymentAuthorizationController(paymentRequest = request)
                controller.delegate = delegate
                delegate.retain(controller)
                // Registered before presenting: a cancellation racing the presentation must still dismiss.
                continuation.invokeOnCancellation { delegate.abandon() }
                controller.presentWithCompletion { presented ->
                    if (!presented) delegate.failToPresent()
                }
            }
        }
        outcome ?: throw HiPayException(
            code = HiPayErrorCode.SERVER,
            message = "Apple Pay did not answer in time; the outcome is unknown — reconcile on the order id",
        )
    } finally {
        // Released on every exit — completion, decline, cancel or failure — so the component is
        // immediately usable for a new payment.
        paymentInFlight.end()
    }
}

/** One payment at a time, per process — the sheet is a single system-modal surface anyway. */
private val paymentInFlight = PaymentInFlightGuard()

/**
 * How long the tokenize → order chain may take before the sheet is completed without a verdict.
 *
 * PassKit expects its authorization handler within roughly half a minute and dismisses the sheet on its
 * own if it waits longer — at which point our answer arrives too late and the outcome can no longer be
 * reported at all. A budget below that leaves the SDK in control: it reports an indeterminate
 * [ApplePayPaymentResult.Pending] the host can reconcile, instead of losing the payment silently. A
 * stage order was measured at about twelve seconds, so this leaves ample headroom.
 */
private const val WALLET_PAYMENT_DEADLINE_MS = 25_000L

/**
 * Hard ceiling on the whole sheet interaction, guarding against a PassKit callback that never arrives.
 *
 * It is not a business timeout: the payer may legitimately sit on the sheet for a while, and the
 * authorization chain has its own [WALLET_PAYMENT_DEADLINE_MS] budget. This only stops a lost callback
 * from leaving the process-wide in-flight guard acquired forever, which would refuse every subsequent
 * payment until the app restarts.
 */
private const val SHEET_LIFETIME_CEILING_MS = 180_000L

/**
 * `PKPaymentAuthorizationControllerDelegate` bridged to the suspended [continuation]. The controller
 * holds its delegate WEAKLY, so the delegate keeps a strong self-reference (via [retain]) until the
 * flow ends — released on EVERY exit path (finish, presentation failure, cancellation), never only on
 * `didFinish`, or the delegate, the controller and the config (which holds the `.p12` password) would
 * be retained for the life of the process.
 */
private class PaymentDelegate(
    private val coordinator: WalletCoordinator,
    private val resolver: ThreeDSResolver,
    private val applePayConfig: HiPayApplePayConfig,
    private val order: ApplePayOrder,
    private val scope: CoroutineScope,
    private val continuation: CancellableContinuation<ApplePayPaymentResult>,
) : NSObject(), PKPaymentAuthorizationControllerDelegateProtocol {

    private var selfRef: PaymentDelegate? = null
    private var controller: PKPaymentAuthorizationController? = null
    private var outcome: ApplePayPaymentResult? = null
    private var stepUp: Transaction? = null
    private var failure: Throwable? = null
    private var authorizing = false

    fun retain(controller: PKPaymentAuthorizationController) {
        this.controller = controller
        this.selfRef = this
    }

    /** The sheet never appeared: there is nothing to dismiss and no handler to answer. */
    fun failToPresent() {
        resumeAndRelease(
            HiPayException(
                code = HiPayErrorCode.CLIENT,
                message = "Apple Pay sheet could not be presented",
            ),
        )
    }

    /** The awaiting coroutine was cancelled: dismiss the sheet and drop our references. Note that a
     *  programmatic dismissal does NOT call back into `didFinish`, so the release happens here. */
    fun abandon() {
        controller?.dismissWithCompletion(null)
        release()
    }

    override fun paymentAuthorizationController(
        controller: PKPaymentAuthorizationController,
        didAuthorizePayment: PKPayment,
        handler: (PKPaymentAuthorizationResult?) -> Unit,
    ) {
        // PassKit keeps the sheet usable after a failure result, so a second authorization can
        // arrive: answer it, but never start a second order for the same order id.
        if (authorizing || outcome != null || stepUp != null) {
            handler(result(PKPaymentAuthorizationStatusFailure))
            return
        }
        authorizing = true
        // The opaque wallet payload, as its UTF-8 JSON string (never logged).
        val paymentData = NSString.create(
            data = didAuthorizePayment.token.paymentData,
            encoding = NSUTF8StringEncoding,
        ) as String?
        scope.launch {
            var status = PKPaymentAuthorizationStatusFailure
            try {
                if (paymentData.isNullOrEmpty()) {
                    // Sending an empty token would only earn an opaque backend error.
                    throw HiPayException(
                        code = HiPayErrorCode.CLIENT,
                        message = "Apple Pay returned an unreadable payment token",
                    )
                }
                val tx = withTimeout(WALLET_PAYMENT_DEADLINE_MS) {
                    coordinator.pay(
                        paymentData = paymentData,
                        applePayConfig = applePayConfig,
                        order = order,
                    )
                }
                when {
                    tx.state == TransactionState.COMPLETED -> {
                        outcome = ApplePayPaymentResult.Completed(tx)
                        status = PKPaymentAuthorizationStatusSuccess
                    }
                    // The gateway wants an authentication challenge. It cannot be presented over the
                    // sheet, so complete the sheet now and resolve it once the sheet has dismissed.
                    resolver.willPresentChallenge(tx) -> {
                        stepUp = tx
                        status = PKPaymentAuthorizationStatusSuccess
                    }
                    // Declined, errored, or gateway-reported pending: the sheet must show a failure,
                    // not a checkmark — but this is a result, not an exception.
                    else -> outcome = tx.toApplePayPaymentResult(order.orderId)
                }
            } catch (_: TimeoutCancellationException) {
                // Past the deadline the order may well have been created: reporting a failure would be
                // a lie, so the outcome is explicitly indeterminate. No reference is available (the
                // order call never answered), so the outcome carries the order id the host supplied —
                // its only handle on the payment.
                outcome = indeterminate()
            } catch (e: CancellationException) {
                failure = e
                throw e
            } catch (e: Throwable) {
                // Nothing but a HiPayException may cross the Kotlin/Native boundary — an undeclared
                // exception out of an exported suspend function terminates the host app.
                failure = e as? HiPayException ?: HiPayException(
                    code = HiPayErrorCode.CLIENT,
                    message = "Apple Pay payment failed",
                    cause = e,
                )
            } finally {
                // PassKit must get exactly one answer, cancellation included, or the sheet never
                // stops spinning and `didFinish` never arrives.
                handler(result(status))
                authorizing = false
            }
        }
    }

    override fun paymentAuthorizationControllerDidFinish(controller: PKPaymentAuthorizationController) {
        val challenged = stepUp
        if (challenged == null) {
            controller.dismissWithCompletion(null)
            resumeAndRelease(null)
            return
        }
        stepUp = null
        // The dismissal is asynchronous, and a challenge cannot be presented while the sheet still owns
        // the anchor window — a session started too early simply refuses to appear. So the challenge
        // waits for the dismissal to complete rather than starting from here.
        controller.dismissWithCompletion {
            scope.launch {
                try {
                    val final = try {
                        resolver.resolve(challenged, order.redirectScheme.trim())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // The resolver already degrades an unreadable state to a pending snapshot;
                        // anything else that fails here is equally indeterminate — never report a
                        // possible capture as failed.
                        Transaction.verificationPending(challenged.transactionReference)
                    }
                    outcome = final.toApplePayPaymentResult(order.orderId)
                } finally {
                    // The awaiting caller must be answered on every path, cancellation included, or the
                    // payment hangs for the life of the process and the component stays blocked. The
                    // wallet already authorized and the order exists, so an unresolved challenge is
                    // indeterminate — never a cancel.
                    if (outcome == null) outcome = indeterminate(challenged.transactionReference)
                    resumeAndRelease(null)
                }
            }
        }
    }

    private fun resumeAndRelease(presentationFailure: Throwable?) {
        if (continuation.isActive) {
            val settled = outcome
            val error = presentationFailure ?: failure
            when {
                // A settled outcome wins over a later failure: if the customer was charged, reporting
                // that payment as failed would be worse than any follow-up error.
                settled != null -> continuation.resume(settled)
                error != null -> continuation.resumeWithException(error)
                // The sheet went away while the order was still running — PassKit tears it down on its
                // own past roughly half a minute, ahead of our own deadline. The payment may have been
                // accepted, so this is indeterminate, not a cancel; the outcome the in-flight call is
                // about to produce can no longer be reported.
                authorizing -> continuation.resume(indeterminate())
                // Nothing was ever authorized → the customer dismissed the sheet. Not an error.
                else -> continuation.resume(ApplePayPaymentResult.Cancelled)
            }
        }
        release()
    }

    /** The indeterminate outcome, carrying whatever identifies the payment: a reference when the order
     *  answered with one, and always the order id the host can reconcile on server-side. */
    private fun indeterminate(reference: String? = null) = ApplePayPaymentResult.Pending(
        transaction = Transaction.verificationPending(reference),
        orderId = order.orderId,
    )

    private fun release() {
        controller = null
        selfRef = null
    }

    private fun result(status: PKPaymentAuthorizationStatus) =
        PKPaymentAuthorizationResult(status = status, errors = null)
}
