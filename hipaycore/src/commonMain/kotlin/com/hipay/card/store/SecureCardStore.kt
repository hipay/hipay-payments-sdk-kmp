// PCI: com.hipay.card path — NEVER log here, never expose the raw PAN or token.
package com.hipay.card.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Max saved cards kept on the device: a valid card is never evicted below this ceiling; LRU eviction
 * bites only at the 20th-card boundary. The number of cards the UI *shows* is a separate,
 * integrator-configurable display count (default 3) — see the card component.
 */
private const val MAX_CARDS = 20

/**
 * Storage format version — bump and migrate when the persisted shape changes.
 *
 * Deliberately NOT bumped when [MAX_CARDS] was raised: the envelope shape did not change, and the
 * version gate below is fail-closed (an unknown version discards the blob), so a bump would destroy
 * every payer's saved cards on the way *up*. The cost of that choice is that downgrading the SDK to a
 * build with a lower ceiling truncates the list on its next write — downgrades are not supported.
 */
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

// STRICT on purpose (the default, no ignoreUnknownKeys): the store owns this envelope, so an
// unexpected shape is corruption — strict parsing + the version gate keep the fail-closed / fail-soft
// contract honest. (Distinct from the gateway's lenient Json, which must tolerate a third-party wire format.)
// Resolved through a getter, NOT a file-level `val`: in the RELEASE static framework, a file-level
// property reached through the ObjC bridge was observed null-initialized (EXC_BAD_ACCESS at 0x8
// inside encodeToString) — the getter dodges the file-initializer entirely.
private inline val storeJson: Json get() = Json.Default

/**
 * Saved-card store LOGIC — platform-free, single-sourced in Kotlin.
 *
 * Owns: consent gate, overwrite-on-match by masked PAN, storage cap (MAX_CARDS) + Least Recently Used (LRU),
 * expired-card auto-purge, versioned (de)serialization (fail-closed on an unknown version), and
 * fail-soft graceful degrade (any storage/parse failure ⇒ behaves as "no saved cards", never throws).
 * Mutating operations return whether the change was persisted, so the caller can react — e.g. surface
 * a failed save, or confirm a consent-withdrawal deletion.
 *
 * NOT thread-safe: each mutator does a read-modify-write with no locking — confine it to a single
 * BACKGROUND thread: platform [RawSecureStore] implementations may do blocking I/O, so keep it
 * off the main thread.
 *
 * It drives an injected [RawSecureStore] (the platform Keychain/Keystore primitive; a fake in tests).
 * Never logs; never stores the PAN or CVV.
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
     * Overwrites any card with the same identity — the masked PAN alone, see [SavedCard.identity].
     * Expiry is deliberately NOT part of it: a renewed card keeps its PAN and only changes expiry, and
     * it must update the existing entry in place rather than sit beside the stale one. Enforces the
     * storage cap by evicting the least-recently-used. Returns true iff the card was persisted (false
     * without consent or on a storage-write failure).
     */
    public fun save(card: SavedCard, consentGiven: Boolean): Boolean {
        if (!consentGiven) return false
        val env = load()
        val nextSeq = env.seq + 1
        val kept = env.cards.filterNot { it.card.identity == card.identity }.toMutableList()
        kept.add(StoredCard(card, nextSeq))
        val capped = kept.sortedByDescending { it.seq }.take(MAX_CARDS)
        return persist(SavedCardsEnvelope(seq = nextSeq, cards = capped))
    }

    /**
     * Bump a card's recency (call when it pays) so LRU reflects real usage. Returns true iff persisted;
     * false if the card is absent or on a write failure.
     */
    public fun touch(card: SavedCard): Boolean {
        val env = load()
        if (env.cards.none { it.card.identity == card.identity }) return false
        val nextSeq = env.seq + 1
        val updated = env.cards.map {
            if (it.card.identity == card.identity) StoredCard(it.card, nextSeq) else it
        }
        return persist(SavedCardsEnvelope(seq = nextSeq, cards = updated))
    }

    /**
     * Remove the matching card (by identity). Returns true iff the store reflects the removal —
     * idempotent when the card is already absent; false only on a write failure.
     */
    public fun delete(card: SavedCard): Boolean {
        val env = load()
        val remaining = env.cards.filterNot { it.card.identity == card.identity }
        if (remaining.size == env.cards.size) return true // already absent — idempotent success
        return persist(env.withCards(remaining))
    }

    /** Remove every saved card (consent withdrawn, or first-launch purge). Returns true iff the clear succeeded. */
    public fun clearAll(): Boolean = runCatching { raw.clear() }.isSuccess

    // --- internals persistent data ---

    private fun load(): SavedCardsEnvelope {
        val blob = runCatching { raw.read() }.getOrNull() ?: return SavedCardsEnvelope()
        val env = runCatching { storeJson.decodeFromString(SavedCardsEnvelope.serializer(), blob) }
            .getOrNull() ?: return SavedCardsEnvelope()               // fail-soft on a corrupt blob
        if (env.version != SAVED_CARDS_VERSION) return SavedCardsEnvelope() // fail-closed on an unknown version
        val now = currentYearMonth()
        if (!now.isPlausible()) return env                            // bogus clock → never destructively purge
        val live = env.cards.filterNot { isExpired(it.card, now) }
        if (live.size != env.cards.size) {                            // purge expired cards on load
            val pruned = env.withCards(live)
            persist(pruned)
            return pruned
        }
        return env
    }

    private fun persist(env: SavedCardsEnvelope): Boolean =
        runCatching { raw.write(storeJson.encodeToString(SavedCardsEnvelope.serializer(), env)) }.isSuccess

    private fun isExpired(card: SavedCard, now: YearMonth): Boolean {
        val year = normalizeYear(card.expiryYear) ?: return true   // unparseable year ⇒ treat as expired (fail-soft)
        val month = card.expiryMonth.trim().toIntOrNull() ?: return true
        if (month !in 1..12) return true                           // invalid month ⇒ treat as expired
        return year < now.year || (year == now.year && month < now.month)
    }
}

/** A clock value is trusted for the destructive expiry purge only when it looks real. */
private fun YearMonth.isPlausible(): Boolean = year in 2000..2100 && month in 1..12

private fun SavedCardsEnvelope.withCards(cards: List<StoredCard>) =
    SavedCardsEnvelope(version = version, seq = seq, cards = cards)
