package dev.oss.ime.ime

import android.content.Context
import android.util.Log
import dev.oss.ime.karukan.KarukanEngine
import java.util.concurrent.Executors

/**
 * Experimental second opinion on a conversion, from karukan's neural model.
 *
 * mozc remains in charge. This asks the model for the same reading, and whatever comes back that
 * mozc did not already offer is added to the strip — so with the feature on the user sees strictly
 * more, and with it off nothing about the existing path changes.
 *
 * Everything runs on one background thread. A language model is orders of magnitude slower than
 * the dictionary lookup it sits beside, and the keyboard must not wait for it: the request is fired
 * off, the strip is drawn from mozc immediately, and the extra candidates arrive when they arrive.
 * A reply for a reading the user has since moved on from is dropped.
 */
class NeuralCandidates(context: Context) {

    fun interface Listener {
        /** Called on the main thread with candidates for [reading], mozc's duplicates removed. */
        fun onNeuralCandidates(reading: String, candidates: List<String>)
    }

    var listener: Listener? = null

    private val appContext = context.applicationContext

    // Single-threaded on purpose: the model holds one llama.cpp context, and queueing keystrokes
    // behind each other is what stops a burst of typing from starting several inferences at once.
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "karukan").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    @Volatile private var engine: KarukanEngine? = null
    @Volatile private var loaded = false

    /** Whether this build could offer the feature at all. Cheap; touches no files. */
    val isBundled: Boolean get() = KarukanEngine.isBundled

    /** The reading most recently asked about, so stale replies can be recognised and dropped. */
    @Volatile private var wanted: String = ""

    /**
     * Asks for candidates for [reading].
     *
     * Returns immediately. [already] is what mozc offered, used to filter the reply rather than to
     * decide whether to ask — the interesting case is exactly the one where the model disagrees.
     */
    fun request(reading: String, context: String, already: Collection<String>) {
        if (reading.isEmpty()) {
            wanted = ""
            return
        }
        wanted = reading
        val seen = already.toSet()
        worker.execute {
            val engine = ensureLoaded() ?: return@execute
            // The user may have typed on while this was queued.
            if (wanted != reading) return@execute

            val fresh = engine.convert(reading, context)
                .filter { it.isNotEmpty() && it !in seen && it != reading }
                .distinct()
            if (fresh.isEmpty() || wanted != reading) return@execute

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (wanted == reading) listener?.onNeuralCandidates(reading, fresh)
            }
        }
    }

    /** Loads the model on first use, and remembers a failure so it is not retried per keystroke. */
    private fun ensureLoaded(): KarukanEngine? {
        engine?.let { return it }
        if (loaded) return null
        loaded = true
        val opened = KarukanEngine.open(appContext)
        if (opened == null) Log.w(TAG, "neural conversion unavailable")
        engine = opened
        return opened
    }

    fun close() {
        worker.execute { engine?.close(); engine = null }
        worker.shutdown()
    }

    private companion object {
        const val TAG = "NeuralCandidates"
    }
}
