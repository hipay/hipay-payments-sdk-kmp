// PCI: com.hipay.card path — NEVER log here, never expose the raw PAN or token.
package com.hipay.card.store

import android.content.Context
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hipay.core.HiPayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Single Preferences DataStore for the module (one file per process — the delegate guarantees a
// single instance). Holds the encrypted saved-card blobs (one per namespace) + a non-secure launch
// flag. SINGLE-PROCESS ONLY: a Preferences DataStore must not be opened from two processes — a host
// app using a secondary process must confine the saved-card store to one of them.
internal val Context.savedCardsDataStore by preferencesDataStore(name = "hipay_saved_cards")

private const val KEY_ALIAS_PREFIX = "com.hipay.savedcards.aeskey."
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val AES_KEY_BITS = 256
private const val GCM_TAG_BITS = 128
private const val IO_TIMEOUT_MS = 5_000L
internal val LAUNCHED_FLAG = booleanPreferencesKey("com.hipay.savedcards.launched")

// Guards Keystore get-or-create across ALL store instances: two stores racing generateKey() on the
// same alias would silently orphan whatever the first one just encrypted.
private val keyLock = Any()

/**
 * Android [RawSecureStore]: the store's serialized blob, encrypted with an AES/GCM key held in the
 * Android Keystore (non-exportable, device-bound) and persisted as ciphertext in Jetpack DataStore.
 * Each namespace owns its own key alias, so wiping one namespace never affects another.
 *
 * At-rest protection = the Keystore key: a backed-up/transferred ciphertext is undecryptable on
 * another device (the key never leaves the secure hardware) and is purged on first read, so it
 * self-heals to "no saved cards" — a host app can additionally exclude the DataStore file from its
 * `dataExtractionRules` (a library cannot set app-level backup rules; see the README security notes).
 *
 * Failure handling on read is two-tier, never throws, never logs:
 *  - unusable key (invalidated) or tampered ciphertext → wipe the entry + key so the next write
 *    re-keys cleanly;
 *  - dead entry (malformed blob, or the key is gone — e.g. a restored backup) → purge the entry;
 *  - transient storage failure → null WITHOUT wiping (the data may still be intact).
 *
 * DataStore is async; this synchronous [RawSecureStore] bridges via a bounded
 * `runBlocking(Dispatchers.IO)` — call the store OFF the main thread (the factory enforces it at
 * assembly; the controller confines it after that). Not thread-safe.
 */
internal class AndroidSecureCardStore(
    context: Context,
    namespace: String,
) : RawSecureStore {

    private val appContext = context.applicationContext
    private val entryKey = stringPreferencesKey(namespace)
    private val keyAlias = KEY_ALIAS_PREFIX + namespace

    override fun read(): String? {
        val stored = try {
            runBlocking(Dispatchers.IO) {
                withTimeout(IO_TIMEOUT_MS) { appContext.savedCardsDataStore.data.first()[entryKey] }
            }
        } catch (_: Exception) {
            return null // transient storage failure — keep the entry, behave as empty
        } ?: return null
        return try {
            decrypt(stored) ?: run {
                // Dead entry: malformed blob, or the key is gone (restored backup) — purge it.
                runCatching { clear() }
                null
            }
        } catch (_: GeneralSecurityException) {
            // Unusable key (invalidated) or tampered ciphertext — wipe both so the next write re-keys.
            runCatching { clear() }
            deleteKey()
            null
        } catch (_: Exception) {
            null // transient — keep the entry
        }
    }

    override fun write(value: String) {
        val encrypted = encrypt(value)
        runBlocking(Dispatchers.IO) {
            withTimeout(IO_TIMEOUT_MS) { appContext.savedCardsDataStore.edit { it[entryKey] = encrypted } }
        }
    }

    override fun clear() {
        runBlocking(Dispatchers.IO) {
            withTimeout(IO_TIMEOUT_MS) { appContext.savedCardsDataStore.edit { it.remove(entryKey) } }
        }
    }

    // --- crypto ---

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return b64(iv) + ":" + b64(ciphertext)
    }

    /**
     * Null on a dead entry (malformed/undecodable blob, missing key); throws
     * [GeneralSecurityException] on an unusable key or a tampered ciphertext (handled by [read]).
     */
    private fun decrypt(stored: String): String? {
        val parts = stored.split(":", limit = 2)
        if (parts.size != 2) return null
        val iv = unb64OrNull(parts[0]) ?: return null
        val ciphertext = unb64OrNull(parts[1]) ?: return null
        val key = loadKeyOrNull() ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun loadKeyOrNull(): SecretKey? =
        (keyStore().getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun getOrCreateKey(): SecretKey = synchronized(keyLock) {
        val existing = try {
            loadKeyOrNull()
        } catch (_: Exception) {
            deleteKey() // corrupt/unreadable Keystore entry — drop it so the write path re-keys
            null
        }
        existing ?: run {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(AES_KEY_BITS)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    // No setUserAuthenticationRequired: one-click has no per-use auth, so a lock-screen /
                    // biometric change does not invalidate this key (avoids KeyPermanentlyInvalidated).
                    .build(),
            )
            generator.generateKey()
        }
    }

    internal fun deleteKey() {
        runCatching { keyStore().deleteEntry(keyAlias) }
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64OrNull(s: String): ByteArray? =
        runCatching { Base64.decode(s, Base64.NO_WRAP) }.getOrNull()
}

/**
 * Assemble a ready [SecureCardStore] on Android: the Keystore+DataStore [AndroidSecureCardStore] +
 * a `Calendar`-based clock (minSdk 24 < `java.time.YearMonth` API 26). Runs a one-time first-launch
 * purge of ALL namespaces (uniform with iOS, where the Keychain survives uninstall). [HiPayConfig]
 * carries no one-click reference — enabling one-click is the UI layer's opt-in.
 *
 * MUST be called from a background thread: it performs blocking disk I/O (and the store it returns
 * keeps doing so on every operation). Calling it on the main thread throws [IllegalStateException].
 */
public fun createSecureCardStore(context: Context, config: HiPayConfig): SecureCardStore {
    check(Looper.myLooper() != Looper.getMainLooper()) {
        "createSecureCardStore performs blocking disk I/O — call it from a background thread."
    }
    val app = context.applicationContext
    val raw = AndroidSecureCardStore(app, secureCardStoreNamespace(config))
    runCatching {
        runBlocking(Dispatchers.IO) {
            withTimeout(IO_TIMEOUT_MS) {
                val launched = app.savedCardsDataStore.data.first()[LAUNCHED_FLAG] ?: false
                if (!launched) {
                    // Fresh install: drop every residual entry (all namespaces), then arm the flag.
                    app.savedCardsDataStore.edit {
                        it.clear()
                        it[LAUNCHED_FLAG] = true
                    }
                }
            }
        }
    }
    return SecureCardStore(raw, currentYearMonth = { androidYearMonth() })
}

private fun androidYearMonth(): YearMonth {
    val calendar = Calendar.getInstance()
    return YearMonth(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1)
}
