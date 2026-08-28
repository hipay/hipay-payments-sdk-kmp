package com.hipay.core.threeds

import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import com.hipay.core.gateway.GatewayClient
import com.hipay.core.gateway.model.Transaction
import com.hipay.core.gateway.model.TransactionState
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreeDSResolverTest {

    private val config = HiPayConfig("user", "pass", Environment.STAGE)
    private var fetchedPath: String? = null

    /** Records what it was asked to present and answers with a fixed callback (null = closed). */
    private class FakeLauncher(private val callbackUrl: String?) : ThreeDSLauncher {
        var presentedUrl: String? = null
        var presentedScheme: String? = null
        var launches = 0

        override fun launchInApp(url: String, callbackScheme: String, onResult: (String?) -> Unit) {
            launches++
            presentedUrl = url
            presentedScheme = callbackScheme
            onResult(callbackUrl)
        }
    }

    /** Presents and never answers — the challenge still on screen when the caller gives up. */
    private class SilentLauncher : ThreeDSLauncher {
        var launches = 0
        var cancels = 0

        override fun launchInApp(url: String, callbackScheme: String, onResult: (String?) -> Unit) {
            launches++
        }

        override fun cancel() {
            cancels++
        }
    }

    private fun resolver(
        launcher: ThreeDSLauncher,
        transactionJson: String = COMPLETED_TRANSACTION,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): ThreeDSResolver {
        val engine = MockEngine { request ->
            fetchedPath = request.url.encodedPath
            if (status == HttpStatusCode.OK) {
                respond(transactionJson, status, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                respondError(status)
            }
        }
        return ThreeDSResolver(GatewayClient(config, engine), launcher)
    }

    private fun forwarding(reference: String? = "TX-1") = Transaction(
        stateRaw = "forwarding",
        transactionReference = reference,
        forwardUrl = "https://secure.example/3ds/challenge",
    )

    // A transaction that needs no challenge is returned untouched: callers can hand every order
    // response to the resolver without pre-checking.
    @Test
    fun completedTransactionIsReturnedWithoutPresentingAnything() = runTest {
        val launcher = FakeLauncher(callbackUrl = null)
        val tx = Transaction(stateRaw = "completed", transactionReference = "TX-9")

        val final = resolver(launcher).resolve(tx, callbackScheme = "hipaydemo")

        assertEquals(0, launcher.launches)
        assertEquals(TransactionState.COMPLETED, final.state)
        assertNull(fetchedPath)
    }

    // A FORWARDING transaction with no forward URL cannot be challenged — it must not be presented as
    // one, and must not silently become an unrelated state.
    @Test
    fun forwardingWithoutUrlIsNotPresented() = runTest {
        val launcher = FakeLauncher(callbackUrl = null)
        val tx = Transaction(stateRaw = "forwarding", transactionReference = "TX-2")

        val final = resolver(launcher).resolve(tx, callbackScheme = "hipaydemo")

        assertEquals(0, launcher.launches)
        assertEquals(TransactionState.FORWARDING, final.state)
    }

    // The nominal step-up: present the forward URL on the app's scheme, then take the verdict from the
    // gateway — never from the redirect.
    @Test
    fun challengeIsPresentedThenConfirmedFromTheGateway() = runTest {
        val launcher = FakeLauncher(callbackUrl = CALLBACK_ACCEPT)

        val final = resolver(launcher).resolve(forwarding(), callbackScheme = "hipaydemo")

        assertEquals(1, launcher.launches)
        assertEquals("https://secure.example/3ds/challenge", launcher.presentedUrl)
        assertEquals("hipaydemo", launcher.presentedScheme)
        assertTrue(fetchedPath!!.endsWith("/transaction/TX-1"))
        assertEquals(TransactionState.COMPLETED, final.state)
    }

    // A closed challenge is NOT an abort: the customer may have validated it without the app receiving
    // the redirect, so the state is still read back from the gateway.
    @Test
    fun closedChallengeIsReconciledNotAssumedAborted() = runTest {
        val launcher = FakeLauncher(callbackUrl = null)

        val final = resolver(launcher).resolve(forwarding(), callbackScheme = "hipaydemo")

        assertTrue(fetchedPath!!.endsWith("/transaction/TX-1"))
        assertEquals(TransactionState.COMPLETED, final.state)
    }

    // A challenge genuinely abandoned stays FORWARDING once confirmed — a server-confirmed
    // not-completed, deliberately distinct from the indeterminate PENDING below.
    @Test
    fun abandonedChallengeStaysForwardingAfterConfirmation() = runTest {
        val launcher = FakeLauncher(callbackUrl = null)

        val final = resolver(launcher, transactionJson = FORWARDING_TRANSACTION)
            .resolve(forwarding(), callbackScheme = "hipaydemo")

        assertEquals(TransactionState.FORWARDING, final.state)
    }

    // An unreachable gateway must never produce a false verdict: the outcome is an indeterminate
    // pending snapshot that keeps the reference, so the host can re-query.
    @Test
    fun unreachableGatewayYieldsIndeterminatePending() = runTest {
        val launcher = FakeLauncher(callbackUrl = CALLBACK_ACCEPT)

        val final = resolver(launcher, status = HttpStatusCode.InternalServerError)
            .resolve(forwarding(), callbackScheme = "hipaydemo")

        assertEquals(TransactionState.PENDING, final.state)
        assertEquals("TX-1", final.transactionReference)
    }

    // With no reference anywhere there is nothing to confirm against — still indeterminate, never a
    // thrown error.
    @Test
    fun noReferenceAnywhereYieldsPendingWithoutQuerying() = runTest {
        val launcher = FakeLauncher(callbackUrl = null)

        val final = resolver(launcher).resolve(forwarding(reference = null), callbackScheme = "hipaydemo")

        assertNull(fetchedPath)
        assertEquals(TransactionState.PENDING, final.state)
        assertNull(final.transactionReference)
    }

    // When the order response carried no reference, the callback's is used to recover one.
    @Test
    fun referenceIsRecoveredFromTheCallbackWhenTheOrderHadNone() = runTest {
        val launcher = FakeLauncher(callbackUrl = CALLBACK_ACCEPT)

        resolver(launcher).resolve(forwarding(reference = null), callbackScheme = "hipaydemo")

        assertTrue(fetchedPath!!.endsWith("/transaction/TX-CB"))
    }

    // The order response's reference wins over the callback's: the redirect is client-controlled and
    // must not be able to point the confirmation at another transaction.
    @Test
    fun orderReferenceWinsOverTheCallbackReference() = runTest {
        val launcher = FakeLauncher(callbackUrl = CALLBACK_ACCEPT)

        resolver(launcher).resolve(forwarding(reference = "TX-1"), callbackScheme = "hipaydemo")

        assertTrue(fetchedPath!!.endsWith("/transaction/TX-1"))
    }

    // An unparseable callback must not fail a payment that may have been captured: fall back to the
    // reference we already hold.
    @Test
    fun unparseableCallbackFallsBackToTheKnownReference() = runTest {
        val launcher = FakeLauncher(callbackUrl = "not even a url")

        val final = resolver(launcher).resolve(forwarding(reference = "TX-1"), callbackScheme = "hipaydemo")

        assertTrue(fetchedPath!!.endsWith("/transaction/TX-1"))
        assertEquals(TransactionState.COMPLETED, final.state)
    }

    // An unparseable callback with no known reference is indeterminate, not an exception.
    @Test
    fun unparseableCallbackWithoutReferenceYieldsPending() = runTest {
        val launcher = FakeLauncher(callbackUrl = "not even a url")

        val final = resolver(launcher).resolve(forwarding(reference = null), callbackScheme = "hipaydemo")

        assertNull(fetchedPath)
        assertEquals(TransactionState.PENDING, final.state)
    }

    // A caller that stops awaiting the challenge must not leave it on screen: the customer would keep
    // authenticating a payment whose outcome nobody will read, while the component is already free for
    // a second one.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun abandoningTheCallDismissesTheChallenge() = runTest {
        val launcher = SilentLauncher()
        val subject = resolver(launcher)

        val job = launch { subject.resolve(forwarding(), callbackScheme = "hipaydemo") }
        runCurrent()
        job.cancelAndJoin()

        assertEquals(1, launcher.launches)
        assertEquals(1, launcher.cancels)
    }

    // A launcher that cannot present anything reports it as "no callback": the payment is reconciled
    // from the gateway instead of hanging on a session that never answers.
    @Test
    fun aLauncherThatCannotPresentStillResolves() = runTest {
        val launcher = FakeLauncher(callbackUrl = null)

        val final = resolver(launcher).resolve(forwarding(), callbackScheme = "hipaydemo")

        assertEquals(TransactionState.COMPLETED, final.state)
    }

    // The guard and the presentation decision are the same rule — they can never drift.
    @Test
    fun willPresentChallengeMatchesWhatResolveDoes() = runTest {
        val subject = resolver(FakeLauncher(callbackUrl = null))

        assertTrue(subject.willPresentChallenge(forwarding()))
        assertTrue(!subject.willPresentChallenge(Transaction(stateRaw = "forwarding")))
        assertTrue(!subject.willPresentChallenge(Transaction(stateRaw = "completed", forwardUrl = "https://x")))
    }

    private companion object {
        const val COMPLETED_TRANSACTION =
            "{\"transaction\":{\"state\":\"completed\",\"status\":\"118\",\"transactionReference\":\"TX-1\"}}"
        const val FORWARDING_TRANSACTION =
            "{\"transaction\":{\"state\":\"forwarding\",\"transactionReference\":\"TX-1\"}}"
        const val CALLBACK_ACCEPT =
            "hipaydemo://hipay-payments/gateway/orders/AP-1/accept?reference=TX-CB"
    }
}
