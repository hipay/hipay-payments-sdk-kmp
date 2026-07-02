// PCI: com.hipay.card path — NEVER log here, never expose the raw PAN or token.
package com.hipay.card.store

import android.content.Context
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
import java.security.KeyStore
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Single Preferences DataStore for the module (one file per process — the delegate guarantees a
// single instance). Holds the encrypted saved-card blob (per namespace) + a non-secure launch flag.
private val Context.savedCardsDataStore by preferencesDataStore(name = "hipay_saved_cards")

private const val KEY_ALIAS = "com.hipay.savedcards.aeskey"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128
private val LAUNCHED_FLAG = booleanPreferencesKey("com.hipay.savedcards.launched")

/**
 * Android [RawSecureStore]: the store's serialized blob, encrypted with an AES/GCM key held in the
 * Android Keystore (non-exportable, device-bound) and persisted as ciphertext in Jetpack DataStore.
 *
 * At-rest protection = the Keystore key: a backed-up/transferred ciphertext is undecryptable on
 * another device (the key never leaves the secure hardware), so it self-heals to "no saved cards" —
 * this is why no app-level backup rule is required (a library cannot set `dataExtractionRules` anyway).
 *
 * All storage/crypto failures (incl. `KeyPermanentlyInvalidatedException`, GCM tag mismatch) degrade
 * to null on read + wipe the unusable key/entry so the next write re-keys cleanly. Never logs.
 *
 * DataStore is async; this synchronous [RawSecureStore] bridges via `runBlocking(Dispatchers.IO)` —
 * call the store OFF the main thread (the controller confines it). Not thread-safe.
 */
internal class AndroidSecureCardStore(
    context: Context,
    namespace: String,
) : RawSecureStore {

    private val appContext = context.applicationContext
    private val entryKey = stringPreferencesKey(namespace)

    override fun read(): String? = try {
        val stored = runBlocking(Dispatchers.IO) { appContext.savedCardsDataStore.data.first()[entryKey] }
        stored?.let { decrypt(it) }
    } catch (e: Exception) {
        // Key invalidated / ciphertext tampered / undecodable → wipe and behave as empty.
        runCatching { clear() }
        runCatching { deleteKey() }
        null
    }

    override fun write(value: String) {
        val encrypted = encrypt(value)
        runBlocking(Dispatchers.IO) { appContext.savedCardsDataStore.edit { it[entryKey] = encrypted } }
    }

    override fun clear() {
        runBlocking(Dispatchers.IO) { appContext.savedCardsDataStore.edit { it.remove(entryKey) } }
    }

    // --- crypto ---

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return b64(iv) + ":" + b64(ciphertext)
    }

    /** Returns null on a malformed blob (fail-soft); throws on a key/tag failure (caught by [read]). */
    private fun decrypt(stored: String): String? {
        val parts = stored.split(":", limit = 2)
        if (parts.size != 2) return null
        val key = loadKeyOrNull() ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, unb64(parts[0])))
        return cipher.doFinal(unb64(parts[1])).toString(Charsets.UTF_8)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun loadKeyOrNull(): SecretKey? =
        (keyStore().getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun getOrCreateKey(): SecretKey = loadKeyOrNull() ?: run {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // No setUserAuthenticationRequired: one-click has no per-use auth, so a lock-screen /
                // biometric change does not invalidate this key (avoids KeyPermanentlyInvalidated).
                .build(),
        )
        generator.generateKey()
    }

    private fun deleteKey() {
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}

/**
 * Assemble a ready [SecureCardStore] on Android: the Keystore+DataStore [AndroidSecureCardStore] +
 * a `Calendar`-based clock (minSdk 24 < `java.time.YearMonth` API 26). Runs a one-time first-launch
 * purge (uniform with iOS, where the Keychain survives uninstall). [HiPayConfig] carries no one-click
 * reference — enabling one-click is the UI layer's opt-in.
 */
public fun createSecureCardStore(context: Context, config: HiPayConfig): SecureCardStore {
    val app = context.applicationContext
    val raw = AndroidSecureCardStore(app, secureCardStoreNamespace(config))
    runCatching {
        runBlocking(Dispatchers.IO) {
            val launched = app.savedCardsDataStore.data.first()[LAUNCHED_FLAG] ?: false
            if (!launched) {
                raw.clear()
                app.savedCardsDataStore.edit { it[LAUNCHED_FLAG] = true }
            }
        }
    }
    return SecureCardStore(raw, currentYearMonth = { androidYearMonth() })
}

private fun androidYearMonth(): YearMonth {
    val calendar = Calendar.getInstance()
    return YearMonth(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1)
}
