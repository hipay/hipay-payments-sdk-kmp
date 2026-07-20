package com.hipay.card.cmp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.hipay.card.validation.CardEntryStringKey
import com.hipay.core.resolveLanguage

/**
 * Locale-aware string resolution for the shared card component (fr/en/it), keyed by
 * [CardEntryStringKey] — the same key authority as the native Android (`values-*`) and iOS
 * (`.lproj`) catalogs, with the values copied **verbatim** from them (single validated wording).
 * English is the baseline and the fallback for any unsupported language.
 *
 * Deliberately a locale-keyed Kotlin catalog rather than compose-resources
 * (`composeResources/values-*` + `stringResource`): forcing a language programmatically through
 * compose-resources requires providing `LocalComposeEnvironment` with a custom
 * `ResourceEnvironment`, whose `LanguageQualifier` constructor is `@InternalResourceApi`
 * (compose-multiplatform 1.9.0) — internal API the host app's resolved compose-resources version
 * could break at any time, and the only other iOS route mutates the app-global, persisted
 * `AppleLanguages` user default, which an embedded SDK component must never do. The Kotlin
 * catalog gives identical behaviour on both targets, byte-parity with the validated
 * translations, and direct assertability from common and iOS test suites.
 * Key-set parity with the native catalogs is enforced by `scripts/check-i18n-parity.sh`.
 */
internal val LocalHiPayCardLanguage = staticCompositionLocalOf { "en" }

/** Resolves [key] in the language provided by [CmpCardEntry] (override or system locale). */
@Composable
internal fun cmpString(key: CardEntryStringKey): String =
    cmpString(key, LocalHiPayCardLanguage.current)

/**
 * Resolves [key] for a raw [languageTag] ("fr" — any case, with or without a region, either
 * separator); any language outside the catalog (or null) falls back to English. A French or
 * Italian catalog missing an individual key degrades to the English value; the English catalog
 * is the authority and must stay complete — `getValue` fails composition on an incomplete
 * English catalog, which the parity script and the golden test prevent from ever shipping.
 */
internal fun cmpString(key: CardEntryStringKey, languageTag: String?): String =
    when (cardEntryLanguage(languageTag)) {
        "fr" -> cmpStringsFr[key]
        "it" -> cmpStringsIt[key]
        else -> null
    } ?: cmpStringsEn.getValue(key)

/** The catalog language a tag's primary subtag selects, or null when unsupported. */
private fun cardEntryLanguageOrNull(languageTag: String?): String? =
    when (languageTag?.trim()?.split('-', '_')?.first()?.lowercase()) {
        "fr" -> "fr"
        "it" -> "it"
        "en" -> "en"
        else -> null
    }

/** Normalizes a language tag to a supported catalog language: "fr", "it", else "en". */
internal fun cardEntryLanguage(languageTag: String?): String =
    cardEntryLanguageOrNull(languageTag) ?: "en"

/**
 * The first catalog-supported language in a preference-ordered tag list, English when none —
 * the same walk the native platforms do (an unsupported first language falls through to the
 * next preference instead of forcing English).
 */
internal fun firstSupportedLanguage(languageTags: List<String>): String =
    languageTags.firstNotNullOfOrNull(::cardEntryLanguageOrNull) ?: "en"

/**
 * The language the component renders in. Precedence: the integrator's per-component [localeOverride]
 * → the SDK-wide [settingsOverride] (from `HiPayConfig.settings`) → the device's preferred-language
 * list. The two overrides are normalized case-insensitively and region-tolerantly (`"FR"`/`"fr-FR"`
 * → `"fr"`) via the shared core helper; an unsupported forced language falls back to English.
 */
internal fun resolvedCardEntryLanguage(localeOverride: String?, settingsOverride: String? = null): String {
    val forced = resolveLanguage(localeOverride, settingsOverride, device = null)
    return forced?.let(::cardEntryLanguage)
        ?: firstSupportedLanguage(systemLocaleLanguages())
}

/** Positional `%n$s` substitution for the catalog templates (e.g. the saved-card a11y label). */
internal fun cmpFormat(template: String, vararg args: String): String {
    var out = template
    args.forEachIndexed { i, arg -> out = out.replace("%${i + 1}\$s", arg) }
    return out
}

// The three catalogs below mirror hipaycard/src/main/res/values{,-fr,-it}/strings.xml (and the
// iOS .lproj equivalents) VERBATIM in wording — never edit wording here without changing the
// native catalogs identically. Placeholders use the Android `%n$s` form; the iOS catalogs use
// `%n$@` for the same slots (platform format syntax, not a wording difference). One
// `CardEntryStringKey.X to "…"` per line: check-i18n-parity.sh parses these blocks for
// key-set parity and non-empty values.

private val cmpStringsEn: Map<CardEntryStringKey, String> = mapOf(
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
    // PROVISIONAL consent wording — NOT yet legally approved: do not ship to production as-is.
    // The RGPD-validated copy and a slot for the merchant's own privacy-policy text come in a
    // later release. The key and its placement are stable — only the text will change.
    CardEntryStringKey.CONSENT_SAVE_CARD to "Save this card for faster checkout. You can remove it at any time.",
    CardEntryStringKey.A11Y_SAVED_CARD to "%1\$s finishing %2\$s, expires %3\$s",
    CardEntryStringKey.A11Y_EXPANDED to "expanded",
    CardEntryStringKey.A11Y_COLLAPSED to "collapsed",
    // PROVISIONAL delete copy — NOT yet legally/UX approved; finalized in a later release.
    CardEntryStringKey.LABEL_DELETE_CARD to "Delete card",
    CardEntryStringKey.CONFIRM_DELETE_CARD to "Remove this saved card? You can save it again next time you pay.",
    CardEntryStringKey.LABEL_CANCEL to "Cancel",
    CardEntryStringKey.ERROR_ONE_CLICK_DECLINED to "Payment declined. Try another card or enter a new one.",
    CardEntryStringKey.ERROR_ONE_CLICK_CARD_REMOVED to "This card can no longer be used and was removed. Pay with a new card.",
    CardEntryStringKey.ERROR_ONE_CLICK_3DS to "Authentication failed or was cancelled. Try again or use another card.",
    CardEntryStringKey.ERROR_ONE_CLICK_EXPIRED to "This card has expired. Pay with another card.",
    CardEntryStringKey.ERROR_ONE_CLICK_GENERIC to "The payment could not be completed. Try again or use another card.",
    // PROVISIONAL copy (soft pending hint) — final wording pending UX/compliance sign-off.
    CardEntryStringKey.ERROR_ONE_CLICK_PENDING to "Your payment is still being confirmed. Please wait a moment before trying again.",
)

private val cmpStringsFr: Map<CardEntryStringKey, String> = mapOf(
    CardEntryStringKey.LABEL_HOLDER to "Nom du titulaire",
    CardEntryStringKey.LABEL_NUMBER to "Numéro de carte",
    CardEntryStringKey.LABEL_EXPIRY to "Date d'expiration",
    CardEntryStringKey.LABEL_CVV to "Code de sécurité",
    CardEntryStringKey.PLACEHOLDER_HOLDER to "Nom sur la carte",
    CardEntryStringKey.PLACEHOLDER_NUMBER to "1234 5678 9012 3456",
    CardEntryStringKey.PLACEHOLDER_EXPIRY to "MM/AA",
    CardEntryStringKey.PLACEHOLDER_CVV to "CVV",
    CardEntryStringKey.CVV_OPTIONAL to "Facultatif",
    CardEntryStringKey.CVV_TOOLTIP to "Saisissez le CVV ou code de sécurité de votre carte.",
    CardEntryStringKey.ERROR_INVALID_NUMBER to "Numéro de carte invalide",
    CardEntryStringKey.ERROR_INCOMPLETE_NUMBER to "Numéro de carte incomplet",
    CardEntryStringKey.ERROR_INVALID_EXPIRY to "Date d'expiration invalide",
    CardEntryStringKey.ERROR_EXPIRED to "Cette carte a expiré",
    CardEntryStringKey.ERROR_INVALID_CVV to "Code de sécurité invalide",
    CardEntryStringKey.ERROR_INCOMPLETE_CVV to "Code de sécurité incomplet",
    CardEntryStringKey.ERROR_HOLDER_TOO_LONG to "Nom du titulaire trop long",
    CardEntryStringKey.ERROR_HOLDER_TOO_SHORT to "Minimum 3 caractères",
    CardEntryStringKey.ERROR_NETWORK_NOT_AUTHORIZED to "Type de carte non autorisé",
    CardEntryStringKey.LABEL_SAVED_CARDS to "Cartes enregistrées",
    CardEntryStringKey.LABEL_NEW_CARD to "Nouvelle carte",
    CardEntryStringKey.LABEL_SAVE_CARD to "Enregistrer cette carte",
    // PROVISIONAL consent wording — see the English catalog note.
    CardEntryStringKey.CONSENT_SAVE_CARD to "Enregistrer cette carte pour payer plus vite. Vous pourrez la supprimer à tout moment.",
    CardEntryStringKey.A11Y_SAVED_CARD to "%1\$s se terminant par %2\$s, expire %3\$s",
    CardEntryStringKey.A11Y_EXPANDED to "déplié",
    CardEntryStringKey.A11Y_COLLAPSED to "replié",
    // PROVISIONAL delete copy — see the English catalog note.
    CardEntryStringKey.LABEL_DELETE_CARD to "Supprimer la carte",
    CardEntryStringKey.CONFIRM_DELETE_CARD to "Supprimer cette carte enregistrée ? Vous pourrez l'enregistrer à nouveau lors d'un prochain paiement.",
    CardEntryStringKey.LABEL_CANCEL to "Annuler",
    CardEntryStringKey.ERROR_ONE_CLICK_DECLINED to "Paiement refusé. Essayez une autre carte ou saisissez-en une nouvelle.",
    CardEntryStringKey.ERROR_ONE_CLICK_CARD_REMOVED to "Cette carte ne peut plus être utilisée et a été supprimée. Payez avec une nouvelle carte.",
    CardEntryStringKey.ERROR_ONE_CLICK_3DS to "L'authentification a échoué ou a été annulée. Réessayez ou utilisez une autre carte.",
    CardEntryStringKey.ERROR_ONE_CLICK_EXPIRED to "Cette carte a expiré. Payez avec une autre carte.",
    CardEntryStringKey.ERROR_ONE_CLICK_GENERIC to "Le paiement n'a pas pu être effectué. Réessayez ou utilisez une autre carte.",
    // PROVISIONAL copy (soft pending hint) — see the English catalog note.
    CardEntryStringKey.ERROR_ONE_CLICK_PENDING to "Votre paiement est en cours de confirmation. Veuillez patienter un instant avant de réessayer.",
)

private val cmpStringsIt: Map<CardEntryStringKey, String> = mapOf(
    CardEntryStringKey.LABEL_HOLDER to "Nome del titolare",
    CardEntryStringKey.LABEL_NUMBER to "Numero della carta",
    CardEntryStringKey.LABEL_EXPIRY to "Data di scadenza",
    CardEntryStringKey.LABEL_CVV to "Codice di sicurezza",
    CardEntryStringKey.PLACEHOLDER_HOLDER to "Nome sulla carta",
    CardEntryStringKey.PLACEHOLDER_NUMBER to "1234 5678 9012 3456",
    CardEntryStringKey.PLACEHOLDER_EXPIRY to "MM/AA",
    CardEntryStringKey.PLACEHOLDER_CVV to "CVV",
    CardEntryStringKey.CVV_OPTIONAL to "Facoltativo",
    CardEntryStringKey.CVV_TOOLTIP to "Inserisci il CVV o codice di sicurezza della tua carta.",
    CardEntryStringKey.ERROR_INVALID_NUMBER to "Numero della carta non valido",
    CardEntryStringKey.ERROR_INCOMPLETE_NUMBER to "Numero della carta incompleto",
    CardEntryStringKey.ERROR_INVALID_EXPIRY to "Data di scadenza non valida",
    CardEntryStringKey.ERROR_EXPIRED to "Questa carta è scaduta",
    CardEntryStringKey.ERROR_INVALID_CVV to "Codice di sicurezza non valido",
    CardEntryStringKey.ERROR_INCOMPLETE_CVV to "Codice di sicurezza incompleto",
    CardEntryStringKey.ERROR_HOLDER_TOO_LONG to "Nome del titolare troppo lungo",
    CardEntryStringKey.ERROR_HOLDER_TOO_SHORT to "Minimo 3 caratteri",
    CardEntryStringKey.ERROR_NETWORK_NOT_AUTHORIZED to "Tipo di carta non autorizzato",
    CardEntryStringKey.LABEL_SAVED_CARDS to "Carte salvate",
    CardEntryStringKey.LABEL_NEW_CARD to "Nuova carta",
    CardEntryStringKey.LABEL_SAVE_CARD to "Salva questa carta",
    // PROVISIONAL consent wording — see the English catalog note.
    CardEntryStringKey.CONSENT_SAVE_CARD to "Salva questa carta per pagare più velocemente. Potrai rimuoverla in qualsiasi momento.",
    CardEntryStringKey.A11Y_SAVED_CARD to "%1\$s che termina con %2\$s, scade %3\$s",
    CardEntryStringKey.A11Y_EXPANDED to "espanso",
    CardEntryStringKey.A11Y_COLLAPSED to "compresso",
    // PROVISIONAL delete copy — see the English catalog note.
    CardEntryStringKey.LABEL_DELETE_CARD to "Elimina carta",
    CardEntryStringKey.CONFIRM_DELETE_CARD to "Rimuovere questa carta salvata? Potrai salvarla di nuovo al prossimo pagamento.",
    CardEntryStringKey.LABEL_CANCEL to "Annulla",
    CardEntryStringKey.ERROR_ONE_CLICK_DECLINED to "Pagamento rifiutato. Prova un'altra carta o inseriscine una nuova.",
    CardEntryStringKey.ERROR_ONE_CLICK_CARD_REMOVED to "Questa carta non può più essere utilizzata ed è stata rimossa. Paga con una nuova carta.",
    CardEntryStringKey.ERROR_ONE_CLICK_3DS to "L'autenticazione non è riuscita o è stata annullata. Riprova o usa un'altra carta.",
    CardEntryStringKey.ERROR_ONE_CLICK_EXPIRED to "Questa carta è scaduta. Paga con un'altra carta.",
    CardEntryStringKey.ERROR_ONE_CLICK_GENERIC to "Impossibile completare il pagamento. Riprova o usa un'altra carta.",
    // PROVISIONAL copy (soft pending hint) — see the English catalog note.
    CardEntryStringKey.ERROR_ONE_CLICK_PENDING to "Il pagamento è ancora in fase di conferma. Attendi un momento prima di riprovare.",
)
