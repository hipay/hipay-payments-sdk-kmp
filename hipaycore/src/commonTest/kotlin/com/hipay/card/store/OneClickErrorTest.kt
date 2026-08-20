package com.hipay.card.store

import com.hipay.card.validation.CardEntryStringKey
import com.hipay.core.gateway.model.TransactionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OneClickErrorTest {

    private fun card(
        maskedPan: String = "411111xxxxxx1111",
        month: String = "12",
        year: String = "2029",
        token: String = "a".repeat(64),
    ) = SavedCard(
        token = token,
        maskedPan = maskedPan,
        network = "VISA",
        holder = "JANE DOE",
        expiryMonth = month,
        expiryYear = year,
    )

    // --- OneClickError value semantics ---

    @Test
    fun carriesTheMaskedIdentityAndReason() {
        val error = OneClickError(card(), OneClickErrorReason.DECLINED)
        assertEquals("411111xxxxxx1111", error.maskedPan)
        assertEquals("12", error.expiryMonth)
        assertEquals("2029", error.expiryYear)
        assertEquals(OneClickErrorReason.DECLINED, error.reason)
    }

    @Test
    fun matchesTheSameCardAcrossAReloadAndCosmeticRemask() {
        val error = OneClickError(card(), OneClickErrorReason.DECLINED)
        // A fresh store read yields a new instance; a cosmetic re-mask (case) OR a renewed expiry
        // must still resolve to the same card — the store's dedup identity is the masked PAN only.
        assertTrue(error.matches(card(token = "b".repeat(64))))
        assertTrue(error.matches(card(maskedPan = "411111XXXXXX1111", year = "29")))
        assertTrue(error.matches(card(month = "11", year = "2031"))) // renewed expiry, same PAN → same card
        assertFalse(error.matches(card(maskedPan = "555555xxxxxx4444"))) // different PAN → different card
    }

    @Test
    fun valueEqualOnCardIdentityPlusReason() {
        assertEquals(
            OneClickError(card(), OneClickErrorReason.GENERIC),
            OneClickError(card(token = "b".repeat(64)), OneClickErrorReason.GENERIC),
        )
        assertNotEquals(
            OneClickError(card(), OneClickErrorReason.GENERIC),
            OneClickError(card(), OneClickErrorReason.DECLINED),
        )
    }

    @Test
    fun neverExposesTheToken() {
        val error = OneClickError(card(), OneClickErrorReason.TOKEN_INVALID)
        assertFalse(error.toString().contains("a".repeat(64)))
    }

    // --- messageKey ---

    @Test
    fun everyReasonResolvesToItsOwnKey() {
        assertEquals(CardEntryStringKey.ERROR_ONE_CLICK_DECLINED, OneClickErrorReason.DECLINED.messageKey())
        assertEquals(CardEntryStringKey.ERROR_ONE_CLICK_CARD_REMOVED, OneClickErrorReason.TOKEN_INVALID.messageKey())
        assertEquals(CardEntryStringKey.ERROR_ONE_CLICK_3DS, OneClickErrorReason.THREE_DS_FAILED.messageKey())
        assertEquals(CardEntryStringKey.ERROR_ONE_CLICK_EXPIRED, OneClickErrorReason.EXPIRED.messageKey())
        assertEquals(CardEntryStringKey.ERROR_ONE_CLICK_GENERIC, OneClickErrorReason.GENERIC.messageKey())
    }

    // --- oneClickErrorSurface (the single rendering-policy point) ---

    @Test
    fun surfacesInlineWhileTheCardIsStillListed() {
        val error = OneClickError(card(), OneClickErrorReason.DECLINED)
        assertEquals(
            OneClickErrorSurface.INLINE_CARD,
            oneClickErrorSurface(error, listOf(card(token = "b".repeat(64)))),
        )
    }

    @Test
    fun surfacesAtSectionLevelWhenThePurgedCardIsGone() {
        val error = OneClickError(card(), OneClickErrorReason.TOKEN_INVALID)
        assertEquals(OneClickErrorSurface.SECTION, oneClickErrorSurface(error, emptyList()))
        // Fail-soft purge edge: the invalid card still listed → anchor inline on it.
        assertEquals(OneClickErrorSurface.INLINE_CARD, oneClickErrorSurface(error, listOf(card())))
    }

    @Test
    fun surfacesNothingWithoutAnErrorOrForAVanishedNonPurgedCard() {
        assertNull(oneClickErrorSurface(null, listOf(card())))
        // The affected card vanished for another cause (e.g. the payer deleted it):
        // a "declined" notice with no cell to anchor to would be noise.
        val error = OneClickError(card(), OneClickErrorReason.DECLINED)
        assertNull(oneClickErrorSurface(error, emptyList()))
        assertNull(oneClickErrorSurface(error, listOf(card(maskedPan = "555555xxxxxx4444"))))
    }

    // --- oneClickReasonForOutcome (transaction path) ---

    @Test
    fun completedOutcomeRaisesNoError() {
        for (challenged in listOf(true, false)) {
            assertNull(oneClickReasonForOutcome(TransactionState.COMPLETED, challenged, null, false))
        }
    }

    @Test
    fun pendingOutcomeIsASoftPendingHint() {
        // Indeterminate (verification pending / server unreachable): not a failure, but surfaced
        // as a soft hint so the payer is not left on a silently-cleared spinner.
        for (challenged in listOf(true, false)) {
            assertEquals(
                OneClickErrorReason.PENDING,
                oneClickReasonForOutcome(TransactionState.PENDING, challenged, null, false),
            )
        }
    }

    @Test
    fun declinedWithoutAChallengeIsAPlainDecline() {
        assertEquals(
            OneClickErrorReason.DECLINED,
            oneClickReasonForOutcome(TransactionState.DECLINED, challenged = false, authenticationStatus = null, cardExpiredAtAttempt = false),
        )
    }

    @Test
    fun errorStateWithoutAChallengeIsGeneric() {
        assertEquals(
            OneClickErrorReason.GENERIC,
            oneClickReasonForOutcome(TransactionState.ERROR, challenged = false, authenticationStatus = null, cardExpiredAtAttempt = false),
        )
    }

    @Test
    fun abandonedChallengeStaysForwardingAndIsAThreeDSFailure() {
        // Server-confirmed still-FORWARDING after a presented challenge = genuine abandon.
        assertEquals(
            OneClickErrorReason.THREE_DS_FAILED,
            oneClickReasonForOutcome(TransactionState.FORWARDING, challenged = true, authenticationStatus = null, cardExpiredAtAttempt = false),
        )
        // Without a presented challenge, FORWARDING is the host's manual-3DS hand-back — not a failure.
        assertNull(oneClickReasonForOutcome(TransactionState.FORWARDING, challenged = false, authenticationStatus = null, cardExpiredAtAttempt = false))
    }

    @Test
    fun failedChallengeOutcomesAreThreeDSFailures() {
        assertEquals(
            OneClickErrorReason.THREE_DS_FAILED,
            oneClickReasonForOutcome(TransactionState.DECLINED, challenged = true, authenticationStatus = null, cardExpiredAtAttempt = false),
        )
        assertEquals(
            OneClickErrorReason.THREE_DS_FAILED,
            oneClickReasonForOutcome(TransactionState.ERROR, challenged = true, authenticationStatus = "N", cardExpiredAtAttempt = false),
        )
    }

    @Test
    fun declineAfterASuccessfulChallengeIsThePaymentsNotThreeDSs() {
        // authenticationStatus Y (authenticated) / A (attempted): the challenge itself
        // succeeded — the decline is a payment decline, not a 3DS failure.
        assertEquals(
            OneClickErrorReason.DECLINED,
            oneClickReasonForOutcome(TransactionState.DECLINED, challenged = true, authenticationStatus = "Y", cardExpiredAtAttempt = false),
        )
        assertEquals(
            OneClickErrorReason.DECLINED,
            oneClickReasonForOutcome(TransactionState.DECLINED, challenged = true, authenticationStatus = " a ", cardExpiredAtAttempt = false),
        )
    }

    @Test
    fun declineOfACardExpiredAtAttemptTimeIsExpired() {
        assertEquals(
            OneClickErrorReason.EXPIRED,
            oneClickReasonForOutcome(TransactionState.DECLINED, challenged = false, authenticationStatus = null, cardExpiredAtAttempt = true),
        )
        // The refinement applies to confirmed declines only — an ERROR state stays generic.
        assertEquals(
            OneClickErrorReason.GENERIC,
            oneClickReasonForOutcome(TransactionState.ERROR, challenged = false, authenticationStatus = null, cardExpiredAtAttempt = true),
        )
    }

    // --- savedCardExpiredAt ---

    @Test
    fun expiryComparisonIsMonthGranularAndInclusive() {
        val now = YearMonth(2029, 6)
        assertTrue(savedCardExpiredAt(card(month = "5", year = "2029"), now))
        assertFalse(savedCardExpiredAt(card(month = "6", year = "2029"), now)) // expires AFTER its month
        assertFalse(savedCardExpiredAt(card(month = "1", year = "2030"), now))
        assertTrue(savedCardExpiredAt(card(month = "12", year = "28"), now)) // 2-digit year normalised
    }

    @Test
    fun implausibleClockOrUnparseableExpiryNeverClaimsExpired() {
        assertFalse(savedCardExpiredAt(card(month = "1", year = "2020"), YearMonth(1970, 1)))
        assertFalse(savedCardExpiredAt(card(month = "1", year = "2020"), YearMonth(2029, 13)))
        assertFalse(savedCardExpiredAt(card(month = "xx", year = "2020"), YearMonth(2029, 6)))
        assertFalse(savedCardExpiredAt(card(month = "1", year = ""), YearMonth(2029, 6)))
    }
}
