package com.hipay.card.cmp

import com.hipay.card.model.CardInfo
import com.hipay.card.validation.CardEntryStringKey
import com.hipay.card.validation.CardNetwork
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
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
}
