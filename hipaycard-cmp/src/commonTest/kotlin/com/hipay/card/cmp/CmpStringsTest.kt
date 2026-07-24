package com.hipay.card.cmp

import com.hipay.card.validation.CardEntryStringKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locale-keyed string resolution: catalog completeness, verbatim parity with the native
 * catalogs (spot checks), English golden output, tag normalization, and override-vs-system
 * precedence. Runs on the JVM AND as Kotlin/Native on the iOS simulator target — the iOS run
 * is what validates the mechanism on the CMP-iOS side.
 */
class CmpStringsTest {

    private val languages = listOf("en", "fr", "it")

    @Test
    fun every_key_resolves_non_empty_in_every_language() {
        for (lang in languages) {
            for (key in CardEntryStringKey.entries) {
                assertTrue(cmpString(key, lang).isNotBlank(), "empty value: $key [$lang]")
            }
        }
    }

    @Test
    fun translated_keys_differ_from_english() {
        // Placeholders like the number/CVV are identical across languages by design; label and
        // message keys must actually be translated, not English copies.
        val translated = listOf(
            CardEntryStringKey.LABEL_HOLDER,
            CardEntryStringKey.LABEL_EXPIRY,
            CardEntryStringKey.CVV_TOOLTIP,
            CardEntryStringKey.ERROR_INVALID_NUMBER,
            CardEntryStringKey.LABEL_SAVED_CARDS,
            CardEntryStringKey.ERROR_ONE_CLICK_DECLINED,
        )
        for (key in translated) {
            val en = cmpString(key, "en")
            assertTrue(cmpString(key, "fr") != en, "not translated to French: $key")
            assertTrue(cmpString(key, "it") != en, "not translated to Italian: $key")
        }
    }

    @Test
    fun english_resolution_matches_the_previous_english_only_output_byte_for_byte() {
        val expected = mapOf(
            CardEntryStringKey.LABEL_HOLDER to "Cardholder name",
            CardEntryStringKey.LABEL_NUMBER to "Card number",
            CardEntryStringKey.LABEL_EXPIRY to "Expiry date",
            CardEntryStringKey.LABEL_CVV to "Security code",
            CardEntryStringKey.PLACEHOLDER_HOLDER to "Name on card",
            CardEntryStringKey.PLACEHOLDER_NUMBER to "1234 5678 9012 3456",
            CardEntryStringKey.PLACEHOLDER_EXPIRY to "MM/YY",
            CardEntryStringKey.PLACEHOLDER_CVV to "CVV",
            CardEntryStringKey.CVV_OPTIONAL to "Optional",
            CardEntryStringKey.CVV_TOOLTIP to "Enter the CVV or security code on your card.",
            CardEntryStringKey.ERROR_INVALID_NUMBER to "Invalid card number",
            CardEntryStringKey.ERROR_INCOMPLETE_NUMBER to "Card number is incomplete",
            CardEntryStringKey.ERROR_INVALID_EXPIRY to "Invalid expiry date",
            CardEntryStringKey.ERROR_EXPIRED to "This card has expired",
            CardEntryStringKey.ERROR_INVALID_CVV to "Invalid security code",
            CardEntryStringKey.ERROR_INCOMPLETE_CVV to "Security code is incomplete",
            CardEntryStringKey.ERROR_HOLDER_TOO_LONG to "Cardholder name is too long",
            CardEntryStringKey.ERROR_HOLDER_TOO_SHORT to "Minimum 3 characters",
            CardEntryStringKey.ERROR_NETWORK_NOT_AUTHORIZED to "Card type not allowed",
            CardEntryStringKey.LABEL_SAVED_CARDS to "Saved cards",
            CardEntryStringKey.LABEL_NEW_CARD to "New card",
            CardEntryStringKey.LABEL_SAVE_CARD to "Save this card",
            CardEntryStringKey.CONSENT_SAVE_CARD to
                "Save this card for faster checkout. You can remove it at any time.",
            CardEntryStringKey.A11Y_SAVED_CARD to "%1\$s finishing %2\$s, expires %3\$s",
            CardEntryStringKey.A11Y_EXPANDED to "expanded",
            CardEntryStringKey.A11Y_COLLAPSED to "collapsed",
            CardEntryStringKey.LABEL_SHOW_MORE to "Show more",
            CardEntryStringKey.LABEL_DELETE_CARD to "Delete card",
            CardEntryStringKey.CONFIRM_DELETE_CARD to
                "Remove this saved card? You can save it again next time you pay.",
            CardEntryStringKey.LABEL_CANCEL to "Cancel",
            CardEntryStringKey.ERROR_ONE_CLICK_DECLINED to
                "Payment declined. Try another card or enter a new one.",
            CardEntryStringKey.ERROR_ONE_CLICK_CARD_REMOVED to
                "This card can no longer be used and was removed. Pay with a new card.",
            CardEntryStringKey.ERROR_ONE_CLICK_3DS to
                "Authentication failed or was cancelled. Try again or use another card.",
            CardEntryStringKey.ERROR_ONE_CLICK_EXPIRED to
                "This card has expired. Pay with another card.",
            CardEntryStringKey.ERROR_ONE_CLICK_GENERIC to
                "The payment could not be completed. Try again or use another card.",
            CardEntryStringKey.ERROR_ONE_CLICK_PENDING to
                "Your payment is still being confirmed. Please wait a moment before trying again.",
        )
        assertEquals(CardEntryStringKey.entries.toSet(), expected.keys, "golden map out of sync")
        for ((key, value) in expected) {
            assertEquals(value, cmpString(key, "en"), key.name)
        }
    }

    @Test
    fun french_spot_checks_match_the_native_catalogs_verbatim() {
        assertEquals("Nom du titulaire", cmpString(CardEntryStringKey.LABEL_HOLDER, "fr"))
        assertEquals("Date d'expiration", cmpString(CardEntryStringKey.LABEL_EXPIRY, "fr"))
        assertEquals("MM/AA", cmpString(CardEntryStringKey.PLACEHOLDER_EXPIRY, "fr"))
        assertEquals("Numéro de carte invalide", cmpString(CardEntryStringKey.ERROR_INVALID_NUMBER, "fr"))
        assertEquals("Cartes enregistrées", cmpString(CardEntryStringKey.LABEL_SAVED_CARDS, "fr"))
        assertEquals(
            "Paiement refusé. Essayez une autre carte ou saisissez-en une nouvelle.",
            cmpString(CardEntryStringKey.ERROR_ONE_CLICK_DECLINED, "fr"),
        )
    }

    @Test
    fun italian_spot_checks_match_the_native_catalogs_verbatim() {
        assertEquals("Nome del titolare", cmpString(CardEntryStringKey.LABEL_HOLDER, "it"))
        assertEquals("Data di scadenza", cmpString(CardEntryStringKey.LABEL_EXPIRY, "it"))
        assertEquals("MM/AA", cmpString(CardEntryStringKey.PLACEHOLDER_EXPIRY, "it"))
        assertEquals("Questa carta è scaduta", cmpString(CardEntryStringKey.ERROR_EXPIRED, "it"))
        assertEquals("Carte salvate", cmpString(CardEntryStringKey.LABEL_SAVED_CARDS, "it"))
        assertEquals(
            "Pagamento rifiutato. Prova un'altra carta o inseriscine una nuova.",
            cmpString(CardEntryStringKey.ERROR_ONE_CLICK_DECLINED, "it"),
        )
    }

    @Test
    fun language_tags_normalize_case_region_and_separator() {
        assertEquals("fr", cardEntryLanguage("fr"))
        assertEquals("fr", cardEntryLanguage("Fr"))
        assertEquals("fr", cardEntryLanguage("fr-CA"))
        assertEquals("fr", cardEntryLanguage("fr_CH"))
        assertEquals("it", cardEntryLanguage("it"))
        assertEquals("it", cardEntryLanguage("IT"))
        assertEquals("it", cardEntryLanguage("it-CH"))
        assertEquals("en", cardEntryLanguage("en-GB"))
        assertEquals("en", cardEntryLanguage(" en "))
    }

    @Test
    fun unsupported_or_absent_languages_fall_back_to_english() {
        for (tag in listOf("de", "es", "pt-BR", "", null)) {
            assertEquals("en", cardEntryLanguage(tag), "tag: $tag")
        }
        assertEquals("Card number", cmpString(CardEntryStringKey.LABEL_NUMBER, "de"))
        assertEquals("Card number", cmpString(CardEntryStringKey.LABEL_NUMBER, null))
    }

    @Test
    fun override_wins_and_null_or_blank_falls_back_to_the_system_locale() {
        assertEquals("fr", resolvedCardEntryLanguage("fr"))
        assertEquals("it", resolvedCardEntryLanguage("it-CH"))
        assertEquals("en", resolvedCardEntryLanguage("de")) // unsupported override → EN, not system
        val system = firstSupportedLanguage(systemLocaleLanguages())
        assertEquals(system, resolvedCardEntryLanguage(null))
        assertEquals(system, resolvedCardEntryLanguage("   "))
    }

    @Test
    fun system_resolution_walks_the_preference_list_for_the_first_supported_language() {
        // A device preferring an unsupported language first must land on the next supported
        // one — like native resource resolution — not on the English fallback.
        assertEquals("fr", firstSupportedLanguage(listOf("de-DE", "fr-CA", "en")))
        assertEquals("it", firstSupportedLanguage(listOf("pt-BR", "it-CH", "fr")))
        assertEquals("en", firstSupportedLanguage(listOf("en-GB", "fr")))
        assertEquals("en", firstSupportedLanguage(listOf("de", "es")))
        assertEquals("en", firstSupportedLanguage(emptyList()))
    }

    @Test
    fun a11y_saved_card_template_formats_positionally_in_each_language() {
        assertEquals(
            "Visa finishing 1111, expires 12/2030",
            cmpFormat(cmpString(CardEntryStringKey.A11Y_SAVED_CARD, "en"), "Visa", "1111", "12/2030"),
        )
        assertEquals(
            "Visa se terminant par 1111, expire 12/2030",
            cmpFormat(cmpString(CardEntryStringKey.A11Y_SAVED_CARD, "fr"), "Visa", "1111", "12/2030"),
        )
        assertEquals(
            "Visa che termina con 1111, scade 12/2030",
            cmpFormat(cmpString(CardEntryStringKey.A11Y_SAVED_CARD, "it"), "Visa", "1111", "12/2030"),
        )
    }
}
