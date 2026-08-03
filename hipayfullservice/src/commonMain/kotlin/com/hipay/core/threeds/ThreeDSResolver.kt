// PCI: a forward URL and a transaction reference are order data — never log here.
package com.hipay.core.threeds

import com.hipay.core.HiPayException
import com.hipay.core.callback.CallbackUrlParser
import com.hipay.core.gateway.GatewayClient
import com.hipay.core.gateway.model.Transaction
import com.hipay.core.gateway.model.TransactionState
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

/**
 * Presents a 3DS challenge page and reports the callback it captured.
 *
 * Deliberately product-agnostic: nothing here knows about cards or wallets, so every redirect-based
 * payment product reuses the same resolution logic and only supplies its own presentation. [launchInApp]
 * shows [url] in an in-app browser session bound to [callbackScheme] — the session captures the
 * `scheme://` redirect itself and dismisses — then invokes [onResult] with the callback URL, or with
 * `null` when the customer closed the session without one.
 *
 * [onResult] must be invoked exactly once, `null` included: an implementation that cannot present
 * anything has to say so, or the payment it was awaiting never reports an outcome at all.
 */
public interface ThreeDSLauncher {
    public fun launchInApp(url: String, callbackScheme: String, onResult: (String?) -> Unit)

    /**
     * Takes a challenge still on screen off it, because the caller stopped waiting for its outcome.
     * Called at most once per [launchInApp] and never expected to invoke `onResult` — nobody is
     * listening any more. Without it the customer would be left on a challenge page for a payment no
     * one will read the verdict of. Implementations with nothing to dismiss may leave this empty.
     */
    public fun cancel() {}
}

/**
 * Resolves a 3DS challenge to a server-confirmed final [Transaction] — one implementation for every
 * payment product that can be forwarded (card, wallet, and any later redirect product).
 *
 * The rule that makes it safe: the outcome comes from the gateway, never from the redirect. A captured
 * callback URL is used only to recover a transaction reference when the order response carried none;
 * its status segment and query parameters are never trusted as the verdict, because a redirect is
 * client-controlled.
 *
 * A challenge the customer closes is NOT assumed to be an abort either: they may well have validated
 * it without the app ever receiving the redirect, so the state is re-read from the gateway. When it
 * cannot be read at all, the result is an indeterminate [Transaction.verificationPending] snapshot
 * rather than a thrown error or a false abort — the host can re-query later.
 */
public class ThreeDSResolver(
    private val gateway: GatewayClient,
    private val launcher: ThreeDSLauncher,
) {
    /**
     * Whether [transaction] actually needs a challenge — the single guard, so a caller deciding
     * "was this challenged?" can never drift from what [resolve] does.
     */
    public fun willPresentChallenge(transaction: Transaction): Boolean =
        transaction.state == TransactionState.FORWARDING && !transaction.forwardUrl.isNullOrBlank()

    /**
     * Presents the challenge for [transaction] and returns the final transaction. A transaction that
     * needs no challenge is returned untouched, so callers can hand every order response to this
     * method. [callbackScheme] is the app's URL scheme the return is captured on; [signature] is the
     * merchant-computed Gateway signature, when one is in use.
     */
    @Throws(HiPayException::class, CancellationException::class)
    public suspend fun resolve(
        transaction: Transaction,
        callbackScheme: String,
        signature: String? = null,
    ): Transaction {
        val forwardUrl = transaction.forwardUrl
        if (forwardUrl == null || !willPresentChallenge(transaction)) return transaction

        val callbackUrl: String? = suspendCancellableCoroutine { continuation ->
            // Registered before presenting: a caller that stops awaiting the challenge must not leave
            // it on screen, or the customer keeps authenticating a payment nobody will report.
            continuation.invokeOnCancellation { launcher.cancel() }
            launcher.launchInApp(forwardUrl, callbackScheme) { url ->
                if (continuation.isActive) continuation.resume(url)
            }
        }
        // Prefer the reference the order response already gave us; the callback is only a fallback for
        // recovering one, never the verdict.
        val reference = transaction.transactionReference ?: callbackUrl?.let { referenceFrom(it) }
        return reconcileOrPending(reference, signature)
    }

    /** The callback's `reference` parameter, or null when the URL is not a parseable HiPay return —
     *  an unusable callback must not fail a payment that may well have been captured. */
    private fun referenceFrom(callbackUrl: String): String? = try {
        CallbackUrlParser.parse(callbackUrl).queryParams["reference"]
    } catch (_: HiPayException) {
        null
    }

    /** The authoritative state for [reference]; an indeterminate pending snapshot when it cannot be
     *  confirmed — no reference to query, or the gateway is unreachable. */
    private suspend fun reconcileOrPending(reference: String?, signature: String?): Transaction {
        if (reference == null) return Transaction.verificationPending(null)
        return try {
            gateway.getTransaction(reference, signature)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Transaction.verificationPending(reference)
        }
    }
}
