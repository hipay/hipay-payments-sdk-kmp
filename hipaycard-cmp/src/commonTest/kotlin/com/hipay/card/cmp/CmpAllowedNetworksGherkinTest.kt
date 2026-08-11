package com.hipay.card.cmp

import com.hipay.card.model.CardInfo
import com.hipay.card.validation.CardEntryStringKey
import com.hipay.card.validation.CardNetwork
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contractual allowed-networks Gherkin scenarios, verbatim from the merchant contract
 * (2026-07): mono-network auto-select, disallowed-network error, co-brand filtering,
 * and selection reset on number change. The backend verdict is faked through the
 * in-module resolver seam (same convention as [CmpCoBrandResolutionTest]).
 *
 * The "not authorized" error is NOT blur-gated (unlike expiry/CVV) but IS
 * backend-verdict-gated: it surfaces only once the BIN verdict for the current
 * number leaves no allowed network — never from local detection alone, which
 * would flash a false error on a co-branded card (CB+Visa with only CB allowed
 * locally detects the disallowed Visa until the verdict lands).
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle
class CmpAllowedNetworksGherkinTest {

    // Real stage test PANs (Luhn-valid): mono Visa, mono Mastercard, co-branded CB+Visa.
    private val visaPan = "4111111111111111"
    private val mcPan = "5555555555554444"
    private val cbVisaPan = "4484120000000029"

    private fun config() = HiPayConfig(username = "u", password = "p", environment = Environment.STAGE)

    /** A permissive account ceiling: these scenarios are about the INTEGRATOR restriction, so the
     *  account must not be what narrows them. Set on every controller that does not test the ceiling
     *  itself — without it the ceiling stays pending and no brand icon is shown at all. */
    private val everyNetworkAccepted: suspend () -> Set<CardNetwork> = {
        // The six real card networks, NOT `entries` — that would put UNKNOWN into the ceiling, a value
        // the gateway can never answer, so the fixture would exercise a state production cannot reach.
        setOf(
            CardNetwork.VISA, CardNetwork.MASTERCARD, CardNetwork.AMEX,
            CardNetwork.MAESTRO, CardNetwork.CB, CardNetwork.BCMC,
        )
    }

    /** Verdict keyed on the BIN: CB+Visa co-brand for the CB pan, mono brand otherwise. */
    private val fakeVault: suspend (String) -> CardInfo = { digits ->
        when {
            digits == cbVisaPan -> CardInfo(brand = "VISA", domesticNetwork = "cb")
            digits.startsWith("4") -> CardInfo(brand = "VISA")
            else -> CardInfo(brand = "MASTERCARD")
        }
    }

    // Scénario 1 : réseau mono-réseau autorisé détecté et sélectionné automatiquement.
    @Test
    fun monoNetworkAllowedIsAutoSelected() = runTest {
        val c = CmpCardController(config(), allowed = listOf(CardNetwork.VISA, CardNetwork.MASTERCARD), scope = this)
        c.cardInfoResolver = fakeVault
        c.accountNetworksResolver = everyNetworkAccepted
        c.onNumberChange(visaPan)
        // Auto-selected from local detection alone, before any backend verdict, no user action.
        assertEquals(listOf(CardNetwork.VISA), c.networks)
        assertEquals(CardNetwork.VISA, c.selectedNetwork)
        advanceUntilIdle()
        // The backend verdict confirms — still visa, still auto-selected.
        assertEquals(listOf(CardNetwork.VISA), c.networks)
        assertEquals(CardNetwork.VISA, c.selectedNetwork)
    }

    // Scénario 2 : réseau détecté non autorisé (cas AMBIGU) — erreur affichée au verdict BIN, pas
    // avant. Seul "cb" est autorisé et le BIN est Visa : CB peut chevaucher un BIN Visa, donc la
    // détection locale seule ne doit PAS rejeter (le cas NON ambigu, ex. Amex/CB-only, rejette
    // immédiatement — cf. CmpCardValidationGherkinTest). Refinement 2026-07-20.
    @Test
    fun disallowedNetworkShowsErrorOnBackendVerdictAndHidesLogo() = runTest {
        val c = CmpCardController(config(), allowed = listOf(CardNetwork.CB), scope = this)
        c.cardInfoResolver = fakeVault
        c.accountNetworksResolver = everyNetworkAccepted
        c.onNumberChange("4111") // partial visa BIN — local detection only; CB could ride it
        // No error from local detection alone; the disallowed logo is already hidden (neutral glyph).
        assertNull(c.numberSlotErrorKey)
        assertTrue(c.networks.isEmpty())
        c.onNumberChange(visaPan) // complete PAN queues the BIN verdict
        assertNull(c.numberSlotErrorKey) // still none until the verdict lands
        advanceUntilIdle()
        // Verdict: visa only, nothing allowed → the contractual error, no blur needed, no logo.
        assertEquals(CardEntryStringKey.ERROR_NETWORK_NOT_AUTHORIZED, c.numberSlotErrorKey)
        assertTrue(c.networks.isEmpty())
        assertNull(c.selectedNetwork)
        // Any further edit clears the verdict error.
        c.onNumberChange(visaPan.dropLast(1))
        assertNull(c.numberSlotErrorKey)
    }

    // Cas rapporté le 2026-07-17 : cobrandée CB+Visa avec seul CB autorisé — la détection
    // locale (visa, non autorisé) ne doit JAMAIS afficher d'erreur transitoire pendant la
    // saisie ; le verdict BIN offre CB et la saisie est valide.
    @Test
    fun coBrandedCardNeverFlashesErrorWhileTypingWhenOnlyCoBrandAllowed() = runTest {
        val c = CmpCardController(config(), allowed = listOf(CardNetwork.CB), scope = this)
        c.cardInfoResolver = fakeVault
        c.accountNetworksResolver = everyNetworkAccepted
        c.onNumberChange("448412") // partial BIN — locally detected visa is NOT allowed
        assertNull(c.numberSlotErrorKey) // no transient error
        assertTrue(c.networks.isEmpty()) // visa logo hidden, neutral glyph
        c.onNumberChange(cbVisaPan) // complete PAN
        assertNull(c.numberSlotErrorKey) // still none while the verdict is in flight
        advanceUntilIdle()
        // Verdict [CB, VISA] ∩ [CB] = [CB] → CB offered and auto-selected, entry valid.
        assertEquals(listOf(CardNetwork.CB), c.networks)
        assertEquals(CardNetwork.CB, c.selectedNetwork)
        assertNull(c.numberSlotErrorKey)
    }

    // Scénario 3 : carte cobrandée — seuls les réseaux autorisés sont proposés.
    @Test
    fun coBrandedCardOffersOnlyAllowedNetworks() = runTest {
        val c = CmpCardController(config(), allowed = listOf(CardNetwork.VISA), scope = this)
        c.cardInfoResolver = fakeVault
        c.accountNetworksResolver = everyNetworkAccepted
        c.onNumberChange(cbVisaPan)
        advanceUntilIdle()
        // Backend resolves [CB, VISA]; only visa survives the allow-list — cb is never proposed.
        assertEquals(listOf(CardNetwork.VISA), c.networks)
        assertEquals(CardNetwork.VISA, c.selectedNetwork)
        assertNull(c.numberSlotErrorKey)
    }

    // Scénario 4 (branche "activé") : cb sélectionné, remplacement par un BIN mono mastercard
    // activé → sélection réinitialisée puis mastercard auto-sélectionné.
    @Test
    fun numberChangeResetsSelectionAndAutoSelectsAllowedNetwork() = runTest {
        val c = CmpCardController(config(), allowed = listOf(CardNetwork.CB, CardNetwork.MASTERCARD), scope = this)
        c.cardInfoResolver = fakeVault
        c.accountNetworksResolver = everyNetworkAccepted
        c.onNumberChange(cbVisaPan)
        advanceUntilIdle()
        c.selectNetwork(CardNetwork.CB) // explicit payer choice
        assertEquals(CardNetwork.CB, c.selectedNetwork)

        c.onNumberChange(mcPan) // replace with a mono mastercard BIN
        // Previous selection is reset and mastercard (allowed) is auto-selected.
        assertEquals(CardNetwork.MASTERCARD, c.selectedNetwork)
        advanceUntilIdle()
        assertEquals(listOf(CardNetwork.MASTERCARD), c.networks)
        assertEquals(CardNetwork.MASTERCARD, c.selectedNetwork)
        assertNull(c.numberSlotErrorKey)
    }

    // Scénario 4 (branche "sinon") : mastercard NON activé → message "non autorisé".
    @Test
    fun numberChangeResetsSelectionAndShowsErrorWhenNewNetworkDisallowed() = runTest {
        val c = CmpCardController(config(), allowed = listOf(CardNetwork.CB, CardNetwork.VISA), scope = this)
        c.cardInfoResolver = fakeVault
        c.accountNetworksResolver = everyNetworkAccepted
        c.onNumberChange(cbVisaPan)
        advanceUntilIdle()
        c.selectNetwork(CardNetwork.CB)
        assertEquals(CardNetwork.CB, c.selectedNetwork)

        c.onNumberChange(mcPan) // mastercard is NOT in the allow-list
        // Selection reset, nothing offered; no error yet (verdict in flight).
        assertNull(c.selectedNetwork)
        assertTrue(c.networks.isEmpty())
        assertNull(c.numberSlotErrorKey)
        advanceUntilIdle()
        // Verdict: mastercard only, not allowed → the contractual error (no blur needed).
        assertTrue(c.networks.isEmpty())
        assertEquals(CardEntryStringKey.ERROR_NETWORK_NOT_AUTHORIZED, c.numberSlotErrorKey)
    }

    // ---- Account ceiling (the merchant's HiPay contract) ----

    // The reported bug: NO integrator restriction, and a network the ACCOUNT does not accept must
    // still be refused. Before the ceiling existed, an absent allow-list accepted everything.
    @Test
    fun networkTheAccountDoesNotAcceptIsRefusedWithoutAnyIntegratorRestriction() = runTest {
        val c = CmpCardController(config(), scope = this)
        c.cardInfoResolver = fakeVault
        c.accountNetworksResolver = { setOf(CardNetwork.MASTERCARD, CardNetwork.CB) }

        c.onNumberChange(visaPan)
        advanceUntilIdle()

        assertTrue(c.networks.isEmpty())
        assertEquals(CardEntryStringKey.ERROR_NETWORK_NOT_AUTHORIZED, c.numberSlotErrorKey)
        assertEquals(false, c.isNetworkAuthorized)
    }

    // The other half of the contract: a network the account DOES accept still works untouched.
    @Test
    fun networkTheAccountAcceptsIsOffered() = runTest {
        val c = CmpCardController(config(), scope = this)
        c.cardInfoResolver = fakeVault
        c.accountNetworksResolver = { setOf(CardNetwork.VISA, CardNetwork.MASTERCARD) }

        c.onNumberChange(visaPan)
        advanceUntilIdle()

        assertEquals(listOf(CardNetwork.VISA), c.networks)
        assertEquals(CardNetwork.VISA, c.selectedNetwork)
        assertNull(c.numberSlotErrorKey)
    }

    // An integrator cannot authorize what the account cannot process — the gateway would refuse the
    // order anyway, so offering it would only fail after the payer filled the form.
    @Test
    fun integratorCannotWidenBeyondTheAccountCeiling() = runTest {
        val c = CmpCardController(config(), allowed = listOf(CardNetwork.VISA, CardNetwork.MASTERCARD), scope = this)
        c.cardInfoResolver = fakeVault
        c.accountNetworksResolver = { setOf(CardNetwork.MASTERCARD) }

        c.onNumberChange(visaPan)
        advanceUntilIdle()

        assertTrue(c.networks.isEmpty())
        assertEquals(CardEntryStringKey.ERROR_NETWORK_NOT_AUTHORIZED, c.numberSlotErrorKey)
    }

    // The ceiling lands asynchronously and may arrive AFTER the payer typed a full number: the
    // verdict must be re-derived for what is in the field, not left as it was decided without it.
    @Test
    fun aCeilingArrivingAfterTypingIsAppliedToTheNumberAlreadyEntered() = runTest {
        val ceiling = CompletableDeferred<Set<CardNetwork>>()
        val c = CmpCardController(config(), scope = this)
        c.cardInfoResolver = fakeVault
        c.accountNetworksResolver = { ceiling.await() }

        c.onNumberChange(visaPan)
        advanceUntilIdle()
        // Ceiling still unknown → the pre-fix behaviour: visa offered, nothing refused.
        assertEquals(listOf(CardNetwork.VISA), c.networks)
        assertNull(c.numberSlotErrorKey)

        ceiling.complete(setOf(CardNetwork.MASTERCARD))
        advanceUntilIdle()

        // The ceiling landed: the same number is now refused, without re-querying the vault.
        assertTrue(c.networks.isEmpty())
        assertEquals(CardEntryStringKey.ERROR_NETWORK_NOT_AUTHORIZED, c.numberSlotErrorKey)
    }

    // A technical failure must never block entry: the ceiling stays open (pre-fix behaviour) and no
    // error is shown. A payment form killed by a network hiccup would be worse than the gap.
    @Test
    fun aFailedCeilingQueryLeavesEntryOpen() = runTest {
        val c = CmpCardController(config(), scope = this)
        c.cardInfoResolver = fakeVault
        c.accountNetworksResolver = { throw IllegalStateException("offline") }

        c.onNumberChange(visaPan)
        advanceUntilIdle()

        assertEquals(listOf(CardNetwork.VISA), c.networks)
        assertNull(c.numberSlotErrorKey)
        assertTrue(c.isNetworkAuthorized)
    }

    // ...and it retries on the next edit rather than giving up for the life of the controller.
    @Test
    fun aFailedCeilingQueryRetriesOnTheNextEdit() = runTest {
        var attempts = 0
        val c = CmpCardController(config(), scope = this)
        c.cardInfoResolver = fakeVault
        c.accountNetworksResolver = {
            attempts++
            if (attempts == 1) throw IllegalStateException("offline") else setOf(CardNetwork.MASTERCARD)
        }

        c.onNumberChange(visaPan)
        advanceUntilIdle()
        assertEquals(1, attempts)

        c.onNumberChange(mcPan) // any further valid number re-arms the query
        advanceUntilIdle()
        assertEquals(2, attempts)
    }

    // One query per controller — not one per keystroke.
    @Test
    fun theCeilingIsQueriedOnceForTheWholeEntry() = runTest {
        var attempts = 0
        val c = CmpCardController(config(), scope = this)
        c.cardInfoResolver = fakeVault
        c.accountNetworksResolver = { attempts++; setOf(CardNetwork.VISA) }

        // Type the whole PAN digit by digit, then edit it back to a valid one twice: only a Luhn-valid
        // number passes the gate, so this drives it three times over — a per-query-per-pass
        // implementation would show three.
        visaPan.forEachIndexed { i, _ -> c.onNumberChange(visaPan.take(i + 1)) }
        c.onNumberChange(visaPan.dropLast(1))
        c.onNumberChange(visaPan)
        advanceUntilIdle()

        assertEquals(1, attempts)
    }

    // ---- No brand icon may be shown before the ceiling is known ----

    // The payer types "41": Visa is locally detected, but whether the account even accepts Visa is
    // still in flight. Showing the logo there and taking it back a moment later is what the merchant
    // reported, so nothing is shown until the ceiling lands.
    @Test
    fun noBrandIconIsShownWhileTheCeilingIsStillPending() = runTest {
        val ceiling = CompletableDeferred<Set<CardNetwork>>()
        val c = CmpCardController(config(), scope = this)
        c.cardInfoResolver = fakeVault
        c.accountNetworksResolver = { ceiling.await() }

        launch { c.loadAccountNetworksIfNeeded() } // what the Composable does on first composition
        runCurrent()
        c.onNumberChange("41")

        assertTrue(c.networks.isEmpty())
        assertNull(c.selectedNetwork)

        ceiling.complete(setOf(CardNetwork.VISA))
        advanceUntilIdle()

        // The ceiling allows Visa → the icon appears, once, without ever having been wrong.
        assertEquals(listOf(CardNetwork.VISA), c.networks)
    }

    // ...and a ceiling that excludes the detected network never shows its icon at all.
    @Test
    fun aDisallowedNetworkNeverShowsItsIcon() = runTest {
        val c = CmpCardController(config(), scope = this)
        c.cardInfoResolver = fakeVault
        c.accountNetworksResolver = { setOf(CardNetwork.MASTERCARD, CardNetwork.CB) }

        launch { c.loadAccountNetworksIfNeeded() }
        runCurrent()
        c.onNumberChange("41")
        advanceUntilIdle()

        assertTrue(c.networks.isEmpty())
    }

    // A failed query must not leave the payer without a brand icon for the whole entry: the ceiling
    // is declared unavailable and the component degrades to what it did before it existed.
    @Test
    fun aFailedCeilingQueryRestoresTheLocalBrandIcon() = runTest {
        val c = CmpCardController(config(), scope = this)
        c.cardInfoResolver = fakeVault
        c.accountNetworksResolver = { throw IllegalStateException("offline") }

        launch { c.loadAccountNetworksIfNeeded() }
        runCurrent()
        c.onNumberChange("41")
        advanceUntilIdle()

        assertEquals(listOf(CardNetwork.VISA), c.networks)
        assertEquals(CardNetwork.VISA, c.selectedNetwork)
    }

    // A cancelled appearance-time fetch must RELEASE the one-shot guard. Otherwise the controller stays
    // pending for its whole life: no brand icon ever again, and the ceiling silently degrades to the
    // pre-fix "accept every network" posture. Reachable by navigating away while the query is in flight.
    @Test
    fun aCancelledCeilingFetchDoesNotWedgeTheControllerInPending() = runTest {
        val ceiling = CompletableDeferred<Set<CardNetwork>>()
        var attempts = 0
        val c = CmpCardController(config(), scope = this)
        c.cardInfoResolver = fakeVault
        c.accountNetworksResolver = { attempts++; ceiling.await() }

        val appearance = launch { c.loadAccountNetworksIfNeeded() }
        runCurrent()
        assertEquals(1, attempts)
        appearance.cancelAndJoin() // the component left composition mid-flight

        // A second appearance must ask again rather than sit on the guard forever.
        val second = launch { c.loadAccountNetworksIfNeeded() }
        runCurrent()
        assertEquals(2, attempts)
        ceiling.complete(setOf(CardNetwork.VISA))
        second.join()

        c.onNumberChange(visaPan)
        advanceUntilIdle()
        assertEquals(listOf(CardNetwork.VISA), c.networks)
    }

    // A verdict belongs to the PAN it was resolved for. If the payer replaces the number and the
    // ceiling lands before the new verdict, applying the previous card's networks would refuse a good
    // card — or offer, and pay with, the wrong network.
    @Test
    fun aVerdictIsNeverAppliedToADifferentNumber() = runTest {
        val ceiling = CompletableDeferred<Set<CardNetwork>>()
        val c = CmpCardController(config(), scope = this)
        c.cardInfoResolver = fakeVault
        c.accountNetworksResolver = { ceiling.await() }

        launch { c.loadAccountNetworksIfNeeded() }
        runCurrent()
        c.onNumberChange(cbVisaPan) // vault resolves CB+Visa
        advanceUntilIdle()
        c.onNumberChange(mcPan) // replaced before the ceiling lands

        ceiling.complete(setOf(CardNetwork.MASTERCARD, CardNetwork.CB))
        advanceUntilIdle()

        // Mastercard is accepted by the ceiling: the card must be offered, NOT refused with the
        // previous card's CB+Visa verdict.
        assertEquals(listOf(CardNetwork.MASTERCARD), c.networks)
        assertNull(c.numberSlotErrorKey)
    }

    // The pending gate must cover the VAULT path too, not only local detection: on an account whose
    // gateway answers slower than the vault, the chips would otherwise appear and be withdrawn — the
    // exact symptom the pending state exists to prevent.
    @Test
    fun aVaultVerdictIsNotOfferedWhileTheCeilingIsStillPending() = runTest {
        val ceiling = CompletableDeferred<Set<CardNetwork>>()
        val c = CmpCardController(config(), scope = this)
        c.cardInfoResolver = fakeVault
        c.accountNetworksResolver = { ceiling.await() }

        launch { c.loadAccountNetworksIfNeeded() }
        runCurrent()
        c.onNumberChange(visaPan)
        advanceUntilIdle() // the vault has answered; the ceiling has not

        assertTrue(c.networks.isEmpty())
        assertNull(c.numberSlotErrorKey)

        ceiling.complete(setOf(CardNetwork.VISA))
        advanceUntilIdle()
        assertEquals(listOf(CardNetwork.VISA), c.networks)
    }

    // A ceiling landing after the payer picked a co-brand must not silently move the selection: the
    // network drives `payment_product`, so a CB flipped back to Visa changes what is charged.
    // A ceiling arriving while a co-brand is selected must not move the selection: the network drives
    // `payment_product`, so a CB silently flipped to Visa changes the scheme the payment is routed on.
    // Reachable without any edit: the first fetch failed, the screen re-appears, the retry lands.
    @Test
    fun aCeilingArrivingLaterKeepsThePayersExplicitCoBrandChoice() = runTest {
        var attempts = 0
        val c = CmpCardController(config(), scope = this)
        c.cardInfoResolver = fakeVault
        c.accountNetworksResolver = {
            attempts++
            if (attempts == 1) throw IllegalStateException("offline")
            setOf(CardNetwork.VISA, CardNetwork.CB)
        }

        c.loadAccountNetworksIfNeeded() // first appearance: fails → ceiling unavailable
        advanceUntilIdle()
        c.onNumberChange(cbVisaPan)
        advanceUntilIdle()
        assertEquals(listOf(CardNetwork.CB, CardNetwork.VISA), c.networks)

        c.selectNetwork(CardNetwork.VISA)
        c.selectNetwork(CardNetwork.CB)
        assertEquals(CardNetwork.CB, c.selectedNetwork)

        c.loadAccountNetworksIfNeeded() // second appearance: the retry lands, no edit in between
        advanceUntilIdle()

        assertEquals(listOf(CardNetwork.CB, CardNetwork.VISA), c.networks)
        assertEquals(CardNetwork.CB, c.selectedNetwork)
    }

    // An account contracted for no card refuses every card — and does not need the vault verdict to
    // say so, since no resolution could rescue it.
    @Test
    fun anAccountWithNoCardProductRefusesEverything() = runTest {
        val c = CmpCardController(config(), scope = this)
        c.cardInfoResolver = fakeVault
        c.accountNetworksResolver = { emptySet() }

        c.onNumberChange(visaPan)
        advanceUntilIdle()

        assertTrue(c.networks.isEmpty())
        assertEquals(CardEntryStringKey.ERROR_NETWORK_NOT_AUTHORIZED, c.numberSlotErrorKey)
        assertEquals(false, c.isNetworkAuthorized)
    }
}
