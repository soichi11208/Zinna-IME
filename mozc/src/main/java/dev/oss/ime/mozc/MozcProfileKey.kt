package dev.oss.ime.mozc

import android.content.Context
import android.util.Log
import com.google.android.apps.inputmethod.libs.mozc.session.MozcJNI
import java.io.File
import java.security.SecureRandom

/**
 * Supplies the key that mozc uses to encrypt the files it writes about what the user types:
 * the conversion history (`.history.db`) and the user dictionary (`user_dictionary.db`).
 *
 * mozc already encrypts those, but its Linux `PasswordManager` — which Android would otherwise
 * inherit, since Android is Linux — saves the key as `.encrypt_key.db` in plain text, in the same
 * directory as the data it protects. Anyone who can read one can read the other, so it protects
 * nothing. Our patched build asks for the key instead of managing a file, and this is where it
 * comes from: 32 random bytes sealed under the same hardware-backed Keystore key as the rest of the
 * user's data (see [SecureStore]).
 *
 * The key must be installed before the engine is built — mozc reads the history while starting up.
 */
internal object MozcProfileKey {

    private const val TAG = "MozcProfileKey"

    /** Must equal mozc's `kPasswordSize` in `base/password_manager.cc`; it rejects other sizes. */
    private const val KEY_SIZE = 32

    private const val SEALED_FILE = "mozc_profile_key.enc"

    /** What mozc's own password manager would have written, in the clear. */
    private const val LEGACY_FILE = ".encrypt_key.db"

    /**
     * Generates or loads the key and hands it to the native side.
     *
     * @return false if the key could not be produced. mozc then declines to read or write the
     *   encrypted files rather than falling back to a key it cannot persist, so the history and the
     *   dictionary stop being saved — but nothing already on disk is destroyed.
     */
    fun install(context: Context, profileDir: File): Boolean {
        val key = load(context, profileDir) ?: return false
        return try {
            MozcJNI.setEncryptionKey(key).also {
                if (!it) Log.e(TAG, "native side rejected the encryption key")
            }
        } catch (e: UnsatisfiedLinkError) {
            // An unpatched libmozc.so: the native method was never registered. Worth failing
            // loudly, because the app would otherwise silently go back to plain-text keys.
            Log.e(TAG, "libmozc.so has no setEncryptionKey — profile encryption is NOT active", e)
            false
        }
    }

    /**
     * The raw key, for [ProfileBackup] alone.
     *
     * Deliberately does not fall back to generating one: a backup taken before the engine has ever
     * run would seal a key nothing was encrypted under, and restoring it would throw away whatever
     * history the target device already had.
     */
    internal fun sealedKey(context: Context): ByteArray? =
        SecureStore.readBytes(File(context.applicationContext.filesDir, SEALED_FILE))
            ?.takeIf { it.size == KEY_SIZE }

    /**
     * Replaces the key with one out of a backup, so the restored history can be read.
     *
     * Whatever was encrypted under the old key becomes unreadable, which is what restoring means —
     * the caller has already replaced that data.
     */
    internal fun replaceSealedKey(context: Context, key: ByteArray): Boolean {
        if (key.size != KEY_SIZE) {
            Log.e(TAG, "restored key is ${key.size} bytes, expected $KEY_SIZE")
            return false
        }
        return SecureStore.writeBytes(File(context.applicationContext.filesDir, SEALED_FILE), key)
    }

    private fun load(context: Context, profileDir: File): ByteArray? {
        val sealed = File(context.filesDir, SEALED_FILE)
        SecureStore.readBytes(sealed)?.let { existing ->
            if (existing.size == KEY_SIZE) return existing
            // Wrong size means the file is not what we wrote. Refuse rather than re-key, which
            // would leave the history undecryptable.
            Log.e(TAG, "sealed key is ${existing.size} bytes, expected $KEY_SIZE")
            return null
        }

        // No sealed key yet. Adopt the plain-text one if a previous build left it behind, so the
        // history and dictionary written under it stay readable; otherwise start fresh.
        val legacy = File(profileDir, LEGACY_FILE)
        val legacyBytes = legacy.takeIf { it.isFile }?.runCatching { readBytes() }?.getOrNull()
        val key = if (legacyBytes != null && legacyBytes.size == KEY_SIZE) {
            Log.i(TAG, "adopting the existing plain-text key and sealing it")
            legacyBytes
        } else {
            ByteArray(KEY_SIZE).also { SecureRandom().nextBytes(it) }
        }

        if (!SecureStore.writeBytes(sealed, key)) return null

        // Only now that the key is safely sealed is it safe to remove the plain-text copy. mozc
        // leaves that file mode 0400, but the directory is ours, so unlinking still works.
        if (legacy.isFile && !legacy.delete()) {
            Log.w(TAG, "could not remove the plain-text key at ${legacy.path}")
        }
        return key
    }
}
