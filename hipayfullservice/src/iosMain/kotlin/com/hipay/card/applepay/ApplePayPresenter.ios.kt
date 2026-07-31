// PCI: com.hipay.card path — NEVER log here (paymentData / token must never be logged).
@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.hipay.card.applepay

import com.hipay.card.validation.CardNetwork
import com.hipay.core.HiPayConfig
import com.hipay.core.HiPayException
import com.hipay.core.gateway.model.Transaction
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.PassKit.PKPayment
import platform.PassKit.PKPaymentAuthorizationController
import platform.PassKit.PKPaymentAuthorizationControllerDelegateProtocol
import platform.PassKit.PKPaymentAuthorizationResult
import platform.PassKit.PKPaymentAuthorizationStatus.PKPaymentAuthorizationStatusFailure
import platform.PassKit.PKPaymentAuthorizationStatus.PKPaymentAuthorizationStatusSuccess
import platform.darwin.NSObject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Presents the native Apple Pay sheet and runs the nominal payment — ONE implementation both
 * delivery channels (Swift SPM + CMP) call, so sheet behaviour never diverges.
 *
 * Flow: build the `PKPaymentRequest` (store name, resolved networks, no contact fields), present the
 * sheet, on biometric authorization capture `paymentData`, run the [WalletCoordinator]
 * (tokenize → order → transaction), complete the sheet, and return the [Transaction]. A user cancel
 * surfaces as [CancellationException]; a decline/error surfaces as [HiPayException] (typed
 * cancel/decline outcomes are refined in 17.4). Double-tap protection is the caller's
 * [PaymentInFlightGuard] around the button tap.
 */
@Throws(HiPayException::class, CancellationException::class)
public suspend fun runApplePayPayment(
    config: HiPayConfig,
    applePayConfig: HiPayApplePayConfig,
    resolvedNetworks: List<CardNetwork>,
    order: ApplePayOrder,
): Transaction {
    val sheet = applePaySheetRequest(
        config = applePayConfig,
        resolvedNetworks = resolvedNetworks,
        amount = order.amount,
        currencyCode = order.currency,
        countryCode = order.countryCode,
    )
    val request = buildPaymentRequest(sheet)
    val coordinator = WalletCoordinator(config)
    val scope = CoroutineScope(Dispatchers.Main)
    try {
        return suspendCancellableCoroutine { continuation ->
            val delegate = PaymentDelegate(coordinator, applePayConfig, order, scope, continuation)
            val controller = PKPaymentAuthorizationController(paymentRequest = request)
            controller.delegate = delegate
            delegate.retain(controller)
            controller.presentWithCompletion { presented ->
                if (!presented && continuation.isActive) {
                    continuation.resumeWithException(
                        HiPayException(
                            code = com.hipay.core.HiPayErrorCode.CLIENT,
                            message = "Apple Pay sheet could not be presented",
                        ),
                    )
                }
            }
            continuation.invokeOnCancellation { controller.dismissWithCompletion(null) }
        }
    } finally {
        scope.cancel()
    }
}

/**
 * `PKPaymentAuthorizationControllerDelegate` bridged to the suspended [continuation]. The controller
 * holds its delegate WEAKLY, so the delegate keeps a strong self-reference (via [retain]) until the
 * sheet finishes.
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

    fun retain(controller: PKPaymentAuthorizationController) {
        this.controller = controller
        this.selfRef = this
    }

    override fun paymentAuthorizationController(
        controller: PKPaymentAuthorizationController,
        didAuthorizePayment: PKPayment,
        handler: (PKPaymentAuthorizationResult?) -> Unit,
    ) {
        // The opaque wallet payload, as its UTF-8 JSON string (never logged).
        val paymentData = NSString.create(
            data = didAuthorizePayment.token.paymentData,
            encoding = NSUTF8StringEncoding,
        ) as String?
        scope.launch {
            try {
                transaction = coordinator.pay(
                    paymentData = paymentData.orEmpty(),
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
                handler(PKPaymentAuthorizationResult(status = PKPaymentAuthorizationStatusSuccess, errors = null))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failure = e
                handler(PKPaymentAuthorizationResult(status = PKPaymentAuthorizationStatusFailure, errors = null))
            }
        }
    }

    override fun paymentAuthorizationControllerDidFinish(controller: PKPaymentAuthorizationController) {
        controller.dismissWithCompletion(null)
        if (continuation.isActive) {
            val error = failure
            val tx = transaction
            when {
                error != null -> continuation.resumeWithException(error)
                tx != null -> continuation.resume(tx)
                // No authorization happened → the user cancelled.
                else -> continuation.resumeWithException(CancellationException("Apple Pay cancelled"))
            }
        }
        this.controller = null
        selfRef = null
    }
}
