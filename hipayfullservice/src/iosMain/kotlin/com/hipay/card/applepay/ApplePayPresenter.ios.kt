// PCI: com.hipay.card path — NEVER log here (paymentData / token must never be logged).
@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.hipay.card.applepay

import com.hipay.card.validation.CardNetwork
import com.hipay.core.HiPayConfig
import com.hipay.core.HiPayErrorCode
import com.hipay.core.HiPayException
import com.hipay.core.gateway.model.Transaction
import com.hipay.core.gateway.model.TransactionState
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
 * (tokenize → order → transaction), complete the sheet and return the [Transaction].
 *
 * **Only a completed transaction resolves successfully.** Any other gateway state completes the sheet
 * as a failure and surfaces a [HiPayException], so the customer never sees Apple's success checkmark
 * for a payment the gateway did not accept. A user cancel surfaces as [CancellationException]. (Typed
 * decline outcomes, and resuming a step-up when the gateway asks for one, come with the resilience
 * work.)
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
): Transaction = withContext(Dispatchers.Main) {
    // PassKit and UIKit are main-thread only: the controller is built, delegated, presented and
    // dismissed here, and the delegate callbacks arrive on this same thread.
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
        // The authorization job must survive a cancellation of THIS coroutine: PassKit owns the sheet
        // and must be answered exactly once, so the job gets its own scope instead of being a child
        // that cancellation would kill mid-order, leaving the sheet spinning forever.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        suspendCancellableCoroutine { continuation ->
            val delegate = PaymentDelegate(coordinator, applePayConfig, order, scope, continuation)
            val controller = PKPaymentAuthorizationController(paymentRequest = request)
            controller.delegate = delegate
            delegate.retain(controller)
            // Registered before presenting: a cancellation racing the presentation must still dismiss.
            continuation.invokeOnCancellation { delegate.abandon() }
            controller.presentWithCompletion { presented ->
                if (!presented) delegate.failToPresent()
            }
        }
    } finally {
        paymentInFlight.end()
    }
}

/** One payment at a time, per process — the sheet is a single system-modal surface anyway. */
private val paymentInFlight = PaymentInFlightGuard()

/**
 * `PKPaymentAuthorizationControllerDelegate` bridged to the suspended [continuation]. The controller
 * holds its delegate WEAKLY, so the delegate keeps a strong self-reference (via [retain]) until the
 * flow ends — released on EVERY exit path (finish, presentation failure, cancellation), never only on
 * `didFinish`, or the delegate, the controller and the config (which holds the `.p12` password) would
 * be retained for the life of the process.
 */
private class PaymentDelegate(
    private val coordinator: WalletCoordinator,
    private val applePayConfig: HiPayApplePayConfig,
    private val order: ApplePayOrder,
    private val scope: CoroutineScope,
    private val continuation: CancellableContinuation<Transaction>,
) : NSObject(), PKPaymentAuthorizationControllerDelegateProtocol {

    private var selfRef: PaymentDelegate? = null
    private var controller: PKPaymentAuthorizationController? = null
    private var transaction: Transaction? = null
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
        if (authorizing || transaction != null) {
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
                val tx = coordinator.pay(
                    paymentData = paymentData,
                    applePayConfig = applePayConfig,
                    orderId = order.orderId,
                    amount = order.amount,
                    currency = order.currency,
                    description = order.description,
                    acceptUrl = order.acceptUrl,
                    declineUrl = order.declineUrl,
                    pendingUrl = order.pendingUrl,
                    exceptionUrl = order.exceptionUrl,
                    cancelUrl = order.cancelUrl,
                    language = order.language,
                )
                if (tx.state == TransactionState.COMPLETED) {
                    transaction = tx
                    status = PKPaymentAuthorizationStatusSuccess
                } else {
                    // The gateway answered, but not with a completed payment: the sheet must show a
                    // failure, not a checkmark.
                    failure = HiPayException(
                        code = HiPayErrorCode.API,
                        message = "Apple Pay payment not completed (state=${tx.state})",
                    )
                }
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
        controller.dismissWithCompletion(null)
        resumeAndRelease(null)
    }

    private fun resumeAndRelease(presentationFailure: Throwable?) {
        if (continuation.isActive) {
            val tx = transaction
            val error = presentationFailure ?: failure
            when {
                // A completed transaction wins over a later failure: the customer was charged, and
                // reporting that payment as failed would be worse than any follow-up error.
                tx != null -> continuation.resume(tx)
                error != null -> continuation.resumeWithException(error)
                // Nothing was ever authorized → the customer dismissed the sheet.
                else -> continuation.resumeWithException(CancellationException("Apple Pay cancelled"))
            }
        }
        release()
    }

    private fun release() {
        controller = null
        selfRef = null
    }

    private fun result(status: PKPaymentAuthorizationStatus) =
        PKPaymentAuthorizationResult(status = status, errors = null)
}
