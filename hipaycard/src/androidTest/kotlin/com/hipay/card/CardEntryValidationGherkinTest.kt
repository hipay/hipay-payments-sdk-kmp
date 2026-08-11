package com.hipay.card

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hipay.core.Environment
import com.hipay.core.HiPayConfig
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

/**
 * Field-validation Gherkin scenarios (PI-6048), verbatim in French above each test.
 * NETWORK-FREE: partial (non-Luhn) prefixes only, so the backend resolver never
 * fires. English locale pinned for deterministic string assertions (7.4 convention).
 * The invalid BORDER (invalidTextColor, blur-gated) is not asserted here — border
 * color is not exposed through semantics; it renders iff the same error key that
 * drives the asserted inline message is non-null (see HiPayStyledField.isError).
 */
@RunWith(AndroidJUnit4::class)
class CardEntryValidationGherkinTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun controller(allowed: List<HiPayCardNetwork> = emptyList()) =
        HiPayCardEntryController(
            HiPayConfig("test-user", "test-pass", Environment.STAGE), allowedNetworks = allowed,
        )

    private fun robot(allowed: List<HiPayCardNetwork> = emptyList()): CardEntryRobot {
        val robot = CardEntryRobot(composeRule)
        robot.setContent { HiPayCardEntry(controller(allowed), localeOverride = "en") }
        return robot
    }

    // Scénario : Vérification des patterns à la saisie du premier numéro de carte
    //   Si le numéro ne respecte pas les patterns des réseaux supportés, l'erreur
    //   "Numéro de carte invalide" est affichée directement à la saisie — sans blur.
    @Test
    fun impossiblePrefixErrorsImmediatelyWhileTyping() {
        val robot = robot()
        robot.type(HiPayCardEntryTags.NUMBER, "1") // no supported network starts with 1
        // No blur: the error is already showing.
        robot.assertTagExists(HiPayCardEntryTags.error("number"))
        robot.assertTextShown("Invalid card number")
    }

    // Scénario : Type de carte non autorisé — détection locale NON AMBIGUË (refinement 2026-07-20)
    //   Seul "cb" autorisé ; un préfixe Amex ne peut jamais être une co-marque CB
    //   → "Type de carte non autorisé" s'affiche immédiatement pendant la saisie (sans blur).
    @Test
    fun unambiguousDisallowedNetworkErrorsImmediately() {
        val robot = robot(allowed = listOf(HiPayCardNetwork.CB))
        robot.type(HiPayCardEntryTags.NUMBER, "3714") // Amex prefix, no focus change
        robot.assertTagExists(HiPayCardEntryTags.error("number"))
        robot.assertTextShown("Card type not allowed")
    }

    // Scénario : Cas AMBIGU — Visa détecté avec seul "cb" autorisé (contrat 2026-07-17 préservé)
    //   Une cobrandée CB+Visa est localement "visa" → aucune erreur réseau pendant la saisie ;
    //   seul "Numéro de carte incomplet" au blur.
    @Test
    fun ambiguousCoBrandStaysQuietUntilBlur() {
        val robot = robot(allowed = listOf(HiPayCardNetwork.CB))
        robot.type(HiPayCardEntryTags.NUMBER, "4111") // locally Visa — could be CB+Visa
        robot.assertTagAbsent(HiPayCardEntryTags.error("number")) // nothing while focused
        robot.focus(HiPayCardEntryTags.HOLDER) // blur
        robot.assertTagExists(HiPayCardEntryTags.error("number"))
        robot.assertTextShown("Card number is incomplete")
    }

    // Scénario : Date d'expiration dans le passé
    //   Quand l'utilisateur quitte le champ, le champ passe en état erreur
    //   et le message "Date expirée" est affiché.
    @Test
    fun pastExpiryErrorsOnBlur() {
        val robot = robot()
        robot.type(HiPayCardEntryTags.EXPIRY, "122") // still incomplete — no error yet
        robot.assertTagAbsent(HiPayCardEntryTags.error("expiry"))
        // Completing 12/20 (past) auto-advances the focus to the CVC — that IS the blur.
        robot.type(HiPayCardEntryTags.EXPIRY, "0")
        robot.assertTagExists(HiPayCardEntryTags.error("expiry"))
        robot.assertTextShown("This card has expired")
    }

    // Scénario : Année de la date d'expiration max 15 ans dans le futur
    @Test
    fun expiryBeyondFifteenYearsErrorsOnBlur() {
        val robot = robot()
        val yy = (Calendar.getInstance().get(Calendar.YEAR) + 16) % 100
        robot.type(HiPayCardEntryTags.EXPIRY, "12" + yy.toString().padStart(2, '0'))
        robot.focus(HiPayCardEntryTags.HOLDER)
        robot.assertTagExists(HiPayCardEntryTags.error("expiry"))
        robot.assertTextShown("Invalid expiry date")
    }

    // Plan du scénario : CVV invalide selon le réseau — | visa | "12" |
    //   (cb/mastercard idem 3 chiffres, amex 4 : règle partagée pinnée en commonTest)
    @Test
    fun tooShortCvcErrorsOnBlur() {
        val robot = robot()
        robot.type(HiPayCardEntryTags.NUMBER, "4111") // visa prefix → CVC = 3
        robot.type(HiPayCardEntryTags.CVC, "12")
        robot.assertTagAbsent(HiPayCardEntryTags.error("cvc"))
        robot.focus(HiPayCardEntryTags.HOLDER) // blur the CVC field
        robot.assertTagExists(HiPayCardEntryTags.error("cvc"))
        robot.assertTextShown("CVV is incomplete")
    }

    // Scénario : CVV désactivé pour Bancontact
    //   Le champ CVV est désactivé et non saisissable, aucun message d'erreur CVV.
    @Test
    fun bancontactDisablesCvcWithoutError() {
        val robot = robot()
        robot.type(HiPayCardEntryTags.NUMBER, "6703") // local BCMC prefix (partial — no backend)
        robot.assertEnabled(HiPayCardEntryTags.CVC, enabled = false)
        robot.focus(HiPayCardEntryTags.HOLDER)
        robot.assertTagAbsent(HiPayCardEntryTags.error("cvc"))
    }

    // Scénario : Nom du porteur trop court
    //   Moins de 3 caractères → au blur, "Minimum 3 caractères".
    @Test
    fun holderUnderThreeCharsErrorsOnBlur() {
        val robot = robot()
        robot.type(HiPayCardEntryTags.HOLDER, "ab")
        robot.assertTagAbsent(HiPayCardEntryTags.error("holder"))
        robot.focus(HiPayCardEntryTags.NUMBER) // blur the holder field
        robot.assertTagExists(HiPayCardEntryTags.error("holder"))
        robot.assertTextShown("Minimum 3 characters")
    }

    // Scénario : Nom du porteur bloqué à 60 caractères
    //   Le 61ème caractère n'est pas accepté.
    @Test
    fun holderBlocksTheSixtyFirstCharacter() {
        val robot = robot()
        robot.type(HiPayCardEntryTags.HOLDER, "a".repeat(61))
        robot.assertText(HiPayCardEntryTags.HOLDER, "A".repeat(60)) // uppercased, capped
    }

    // Scénario : Pas plus de 8 digits dans le champs card holder
    @Test
    fun holderKeepsAtMostEightDigits() {
        val robot = robot()
        robot.type(HiPayCardEntryTags.HOLDER, "jean 123456789") // 9 digits typed
        robot.assertText(HiPayCardEntryTags.HOLDER, "JEAN 12345678") // 9th digit dropped
    }

    // Scénario : Pas de lettres dans le champs numéro de carte
    // Scénario : Pas de lettres dans le champs date d'expiration
    // Scénario : Pas de lettres dans le champs CVV
    @Test
    fun lettersAreRejectedInNumberExpiryAndCvc() {
        val robot = robot()
        robot.type(HiPayCardEntryTags.NUMBER, "4a1b")
        robot.assertText(HiPayCardEntryTags.NUMBER, "41")
        robot.type(HiPayCardEntryTags.EXPIRY, "1a2b30")
        robot.assertText(HiPayCardEntryTags.EXPIRY, "1230")
        robot.type(HiPayCardEntryTags.CVC, "1x2")
        robot.assertText(HiPayCardEntryTags.CVC, "12")
    }
}
