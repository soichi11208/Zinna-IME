package dev.oss.ime.ime

/**
 * Keeps one Backspace owned by the state that existed before Mozc handled it.
 *
 * Deleting the final composing character produces an empty response, which is otherwise
 * indistinguishable from pressing Backspace while the session was already idle.
 */
internal object BackspaceRouting {

    /**
     * Whether the keystroke belongs to the editor rather than to mozc.
     *
     * Only when nothing was composing. If a composition existed the key is mozc's — and that holds
     * even when mozc fails to answer, because the composing span is still sitting in the editor and
     * `deleteSurroundingText` would take the character *before* it, which is not the one the user
     * aimed at. A lost response is spent on recovery instead; see
     * [ZinnaImeService.recoverFromLostSession].
     */
    fun shouldDeleteFromEditor(hadComposition: Boolean): Boolean = !hadComposition

    /**
     * Finishing an old composing span confirms it. When Mozc has just deleted that composition,
     * the editor-side span must instead be replaced with empty text.
     */
    fun shouldRemoveOldEditorComposition(
        hadComposition: Boolean,
        nextHasComposition: Boolean,
        committedText: String,
    ): Boolean =
        hadComposition && !nextHasComposition && committedText.isEmpty()
}
