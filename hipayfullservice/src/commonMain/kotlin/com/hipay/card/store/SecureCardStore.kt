// PCI: com.hipay.card path — NEVER log here, never expose the raw PAN or token.
package com.hipay.card.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Max saved cards kept on the device. */
private const val MAX_CARDS = 3

/** Storage format version — bump and migrate when the persisted shape changes. */
internal const val SAVED_CARDS_VERSION = 1

/** A saved card plus its recency rank (`seq`); `seq` is store-managed, never exposed publicly. */
@Serializable
internal class StoredCard(val card: SavedCard, val seq: Long)

/** The persisted envelope: a version + a monotonic recency counter + the cards. */
@Serializable
internal class SavedCardsEnvelope(
    val version: Int = SAVED_CARDS_VERSION,
    val seq: Long = 0L,
    val cards: List<StoredCard> = emptyList(),
)

private val storeJson = Json { ignoreUnknownKeys = true }

/**
 * Saved-card store LOGIC — platform-free, single-sourced in Kotlin.
 *
 * Owns: consent gate, overwrite-on-match by masked-PAN+expiry, cap-3 + Least Recently Used (LRU),
 * expired-card auto-purge, versioned (de)serialization (fail-closed on an unknown version), and
 * fail-soft graceful degrade (any storage/parse failure ⇒ behaves as "no saved cards", never throws).
 *
 * It drives an injected [RawSecureStore] (the platform Keychain/Keystore primitive; a
 * fake in tests). Never logs; never stores the PAN or CVV.
 *
 * @param raw the platform secure-storage primitive.
 * @param currentYearMonth current year/month for the expiry purge — injected, so no date dependency.
 */
public class SecureCardStore(
    private val raw: RawSecureStore,
    private val currentYearMonth: () -> YearMonth,
) {
    /** Saved cards, most-recently-used first, with expired ones already purged. Never throws. */
    public fun list(): List<SavedCard> =
        load().cards.sortedByDescending { it.seq }.map { it.card }

    /**
     * Persist [card] only if [consentGiven] — consent is enforced at the store, not just in the UI.
     * Overwrites any card with the same identity (masked-PAN + expiry); enforces the 3-card cap by
     * evicting the least-recently-used. A no-op without consent.
     */
    public fun save(card: SavedCard, consentGiven: Boolean) {
        if (!consentGiven) return
        val env = load()
        val nextSeq = env.seq + 1
        val kept = env.cards.filterNot { it.card.identity == card.identity }.toMutableList() // overwrite-on-match
        kept.add(StoredCard(card, nextSeq))
        val capped = kept.sortedByDescending { it.seq }.take(MAX_CARDS) // cap-3 + LRU
        persist(SavedCardsEnvelope(seq = nextSeq, cards = capped))
    }

    /** Bump a card's recency (call when it pays) so LRU reflects real usage. No-op if absent. */
    public fun touch(card: SavedCard) {
        val env = load()
        if (env.cards.none { it.card.identity == card.identity }) return
        val nextSeq = env.seq + 1
        val updated = env.cards.map {
            if (it.card.identity == card.identity) StoredCard(it.card, nextSeq) else it
        }
        persist(SavedCardsEnvelope(seq = nextSeq, cards = updated))
    }

    /** Remove the matching card (by identity). No-op if absent. */
    public fun delete(card: SavedCard) {
        val env = load()
        val remaining = env.cards.filterNot { it.card.identity == card.identity }
        if (remaining.size == env.cards.size) return
        persist(env.withCards(remaining))
    }

    /** Remove every saved card (consent withdrawn, or first-launch purge). */
    public fun clearAll() {
        runCatching { raw.clear() }
    }

    // --- internals ---

    private fun load(): SavedCardsEnvelope {
        val blob = runCatching { raw.read() }.getOrNull() ?: return SavedCardsEnvelope()
        val env = runCatching { storeJson.decodeFromString(SavedCardsEnvelope.serializer(), blob) }
            .getOrNull() ?: return SavedCardsEnvelope()               // fail-soft on a corrupt blob
        if (env.version != SAVED_CARDS_VERSION) return SavedCardsEnvelope() // fail-closed on an unknown version
        val now = currentYearMonth()
        val live = env.cards.filterNot { isExpired(it.card, now) }
        if (live.size != env.cards.size) {                            // purge expired cards on load
            val pruned = env.withCards(live)
            persist(pruned)
            return pruned
        }
        return env
    }

    private fun persist(env: SavedCardsEnvelope) {
        runCatching { raw.write(storeJson.encodeToString(SavedCardsEnvelope.serializer(), env)) }
    }

    private fun isExpired(card: SavedCard, now: YearMonth): Boolean {
        val year = card.expiryYear.toIntOrNull() ?: return true   // unparseable expiry ⇒ treat as expired (fail-soft)
        val month = card.expiryMonth.toIntOrNull() ?: return true
        return year < now.year || (year == now.year && month < now.month)
    }
}

private fun SavedCardsEnvelope.withCards(cards: List<StoredCard>) =
    SavedCardsEnvelope(version = version, seq = seq, cards = cards)
