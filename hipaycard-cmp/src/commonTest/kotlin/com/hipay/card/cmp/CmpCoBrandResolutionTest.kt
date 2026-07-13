package com.hipay.card.cmp

import com.hipay.card.model.CardInfo
import com.hipay.card.validation.CardNetwork
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Backend co-brand refinement in the CMP controller: the offered set expands to the Secure
 * Vault verdict, stale verdicts are dropped, failures degrade to the local icon. The backend
 * is faked through the in-module resolver seam — the contract under test is the controller's
 * orchestration (debounce, stale-guard, degrade, selection/CVC reuse), not the vault call.
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle
class CmpCoBrandResolutionTest {

    // Real co-branded stage test PANs (Luhn-valid): CB+Visa and CB+Mastercard.
    private val cbVisaPan = "4484120000000029"
    private val cbMcPan = "5341013985664960"

    private fun config() = HiPayConfig(username = "u", password = "p", environment = Environment.STAGE)

    @Test
    fun backendVerdictExpandsOfferedSet_domesticCoBrandDefaultSelected() = runTest {
        val c = CmpCardController(config(), scope = this)
        c.cardInfoResolver = { CardInfo(brand = "VISA", domesticNetwork = "cb") }
        c.onNumberChange(cbVisaPan)
        // The local single-network detection drives the icon immediately, before the verdict.
        assertEquals(listOf(CardNetwork.VISA), c.networks)
        advanceUntilIdle()
        // Verdict lands: domestic co-brand first (default-selected), international brand second.
        assertEquals(listOf(CardNetwork.CB, CardNetwork.VISA), c.networks)
        assertEquals(CardNetwork.CB, c.selectedNetwork)
    }

    @Test
    fun userSelectionIsPreservedAcrossRefinement() = runTest {
        val c = CmpCardController(config(), scope = this)
        c.cardInfoResolver = { CardInfo(brand = "VISA", domesticNetwork = "cb") }
        c.onNumberChange(cbVisaPan)
        advanceUntilIdle()
        c.selectNetwork(CardNetwork.VISA) // explicit payer choice over the CB default
        // A different valid PAN re-resolves; the explicit choice stays while still offered.
        c.onNumberChange("4111111111111111")
        advanceUntilIdle()
        assertEquals(listOf(CardNetwork.CB, CardNetwork.VISA), c.networks)
        assertEquals(CardNetwork.VISA, c.selectedNetwork)
    }

    @Test
    fun staleVerdictIsDropped() = runTest {
        val c = CmpCardController(config(), scope = this)
        c.cardInfoResolver = { CardInfo(brand = "VISA", domesticNetwork = "cb") }
        c.onNumberChange(cbVisaPan) // queues a resolve for this PAN
        c.onNumberChange("4111") // payer kept typing another number before the verdict landed
        advanceUntilIdle()
        // The co-brand verdict for the OLD pan must not decorate the new partial one.
        assertEquals(listOf(CardNetwork.VISA), c.networks)
        assertEquals(CardNetwork.VISA, c.selectedNetwork)
    }

    @Test
    fun resolvesOncePerDistinctValidPan() = runTest {
        var calls = 0
        val c = CmpCardController(config(), scope = this)
        c.cardInfoResolver = { calls++; CardInfo(brand = "VISA", domesticNetwork = "cb") }
        c.onNumberChange(cbVisaPan)
        advanceUntilIdle()
        c.onNumberChange(cbVisaPan) // same value re-entered (e.g. paste over) → no re-resolve
        advanceUntilIdle()
        assertEquals(1, calls)
        c.onNumberChange("4111111111111111") // a newly-valid DIFFERENT pan re-resolves
        advanceUntilIdle()
        assertEquals(2, calls)
    }

    @Test
    fun failureKeepsLocalIconSurfacesNoErrorAndRearmsRetry() = runTest {
        var calls = 0
        val c = CmpCardController(config(), scope = this)
        c.cardInfoResolver = {
            calls++
            throw IllegalStateException("offline") // any failure shape — the degrade path is typed-agnostic
        }
        c.onNumberChange(cbVisaPan)
        advanceUntilIdle()
        assertEquals(listOf(CardNetwork.VISA), c.networks) // local icon kept
        c.markBlurred(CmpCardController.Field.NUMBER)
        assertNull(c.numberSlotErrorKey) // resolution failure never surfaces as a field error
        // Next edit retries: drop a digit, retype it.
        c.onNumberChange(cbVisaPan.dropLast(1))
        c.onNumberChange(cbVisaPan)
        advanceUntilIdle()
        assertEquals(2, calls)
    }

    @Test
    fun coBrandedMaestroDropsTheCvcRequirement() = runTest {
        val c = CmpCardController(config(), scope = this)
        c.cardInfoResolver = { CardInfo(brand = "MAESTRO", domesticNetwork = "cb") }
        c.onNumberChange(cbMcPan)
        c.onCvcChange("123")
        advanceUntilIdle()
        assertEquals(listOf(CardNetwork.CB, CardNetwork.MAESTRO), c.networks)
        c.selectNetwork(CardNetwork.MAESTRO)
        // applyOffered's co-brand-aware CVC policy is reused verbatim: co-branded Maestro → no CVC.
        assertFalse(c.isCvcRequired)
        assertTrue(c.isCvcComplete)
    }
}
