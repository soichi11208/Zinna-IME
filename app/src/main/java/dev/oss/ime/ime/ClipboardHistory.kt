package dev.oss.ime.ime

import android.content.ClipDescription
import android.content.ClipboardManager
import android.util.Log

/**
 * Recent clipboard text, offered on the candidate strip when there is nothing to convert.
 *
 * **Held in memory only, and deliberately so.** Everything else this keyboard remembers about the
 * user is written to disk under a Keystore key, but the clipboard is different in kind: it is where
 * password managers put passwords, where banking apps put account numbers, and none of it was
 * typed here on purpose. Keeping it for the life of the process gives the feature its use — the
 * clip is still there when you switch apps to paste it — without creating a file of the user's
 * secrets that outlives the reason they were copied.
 *
 * Reading the clipboard at all is only possible because an input method is exempt from the
 * background-access ban while it is the active one; [record] is therefore called when the keyboard
 * opens rather than from a listener that would fire when we cannot read anything.
 */
class ClipboardHistory(private val limit: Int = LIMIT) {

    private val entries = ArrayDeque<String>()

    /** Most recent first. */
    fun items(): List<String> = entries.toList()

    fun clear() = entries.clear()

    /**
     * Captures the current clip, if there is one worth keeping.
     *
     * Skips clips the source marked sensitive — password managers set that flag precisely so
     * keyboards do not do what this class does.
     */
    fun record(clipboard: ClipboardManager?) {
        val clip = runCatching { clipboard?.primaryClip }
            .onFailure { Log.w(TAG, "clipboard unreadable", it) }
            .getOrNull() ?: return

        if (isSensitive(clip.description)) return

        val text = (0 until clip.itemCount)
            .mapNotNull { clip.getItemAt(it)?.coerceToText(null)?.toString() }
            .joinToString("\n")
            .trim()
        if (text.isEmpty()) return

        add(text)
    }

    /** The bookkeeping half of [record], separated so it can be tested without a live clipboard. */
    internal fun add(text: String) {
        if (text.isEmpty()) return
        // A re-copy of something already held moves it to the front instead of duplicating it.
        entries.remove(text)
        entries.addFirst(text)
        while (entries.size > limit) entries.removeLast()
    }

    private fun isSensitive(description: ClipDescription?): Boolean {
        val extras = description?.extras ?: return false
        // ClipDescription.EXTRA_IS_SENSITIVE is API 33; the string is the same on older releases,
        // where an app that knows about it can still set it.
        return extras.getBoolean("android.content.extra.IS_SENSITIVE", false)
    }

    private companion object {
        const val TAG = "ClipboardHistory"
        const val LIMIT = 20
    }
}
