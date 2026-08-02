package dev.oss.ime.mozc

import android.content.Context
import android.util.Log
import com.google.android.apps.inputmethod.libs.mozc.session.MozcJNI
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Command
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Input
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Output
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.SessionCommand
import java.io.File

/**
 * Owns the single native mozc SessionHandler and the one session we talk to it through.
 *
 * Everything here runs entirely on-device: [ensureLoaded] copies mozc.data out of the APK once and
 * hands the native side a plain filesystem path. No network is touched at any point, and the
 * manifest declares no INTERNET permission, so that property is enforced rather than merely
 * intended.
 *
 * Threading: the native SessionHandler is a single global with no internal locking, so every call
 * funnels through [lock]. Callers should stay off the main thread for anything but short
 * key events.
 */
class MozcEngine private constructor() {

    private val lock = Any()
    private var sessionId: Long = INVALID_SESSION_ID

    val dataVersion: String
        get() = synchronized(lock) { MozcJNI.getDataVersion() }

    /** Creates a mozc session. Safe to call repeatedly; a live session is reused. */
    fun ensureSession(): Boolean = synchronized(lock) { ensureSessionLocked() }

    fun deleteSession() = synchronized(lock) {
        if (sessionId == INVALID_SESSION_ID) return
        evalLocked(
            Input.newBuilder()
                .setType(Input.CommandType.DELETE_SESSION)
                .setId(sessionId)
        )
        sessionId = INVALID_SESSION_ID
    }

    /**
     * Sends a SEND_KEY input. [keyEvent] carries the reading produced by the input method — for
     * flick input that is the resolved kana, not the physical key.
     */
    fun sendKey(
        keyEvent: ProtoCommandsKeyEvent,
        clientContext: org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Context? = null,
    ): Output? = synchronized(lock) {
        if (!ensureSessionLocked()) return null
        val input = Input.newBuilder()
            .setType(Input.CommandType.SEND_KEY)
            .setId(sessionId)
            .setKey(keyEvent)
        // mozc reads this on the transition into composing and rebuilds its history segments from
        // the text to the left; see MozcSession.clientContext.
        if (clientContext != null) input.context = clientContext
        evalLocked(input)
    }

    /** Sends a session command such as SUBMIT, REVERT, or candidate selection. */
    fun sendCommand(command: SessionCommand): Output? = synchronized(lock) {
        if (!ensureSessionLocked()) return null
        evalLocked(
            Input.newBuilder()
                .setType(Input.CommandType.SEND_COMMAND)
                .setId(sessionId)
                .setCommand(command)
        )
    }

    /** Escape hatch for inputs this facade does not model yet (config, user dictionary, …). */
    fun eval(input: Input.Builder): Output? = synchronized(lock) { evalLocked(input) }

    private fun ensureSessionLocked(): Boolean {
        if (sessionId != INVALID_SESSION_ID) return true
        val output = evalLocked(Input.newBuilder().setType(Input.CommandType.CREATE_SESSION))
            ?: return false
        if (!output.hasId()) {
            Log.e(TAG, "CREATE_SESSION returned no session id")
            return false
        }
        sessionId = output.id
        return true
    }

    private fun evalLocked(input: Input.Builder): Output? {
        val command = Command.newBuilder().setInput(input).build()
        return try {
            val responseBytes = MozcJNI.evalCommand(command.toByteArray()) ?: return null
            Command.parseFrom(responseBytes).output
        } catch (e: Exception) {
            Log.e(TAG, "evalCommand failed for ${input.type}", e)
            null
        }
    }

    companion object {
        private const val TAG = "MozcEngine"
        private const val INVALID_SESSION_ID = 0L
        private const val DATA_ASSET = "mozc.data"

        @Volatile
        private var instance: MozcEngine? = null

        /**
         * Loads libmozc.so, materialises mozc.data, and brings up the native SessionHandler.
         *
         * The native side keeps one global handler, so this is idempotent by construction: a second
         * call to onPostLoad returns true without rebuilding anything.
         */
        fun get(context: Context): MozcEngine? {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val created = load(context.applicationContext) ?: return null
                instance = created
                return created
            }
        }

        private fun load(context: Context): MozcEngine? {
            try {
                System.loadLibrary("mozc")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "libmozc.so missing for this ABI — run scripts/build_mozc.sh", e)
                return null
            }
            if (!MozcJNI.initialize()) {
                Log.e(TAG, "MozcJNI.initialize() failed to register natives")
                return null
            }

            val profileDir = File(context.filesDir, "mozc").apply { mkdirs() }
            val dataFile = extractDataFile(context) ?: return null

            // Before onPostLoad, not after: building the engine reads the conversion history, and
            // mozc needs the key in hand to decrypt it. Carrying on without it is deliberate — the
            // IME still converts, it just stops persisting what it learns.
            if (!MozcProfileKey.install(context, profileDir)) {
                Log.e(TAG, "profile encryption key unavailable; history and dictionary will not persist")
            }

            if (!MozcJNI.onPostLoad(profileDir.absolutePath, dataFile.absolutePath)) {
                Log.e(TAG, "onPostLoad failed")
                return null
            }
            Log.i(TAG, "mozc up, data version=${MozcJNI.getDataVersion()}")
            val engine = MozcEngine()

            // Only the user's own words are pushed at start-up now. The bundled dictionaries used
            // to be imported here as a user dictionary, which gave them mozc's user-dictionary cost
            // advantage — enough of an advantage that six entries pushed 日本 out of first place for
            // にほん. They live in the system dictionary instead; see
            // scripts/gen_system_dictionary.py.
            Thread({ UserDictionary(context).sync(engine) }, "mozc-user-dict")
                .apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
                .start()

            return engine
        }

        /**
         * mozc's DataManager opens mozc.data by path, so the 18 MB asset has to exist as a real
         * file on disk.
         *
         * Freshness is tracked with a stamp file holding the package's `lastUpdateTime` rather than
         * by comparing sizes: the asset is deflated inside the APK, so its stored length is not the
         * extracted length, and `openFd` cannot open it at all. lastUpdateTime changes on every
         * install and upgrade, which is exactly when the extracted copy goes stale.
         */
        private fun extractDataFile(context: Context): File? {
            val target = File(context.filesDir, DATA_ASSET)
            val stamp = File(context.filesDir, "$DATA_ASSET.stamp")
            return try {
                val version = context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .lastUpdateTime
                    .toString()

                if (target.isFile && stamp.isFile && stamp.readText() == version) return target

                context.assets.open(DATA_ASSET).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                stamp.writeText(version)
                target
            } catch (e: Exception) {
                Log.e(TAG, "failed to extract $DATA_ASSET", e)
                null
            }
        }
    }
}

/** Alias so callers do not have to import the deeply-nested generated name. */
typealias ProtoCommandsKeyEvent = org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.KeyEvent
