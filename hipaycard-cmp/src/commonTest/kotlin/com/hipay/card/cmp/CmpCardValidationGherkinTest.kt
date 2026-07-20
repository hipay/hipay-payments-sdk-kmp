package com.hipay.card.cmp

import com.hipay.card.model.CardInfo
import com.hipay.card.validation.CardEntryStringKey
import com.hipay.card.validation.CardNetwork
import com.hipay.card.validation.CardNetworks
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Field-validation Gherkin scenarios (PI-6048), verbatim in French above each test.
 * Controller-level over the shared commonMain contract — no network (empty backend
 * verdict through the resolver seam), no UI. The blur gate is exercised through
 * [CmpCardController.markBlurred] — the renderer wires it via `Modifier.blurring`.
 *
 * NOTE — the "premier chiffre" rule (2026-07-17 decision): the immediate error fires
 * when the digit PREFIX can no longer match any supported network (progressive check,
 * all SDK networks incl. Bancontact/Maestro), and is NOT blur-gated. It is distinct
 * from the contractual backend-verdict "Type de carte non autorisé" error
 * ([CmpAllowedNetworksGherkinTest]), which stays untouched.
 */
class CmpCardValidationGherkinTest {

    private fun controller(allowed: List<CardNetwork> = emptyList()) =
        CmpCardController(
            HiPayConfig(username = "u", password = "p", environment = Environment.STAGE),
            allowed,
            scope = CoroutineScope(Dispatchers.Unconfined),
        ).apply { cardInfoResolver = { CardInfo() } }

    // Scénario : Numéro de carte invalide
    //   Étant donné que le champ numéro de carte contient une valeur invalide ou incomplète
    //   Quand l'utilisateur quitte le champ
    //   Alors le champ passe en état erreur
    //   Et le message "Numéro de carte invalide" est affiché
    @Test
    fun invalidNumberErrorsOnBlur() {
        val c = controller()
        c.onNumberChange("4111111111111112") // 16 digits, Luhn fails
        assertNull(c.numberSlotErrorKey) // nothing while the field still has focus
        c.markBlurred(CmpCardController.Field.NUMBER)
        assertEquals(CardEntryStringKey.ERROR_INVALID_NUMBER, c.numberSlotErrorKey)
    }

    // Scénario : Vérification des patterns à la saisie du premier numéro de carte
    //   Si le numéro ne respecte pas les patterns des réseaux supportés, l'erreur
    //   "Numéro de carte invalide" est affichée directement à la saisie — sans blur.
    @Test
    fun impossiblePrefixErrorsImmediatelyWhileTyping() {
        val c = controller()
        c.onNumberChange("1") // no supported network starts with 1
        assertEquals(CardEntryStringKey.ERROR_INVALID_NUMBER, c.numberSlotErrorKey) // no blur needed
        c.onNumberChange("") // clearing the digit clears the error
        assertNull(c.numberSlotErrorKey)
        c.onNumberChange("3") // could still become 34/37 (Amex) → no premature flag
        assertNull(c.numberSlotErrorKey)
        c.onNumberChange("30") // neither 34 nor 37 — unrepairable from the 2nd digit
        assertEquals(CardEntryStringKey.ERROR_INVALID_NUMBER, c.numberSlotErrorKey)
    }

    // Scénario : Type de carte non autorisé — détection locale NON AMBIGUË (refinement 2026-07-20)
    //   Étant donné que seul le réseau "cb" est autorisé
    //   Quand je saisis un préfixe Amex (34/37), qui ne peut jamais être une co-marque CB
    //   Alors "Type de carte non autorisé" s'affiche immédiatement pendant la saisie (sans blur)
    @Test
    fun unambiguousDisallowedNetworkErrorsImmediatelyWhileTyping() {
        val c = controller(allowed = listOf(CardNetwork.CB))
        c.onNumberChange("3714") // Amex prefix — Amex carries no CB co-brand
        assertEquals(CardEntryStringKey.ERROR_NETWORK_NOT_AUTHORIZED, c.numberSlotErrorKey) // no blur
    }

    // Scénario : Cas AMBIGU — Visa détecté avec seul "cb" autorisé (contrat 2026-07-17 préservé)
    //   CB peut chevaucher un BIN Visa (une cobrandée CB+Visa est locale­ment "visa") → aucune
    //   erreur réseau pendant la saisie ; seul "Numéro de carte incomplet" au blur. Le verdict
    //   BIN backend reste l'autorité.
    @Test
    fun ambiguousCoBrandStaysQuietUntilBlurThenIncomplete() {
        val c = controller(allowed = listOf(CardNetwork.CB))
        c.onNumberChange("4111") // locally Visa — could be a CB+Visa co-brand
        assertNull(c.numberSlotErrorKey) // no network error, no pattern error, incomplete is blur-gated
        c.markBlurred(CmpCardController.Field.NUMBER)
        assertEquals(CardEntryStringKey.ERROR_INCOMPLETE_NUMBER, c.numberSlotErrorKey)
    }

    // Scénario : Date d'expiration dans le passé
    //   Étant donné que le champ date d'expiration contient une date antérieure à la date courante
    //   Quand l'utilisateur quitte le champ
    //   Alors le champ passe en état erreur et le message "Date expirée" est affiché
    @Test
    fun pastExpiryErrorsOnBlur() {
        val c = controller()
        c.onExpiryChange("12" + ((currentYear() - 1) % 100).toString().padStart(2, '0'))
        assertNull(c.expiryErrorKey)
        c.markBlurred(CmpCardController.Field.EXPIRY)
        assertEquals(CardEntryStringKey.ERROR_EXPIRED, c.expiryErrorKey)
    }

    // Scénario : Année de la date d'expiration max 15 ans dans le futur
    @Test
    fun expiryBeyondFifteenYearsErrorsOnBlur() {
        val c = controller()
        c.onExpiryChange("12" + ((currentYear() + 16) % 100).toString().padStart(2, '0'))
        c.markBlurred(CmpCardController.Field.EXPIRY)
        assertEquals(CardEntryStringKey.ERROR_INVALID_EXPIRY, c.expiryErrorKey)
        // ...while +15 is still accepted.
        c.onExpiryChange("12" + ((currentYear() + 15) % 100).toString().padStart(2, '0'))
        assertNull(c.expiryErrorKey)
    }

    // Scénario : Formattage auto du champs date
    //   Le contrôleur stocke les chiffres bruts "MMYY" ; le "/" est rendu par la
    //   VisualTransformation partagée (source unique, story 11.8).
    @Test
    fun expiryStoresRawDigitsForTheAutoFormatter() {
        val c = controller()
        c.onExpiryChange("12/30") // even a pasted "/" is stripped
        assertEquals("1230", c.expiry)
        assertEquals("12/30", com.hipay.card.validation.formatExpiryWithOffsets(c.expiry).text)
    }

    // Plan du scénario : CVV invalide selon le réseau
    //   | réseau     | saisie |   (cb/visa/mastercard : 3 chiffres — amex : 4)
    //   | visa       | "12"   |
    //   | mastercard | "12"   |
    //   | amex       | "123"  |
    @Test
    fun tooShortCvcErrorsOnBlurPerNetwork() {
        val visa = controller()
        visa.onNumberChange("4111111111111111")
        visa.onCvcChange("12")
        assertNull(visa.cvcErrorKey)
        visa.markBlurred(CmpCardController.Field.CVC)
        assertEquals(CardEntryStringKey.ERROR_INCOMPLETE_CVV, visa.cvcErrorKey)

        val mc = controller()
        mc.onNumberChange("5555555555554444")
        mc.onCvcChange("12")
        mc.markBlurred(CmpCardController.Field.CVC)
        assertEquals(CardEntryStringKey.ERROR_INCOMPLETE_CVV, mc.cvcErrorKey)

        val amex = controller()
        amex.onNumberChange("371449635398431")
        amex.onCvcChange("123") // complete for visa, one short for amex
        amex.markBlurred(CmpCardController.Field.CVC)
        assertEquals(CardEntryStringKey.ERROR_INCOMPLETE_CVV, amex.cvcErrorKey)
        amex.onCvcChange("1234")
        assertNull(amex.cvcErrorKey)
    }

    // Scénario : CVV désactivé pour Bancontact
    //   Étant donné que le réseau sélectionné est "bancontact"
    //   Alors le champ CVV est désactivé et non saisissable
    //   Et aucun message d'erreur CVV n'est affiché
    @Test
    fun bancontactDisablesCvcWithoutError() {
        val c = controller()
        c.onNumberChange("6703") // local BCMC prefix
        assertEquals(CardNetwork.BCMC, c.network)
        assertFalse(c.isCvcRequired) // the renderer disables the field on this flag
        c.onCvcChange("123") // a typed CVC is not kept when not required
        c.markBlurred(CmpCardController.Field.CVC)
        assertNull(c.cvcErrorKey)
    }

    // Scénario : Nom du porteur trop court
    //   Étant donné que le champ nom du porteur contient moins de 3 caractères
    //   Quand l'utilisateur quitte le champ
    //   Alors le champ passe en état erreur et le message "Minimum 3 caractères" est affiché
    @Test
    fun holderUnderThreeCharsErrorsOnBlur() {
        val c = controller()
        c.onHolderChange("AB")
        assertNull(c.holderErrorKey)
        c.markBlurred(CmpCardController.Field.HOLDER)
        assertEquals(CardEntryStringKey.ERROR_HOLDER_TOO_SHORT, c.holderErrorKey)
        c.onHolderChange("ABC")
        assertNull(c.holderErrorKey)
    }

    // Scénario : Nom du porteur bloqué à 60 caractères
    //   Quand l'utilisateur saisit un 61ème caractère dans le champ nom du porteur
    //   Alors le caractère supplémentaire n'est pas accepté
    @Test
    fun holderBlocksTheSixtyFirstCharacter() {
        val c = controller()
        c.onHolderChange("A".repeat(61))
        assertEquals(60, c.holder.length)
    }

    // Scénario : Pas plus de 8 digits dans le champs card holder
    @Test
    fun holderKeepsAtMostEightDigits() {
        val c = controller()
        c.onHolderChange("JEAN 123456789") // 9 digits typed
        assertEquals("JEAN 12345678", c.holder) // the 9th digit is dropped
    }

    // Scénario : Pas de lettres dans le champs numéro de carte
    // Scénario : Pas de lettres dans le champs date d'expiration
    // Scénario : Pas de lettres dans le champs CVV
    @Test
    fun lettersAreRejectedInNumberExpiryAndCvc() {
        val c = controller()
        c.onNumberChange("4a1b1c1")
        assertEquals("4111", c.cardNumber)
        c.onExpiryChange("1a2b3c0")
        assertEquals("1230", c.expiry)
        c.onCvcChange("1x2y3")
        assertEquals("123", c.cvc)
    }

    // Scénario : Formattage des numéros de cartes en fonction du réseau
    //   Groupes de 4 chiffres, sauf Amex : 4-6-5 (rendu par la VisualTransformation
    //   partagée sur les chiffres bruts stockés).
    @Test
    fun numberFormatsPerNetwork() {
        val c = controller()
        c.onNumberChange("4111111111111111")
        assertEquals("4111 1111 1111 1111", CardNetworks.format(c.cardNumber))
        c.onNumberChange("371449635398431")
        assertEquals("3714 496353 98431", CardNetworks.format(c.cardNumber))
    }
}
