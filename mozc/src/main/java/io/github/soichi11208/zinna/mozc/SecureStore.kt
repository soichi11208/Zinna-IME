package io.github.soichi11208.zinna.mozc

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * At-rest encryption for the files this app writes about what the user types.
 *
 * The key lives in the Android Keystore and never leaves it — on devices with a secure element it
 * is not extractable at all. That is the part app-level crypto can actually add: the data partition
 * is already encrypted by the OS, so the threat this addresses is a backup, an unlocked device
 * being inspected, or an adb pull on a debuggable build, not offline decryption of the flash chip.
 *
 * AES-GCM, with the IV written in front of the ciphertext. GCM authenticates as well as encrypts,
 * so a truncated or tampered file fails to decrypt instead of returning garbage that would then be
 * fed to mozc as dictionary entries.
 */
object SecureStore {

    private const val TAG = "SecureStore"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "io.github.soichi11208.zinna.user_data"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128

    /** Encrypts [text] into [file], replacing whatever was there. */
    fun write(file: File, text: String) = writeBytes(file, text.toByteArray())

    /**
     * @return the decrypted contents, or null if the file is absent, corrupt, or was written under
     *   a key that no longer exists — which happens when the user clears app data or restores to a
     *   new device. Losing the data is the correct outcome there; the alternative is pretending a
     *   file we cannot authenticate is trustworthy.
     */
    fun read(file: File): String? = readBytes(file)?.let { String(it) }

    /** As [write], for data that is not text — see [MozcProfileKey]. */
    fun writeBytes(file: File, bytes: ByteArray): Boolean {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
            file.parentFile?.mkdirs()
            file.outputStream().use { out ->
                out.write(cipher.iv)
                out.write(cipher.doFinal(bytes))
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "failed to write ${file.name}", e)
            false
        }
    }

    /** As [read], for data that is not text. */
    fun readBytes(file: File): ByteArray? {
        if (!file.isFile) return null
        return try {
            val bytes = file.readBytes()
            if (bytes.size <= IV_LENGTH) return null
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(TAG_LENGTH_BITS, bytes, 0, IV_LENGTH),
                )
            }
            cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH)
        } catch (e: Exception) {
            Log.e(TAG, "failed to read ${file.name}", e)
            null
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    // Deliberately not requiring authentication: the IME has to read the user's
                    // dictionary on a locked screen, in the lock-screen password field itself.
                    .setUserAuthenticationRequired(false)
                    .build()
            )
        }.generateKey()
    }
}
