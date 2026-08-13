package dev.oss.ime.mozc

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * One file holding everything the user cannot recreate: their dictionary and what mozc has learned
 * from them.
 *
 * **Why it needs a passphrase.** The learning data is encrypted with a key held in the Android
 * Keystore, which by design cannot leave the device — so a backup of those files alone would be
 * undecryptable anywhere else, including on this device after a reinstall. To be restorable at all
 * the archive has to carry the key, and a key sitting beside the data it protects is exactly the
 * flaw [MozcProfileKey] exists to fix. So the archive is sealed under a passphrase only the user
 * knows, and the file is useless to anyone who takes it without one.
 *
 * **Why restoring is staged.** mozc holds the history and segment databases open and writes them
 * back on its own schedule, so replacing the files under a running engine loses the restore at the
 * next save. [stageRestore] therefore only puts the archive aside; [applyStagedRestore] unpacks it
 * during start-up, before the engine reads anything.
 */
object ProfileBackup {

    private const val TAG = "ProfileBackup"

    /** Recognises our own files, and rejects anything else before a passphrase is even tried. */
    private val MAGIC = "ZINNABAK".toByteArray()

    private const val FORMAT_VERSION = 1

    // PBKDF2 rather than the Keystore: the whole point is a file that opens on another device.
    private const val KDF = "PBKDF2WithHmacSHA256"
    private const val KDF_ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val SALT_LENGTH = 16

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128

    private const val ENTRY_MANIFEST = "manifest.json"
    private const val ENTRY_DICTIONARY = "user_dictionary.tsv"
    private const val ENTRY_PROFILE_KEY = "profile_key"
    private const val PROFILE_PREFIX = "mozc/"

    private const val STAGED_FILE = "pending_restore.enc"

    /**
     * Profile files that must not travel, for two different reasons.
     *
     * The lock, the socket path and the registry describe a running server rather than the user;
     * restoring them from another device would at best be ignored and at worst point mozc at
     * something that is not there.
     *
     * `.encrypt_key.db` is mozc's own plain-text key file. This build does not write one, but an
     * older one did and [MozcProfileKey] only deletes it once — if that delete ever failed, the
     * file is still sitting there, and copying it into a backup would put a plain-text key beside
     * the data it unlocks in a file meant to leave the device.
     */
    private val NOT_BACKED_UP =
        setOf(".server.lock", ".session.ipc", ".registry.db", ".encrypt_key.db")

    /** Largest archive we will unpack, so a hostile file cannot exhaust storage. */
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    sealed interface Result {
        data object Ok : Result

        /** The file is not one of ours. */
        data object NotABackup : Result

        /** The passphrase is wrong, or the file has been altered since it was written. */
        data object WrongPassphrase : Result

        /** Written by a newer version of the app than this one. */
        data class TooNew(val format: Int) : Result

        data class Failed(val message: String) : Result
    }

    /** Suggested file name, dated so successive backups do not overwrite each other. */
    fun suggestedFileName(): String {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        return "zinna-ime-$day.zinnabak"
    }

    /**
     * Builds the archive. Returns null if there is nothing to back up or the key is unreadable —
     * writing a file that silently omits the history would be worse than refusing.
     */
    fun export(context: Context, passphrase: CharArray): ByteArray? {
        val app = context.applicationContext
        val profileKey = MozcProfileKey.sealedKey(app)
        if (profileKey == null) {
            Log.e(TAG, "no profile key; refusing to write a backup that cannot be restored")
            return null
        }

        val payload = ByteArrayOutputStream()
        ZipOutputStream(payload).use { zip ->
            val manifest = JSONObject().apply {
                put("format", FORMAT_VERSION)
                put("created", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
                put("app", appVersion(app))
            }
            zip.write(ENTRY_MANIFEST, manifest.toString().toByteArray())

            // The user's own words travel as plain TSV, not as our encrypted copy: it is the format
            // mozc itself imports, so the backup stays useful even to someone reading it by hand.
            UserDictionary(app).exportTsv()?.let { zip.write(ENTRY_DICTIONARY, it.toByteArray()) }

            zip.write(ENTRY_PROFILE_KEY, profileKey)
            for (file in profileFiles(app)) {
                zip.write(PROFILE_PREFIX + file.name, file.readBytes())
            }
        }
        return seal(payload.toByteArray(), passphrase)
    }

    /**
     * Checks [archive] and puts it aside for [applyStagedRestore] to unpack at the next start-up.
     *
     * Everything that can be verified is verified here — magic, passphrase, format version — so the
     * user learns the passphrase was wrong while the file picker is still in front of them, rather
     * than through a keyboard that quietly failed to change.
     */
    fun stageRestore(context: Context, archive: ByteArray, passphrase: CharArray): Result {
        val app = context.applicationContext
        val payload = when (val opened = open(archive, passphrase)) {
            is Opened.Failure -> return opened.result
            is Opened.Payload -> opened.bytes
        }

        val entries = try {
            unzip(payload)
        } catch (e: Exception) {
            Log.e(TAG, "archive is not readable", e)
            return Result.Failed("アーカイブを展開できません")
        }

        val manifest = entries[ENTRY_MANIFEST]
            ?: return Result.Failed("バックアップの中身が壊れています")
        val format = runCatching { JSONObject(String(manifest)).getInt("format") }
            .getOrElse { return Result.Failed("バックアップの中身が壊れています") }
        if (format > FORMAT_VERSION) return Result.TooNew(format)

        // Sealed under this device's Keystore while it waits, so a backup does not sit unprotected
        // in app storage between being chosen and being applied.
        return if (SecureStore.writeBytes(File(app.filesDir, STAGED_FILE), payload)) {
            Result.Ok
        } else {
            Result.Failed("復元データを保存できませんでした")
        }
    }

    /** Whether a restore is waiting for the next start-up. */
    fun hasStagedRestore(context: Context): Boolean =
        File(context.applicationContext.filesDir, STAGED_FILE).isFile

    fun discardStagedRestore(context: Context) {
        File(context.applicationContext.filesDir, STAGED_FILE).delete()
    }

    /**
     * Unpacks a staged archive over the profile. Called from [MozcEngine] before the engine reads
     * anything, and never anywhere else — at any other moment mozc would write its own copy back
     * over the restored one.
     *
     * The staged file is deleted whether or not it applied cleanly. A restore that failed once will
     * fail the same way every start, and retrying it forever would keep overwriting whatever the
     * user has typed since.
     */
    internal fun applyStagedRestore(context: Context, profileDir: File) {
        val staged = File(context.filesDir, STAGED_FILE)
        if (!staged.isFile) return
        try {
            val payload = SecureStore.readBytes(staged)
            if (payload == null) {
                Log.e(TAG, "staged restore could not be decrypted; discarding")
                return
            }
            val entries = unzip(payload)

            entries[ENTRY_PROFILE_KEY]?.let { key ->
                if (!MozcProfileKey.replaceSealedKey(context, key)) {
                    // Carrying on would leave the restored history encrypted under a key we no
                    // longer hold, which reads as silent data loss.
                    Log.e(TAG, "could not install the restored profile key; leaving profile alone")
                    return
                }
            } ?: run {
                Log.e(TAG, "backup has no profile key; leaving profile alone")
                return
            }

            entries[ENTRY_DICTIONARY]?.let { UserDictionary(context).importTsv(String(it)) }

            profileDir.mkdirs()
            for ((name, bytes) in entries) {
                if (!name.startsWith(PROFILE_PREFIX)) continue
                val leaf = name.removePrefix(PROFILE_PREFIX)
                // Reject anything that is not a plain file name; a crafted archive must not be able
                // to write outside the profile directory.
                if (leaf.isEmpty() || leaf.contains('/') || leaf.contains('\\') || leaf == "..") {
                    Log.w(TAG, "skipping suspicious entry $name")
                    continue
                }
                if (leaf in NOT_BACKED_UP) continue
                File(profileDir, leaf).writeBytes(bytes)
            }
            Log.i(TAG, "restore applied")
        } catch (e: Exception) {
            Log.e(TAG, "restore failed", e)
        } finally {
            staged.delete()
        }
    }

    // --- archive format ------------------------------------------------------------------------

    /** Internal so the archive format can be round-tripped in a unit test. */
    internal sealed interface Opened {
        data class Payload(val bytes: ByteArray) : Opened
        data class Failure(val result: Result) : Opened
    }

    internal fun seal(payload: ByteArray, passphrase: CharArray): ByteArray? = try {
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, derive(passphrase, salt))
        }
        ByteArrayOutputStream().apply {
            write(MAGIC)
            write(FORMAT_VERSION)
            write(salt)
            write(cipher.iv)
            write(cipher.doFinal(payload))
        }.toByteArray()
    } catch (e: Exception) {
        Log.e(TAG, "could not seal the backup", e)
        null
    }

    internal fun open(archive: ByteArray, passphrase: CharArray): Opened {
        val header = MAGIC.size + 1 + SALT_LENGTH + IV_LENGTH
        if (archive.size <= header) return Opened.Failure(Result.NotABackup)
        for (i in MAGIC.indices) {
            if (archive[i] != MAGIC[i]) return Opened.Failure(Result.NotABackup)
        }
        val format = archive[MAGIC.size].toInt()
        if (format > FORMAT_VERSION) return Opened.Failure(Result.TooNew(format))

        val saltAt = MAGIC.size + 1
        val ivAt = saltAt + SALT_LENGTH
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    derive(passphrase, archive.copyOfRange(saltAt, ivAt)),
                    GCMParameterSpec(TAG_LENGTH_BITS, archive, ivAt, IV_LENGTH),
                )
            }
            Opened.Payload(cipher.doFinal(archive, header, archive.size - header))
        } catch (e: AEADBadTagException) {
            // GCM cannot tell a wrong passphrase from a tampered file, and neither can the user do
            // anything different about them, so they are reported as one.
            Opened.Failure(Result.WrongPassphrase)
        } catch (e: Exception) {
            Log.e(TAG, "could not open the backup", e)
            Opened.Failure(Result.Failed("バックアップを開けません"))
        }
    }

    private fun derive(passphrase: CharArray, salt: ByteArray) = SecretKeySpec(
        SecretKeyFactory.getInstance(KDF)
            .generateSecret(PBEKeySpec(passphrase, salt, KDF_ITERATIONS, KEY_BITS))
            .encoded,
        "AES",
    )

    private fun ZipOutputStream.write(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private fun unzip(payload: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(payload.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_ENTRY_BYTES) error("entry ${entry.name} is implausibly large")
                    out.write(buffer, 0, read)
                }
                entries[entry.name] = out.toByteArray()
            }
        }
        return entries
    }

    private fun profileFiles(context: Context): List<File> =
        File(context.filesDir, MozcEngine.PROFILE_DIR).listFiles()
            ?.filter { it.isFile && it.name !in NOT_BACKED_UP }
            .orEmpty()

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"
}
